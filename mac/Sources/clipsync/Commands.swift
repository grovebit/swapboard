import AppKit
import ClipSyncCore
import Foundation

enum Commands {
    // MARK: - Settings from Alfred's User Configuration

    /// Alfred exposes User Configuration only to scripts it runs, so each CLI call copies the values
    /// into config.json and tells the agent to reload when something changed.
    static func applyAlfredSettings() {
        let env = ProcessInfo.processInfo.environment
        guard env["alfred_workflow_bundleid"] != nil else { return }
        func flag(_ key: String) -> Bool? {
            guard let value = env[key]?.lowercased() else { return nil }
            return ["1", "true", "yes", "on"].contains(value)
        }
        let store = Paths.configStore
        let before = store.load()
        var settings = before.settings
        if let port = env["port"].flatMap({ UInt16($0.trimmingCharacters(in: .whitespaces)) }), port > 0 {
            settings.port = port
        }
        if let notify = flag("notify_on_receive") { settings.notifyOnReceive = notify }
        if let kb = env["max_clip_kb"].flatMap({ Int($0.trimmingCharacters(in: .whitespaces)) }), kb > 0 {
            settings.maxClipKB = kb
        }
        if let apps = env["ignored_apps"] {
            settings.ignoredApps = apps.split(whereSeparator: { $0 == "," || $0.isWhitespace })
                .map(String.init).filter { !$0.isEmpty }
        }
        guard settings != before.settings else { return }
        _ = try? store.update { $0.settings = settings }
        _ = try? ControlClient.request(ControlRequest(cmd: "reload"), timeout: 1)
    }

    // MARK: - Actions

    static func send(text explicitText: String?) throws -> String {
        let text: String
        var sensitive = false
        if let explicitText {
            text = explicitText
        } else {
            guard let current = Pasteboard.readText() else { throw CLIError("The clipboard has no text") }
            (text, sensitive) = current
        }
        return try agentRequest(ControlRequest(cmd: "send", text: text, sensitive: sensitive))
    }

    static func agentRequest(_ request: ControlRequest) throws -> String {
        let response: ControlResponse
        do {
            response = try ControlClient.request(request)
        } catch is ControlClient.NotRunning {
            throw CLIError("The ClipSync agent isn't running. Start it from Alfred (clip → Start agent)")
        }
        guard response.ok else { throw CLIError(response.message ?? "failed") }
        return response.message ?? ""
    }

    /// Writes the last clip received from the phone back onto the Mac clipboard.
    static func copyLast() throws -> String {
        let response: ControlResponse
        do {
            response = try ControlClient.request(ControlRequest(cmd: "last"))
        } catch is ControlClient.NotRunning {
            throw CLIError("The ClipSync agent isn't running")
        }
        guard response.ok, let text = response.text else {
            throw CLIError(response.message ?? "Nothing received yet")
        }
        Pasteboard.write(text, sensitive: response.sensitive ?? false)
        return "Copied the last clip from your phone"
    }

    static func unpair(_ target: String?) throws -> String {
        let store = Paths.configStore
        var removed: [Pairing] = []
        try store.update { config in
            removed = config.pairings.filter { target == nil || target == "--all" || $0.pid == target }
            config.pairings.removeAll { p in removed.contains { $0.pid == p.pid } }
        }
        guard !removed.isEmpty else { throw CLIError("No matching pairing") }
        try? FileManager.default.removeItem(at: Paths.qrURL)
        _ = try? ControlClient.request(ControlRequest(cmd: "reload"))
        return removed.count == 1 ? "Unpaired \(removed[0].displayName)" : "Removed \(removed.count) pairings"
    }

    static func ensureAgentRunning() throws {
        if !ControlClient.isAgentRunning() { _ = try LaunchAgentManager.start() }
    }

    /// Entry point for Alfred rows: `clipsync run <action>`. Prints the notification text.
    static func runAction(_ action: String) throws -> String {
        switch action {
        case "send": return try send(text: nil)
        case "pause", "resume": return try agentRequest(ControlRequest(cmd: action))
        case "copy-last", "paste-last": return try copyLast()
        case "pair":
            try ensureAgentRunning()
            return try PairCommand.run()
        case "show-qr":
            PairCommand.showQR()
            return ""
        case "start": return try LaunchAgentManager.start()
        case "stop": return try LaunchAgentManager.stop()
        case "log":
            Shell.spawn("/usr/bin/open", ["-a", "Console", Paths.logURL.path])
            return ""
        default:
            if action.hasPrefix("unpair:") { return try unpair(String(action.dropFirst("unpair:".count))) }
            throw CLIError("Unknown action \(action)")
        }
    }

    // MARK: - Status

    static func statusText() -> String {
        guard let response = try? ControlClient.request(ControlRequest(cmd: "status")), let s = response.status else {
            return "agent: stopped"
        }
        var lines = ["agent: running (port \(s.port), \(s.listening ? "listening" : "NOT listening: \(s.listenError ?? "?")"))",
                     "auto-sync: \(s.paused ? "paused" : "on")"]
        lines += s.peers.map { "connected: \($0.device) via \($0.route) (\($0.host))" }
        if s.peers.isEmpty { lines.append("connected: none") }
        if let sent = s.lastSent { lines.append("last sent: \(relativeTime(sent.at))\(sent.delivered ? "" : " (pending)")") }
        if let received = s.lastReceived { lines.append("last received: \(relativeTime(received.at)) from \(received.device ?? "?")") }
        let pairings = Paths.configStore.load().pairings
        lines += pairings.map { "pairing: \($0.displayName)\($0.confirmed ? "" : " (waiting for scan)")" }
        return lines.joined(separator: "\n")
    }

    static func alfredStatus() -> String {
        let config = Paths.configStore.load()
        let status = (try? ControlClient.request(ControlRequest(cmd: "status"), timeout: 2))?.status
        var items: [AlfredItem] = []

        // Status row
        if let s = status {
            let lastSync = [s.lastSent?.at, s.lastReceived?.at].compactMap { $0 }.max()
            let syncText = s.paused ? "auto-sync paused" : "auto-sync on"
            let lastText = lastSync.map { " · last sync \(relativeTime($0))" } ?? ""
            if !s.listening, s.listenError == SyncServer.noTailscale {
                items.append(AlfredItem(title: "🔴 Tailscale is off on this Mac",
                                        subtitle: "ClipSync only syncs over Tailscale. It resumes as soon as Tailscale connects",
                                        valid: false))
            } else if !s.listening {
                items.append(AlfredItem(title: "🔴 Can't listen on port \(s.port)",
                                        subtitle: s.listenError ?? "Another app may be using the port", valid: false))
            } else if let peer = s.peers.first {
                items.append(AlfredItem(title: "🟢 Connected to \(peer.device)",
                                        subtitle: "via \(peer.route) (\(peer.host)) · \(syncText)\(lastText)", valid: false))
            } else if config.pairings.contains(where: \.confirmed) {
                let name = config.pairings.first(where: \.confirmed)?.displayName ?? "phone"
                items.append(AlfredItem(title: "🟡 Waiting for \(name)…",
                                        subtitle: "Open ClipSync on the phone · port \(s.port) · \(syncText)\(lastText)",
                                        valid: false))
            } else {
                items.append(AlfredItem(title: "🟡 No phone paired yet",
                                        subtitle: "Choose “Pair a phone” and scan the QR code with the ClipSync app",
                                        valid: false))
            }
        } else {
            items.append(AlfredItem(title: "🔴 ClipSync agent is stopped", subtitle: "Choose “Start agent” to sync",
                                    valid: false))
        }

        // Send
        if let current = Pasteboard.readText() {
            items.append(AlfredItem(title: "Send clipboard to phone",
                                    subtitle: preview(current.text, sensitive: current.concealed, limit: 80),
                                    arg: "send", match: "send clipboard phone push"))
        }

        if let s = status {
            if let last = s.lastReceived {
                items.append(AlfredItem(
                    title: "Last from \(last.device ?? "phone"): \(last.preview)",
                    subtitle: "↩ Copy · ⌘↩ Paste · received \(relativeTime(last.at))",
                    arg: "copy-last", match: "last received phone copy paste",
                    mods: ["cmd": .init(subtitle: "Paste into the frontmost app", arg: "paste-last")]))
            }
            items.append(s.paused
                ? AlfredItem(title: "Resume auto-sync", subtitle: "Send every Mac copy to the phone again",
                             arg: "resume", match: "resume auto sync start")
                : AlfredItem(title: "Pause auto-sync", subtitle: "Stop sending Mac copies automatically",
                             arg: "pause", match: "pause auto sync stop"))
        }

        if config.pairings.contains(where: { !$0.confirmed }) {
            items.append(AlfredItem(title: "Show pairing QR code", subtitle: "Pairing in progress: scan it in the ClipSync app",
                                    arg: "show-qr", match: "pair qr code show"))
        }
        items.append(AlfredItem(title: "Pair a phone", subtitle: "Shows a QR code to scan with the ClipSync app",
                                arg: "pair", match: "pair phone add new qr"))
        for pairing in config.pairings where pairing.confirmed {
            let seen = pairing.lastSeen.map { " · last seen \(relativeTime($0))" } ?? ""
            items.append(AlfredItem(title: "Unpair \(pairing.displayName)",
                                    subtitle: "Forget this phone; it will need to pair again\(seen)",
                                    arg: "unpair:\(pairing.pid)", match: "unpair forget remove \(pairing.displayName)"))
        }
        items.append(status == nil
            ? AlfredItem(title: "Start agent", subtitle: "Run ClipSync in the background (starts at login)",
                         arg: "start", match: "start agent run")
            : AlfredItem(title: "Stop agent", subtitle: "Stop syncing and don't start at login",
                         arg: "stop", match: "stop agent quit"))
        items.append(AlfredItem(title: "Show log", subtitle: Paths.logURL.path, arg: "log", match: "log debug console"))

        let output = AlfredOutput(items: items, rerun: 2)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return String(decoding: (try? encoder.encode(output)) ?? Data(), as: UTF8.self)
    }
}

struct AlfredItem: Encodable {
    struct Mod: Encodable {
        var subtitle: String?
        var arg: String?
    }

    var title: String
    var subtitle: String?
    var arg: String?
    var valid: Bool = true
    var match: String?
    var mods: [String: Mod]?

    init(title: String, subtitle: String? = nil, arg: String? = nil, valid: Bool = true, match: String? = nil,
         mods: [String: Mod]? = nil) {
        self.title = title
        self.subtitle = subtitle
        self.arg = arg
        self.valid = valid
        self.match = match.map { "\($0) \(title)" }
        self.mods = mods
    }
}

struct AlfredOutput: Encodable {
    var items: [AlfredItem]
    var rerun: Double?
    var skipknowledge = true
}

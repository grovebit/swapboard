import ClipSyncCore
import Foundation

/// Installs `~/Library/LaunchAgents/com.clipsync.agent.plist` and drives it with launchctl.
enum LaunchAgentManager {
    static var domain: String { "gui/\(getuid())" }
    static var service: String { "\(domain)/\(Paths.agentLabel)" }

    static func isLoaded() -> Bool {
        Shell.run("/bin/launchctl", ["print", service]).status == 0
    }

    static func start() throws -> String {
        try Paths.ensureDataDir()
        if isLoaded() { bootout() }

        // Run a private copy so the agent doesn't depend on the workflow folder (or an external disk).
        guard let source = Bundle.main.executableURL?.resolvingSymlinksInPath() else {
            throw CLIError("cannot locate the clipsync binary")
        }
        let destination = Paths.binURL
        let fm = FileManager.default
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        if source.path != destination.path {
            let tmp = destination.deletingLastPathComponent().appendingPathComponent(".clipsync.\(getpid())")
            try? fm.removeItem(at: tmp)
            try fm.copyItem(at: source, to: tmp)
            guard rename(tmp.path, destination.path) == 0 else { throw CLIError("cannot install agent binary") }
        }

        let plist: [String: Any] = [
            "Label": Paths.agentLabel,
            "ProgramArguments": [destination.path, "agent"],
            "RunAtLoad": true,
            "KeepAlive": true,
            "ThrottleInterval": 10,
            "ProcessType": "Interactive",
            "StandardOutPath": Paths.logURL.path,
            "StandardErrorPath": Paths.logURL.path,
            "EnvironmentVariables": [
                "CLIPSYNC_DATA_DIR": Paths.dataDir.path,
                "CLIPSYNC_BUNDLE_ID": Paths.bundleID,
            ],
        ]
        let data = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        try fm.createDirectory(at: Paths.launchAgentPlist.deletingLastPathComponent(),
                               withIntermediateDirectories: true)
        try data.write(to: Paths.launchAgentPlist, options: .atomic)

        let result = Shell.run("/bin/launchctl", ["bootstrap", domain, Paths.launchAgentPlist.path])
        guard result.status == 0 else {
            throw CLIError("launchctl bootstrap failed: \(result.output.trimmingCharacters(in: .whitespacesAndNewlines))")
        }
        for _ in 0..<30 where !ControlClient.isAgentRunning() { usleep(100_000) }
        guard ControlClient.isAgentRunning() else {
            throw CLIError("agent did not start; see \(Paths.logURL.path)")
        }
        return "ClipSync agent started"
    }

    static func stop() throws -> String {
        if isLoaded() { bootout() }
        try? FileManager.default.removeItem(at: Paths.launchAgentPlist)
        return "ClipSync agent stopped"
    }

    private static func bootout() {
        Shell.run("/bin/launchctl", ["bootout", service])
        for _ in 0..<30 where isLoaded() { usleep(100_000) }
    }
}

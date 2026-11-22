import AppKit
import SwapboardCore
import SwiftUI

/// The setup window: Tailscale → phone app → pairing → done. When Swapboard is already set up,
/// opening the app shows the "done" page with the live status instead.
final class SetupWindowController: NSObject, NSWindowDelegate {
    private unowned let agent: Agent
    private var window: NSWindow?
    private var model: SetupModel?

    init(agent: Agent) {
        self.agent = agent
    }

    func show(startAtPairing: Bool) {
        if let window, let model {
            if startAtPairing, model.step != .pair { model.setUpAnotherPhone() }
            bringToFront(window)
            return
        }
        let model = SetupModel(agent: agent, startAtPairing: startAtPairing)
        let view = SetupView(model: model) { [weak self] in self?.window?.close() }
        let window = NSWindow(contentViewController: NSHostingController(rootView: view))
        window.title = "Swapboard"
        window.styleMask = [.titled, .closable]
        window.isReleasedWhenClosed = false
        window.delegate = self
        window.center()
        self.window = window
        self.model = model
        bringToFront(window)
    }

    private func bringToFront(_ window: NSWindow) {
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
    }

    func windowWillClose(_ notification: Notification) {
        model?.stop()
        DispatchQueue.main.async { [weak self] in
            self?.model = nil
            self?.window = nil
        }
    }
}

final class SetupModel: ObservableObject {
    enum Step: Int {
        case tailscale, phoneApp, pair, done
    }

    @Published private(set) var step: Step = .tailscale
    @Published private(set) var tailscaleInstalled = false
    @Published private(set) var tailscaleConnected = false
    @Published private(set) var pairingImage: CGImage?
    @Published private(set) var pairingProblem: String?
    /// Set when a phone paired while this window was open.
    @Published private(set) var pairedDevice: String?
    @Published private(set) var statusTitle = ""
    @Published private(set) var statusDetail = ""
    @Published private(set) var hasPairedPhone = false
    let apkImage: CGImage?

    private unowned let agent: Agent
    private var pendingPID: String?
    private var pairingExpires: Date?
    private var timer: Timer?

    init(agent: Agent, startAtPairing: Bool) {
        self.agent = agent
        apkImage = AppBundle.apkDownloadURL.flatMap { QRCode.image(for: $0.absoluteString, moduleSize: 8) }
        refresh()
        let paired = agent.currentConfig.pairings.contains(where: \.confirmed)
        if paired && !startAtPairing {
            step = .done
        } else if !tailscaleConnected {
            step = .tailscale
        } else {
            go(to: startAtPairing ? .pair : .phoneApp)
        }
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in self?.tick() }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    func go(to step: Step) {
        self.step = step
        if step == .pair { startPairing() }
        if step == .done { Notifier.requestPermission() }
    }

    func next() {
        if let next = Step(rawValue: step.rawValue + 1) { go(to: next) }
    }

    func back() {
        if let previous = Step(rawValue: step.rawValue - 1) { go(to: previous) }
    }

    func setUpAnotherPhone() {
        pairedDevice = nil
        go(to: tailscaleConnected ? .phoneApp : .tailscale)
    }

    func openTailscale() {
        if let app = TailscaleInfo.appURL {
            NSWorkspace.shared.openApplication(at: app, configuration: NSWorkspace.OpenConfiguration())
        } else {
            NSWorkspace.shared.open(TailscaleInfo.downloadURL)
        }
    }

    func startPairing() {
        do {
            let info = try agent.startPairing()
            pendingPID = info.pid
            pairingImage = QRCode.image(for: info.uri)
            pairingExpires = Date().addingTimeInterval(ProtocolConstants.pairingTTL)
            pairingProblem = nil
        } catch {
            pendingPID = nil
            pairingImage = nil
            pairingProblem = tailscaleConnected ? "\(error)" : nil
        }
    }

    private func refresh() {
        tailscaleInstalled = TailscaleInfo.appURL != nil
        tailscaleConnected = TailscaleInfo.isConnected
        let status = agent.currentStatus
        let config = agent.currentConfig
        statusTitle = StatusText.headline(status, config: config).title
        statusDetail = StatusText.explanation(status, config: config)
        hasPairedPhone = config.pairings.contains(where: \.confirmed)
    }

    private func tick() {
        let wasConnected = tailscaleConnected
        refresh()
        switch step {
        case .tailscale:
            if tailscaleConnected, !wasConnected {
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) { [weak self] in
                    if self?.step == .tailscale { self?.next() }
                }
            }
        case .pair:
            if let pid = pendingPID,
               let pairing = agent.currentConfig.pairings.first(where: { $0.pid == pid && $0.confirmed }) {
                pendingPID = nil
                pairedDevice = pairing.displayName
                go(to: .done)
            } else if pairingImage == nil, pairingProblem == nil, tailscaleConnected {
                startPairing() // Tailscale is back
            } else if let expires = pairingExpires, expires < Date(), pendingPID != nil {
                startPairing() // a fresh code replaces the expired one
            }
        case .phoneApp, .done:
            break
        }
    }
}

// MARK: - Views

struct SetupView: View {
    @ObservedObject var model: SetupModel
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            header.padding(.bottom, 22)
            Group {
                switch model.step {
                case .tailscale: TailscaleStep(model: model)
                case .phoneApp: PhoneAppStep(model: model)
                case .pair: PairStep(model: model)
                case .done: DoneStep(model: model)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
            footer.padding(.top, 16)
        }
        .padding(28)
        .frame(width: 560, height: 640)
    }

    private var header: some View {
        HStack(spacing: 14) {
            Image(nsImage: NSApp.applicationIconImage)
                .resizable()
                .frame(width: 52, height: 52)
            VStack(alignment: .leading, spacing: 3) {
                Text(model.step == .done ? "Swapboard" : "Set up Swapboard")
                    .font(.title2.bold())
                if model.step != .done {
                    Text("Step \(model.step.rawValue + 1) of 3").foregroundColor(.secondary)
                }
            }
            Spacer()
        }
    }

    private var footer: some View {
        HStack {
            if model.step == .phoneApp || model.step == .pair {
                Button("Back") { model.back() }
            }
            Spacer()
            switch model.step {
            case .tailscale:
                Button("Continue") { model.next() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(!model.tailscaleConnected)
            case .phoneApp:
                Button("My phone has the app") { model.next() }
                    .keyboardShortcut(.defaultAction)
            case .pair:
                EmptyView()
            case .done:
                Button("Done") { close() }
                    .keyboardShortcut(.defaultAction)
            }
        }
        .controlSize(.large)
    }
}

private struct TailscaleStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Turn on Tailscale on this Mac").font(.title3.bold())
            Paragraph("Swapboard sends your clipboard through **Tailscale**, a free app that connects your own devices privately. Install it, sign in, and leave it on.")
            if model.tailscaleConnected {
                Label("Tailscale is connected", systemImage: "checkmark.circle.fill")
                    .foregroundColor(.green)
                    .font(.headline)
            } else if model.tailscaleInstalled {
                Label("Tailscale isn't connected yet", systemImage: "exclamationmark.circle")
                    .foregroundColor(.orange)
                    .font(.headline)
                Paragraph("Open Tailscale and sign in. You'll find it in the menu bar at the top of your screen once it's open.")
                Button("Open Tailscale") { model.openTailscale() }
                    .controlSize(.large)
            } else {
                Label("Tailscale isn't installed yet", systemImage: "arrow.down.circle")
                    .foregroundColor(.orange)
                    .font(.headline)
                Button("Download Tailscale") { model.openTailscale() }
                    .controlSize(.large)
                Paragraph("After installing, open Tailscale and sign in. You can use a Google, Microsoft, Apple or GitHub account. Use the **same account** on your phone later.")
            }
            if !model.tailscaleConnected {
                Paragraph("This window moves on by itself as soon as Tailscale is connected.")
                    .foregroundColor(.secondary)
            }
        }
    }
}

private struct PhoneAppStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Get Swapboard on your Android phone").font(.title3.bold())
            if let image = model.apkImage {
                Paragraph("Open the **Camera** app on your phone and point it at this code. It downloads the Swapboard app.")
                HStack {
                    Spacer()
                    QRImage(image: image, size: 190)
                    Spacer()
                }
            } else {
                Paragraph("On your phone, download **Swapboard.apk** from the Swapboard releases page on GitHub.")
            }
            VStack(alignment: .leading, spacing: 8) {
                Bullet("When the download finishes, tap it. If your phone asks, tap **Settings**, turn on **Allow from this source**, go back and tap **Install**.")
                Bullet("If Google Play Protect warns about an unknown app, tap **More details → Install anyway**.")
                Bullet("Open Swapboard on your phone. It also helps you set up Tailscale there: sign in with the **same account** as on this Mac.")
            }
            .font(.callout)
        }
    }
}

private struct PairStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Connect your phone").font(.title3.bold())
            Paragraph("In the Swapboard app on your phone, tap **Scan code** and point your phone at this code.")
            HStack {
                Spacer()
                if let image = model.pairingImage {
                    QRImage(image: image, size: 230)
                } else if !model.tailscaleConnected {
                    VStack(spacing: 10) {
                        Label("Tailscale isn't connected on this Mac", systemImage: "exclamationmark.circle")
                            .foregroundColor(.orange)
                        Button("Open Tailscale") { model.openTailscale() }
                    }
                    .frame(height: 230)
                } else {
                    VStack(spacing: 10) {
                        Text(model.pairingProblem ?? "Couldn't make a code").foregroundColor(.secondary)
                        Button("Try Again") { model.startPairing() }
                    }
                    .frame(height: 230)
                }
                Spacer()
            }
            if model.pairingImage != nil {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text("Waiting for your phone…").foregroundColor(.secondary)
                }
                Paragraph("Keep this code private: it contains the key that links your two devices.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }
}

private struct DoneStep: View {
    @ObservedObject var model: SetupModel

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let device = model.pairedDevice {
                Label("Connected to \(device)", systemImage: "checkmark.circle.fill")
                    .font(.title2.bold())
                    .foregroundColor(.green)
                Text("You're all set!").font(.title3)
            } else {
                Text(model.statusTitle).font(.title3.bold())
                Paragraph(model.statusDetail).foregroundColor(.secondary)
            }
            VStack(alignment: .leading, spacing: 10) {
                Bullet("Copy anything on this Mac, then paste it on your phone.")
                Bullet("To send from your phone, tap **Send to Mac** in Quick Settings, or choose **Share → Send to Mac**.")
                Bullet(Text("Swapboard lives in the menu bar (look for \(Image(systemName: "arrow.left.arrow.right.square")) at the top of your screen) and starts by itself when you log in."))
            }
            if model.pairedDevice == nil {
                Button(model.hasPairedPhone ? "Set Up Another Phone" : "Set Up a Phone") { model.setUpAnotherPhone() }
            }
        }
    }
}

private struct QRImage: View {
    let image: CGImage
    let size: CGFloat

    var body: some View {
        Image(decorative: image, scale: 1)
            .interpolation(.none)
            .resizable()
            .frame(width: size, height: size)
            .cornerRadius(6)
            .shadow(radius: 1)
    }
}

/// A wrapping paragraph; **bold** markdown works.
private struct Paragraph: View {
    let text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        Text(LocalizedStringKey(text)).fixedSize(horizontal: false, vertical: true)
    }
}

private struct Bullet: View {
    let text: Text

    init(_ markdown: String) {
        text = Text(LocalizedStringKey(markdown))
    }

    init(_ text: Text) {
        self.text = text
    }

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text("•")
            text.fixedSize(horizontal: false, vertical: true)
        }
    }
}

#if DEBUG
extension SetupModel {
    func debugSet(step: Step, installed: Bool = true, connected: Bool = true, paired: String? = nil) {
        self.step = step
        tailscaleInstalled = installed
        tailscaleConnected = connected
        pairedDevice = paired
    }
}

extension SetupWindowController {
    /// Development only (`swapboard debug-snapshots DIR` with a debug build): renders every setup
    /// page to a PNG without showing a window.
    static func renderSnapshots(agent: Agent, to directory: URL) throws -> String {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let model = SetupModel(agent: agent, startAtPairing: false)
        model.stop()
        let pages: [(String, () -> Void)] = [
            ("1-tailscale-missing", { model.debugSet(step: .tailscale, installed: false, connected: false) }),
            ("1-tailscale-off", { model.debugSet(step: .tailscale, installed: true, connected: false) }),
            ("1-tailscale-on", { model.debugSet(step: .tailscale) }),
            ("2-phone-app", { model.debugSet(step: .phoneApp) }),
            ("3-pair", { model.debugSet(step: .pair); model.startPairing() }),
            ("3-pair-no-tailscale", { model.debugSet(step: .pair, connected: false); model.debugClearPairing() }),
            ("4-done-new", { model.debugSet(step: .done, paired: "Galaxy S25") }),
            ("4-done-status", { model.debugSet(step: .done) }),
        ]
        for (name, configure) in pages {
            configure()
            let root = SetupView(model: model, close: {}).background(Color(NSColor.windowBackgroundColor))
            let host = NSHostingView(rootView: root)
            host.frame = NSRect(x: 0, y: 0, width: 560, height: 640)
            let window = NSWindow(contentRect: host.frame, styleMask: [.borderless], backing: .buffered, defer: false)
            window.contentView = host
            host.layoutSubtreeIfNeeded()
            RunLoop.current.run(until: Date().addingTimeInterval(0.3))
            guard let rep = host.bitmapImageRepForCachingDisplay(in: host.bounds) else { continue }
            host.cacheDisplay(in: host.bounds, to: rep)
            try rep.representation(using: .png, properties: [:])?.write(to: directory.appendingPathComponent("\(name).png"))
        }
        return "Rendered \(pages.count) pages to \(directory.path)"
    }
}

extension SetupModel {
    func debugClearPairing() {
        pairingImage = nil
        pairingProblem = nil
    }
}
#endif

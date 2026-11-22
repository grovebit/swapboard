import AppKit
import ClipSyncCore
import Foundation
import UserNotifications

/// The menu-bar icon and its menu. Only used when the agent runs inside ClipSync.app.
final class AgentUI: NSObject, NSMenuDelegate, UNUserNotificationCenterDelegate {
    private unowned let agent: Agent
    private let statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
    private let menu = NSMenu()
    private var iconTimer: Timer?
    private lazy var setup = SetupWindowController(agent: agent)

    init(agent: Agent) {
        self.agent = agent
        super.init()
        menu.delegate = self
        menu.autoenablesItems = false
        statusItem.menu = menu
        updateIcon()
        iconTimer = Timer.scheduledTimer(withTimeInterval: 3, repeats: true) { [weak self] _ in self?.updateIcon() }
        UNUserNotificationCenter.current().delegate = self
    }

    func showSetup(pairing: Bool) {
        setup.show(startAtPairing: pairing)
    }

    // MARK: - Icon

    /// Filled while a phone is connected.
    private func updateIcon() {
        let connected = !agent.currentStatus.peers.isEmpty
        let symbol = connected ? "arrow.left.arrow.right.square.fill" : "arrow.left.arrow.right.square"
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: "ClipSync")
            ?? NSImage(systemSymbolName: "doc.on.clipboard", accessibilityDescription: "ClipSync")
        image?.isTemplate = true
        statusItem.button?.image = image
        let headline = StatusText.headline(agent.currentStatus, config: agent.currentConfig).title
        statusItem.button?.toolTip = "ClipSync: " + headline.dropFirst(2)
    }

    // MARK: - Menu

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        let status = agent.currentStatus
        let config = agent.currentConfig
        let paired = config.pairings.contains(where: \.confirmed)

        menu.addItem(info(StatusText.headline(status, config: config).title))
        if let last = [status.lastSent?.at, status.lastReceived?.at].compactMap({ $0 }).max() {
            menu.addItem(info("Last sync \(relativeTime(last))", secondary: true))
        }
        menu.addItem(.separator())
        menu.addItem(action("Send Clipboard to Phone", #selector(sendClipboard), enabled: paired))
        if let last = status.lastReceived {
            menu.addItem(action("Copy Last Text from \(last.device ?? "Phone")", #selector(copyLast)))
        }
        menu.addItem(.separator())
        menu.addItem(action(status.paused ? "Resume Auto-Sync" : "Pause Auto-Sync", #selector(togglePause)))
        menu.addItem(action("Notify When Text Arrives", #selector(toggleNotify),
                            checked: config.settings.notifyOnReceive))
        menu.addItem(action("Open at Login", #selector(toggleOpenAtLogin), checked: config.settings.openAtLogin))
        menu.addItem(.separator())
        menu.addItem(action(paired ? "Set Up Another Phone…" : "Set Up a Phone…", #selector(openSetup)))
        for pairing in config.pairings where pairing.confirmed {
            let item = action("Unpair \(pairing.displayName)…", #selector(unpair(_:)))
            item.representedObject = pairing.pid
            menu.addItem(item)
        }
        menu.addItem(.separator())
        if AppBundle.helpURL != nil { menu.addItem(action("ClipSync Help", #selector(openHelp))) }
        menu.addItem(action("Show Log", #selector(showLog)))
        menu.addItem(action("Uninstall ClipSync…", #selector(uninstall)))
        menu.addItem(.separator())
        menu.addItem(action("Quit ClipSync", #selector(quit), key: "q"))
    }

    private func info(_ title: String, secondary: Bool = false) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: nil, keyEquivalent: "")
        item.isEnabled = false
        if secondary {
            item.attributedTitle = NSAttributedString(string: title, attributes: [
                .font: NSFont.menuFont(ofSize: NSFont.smallSystemFontSize),
                .foregroundColor: NSColor.secondaryLabelColor,
            ])
        }
        return item
    }

    private func action(_ title: String, _ selector: Selector, enabled: Bool = true, checked: Bool = false,
                        key: String = "") -> NSMenuItem {
        let item = NSMenuItem(title: title, action: selector, keyEquivalent: key)
        item.target = self
        item.isEnabled = enabled
        item.state = checked ? .on : .off
        return item
    }

    // MARK: - Actions

    @objc private func sendClipboard() {
        Notifier.notify("📤", title: agent.sendClipboard())
    }

    @objc private func copyLast() {
        agent.copyLastReceived()
    }

    @objc private func togglePause() {
        agent.setPaused(!agent.currentConfig.settings.paused)
    }

    @objc private func toggleNotify() {
        agent.setNotifyOnReceive(!agent.currentConfig.settings.notifyOnReceive)
    }

    @objc private func toggleOpenAtLogin() {
        do {
            try agent.setOpenAtLogin(!agent.currentConfig.settings.openAtLogin)
        } catch {
            alert("Couldn't change Open at Login", "\(error)")
        }
    }

    @objc private func openSetup() {
        setup.show(startAtPairing: agent.currentConfig.pairings.contains(where: \.confirmed))
    }

    @objc private func unpair(_ sender: NSMenuItem) {
        guard let pid = sender.representedObject as? String,
              let pairing = agent.currentConfig.pairings.first(where: { $0.pid == pid })
        else { return }
        let confirmed = ask("Unpair \(pairing.displayName)?",
                            "Your Mac forgets this phone. To sync with it again, set it up again.",
                            button: "Unpair")
        if confirmed { agent.unpair(pid: pid) }
    }

    @objc private func openHelp() {
        if let url = AppBundle.helpURL { NSWorkspace.shared.open(url) }
    }

    @objc private func showLog() {
        Shell.spawn("/usr/bin/open", ["-a", "Console", Paths.logURL.path])
    }

    @objc private func uninstall() {
        let message = "ClipSync stops, forgets your paired phones and moves itself to the Trash. " +
            "Remove the app from your phone separately. To use ClipSync again, download it again."
        if ask("Uninstall ClipSync?", message, button: "Uninstall") {
            agent.uninstall()
        }
    }

    @objc private func quit() {
        let atLogin = agent.currentConfig.settings.openAtLogin ? " or the next time you log in" : ""
        if ask("Quit ClipSync?", "Your clipboard stops syncing until you open ClipSync again\(atLogin).", button: "Quit") {
            agent.shutdown()
        }
    }

    private func ask(_ title: String, _ message: String, button: String) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.addButton(withTitle: button)
        alert.addButton(withTitle: "Cancel")
        return alert.runModal() == .alertFirstButtonReturn
    }

    private func alert(_ title: String, _ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.runModal()
    }

    // MARK: - Notifications

    /// Show banners even while a ClipSync window is in front.
    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
}

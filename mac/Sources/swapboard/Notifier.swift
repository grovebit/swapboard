import AppKit
import SwapboardCore
import Foundation
import UserNotifications

/// Banners. Inside Swapboard.app they're native notifications from "Swapboard". Alfred-only installs
/// (no app bundle) show them through Alfred, or as a plain AppleScript notification without Alfred.
enum Notifier {
    static func notify(_ emoji: String, title: String, body: String = "") {
        let text = "\(emoji) \(title)" + (body.isEmpty ? "" : ": \(body)")
        guard AppBundle.current != nil else { return AlfredNotifier.notify(text) }
        let center = UNUserNotificationCenter.current()
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .denied:
                return // the user turned Swapboard's notifications off in System Settings
            case .notDetermined:
                center.requestAuthorization(options: [.alert, .sound]) { granted, error in
                    if granted {
                        post(title: title, body: body, fallback: text)
                    } else if error != nil {
                        fallback(text)
                    }
                }
            default:
                post(title: title, body: body, fallback: text)
            }
        }
    }

    /// Asks for permission while the setup window is open, so the first banner isn't lost.
    static func requestPermission() {
        guard AppBundle.current != nil else { return }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, error in
            if let error { Log.info("notifications unavailable: \(error.localizedDescription)") }
        }
    }

    private static func post(title: String, body: String, fallback text: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if error != nil { fallback(text) }
        }
    }

    private static func fallback(_ text: String) {
        DispatchQueue.main.async { AlfredNotifier.notify(text) }
    }
}

enum AlfredNotifier {
    static let alfredBundleID = "com.runningwithcrayons.Alfred"

    /// Shows a banner through the workflow's External Trigger "notify", or a plain macOS
    /// notification when Alfred isn't installed. Text is passed as argv, never interpolated into
    /// AppleScript source.
    static func notify(_ text: String) {
        if NSWorkspace.shared.urlForApplication(withBundleIdentifier: alfredBundleID) != nil {
            let script = """
            on run argv
                tell application id "\(alfredBundleID)" to run trigger "notify" in workflow (item 1 of argv) with argument (item 2 of argv)
            end run
            """
            Shell.spawn("/usr/bin/osascript", ["-e", script, Paths.bundleID, text])
        } else {
            let script = """
            on run argv
                display notification (item 1 of argv) with title "Swapboard"
            end run
            """
            Shell.spawn("/usr/bin/osascript", ["-e", script, text])
        }
    }
}

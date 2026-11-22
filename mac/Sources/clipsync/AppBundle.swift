import AppKit
import ClipSyncCore
import Foundation

/// ClipSync.app, the menu-bar app. It contains this same binary.
enum AppBundle {
    static let bundleID = "com.clipsync.mac"

    /// The app bundle this process runs from: opened in Finder, or the agent started by launchd.
    static var current: URL? {
        Bundle.main.bundleIdentifier == bundleID ? Bundle.main.bundleURL : nil
    }

    static var standardLocations: [URL] {
        [URL(fileURLWithPath: "/Applications/ClipSync.app", isDirectory: true),
         FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Applications/ClipSync.app", isDirectory: true)]
    }

    static func isInStandardLocation(_ url: URL) -> Bool {
        let path = url.resolvingSymlinksInPath().standardizedFileURL.path
        return standardLocations.contains { $0.standardizedFileURL.path == path }
    }

    /// The installed app: this one if it's in an Applications folder, otherwise one found there.
    static var installedApp: URL? {
        if let current, isInStandardLocation(current) { return current }
        return standardLocations.first { Bundle(url: $0)?.bundleIdentifier == bundleID }
    }

    /// The project's GitHub page. The build writes it into Info.plist from the git remote.
    static var projectURL: URL? {
        let bundle = current != nil ? Bundle.main : installedApp.flatMap { Bundle(url: $0) }
        guard let value = bundle?.object(forInfoDictionaryKey: "ClipSyncProjectURL") as? String,
              value.hasPrefix("https://"), let url = URL(string: value)
        else { return nil }
        return url
    }

    /// Always the newest Android app: GitHub redirects /releases/latest/download/ to it.
    static var apkDownloadURL: URL? {
        projectURL?.appendingPathComponent("releases/latest/download/ClipSync.apk")
    }

    static var helpURL: URL? {
        projectURL.flatMap { URL(string: $0.absoluteString + "#readme") }
    }
}

/// Runs when someone opens ClipSync.app. It makes sure the app sits in Applications, starts the
/// background agent from it, asks the agent to show its window, and exits.
enum Launcher {
    static func run() -> Never {
        let app = NSApplication.shared
        app.setActivationPolicy(.accessory)
        guard let bundle = AppBundle.current else { exit(0) }
        if needsMove(bundle) { moveToApplications(bundle) }
        // The user has approved this app (it's running), so launchd can start it without Gatekeeper
        // getting in the way.
        removeQuarantine(bundle)
        do {
            try ensureAgentRunsThisBuild()
            guard (try? ControlClient.request(ControlRequest(cmd: "show"), timeout: 5))?.ok == true else {
                throw CLIError("ClipSync started, but its window didn't open")
            }
        } catch {
            alert("ClipSync couldn't start", "\(error)\n\nDetails are in \(Paths.logURL.path)")
        }
        exit(0)
    }

    /// Restarts the agent unless it already runs this exact build from this exact place.
    private static func ensureAgentRunsThisBuild() throws {
        let running = (try? ControlClient.request(ControlRequest(cmd: "status"), timeout: 2))?.status
        let program = Bundle.main.executableURL?.resolvingSymlinksInPath().path
        if let stamp = running?.executableStamp, stamp == ExecutableStamp.current,
           LaunchAgentManager.loadedProgram == program {
            return
        }
        _ = try LaunchAgentManager.start()
    }

    // MARK: - Moving to Applications

    private static func needsMove(_ bundle: URL) -> Bool {
        if ProcessInfo.processInfo.environment["CLIPSYNC_ANY_LOCATION"] == "1" { return false }
        return isTranslocated(bundle) || !AppBundle.isInStandardLocation(bundle)
    }

    private static func moveToApplications(_ bundle: URL) -> Never {
        NSApp.activate(ignoringOtherApps: true)
        let ask = NSAlert()
        ask.messageText = "Move ClipSync to your Applications folder?"
        ask.informativeText = "ClipSync needs to be in Applications so it can keep running in the background and start when you log in."
        ask.addButton(withTitle: "Move to Applications")
        ask.addButton(withTitle: "Quit")
        guard ask.runModal() == .alertFirstButtonReturn else { exit(0) }

        let source = originalLocation(of: bundle) ?? bundle
        do {
            relaunch(try install(source))
        } catch {
            let failed = NSAlert()
            failed.messageText = "ClipSync couldn't move itself"
            failed.informativeText = "Drag ClipSync into the Applications folder in Finder, then open it from there.\n\n(\(error.localizedDescription))"
            failed.addButton(withTitle: "Show ClipSync in Finder")
            failed.addButton(withTitle: "Quit")
            if failed.runModal() == .alertFirstButtonReturn {
                NSWorkspace.shared.activateFileViewerSelecting([source])
            }
            exit(0)
        }
    }

    /// Moves (or, from a read-only disk image, copies) the app into /Applications, or into
    /// ~/Applications without admin rights. An older ClipSync there goes to the Trash.
    private static func install(_ source: URL) throws -> URL {
        let fm = FileManager.default
        let system = URL(fileURLWithPath: "/Applications", isDirectory: true)
        let folder = fm.isWritableFile(atPath: system.path)
            ? system : fm.homeDirectoryForCurrentUser.appendingPathComponent("Applications", isDirectory: true)
        try fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let destination = folder.appendingPathComponent("ClipSync.app", isDirectory: true)
        if destination.standardizedFileURL.path == source.standardizedFileURL.path {
            removeQuarantine(destination) // already there, but quarantined: run it from its real place
            return destination
        }
        if fm.fileExists(atPath: destination.path) {
            try fm.trashItem(at: destination, resultingItemURL: nil)
        }
        let readOnly = (try? source.resourceValues(forKeys: [.volumeIsReadOnlyKey]))?.volumeIsReadOnly ?? true
        if readOnly || !fm.isWritableFile(atPath: source.deletingLastPathComponent().path) {
            try fm.copyItem(at: source, to: destination)
        } else {
            try fm.moveItem(at: source, to: destination)
        }
        removeQuarantine(destination)
        return destination
    }

    private static func relaunch(_ app: URL) -> Never {
        let configuration = NSWorkspace.OpenConfiguration()
        configuration.createsNewApplicationInstance = true
        let done = DispatchSemaphore(value: 0)
        NSWorkspace.shared.openApplication(at: app, configuration: configuration) { _, error in
            if let error { Log.error("relaunch failed: \(error)") }
            done.signal()
        }
        _ = done.wait(timeout: .now() + 15)
        exit(0)
    }

    /// Gatekeeper runs a downloaded app from a hidden read-only copy ("translocation") until the
    /// app is moved.
    private static func isTranslocated(_ url: URL) -> Bool {
        url.path.contains("/AppTranslocation/")
    }

    /// Where a translocated app really is. Security.framework exports this function, but it has
    /// no public header.
    private static func originalLocation(of url: URL) -> URL? {
        guard isTranslocated(url),
              let handle = dlopen("/System/Library/Frameworks/Security.framework/Security", RTLD_LAZY),
              let symbol = dlsym(handle, "SecTranslocateCreateOriginalPathForURL")
        else { return nil }
        typealias Function = @convention(c) (CFURL, UnsafeMutablePointer<Unmanaged<CFError>?>?) -> Unmanaged<CFURL>?
        let function = unsafeBitCast(symbol, to: Function.self)
        return function(url as CFURL, nil)?.takeRetainedValue() as URL?
    }

    static func removeQuarantine(_ url: URL) {
        Shell.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", url.path])
    }

    private static func alert(_ title: String, _ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.runModal()
    }
}

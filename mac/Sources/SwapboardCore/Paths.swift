import Foundation

/// File locations. Everything lives in `~/Library/Application Support/Swapboard/`, or in
/// `SWAPBOARD_DATA_DIR` (used by tests).
public enum Paths {
    private static var env: [String: String] { ProcessInfo.processInfo.environment }
    private static var home: URL { FileManager.default.homeDirectoryForCurrentUser }

    /// Bundle ID of the Alfred workflow, whose External Trigger shows notifications.
    public static let defaultBundleID = "app.swapboard.alfred"
    public static let agentLabel = "app.swapboard.agent"

    public static var bundleID: String {
        env["alfred_workflow_bundleid"] ?? env["SWAPBOARD_BUNDLE_ID"] ?? defaultBundleID
    }

    public static var dataDir: URL {
        if let dir = env["SWAPBOARD_DATA_DIR"], !dir.isEmpty {
            return URL(fileURLWithPath: dir, isDirectory: true)
        }
        return home.appendingPathComponent("Library/Application Support/Swapboard", isDirectory: true)
    }

    public static var configStore: ConfigStore { ConfigStore(directory: dataDir) }
    public static var logURL: URL { dataDir.appendingPathComponent("agent.log") }
    public static var qrURL: URL { dataDir.appendingPathComponent("pair-qr.png") }
    public static var binURL: URL { dataDir.appendingPathComponent("bin/swapboard") }
    public static var launchAgentPlist: URL {
        home.appendingPathComponent("Library/LaunchAgents/\(agentLabel).plist")
    }

    /// Unix socket paths are limited to 104 bytes; fall back to the per-user temp dir.
    public static var socketPath: String {
        let preferred = dataDir.appendingPathComponent("agent.sock").path
        if preferred.utf8.count < 100 { return preferred }
        return (NSTemporaryDirectory() as NSString).appendingPathComponent("swapboard-agent.sock")
    }

    public static func ensureDataDir() throws {
        try FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
    }
}

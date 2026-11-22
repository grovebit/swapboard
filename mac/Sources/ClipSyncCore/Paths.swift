import Foundation

/// File locations. The CLI gets `alfred_workflow_data` from Alfred; the LaunchAgent gets
/// `CLIPSYNC_DATA_DIR`. Both resolve to the same directory.
public enum Paths {
    private static var env: [String: String] { ProcessInfo.processInfo.environment }

    public static let defaultBundleID = "com.clipsync.alfred"
    public static let agentLabel = "com.clipsync.agent"

    public static var bundleID: String {
        env["alfred_workflow_bundleid"] ?? env["CLIPSYNC_BUNDLE_ID"] ?? defaultBundleID
    }

    public static var dataDir: URL {
        if let dir = env["CLIPSYNC_DATA_DIR"] ?? env["alfred_workflow_data"], !dir.isEmpty {
            return URL(fileURLWithPath: dir, isDirectory: true)
        }
        return FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Alfred/Workflow Data", isDirectory: true)
            .appendingPathComponent(bundleID, isDirectory: true)
    }

    public static var configStore: ConfigStore { ConfigStore(directory: dataDir) }
    public static var logURL: URL { dataDir.appendingPathComponent("agent.log") }
    public static var qrURL: URL { dataDir.appendingPathComponent("pair-qr.png") }
    public static var binURL: URL { dataDir.appendingPathComponent("bin/clipsync") }
    public static var launchAgentPlist: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents/\(agentLabel).plist")
    }

    /// Unix socket paths are limited to 104 bytes; fall back to the per-user temp dir.
    public static var socketPath: String {
        let preferred = dataDir.appendingPathComponent("agent.sock").path
        if preferred.utf8.count < 100 { return preferred }
        return (NSTemporaryDirectory() as NSString).appendingPathComponent("clipsync-agent.sock")
    }

    public static func ensureDataDir() throws {
        try FileManager.default.createDirectory(at: dataDir, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
    }
}

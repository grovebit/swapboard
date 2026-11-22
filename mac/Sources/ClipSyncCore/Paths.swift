import Foundation

/// File locations. Everything lives in `~/Library/Application Support/ClipSync/`, or in
/// `CLIPSYNC_DATA_DIR` (used by tests). Versions before 1.4 kept their data in Alfred's workflow
/// data folder; `LaunchAgentManager.start()` moves it over once the old agent has stopped.
public enum Paths {
    private static var env: [String: String] { ProcessInfo.processInfo.environment }
    private static var home: URL { FileManager.default.homeDirectoryForCurrentUser }

    /// Bundle ID of the Alfred workflow, whose External Trigger shows notifications.
    public static let defaultBundleID = "com.clipsync.alfred"
    public static let agentLabel = "com.clipsync.agent"

    public static var bundleID: String {
        env["alfred_workflow_bundleid"] ?? env["CLIPSYNC_BUNDLE_ID"] ?? defaultBundleID
    }

    public static var standardDataDir: URL {
        home.appendingPathComponent("Library/Application Support/ClipSync", isDirectory: true)
    }

    /// Where versions before 1.4 kept their data.
    public static var legacyDataDir: URL {
        home.appendingPathComponent("Library/Application Support/Alfred/Workflow Data/com.clipsync.alfred",
                                    isDirectory: true)
    }

    public static var dataDir: URL {
        if let dir = env["CLIPSYNC_DATA_DIR"], !dir.isEmpty {
            return URL(fileURLWithPath: dir, isDirectory: true)
        }
        return resolveDataDir(standard: standardDataDir, legacy: legacyDataDir)
    }

    /// The standard folder, unless only the legacy folder has a config. That means the data hasn't
    /// been migrated yet, and an older agent may still be running from the legacy folder.
    public static func resolveDataDir(standard: URL, legacy: URL) -> URL {
        let fm = FileManager.default
        if !fm.fileExists(atPath: standard.appendingPathComponent("config.json").path),
           fm.fileExists(atPath: legacy.appendingPathComponent("config.json").path) {
            return legacy
        }
        return standard
    }

    /// Moves the pairing config and log out of the legacy folder. Only call this while no agent
    /// runs, because a running older agent still reads the legacy folder.
    @discardableResult
    public static func migrateLegacyData() throws -> Bool {
        if let dir = env["CLIPSYNC_DATA_DIR"], !dir.isEmpty { return false }
        return try migrate(from: legacyDataDir, to: standardDataDir)
    }

    /// Moves `config.json` and `agent.log` from `old` to `new`, unless `new` already has a config.
    /// Removes what's left of the old folder's ClipSync files. Returns true if the config moved.
    @discardableResult
    public static func migrate(from old: URL, to new: URL) throws -> Bool {
        let fm = FileManager.default
        let oldConfig = old.appendingPathComponent("config.json")
        let newConfig = new.appendingPathComponent("config.json")
        guard fm.fileExists(atPath: oldConfig.path), !fm.fileExists(atPath: newConfig.path) else { return false }
        try fm.createDirectory(at: new, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fm.moveItem(at: oldConfig, to: newConfig)
        let oldLog = old.appendingPathComponent("agent.log")
        let newLog = new.appendingPathComponent("agent.log")
        if fm.fileExists(atPath: oldLog.path), !fm.fileExists(atPath: newLog.path) {
            try? fm.moveItem(at: oldLog, to: newLog)
        }
        for leftover in ["agent.log", "pair-qr.png", "bin", "agent.sock"] {
            try? fm.removeItem(at: old.appendingPathComponent(leftover))
        }
        if (try? fm.contentsOfDirectory(atPath: old.path))?.isEmpty == true {
            try? fm.removeItem(at: old)
        }
        return true
    }

    public static var configStore: ConfigStore { ConfigStore(directory: dataDir) }
    public static var logURL: URL { dataDir.appendingPathComponent("agent.log") }
    public static var qrURL: URL { dataDir.appendingPathComponent("pair-qr.png") }
    public static var binURL: URL { dataDir.appendingPathComponent("bin/clipsync") }
    public static var launchAgentPlist: URL {
        home.appendingPathComponent("Library/LaunchAgents/\(agentLabel).plist")
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

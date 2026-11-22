import SwapboardCore
import Foundation

/// Installs `~/Library/LaunchAgents/app.swapboard.agent.plist` and drives it with launchctl.
///
/// The agent runs the installed Swapboard.app's executable. Alfred-only installs (no app) run a
/// private copy of this binary in the data folder instead, so the agent never depends on where
/// the workflow lives.
enum LaunchAgentManager {
    static var domain: String { "gui/\(getuid())" }
    static var service: String { "\(domain)/\(Paths.agentLabel)" }

    static func isLoaded() -> Bool {
        Shell.run("/bin/launchctl", ["print", service]).status == 0
    }

    /// The executable of the loaded agent job (it stays loaded after its plist is removed).
    static var loadedProgram: String? {
        let result = Shell.run("/bin/launchctl", ["print", service])
        guard result.status == 0 else { return nil }
        for line in result.output.split(separator: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("program = ") { return String(trimmed.dropFirst("program = ".count)) }
        }
        return nil
    }

    static func start() throws -> String {
        if isLoaded() { bootout() }
        try Paths.ensureDataDir()
        try writePlist(program: try agentProgram())
        let result = Shell.run("/bin/launchctl", ["bootstrap", domain, Paths.launchAgentPlist.path])
        guard result.status == 0 else {
            throw CLIError("launchctl bootstrap failed: \(result.output.trimmingCharacters(in: .whitespacesAndNewlines))")
        }
        // With "Open at Login" off, the agent runs for this session only: launchd keeps a loaded
        // job after its plist is removed, but doesn't load it again at the next login.
        if !Paths.configStore.load().settings.openAtLogin {
            try? FileManager.default.removeItem(at: Paths.launchAgentPlist)
        }
        for _ in 0..<50 where !ControlClient.isAgentRunning() { usleep(100_000) }
        guard ControlClient.isAgentRunning() else {
            throw CLIError("agent did not start; see \(Paths.logURL.path)")
        }
        return "Swapboard agent started"
    }

    static func stop() throws -> String {
        if isLoaded() { bootout() }
        try? FileManager.default.removeItem(at: Paths.launchAgentPlist)
        return "Swapboard agent stopped"
    }

    /// Turns starting at login on or off without restarting the running agent.
    static func setOpenAtLogin(_ enabled: Bool) throws {
        try Paths.configStore.update { $0.settings.openAtLogin = enabled }
        if enabled {
            try writePlist(program: try agentProgram())
        } else {
            try? FileManager.default.removeItem(at: Paths.launchAgentPlist)
        }
    }

    /// The executable the agent runs: Swapboard.app's when the app is installed.
    private static func agentProgram() throws -> URL {
        if let app = AppBundle.installedApp, let executable = Bundle(url: app)?.executableURL {
            return executable
        }
        return try installPrivateCopy()
    }

    /// Alfred-only installs: a private copy, so the agent doesn't depend on the workflow folder
    /// (which may live on an external disk).
    private static func installPrivateCopy() throws -> URL {
        guard let source = Bundle.main.executableURL?.resolvingSymlinksInPath() else {
            throw CLIError("cannot locate the swapboard binary")
        }
        let destination = Paths.binURL
        let fm = FileManager.default
        try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true,
                               attributes: [.posixPermissions: 0o700])
        if source.path != destination.path {
            let tmp = destination.deletingLastPathComponent().appendingPathComponent(".swapboard.\(getpid())")
            try? fm.removeItem(at: tmp)
            try fm.copyItem(at: source, to: tmp)
            guard rename(tmp.path, destination.path) == 0 else { throw CLIError("cannot install agent binary") }
        }
        return destination
    }

    private static func writePlist(program: URL) throws {
        var environment = ["SWAPBOARD_BUNDLE_ID": Paths.bundleID]
        if let dir = ProcessInfo.processInfo.environment["SWAPBOARD_DATA_DIR"], !dir.isEmpty {
            environment["SWAPBOARD_DATA_DIR"] = dir
        }
        let plist: [String: Any] = [
            "Label": Paths.agentLabel,
            "ProgramArguments": [program.path, "agent"],
            "RunAtLoad": true,
            // Restart after a crash, but not after the user chooses Quit (exit code 0).
            "KeepAlive": ["SuccessfulExit": false],
            "ThrottleInterval": 10,
            "ProcessType": "Interactive",
            "StandardOutPath": Paths.logURL.path,
            "StandardErrorPath": Paths.logURL.path,
            "EnvironmentVariables": environment,
        ]
        let data = try PropertyListSerialization.data(fromPropertyList: plist, format: .xml, options: 0)
        try FileManager.default.createDirectory(at: Paths.launchAgentPlist.deletingLastPathComponent(),
                                                withIntermediateDirectories: true)
        try data.write(to: Paths.launchAgentPlist, options: .atomic)
    }

    private static func bootout() {
        Shell.run("/bin/launchctl", ["bootout", service])
        for _ in 0..<30 where isLoaded() { usleep(100_000) }
    }
}

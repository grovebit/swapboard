import Foundation
import XCTest
@testable import ClipSyncCore

/// Moving data from the pre-1.4 Alfred folder to ~/Library/Application Support/ClipSync.
final class PathsTests: XCTestCase {
    private var root: URL!
    private var old: URL { root.appendingPathComponent("old") }
    private var new: URL { root.appendingPathComponent("new") }
    private let fm = FileManager.default

    override func setUpWithError() throws {
        root = fm.temporaryDirectory.appendingPathComponent("clipsync-paths-\(UUID().uuidString)")
        try fm.createDirectory(at: old.appendingPathComponent("bin"), withIntermediateDirectories: true)
        try Data(#"{"pairings":[]}"#.utf8).write(to: old.appendingPathComponent("config.json"))
        try Data("log".utf8).write(to: old.appendingPathComponent("agent.log"))
        try Data().write(to: old.appendingPathComponent("bin/clipsync"))
    }

    override func tearDownWithError() throws {
        try? fm.removeItem(at: root)
    }

    func testUsesLegacyFolderUntilMigrated() throws {
        XCTAssertEqual(Paths.resolveDataDir(standard: new, legacy: old), old)

        XCTAssertTrue(try Paths.migrate(from: old, to: new))

        XCTAssertEqual(Paths.resolveDataDir(standard: new, legacy: old), new)
        XCTAssertEqual(try String(contentsOf: new.appendingPathComponent("config.json")), #"{"pairings":[]}"#)
        XCTAssertTrue(fm.fileExists(atPath: new.appendingPathComponent("agent.log").path))
        XCTAssertFalse(fm.fileExists(atPath: old.path), "the emptied legacy folder is removed")
    }

    func testMigrationRunsOnlyOnce() throws {
        XCTAssertTrue(try Paths.migrate(from: old, to: new))
        XCTAssertFalse(try Paths.migrate(from: old, to: new))
    }

    func testNeverOverwritesAnExistingConfig() throws {
        try fm.createDirectory(at: new, withIntermediateDirectories: true)
        try Data(#"{"settings":{}}"#.utf8).write(to: new.appendingPathComponent("config.json"))

        XCTAssertEqual(Paths.resolveDataDir(standard: new, legacy: old), new)
        XCTAssertFalse(try Paths.migrate(from: old, to: new))
        XCTAssertEqual(try String(contentsOf: new.appendingPathComponent("config.json")), #"{"settings":{}}"#)
        XCTAssertTrue(fm.fileExists(atPath: old.appendingPathComponent("config.json").path))
    }

    func testFreshInstallUsesStandardFolder() throws {
        try fm.removeItem(at: old)
        XCTAssertEqual(Paths.resolveDataDir(standard: new, legacy: old), new)
        XCTAssertFalse(try Paths.migrate(from: old, to: new))
    }

    func testOpenAtLoginDefaultsToOnForOlderConfigs() throws {
        let settings = try JSONDecoder().decode(Settings.self, from: Data(#"{"port":48613}"#.utf8))
        XCTAssertTrue(settings.openAtLogin)
    }
}

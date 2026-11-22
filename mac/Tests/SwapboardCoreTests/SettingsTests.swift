import Foundation
import XCTest
@testable import SwapboardCore

final class SettingsTests: XCTestCase {
    func testOpenAtLoginDefaultsToOn() throws {
        let settings = try JSONDecoder().decode(Settings.self, from: Data(#"{"port":48613}"#.utf8))
        XCTAssertTrue(settings.openAtLogin)
    }
}

import XCTest

final class NotesSmokeTests: XCTestCase {
    func testMapAndBackupScreensLoad() {
        let app = XCUIApplication()
        app.launch()
        let web = app.webViews.firstMatch
        XCTAssertTrue(web.buttons["Haritayı yakınlaştır"].waitForExistence(timeout: 20))
        let backup = web.buttons["Yedek"]
        XCTAssertTrue(backup.waitForExistence(timeout: 5))
        backup.tap()
        XCTAssertTrue(web.buttons["Dosya Seç"].waitForExistence(timeout: 5))
        XCTAssertTrue(web.buttons["JSON İndir"].exists)
    }
}

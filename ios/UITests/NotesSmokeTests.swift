import XCTest

final class NotesSmokeTests: XCTestCase {
    func testImportedNoteSurvivesRelaunch() {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-test-import-fixture"]
        app.launch()
        let web = app.webViews.firstMatch

        openBackup(web)
        web.buttons["Dosya Seç"].tap()
        let confirm = web.buttons["İçe aktar"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 10))
        confirm.tap()
        XCTAssertTrue(web.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "1 not içe aktarıldı")).firstMatch.waitForExistence(timeout: 10))

        app.terminate()
        app.launch()
        openBackup(web)
        web.buttons["Dosya Seç"].tap()
        XCTAssertTrue(web.staticTexts["Yedek kontrol edildi"].waitForExistence(timeout: 10))
        XCTAssertFalse(confirm.exists)
    }

    private func openBackup(_ web: XCUIElement) {
        let backup = web.buttons["Yedek"]
        XCTAssertTrue(backup.waitForExistence(timeout: 20))
        backup.tap()
        XCTAssertTrue(web.buttons["Dosya Seç"].waitForExistence(timeout: 5))
    }

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

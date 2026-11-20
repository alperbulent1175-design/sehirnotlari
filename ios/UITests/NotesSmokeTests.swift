import XCTest

final class NotesSmokeTests: XCTestCase {
    func testBackupPreviewsJSONFromSystemFilePicker() {
        let token = UUID().uuidString
        let app = launchWithEnglishSystemControls(fixtureToken: token)
        let web = app.webViews.firstMatch
        openBackup(web)
        web.buttons["Dosya Seç"].tap()

        // WebKit may present a source menu before opening the Files picker.
        let chooseFiles = app.buttons.matching(NSPredicate(format: "label IN %@", ["Choose Files", "Choose File"])).firstMatch
        let cancel = app.buttons["Cancel"].firstMatch
        let filesPicker = control(in: app, labels: ["Browse", "Recents", "Locations"])
        let continueButton = app.buttons.matching(NSPredicate(format: "label IN %@", ["Continue", "Get Started"])).firstMatch
        let presentation = NSPredicate { _, _ in
            chooseFiles.exists || continueButton.exists || (filesPicker.exists && cancel.exists)
        }
        let pickerOpened = XCTNSPredicateExpectation(predicate: presentation, object: nil)
        guard XCTWaiter.wait(for: [pickerOpened], timeout: 10) == .completed else {
            XCTFail("WebKit did not present a file source menu or the Files picker.\n\(app.debugDescription)")
            return
        }
        if chooseFiles.exists {
            chooseFiles.tap()
        }
        let filesOpened = NSPredicate { _, _ in continueButton.exists || filesPicker.exists }
        let filesOpenedExpectation = XCTNSPredicateExpectation(predicate: filesOpened, object: nil)
        _ = XCTWaiter.wait(for: [filesOpenedExpectation], timeout: 10)
        if continueButton.exists {
            continueButton.tap()
        }
        guard filesPicker.waitForExistence(timeout: 10), cancel.waitForExistence(timeout: 5) else {
            XCTFail("The system Files picker did not open.\n\(app.debugDescription)")
            return
        }

        let filename = "xctest-yedek-\(token)"
        let fixture = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH %@", filename)).firstMatch
        guard openFixture(fixture, in: app) else {
            XCTFail("The synthetic JSON was not accessible through Files.\n\(app.debugDescription)")
            return
        }
        let preview = web.staticTexts["Yedek içe aktarılsın mı?"]
        let confirm = web.buttons["İçe aktar"]
        XCTAssertTrue(preview.waitForExistence(timeout: 10), app.debugDescription)
        XCTAssertTrue(confirm.exists, app.debugDescription)
        let details = web.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "\(filename).json")).firstMatch
        XCTAssertTrue(details.exists, app.debugDescription)
        web.buttons["Vazgeç"].tap()
        XCTAssertTrue(waitUntilHittable(web.buttons["Dosya Seç"]), app.debugDescription)
        XCTAssertFalse(confirm.exists, app.debugDescription)
    }

    private func openFixture(_ fixture: XCUIElement, in app: XCUIApplication) -> Bool {
        if fixture.exists && fixture.isHittable {
            fixture.tap()
            return true
        }

        let localDevice = control(in: app, labels: ["On My iPhone"])
        if !localDevice.exists {
            let browse = app.tabBars.buttons["Browse"].firstMatch
            guard waitUntilHittable(browse) else { return false }
            browse.tap()
        }
        guard waitUntilHittable(localDevice) else { return false }
        localDevice.tap()

        let appFolder = app.collectionViews.descendants(matching: .any)
            .matching(NSPredicate(format: "label BEGINSWITH %@ OR label BEGINSWITH %@", "Şehir Notları", "SehirNotlari")).firstMatch
        guard waitUntilHittable(appFolder) else { return false }
        appFolder.tap()

        guard waitUntilHittable(fixture) else { return false }
        fixture.tap()
        return true
    }

    private func control(in app: XCUIApplication, labels: [String]) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label IN %@", labels)).firstMatch
    }

    func testBackupOpensSystemShareSheet() {
        let app = launchWithEnglishSystemControls()
        let web = app.webViews.firstMatch
        openBackup(web)
        web.buttons["JSON İndir"].tap()

        let filename = app.descendants(matching: .any)
            .matching(NSPredicate(format: "label IN %@", ["sehir-notlari", "sehir-notlari.json"])).firstMatch
        guard filename.waitForExistence(timeout: 10) else {
            XCTFail("The share sheet did not show the exported filename.\n\(app.debugDescription)")
            return
        }
        let dismiss = app.buttons.matching(NSPredicate(format: "label IN %@", ["Close", "Cancel"])).firstMatch
        guard dismiss.waitForExistence(timeout: 5) else {
            XCTFail("The share sheet did not offer a dismissal control.\n\(app.debugDescription)")
            return
        }
        dismiss.tap()
        XCTAssertTrue(waitUntilHittable(web.buttons["JSON İndir"]))
        XCTAssertFalse(filename.exists)
    }

    private func waitUntilHittable(_ element: XCUIElement) -> Bool {
        let hittable = NSPredicate { _, _ in element.exists && element.isHittable }
        let expectation = XCTNSPredicateExpectation(predicate: hittable, object: nil)
        return XCTWaiter.wait(for: [expectation], timeout: 30) == .completed
    }

    private func launchWithEnglishSystemControls(fixtureToken: String? = nil) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        if let fixtureToken {
            app.launchEnvironment["SEHIR_NOTLARI_SYSTEM_IMPORT_FIXTURE"] = fixtureToken
        }
        app.launch()
        return app
    }

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
        XCTAssertTrue(web.buttons["Haritayı yakınlaştır"].waitForExistence(timeout: 30))
        XCTAssertTrue(waitUntilHittable(backup))
        backup.tap()
        let choose = web.buttons["Dosya Seç"]
        if !choose.waitForExistence(timeout: 5) {
            backup.tap()
        }
        XCTAssertTrue(waitUntilHittable(choose))
    }

    func testMapAndBackupScreensLoad() {
        let app = XCUIApplication()
        app.launch()
        let web = app.webViews.firstMatch
        XCTAssertTrue(web.buttons["Haritayı yakınlaştır"].waitForExistence(timeout: 20))
        let backup = web.buttons["Yedek"]
        XCTAssertTrue(backup.waitForExistence(timeout: 5))
        backup.tap()
        XCTAssertTrue(waitUntilHittable(web.buttons["Dosya Seç"]))
        XCTAssertTrue(web.buttons["JSON İndir"].exists)
    }
}

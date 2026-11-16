import XCTest

final class DailyPracticeUITests: XCTestCase {
    func testDailyPracticeEntryAndUnavailableStateAreAccessible() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-existing-user", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        app.tabBars.buttons["Learn"].tap()
        let entry = app.buttons["daily-practice-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        XCTAssertTrue(app.staticTexts["Daily practice"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["daily-practice-back"].exists)
    }
}

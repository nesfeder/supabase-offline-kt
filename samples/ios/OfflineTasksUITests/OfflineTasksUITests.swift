// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import XCTest

/// The app driven as a user would, in demo mode: no project and no network involved.
final class OfflineTasksUITests: XCTestCase {

    override func setUp() {
        continueAfterFailure = false
    }

    /// Launch, go offline, add a task: it shows at once, marked as not synced, and counted.
    @MainActor
    func testATaskAddedOfflineIsMarkedAndCounted() {
        let app = launchOffline()
        addTask("Buy milk", in: app)

        // It shows at once, marked, and the change is counted.
        XCTAssertTrue(app.staticTexts["Buy milk"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.images["not-synced"].waitForExistence(timeout: 5))
        let status = app.buttons["sync-status"]
        XCTAssertTrue(status.waitForExistence(timeout: 5))
        XCTAssertEqual(status.label, "Offline — 1 change is saved on this device")
        app.tabBars.buttons["Sync"].tap()
        let waiting = app.staticTexts["changes-waiting"]
        XCTAssertTrue(waiting.waitForExistence(timeout: 5))
        XCTAssertEqual(waiting.label, "1 change waiting")
    }

    /// Offline, a task is deleted from its own screen: Delete task is reachable while the offline
    /// status shows above the tab bar.
    @MainActor
    func testATaskIsDeletedFromItsScreenOffline() {
        let app = launchOffline()
        addTask("Return the books", in: app)
        let task = app.staticTexts["Return the books"]
        XCTAssertTrue(task.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["sync-status"].waitForExistence(timeout: 5))

        // Open it, and delete it from its screen.
        task.tap()
        let deleteTask = app.buttons["Delete task"]
        XCTAssertTrue(deleteTask.waitForExistence(timeout: 5))
        deleteTask.tap()
        let confirm = app.buttons["Delete"]
        XCTAssertTrue(confirm.waitForExistence(timeout: 5))
        confirm.tap()

        // The screen closes, and the list no longer has it.
        XCTAssertTrue(app.staticTexts["No tasks yet"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Return the books"].exists)
    }

    /// The app in demo mode, with Simulate offline switched on and the Tasks tab in front.
    @MainActor
    private func launchOffline() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-demo-mode"]
        app.launch()
        XCTAssertTrue(app.staticTexts["No tasks yet"].waitForExistence(timeout: 15))

        // Simulate offline, on the Sync tab.
        app.tabBars.buttons["Sync"].tap()
        let simulateOffline = app.switches["simulate-offline"]
        XCTAssertTrue(simulateOffline.waitForExistence(timeout: 5))
        let control = simulateOffline.switches.firstMatch.exists ? simulateOffline.switches.firstMatch : simulateOffline
        control.tap()
        XCTAssertTrue(app.staticTexts["Offline (simulated)"].waitForExistence(timeout: 5))
        app.tabBars.buttons["Tasks"].tap()
        return app
    }

    /// Adds a task titled `title` through New task.
    @MainActor
    private func addTask(_ title: String, in app: XCUIApplication) {
        app.buttons["new-task"].tap()
        let field = app.textFields["new-task-title"]
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(title)
        app.buttons["add-task"].tap()
    }
}

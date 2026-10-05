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

        // A new task, offline.
        app.tabBars.buttons["Tasks"].tap()
        app.buttons["new-task"].tap()
        let title = app.textFields["new-task-title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        title.tap()
        title.typeText("Buy milk")
        app.buttons["add-task"].tap()

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
}

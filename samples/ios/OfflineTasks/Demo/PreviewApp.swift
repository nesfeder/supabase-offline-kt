// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

extension AppModel {

    /// The app for Xcode's previews: demo mode, opened on `tab`, with a few of the demo user's tasks
    /// already on the stand-in server.
    static func preview(tab: AppTab = .tasks) -> AppModel {
        let session = DemoSession()
        let userID = session.userID ?? ""
        let now = Date.now
        let server = DemoServer(tasks: [
            TaskRow(id: DeviceID.make(), userID: userID, title: "Buy milk", notes: "Two liters", done: false, createdAt: now),
            TaskRow(id: DeviceID.make(), userID: userID, title: "Call the bank", notes: nil, done: false, createdAt: now - 60),
            TaskRow(id: DeviceID.make(), userID: userID, title: "Water the plants", notes: nil, done: true, createdAt: now - 120),
        ])
        let app = AppModel.demo(server: server, session: session)
        app.selectedTab = tab
        return app
    }
}

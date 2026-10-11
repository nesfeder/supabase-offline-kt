// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SwiftUI

/// Offline Tasks: a to-do list with photos that keeps working offline, built on SupabaseOffline.
@main
struct OfflineTasksApp: App {

    /// The app's state, with its one offline client, made once at launch.
    @State private var app = AppModel.launch()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
        }
    }
}

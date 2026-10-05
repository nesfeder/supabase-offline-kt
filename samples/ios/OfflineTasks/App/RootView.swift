// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// The two tabs, the sync status above the tab bar, and the alerts any screen raises.
struct RootView: View {

    @Environment(AppModel.self) private var app

    var body: some View {
        @Bindable var app = app
        tabs
            .task { await app.run() }
            // The live reads the tabs share run here, for as long as the app does, and start again
            // for each user.
            .task(id: app.session.userID) { await app.tasks.observeTasks() }
            .task(id: app.session.userID) { await app.tasks.observePendingTasks() }
            .task(id: app.session.userID) { await app.sync.observe() }
        .alert(
            app.failure?.message ?? "",
            isPresented: Binding(get: { app.failure != nil }, set: { if !$0 { app.dismissFailure() } }),
            presenting: app.failure
        ) { failure in
            if failure.opensRefusedChanges {
                Button("View") { app.openRefusedChanges() }
            }
            Button("OK", role: .cancel) {}
        }
    }

    /// Tasks and Sync, with the count of changes waiting on Sync.
    private var tabs: some View {
        @Bindable var app = app
        return TabView(selection: $app.selectedTab) {
            Tab("Tasks", systemImage: "checklist", value: AppTab.tasks) {
                NavigationStack { TasksView() }
            }
            Tab("Sync", systemImage: "arrow.triangle.2.circlepath", value: AppTab.sync) {
                NavigationStack { SyncView() }
            }
            .badge(app.syncStatus.pendingMutationCount)
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .syncStatusAccessory(app.headline) { app.selectedTab = .sync }
    }
}

extension View {

    /// The status above the tab bar while there is something to say: offline, syncing, changes
    /// waiting, or showing saved data. Tapping it opens the Sync tab.
    @ViewBuilder
    func syncStatusAccessory(_ headline: SyncHeadline, open: @escaping () -> Void) -> some View {
        if #available(iOS 26.1, *) {
            tabViewBottomAccessory(isEnabled: headline.banner != nil) {
                SyncStatusLabel(headline: headline, open: open)
            }
        } else {
            tabViewBottomAccessory {
                SyncStatusLabel(headline: headline, open: open)
            }
        }
    }
}

/// The status line in the tab bar's accessory.
struct SyncStatusLabel: View {

    /// What to say.
    let headline: SyncHeadline

    /// Opens the Sync tab.
    let open: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Button(action: open) {
            Label(headline.banner ?? "All changes synced", systemImage: headline.systemImage)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.pulse, isActive: isSyncing && !reduceMotion)
                .font(.subheadline)
                .lineLimit(1)
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("sync-status")
    }

    /// Whether the queue is being sent now.
    private var isSyncing: Bool {
        if case .syncing = headline { return true }
        return false
    }
}

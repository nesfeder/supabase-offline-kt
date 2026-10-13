// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// The three tabs, the sync status above the tab bar, what a write just did, and the alerts any
/// screen raises.
struct RootView: View {

    @Environment(AppModel.self) private var app
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        @Bindable var app = app
        tabs
            .task { await app.run() }
            // The live reads the tabs share run here, for as long as the app does, and start again
            // for each user, and for each list the Tasks screen asks for.
            .task(id: app.session.userID) { await app.tasks.observeTasks() }
            .task(id: app.session.userID) { await app.tasks.observePendingTasks() }
            .task(id: app.session.userID) { await app.tasks.readOpenCountAtLaunch() }
            .task(id: app.session.userID) { await app.sync.observe() }
            .task(id: VisibleListKey(userID: app.session.userID, query: app.tasks.query, prefersFreshData: app.prefersFreshData)) {
                await app.tasks.observeVisibleTasks(readStrategy: app.listReadStrategy)
            }
            .task(id: app.liveUpdatesKey) { await app.applyLiveUpdates() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { app.syncOnForeground() }
            }
            .overlay(alignment: .top) {
                if let notice = app.notice { NoticeLabel(text: notice) }
            }
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

    /// Tasks, Photos and Sync, with the open tasks and the changes waiting or refused as badges.
    private var tabs: some View {
        @Bindable var app = app
        return TabView(selection: $app.selectedTab) {
            Tab("Tasks", systemImage: "checklist", value: AppTab.tasks) {
                NavigationStack { TasksView() }
            }
            .badge(app.tasks.openCount ?? 0)
            Tab("Photos", systemImage: "photo.on.rectangle", value: AppTab.photos) {
                NavigationStack { PhotosView() }
            }
            Tab("Sync", systemImage: "arrow.triangle.2.circlepath", value: AppTab.sync) {
                NavigationStack { SyncView() }
            }
            .badge(app.syncBadge)
        }
        .tabBarMinimizeBehavior(.onScrollDown)
        .syncStatusAccessory(app.headline) { app.selectedTab = .sync }
    }
}

/// What the Tasks screen's list depends on: the user, the query and the read strategy.
private struct VisibleListKey: Hashable {
    let userID: String?
    let query: TaskQuery
    let prefersFreshData: Bool
}

/// What a write just did, for a moment at the top of the screen.
struct NoticeLabel: View {

    /// What to say.
    let text: String

    var body: some View {
        Text(text)
            .font(.subheadline)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .glassEffect(in: .capsule)
            .padding(.horizontal)
            .padding(.top, 4)
            .transition(.move(edge: .top).combined(with: .opacity))
            .accessibilityIdentifier("notice")
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

#Preview("Tasks") {
    RootView().environment(AppModel.preview())
}

#Preview("Sync") {
    RootView().environment(AppModel.preview(tab: .sync))
}

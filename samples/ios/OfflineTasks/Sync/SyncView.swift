// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// The network and Simulate offline, the changes waiting, the list's refresh, the refused changes,
/// and the two ways to start again.
struct SyncView: View {

    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isConfirmingClear = false
    @State private var isConfirmingStartOver = false

    var body: some View {
        Form {
            connection
            changesWaiting
            refresh
            refusedChanges
            tryIt
            localData
            about
        }
        .navigationTitle("Sync")
        .disabled(app.sync.isStartingOver)
        .overlay {
            if app.sync.isStartingOver {
                ProgressView("Starting over…")
                    .padding()
                    .background(.regularMaterial, in: .rect(cornerRadius: 16))
            }
        }
    }

    // MARK: - Sections

    /// The network as the client sees it, and the switch that cuts this app off.
    private var connection: some View {
        Section {
            Label {
                VStack(alignment: .leading, spacing: 2) {
                    Text(networkTitle)
                    Text(networkBody).font(.footnote).foregroundStyle(.secondary)
                }
            } icon: {
                Image(systemName: isOffline ? "wifi.slash" : "wifi")
                    .contentTransition(.symbolEffect(.replace))
                    .symbolEffect(.breathe, isActive: isOffline && !reduceMotion)
            }
            .accessibilityIdentifier("network-status")
            Toggle("Simulate offline", isOn: Binding(get: { app.isSimulatingOffline }, set: { app.setSimulateOffline($0) }))
                .accessibilityIdentifier("simulate-offline")
        } header: {
            Text("Connection")
        } footer: {
            Text(
                app.mode.isDemo
                    ? "Cuts this app off from the server, as airplane mode would, until you switch it back."
                    : "Cuts this app off from the server, as airplane mode would, until you switch it back. It stays on when the app restarts, so a simulator, which has no airplane mode, can start offline."
            )
        }
    }

    /// The queue, per table, and Sync now.
    private var changesWaiting: some View {
        let status = app.syncStatus
        return Section("Changes waiting") {
            VStack(alignment: .leading, spacing: 4) {
                Text(
                    status.pendingMutationCount == 0
                        ? "Nothing waiting" : "\(Wording.count(status.pendingMutationCount, "change", "changes")) waiting"
                )
                .contentTransition(.numericText(value: Double(status.pendingMutationCount)))
                .accessibilityIdentifier("changes-waiting")
                Text("Tasks: \(app.sync.pendingTaskCount) · Photos: \(app.sync.pendingPhotoCount)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text(lastSynced(status))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                if status.lastSyncError != nil {
                    Text("The last sync stopped early; the rest waits for the server.")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }
            .animation(reduceMotion ? nil : .default, value: status.pendingMutationCount)
            Button(app.sync.isSyncNowRunning ? "Syncing" : "Sync now", systemImage: "arrow.triangle.2.circlepath") {
                app.sync.syncNow()
            }
            .disabled(isOffline || app.sync.isSyncNowRunning)
            if isOffline {
                Text("Sync needs the network.").font(.footnote).foregroundStyle(.secondary)
            }
            if let report = app.sync.lastReport {
                Text("Sent \(report.syncedCount) · refused \(report.rejected.count) · still waiting \(report.remainingCount)")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// The task list's refresh from the server.
    private var refresh: some View {
        Section("Refresh") {
            LabeledContent("Task list", value: refreshText)
        }
    }

    /// The writes the server refused, with Retry and Dismiss.
    private var refusedChanges: some View {
        Section {
            if app.sync.rejected.isEmpty {
                Text("Nothing refused")
            }
            ForEach(app.sync.rejected) { rejection in
                VStack(alignment: .leading, spacing: 6) {
                    Text("Couldn’t save “\(Wording.title(rejection.taskTitle))”").font(.headline)
                    Text(rejection.reason).font(.subheadline)
                    if let serverError = rejection.serverError {
                        Text("The server answered HTTP \(serverError.status) · \(serverError.code ?? "no code")")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    HStack {
                        Button("Retry") { app.sync.retry(rejection) }
                            .disabled(!rejection.isRetryable)
                        Button("Dismiss", role: .destructive) { app.sync.dismiss(rejection) }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                }
                .accessibilityIdentifier("refused-change")
            }
        } header: {
            Text("Refused changes")
        } footer: {
            if app.sync.rejected.isEmpty {
                Text("If the server refuses a change made offline, it shows here.")
            }
        }
    }

    /// A write the server refuses, on purpose.
    private var tryIt: some View {
        Section {
            Button("Queue an invalid title") { app.sync.queueInvalidTitle() }
                .disabled(app.session.userID == nil)
        } header: {
            Text("Try it")
        } footer: {
            Text(
                app.session.userID == nil
                    ? "Needs a signed-in user: the app signs in the first time it reaches the internet."
                    : "Adds a task with a blank title. Online, the server refuses it at once; offline, when it syncs."
            )
        }
    }

    /// Clearing the device, and starting over as a new user.
    private var localData: some View {
        Section {
            Button("Clear local cache", role: .destructive) { isConfirmingClear = true }
                .confirmationDialog("Clear the local cache?", isPresented: $isConfirmingClear, titleVisibility: .visible) {
                    Button("Clear local cache", role: .destructive) { app.sync.clearLocalData() }
                } message: {
                    Text(
                        "Saved tasks, photos and changes not yet synced are deleted from this device. Anything already on the server comes back when you’re online."
                    )
                }
            Button("Start over", role: .destructive) { isConfirmingStartOver = true }
                .confirmationDialog("Start over as a new user?", isPresented: $isConfirmingStartOver, titleVisibility: .visible) {
                    Button("Start over", role: .destructive) { app.sync.startOver() }
                } message: {
                    Text(
                        "You get an empty list. This user’s tasks and photos are deleted from the server first, since an anonymous user can’t sign back in to reach them, so it needs the network."
                    )
                }
        } header: {
            Text("Local data")
        } footer: {
            Text(
                "Clear local cache deletes what this device holds; the server’s copy stays. Start over deletes this user’s tasks and photos, then continues as a new anonymous user."
            )
        }
    }

    /// Where the app runs and who it is signed in as.
    private var about: some View {
        Section("About this build") {
            LabeledContent("Supabase", value: sessionText)
            if app.mode.isDemo {
                DemoModeNotice(reason: app.mode)
            }
        }
    }

    // MARK: - Words

    /// Whether the client may not reach the network: the device is offline, or Simulate offline is on.
    private var isOffline: Bool {
        app.syncStatus.network == .offline || app.syncStatus.network == .disabled
    }

    /// The network, in a word or two.
    private var networkTitle: String {
        switch app.syncStatus.network {
        case .unknown: return "Checking the network…"
        case .online: return "Online"
        case .offline: return "Offline"
        case .disabled: return "Offline (simulated)"
        }
    }

    /// What the network means for changes.
    private var networkBody: String {
        switch app.syncStatus.network {
        case .unknown: return "The app hasn’t heard from the system yet."
        case .online: return "Changes go to the server as you make them."
        case .offline: return "The device has no network. Changes are saved here and sync when it’s back."
        case .disabled: return "This app’s network is switched off. Everything else on the device is online."
        }
    }

    /// When the queue was last emptied.
    private func lastSynced(_ status: SyncStatus) -> String {
        guard let date = status.lastSyncedAt else { return "Not synced yet" }
        return "Last synced \(date.formatted(.relative(presentation: .named)))"
    }

    /// The task list's refresh, in words.
    private var refreshText: String {
        guard app.session.userID != nil else { return "Waits for the first sign-in" }
        switch app.tasks.refresh {
        case .idle: return "Up to date with the server"
        case .refreshing: return "Asking the server…"
        case .paused: return "Paused while offline"
        case .stalled: return "Can’t reach the server — showing saved data"
        }
    }

    /// The sign-in, in words.
    private var sessionText: String {
        if app.mode.isDemo { return "Demo mode — no project" }
        switch app.session.state {
        case .signingIn: return "Signing in…"
        case .signedIn: return "Signed in anonymously"
        case .failed: return "Couldn’t sign in; tries again when online"
        }
    }
}

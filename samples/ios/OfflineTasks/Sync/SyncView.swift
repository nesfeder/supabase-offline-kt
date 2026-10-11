// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SwiftUI

/// The network and Simulate offline, the settings, the changes waiting, the list's refresh, the
/// refused changes, the server and the bucket, Try it, and the two ways to start again.
struct SyncView: View {

    @Environment(AppModel.self) private var app
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var isConfirmingClear = false
    @State private var isConfirmingStartOver = false

    /// The refused changes' section, which an alert's View scrolls to.
    private static let refusedChangesID = "refused-changes"

    var body: some View {
        ScrollViewReader { proxy in
            Form {
                connection
                settings
                waitingChanges
                refresh
                refusedChanges.id(Self.refusedChangesID)
                server
                storage
                tryIt
                localData
                about
            }
            .onChange(of: app.refusedChangesRequests) {
                withAnimation(reduceMotion ? nil : .default) { proxy.scrollTo(Self.refusedChangesID, anchor: .top) }
            }
        }
        .navigationTitle("Sync")
        .navigationDestination(for: BucketFolder.self) { BucketBrowserView(folder: $0) }
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
            Text("Cuts this app off from the server, as airplane mode would, until you switch it back or the app restarts.")
        }
    }

    /// The two switches: how the task list reads, and Realtime.
    private var settings: some View {
        @Bindable var app = app
        return Section {
            Toggle("Prefer fresh data", isOn: $app.prefersFreshData)
                .accessibilityIdentifier("prefer-fresh-data")
            Toggle("Live updates", isOn: $app.isLiveUpdatesOn)
                .accessibilityIdentifier("live-updates")
        } header: {
            Text("Settings")
        } footer: {
            Text(
                "Prefer fresh data asks the server before the task list shows anything; off, the list shows what’s saved at once and refreshes alongside. "
                    + "Live updates applies changes made elsewhere as they happen\(app.mode.isDemo ? " (in demo mode, the stand-in server’s)" : ""). Both last until the app restarts."
            )
        }
    }

    /// Each change waiting, with Discard; Sync now and its report.
    private var waitingChanges: some View {
        let status = app.syncStatus
        return Section {
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
                if let lastSyncError = status.lastSyncError {
                    Text(SampleFailure.sentence(for: lastSyncError))
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            }
            .animation(reduceMotion ? nil : .default, value: status.pendingMutationCount)
            ForEach(app.sync.waiting) { mutation in
                HStack {
                    Text(Wording.waitingChange(mutation, now: .now))
                        .font(.subheadline)
                    Spacer()
                    Button("Discard", role: .destructive) { app.sync.discard(mutation) }
                        .buttonStyle(.borderless)
                        .font(.subheadline)
                }
                .accessibilityIdentifier("waiting-change")
            }
            Button(app.sync.isSyncNowRunning ? "Syncing" : "Sync now", systemImage: "arrow.triangle.2.circlepath") {
                app.sync.syncNow()
            }
            .disabled(isOffline || app.sync.isSyncNowRunning)
            if isOffline {
                Text("Sync needs the network.").font(.footnote).foregroundStyle(.secondary)
            }
            if let report = app.sync.lastReport {
                Text(Wording.syncReport(report))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("sync-report")
            }
        } header: {
            Text("Waiting changes")
        } footer: {
            Text("Discard drops a change without sending it: the task shows again as the server last sent it.")
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
                    Text(Wording.refusedChange(rejection))
                        .font(.footnote)
                        .foregroundStyle(.secondary)
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
                    if !rejection.isRetryable {
                        Text("It can’t be sent again as it was made.").font(.footnote).foregroundStyle(.secondary)
                    }
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

    /// What the server holds, asked of it: a count and a database function.
    private var server: some View {
        Section {
            if let summary = app.sync.serverSummary {
                Text(summary).accessibilityIdentifier("server-summary")
            }
            Button(app.sync.isAskingServer ? "Asking…" : "Ask the server", systemImage: "server.rack") {
                app.sync.askServer()
            }
            .disabled(isOffline || app.sync.isAskingServer || app.session.userID == nil)
        } header: {
            Text("Server")
        } footer: {
            Text(
                "Counts with an exact count and the database function tasks_done_count(). Neither is ever saved on the device, so they need the network."
            )
        }
    }

    /// The bucket's own listing of the user's files.
    private var storage: some View {
        Section {
            NavigationLink(value: BucketFolder(path: "", title: "Your folders")) {
                Label("Browse photo files", systemImage: "folder")
            }
            .disabled(isOffline || app.session.userID == nil)
        } header: {
            Text("Storage")
        } footer: {
            Text("Storage’s listing of the task-photos bucket: a folder per task, a file per photo. It needs the network.")
        }
    }

    /// One call of each kind the rest of the app does not make, each saying what happened.
    private var tryIt: some View {
        Section {
            ForEach(TryItItem.allCases, id: \.self) { item in
                VStack(alignment: .leading, spacing: 4) {
                    Button(item.title) { app.sync.run(item, newestTaskID: app.tasks.newestTaskID) }
                        .disabled(app.session.userID == nil)
                    Text(app.sync.tryItResults[item] ?? item.call)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                .accessibilityIdentifier("try-it")
            }
        } header: {
            Text("Try it")
        } footer: {
            Text(
                app.session.userID == nil
                    ? "Needs a signed-in user: the app signs in the first time it reaches the internet."
                    : "Each line says which call the item makes, then what happened."
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
                        "You get an empty list. Changes not synced yet are discarded, and this user’s tasks and photos are deleted from the server first, since an anonymous user can’t sign back in to reach them, so it needs the network."
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

    // --8<-- [start:refresh-state]
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
    // --8<-- [end:refresh-state]

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

extension TryItItem {

    /// The item's button.
    var title: String {
        switch self {
        case .invalidTitle: "Queue an invalid title"
        case .sampleTasks: "Add five sample tasks"
        case .starterList: "Import the starter list"
        case .tasksWithNotes: "Count tasks with notes"
        case .groupedDelete: "Delete last week’s done tasks"
        case .signedUpload: "Upload through a signed URL"
        }
    }

    /// The call it makes, shown until it has run.
    var call: String {
        switch self {
        case .invalidTitle: "An insert of a JSON row with a blank title: online the server refuses it at once, offline when it syncs."
        case .sampleTasks: "An insert of a list of five rows: one write, one request."
        case .starterList: "An upsert with onConflict and ignoreDuplicates: running it twice changes nothing."
        case .tasksWithNotes: "The generic filter, filter(\"notes\", operator: \"not.is\", value: \"null\"): it asks the server."
        case .groupedDelete: "A delete with gte and lt on created_at, one group the queue can’t hold: offline, it’s refused."
        case .signedUpload: "createSignedUploadURL, then uploadToSignedURL: a generated photo for your newest task."
        }
    }
}

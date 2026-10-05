// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting

/// The app's state above any one screen: the one offline client (inside ``repository``), the sync
/// status every screen shows, the failures waiting to be shown, and the screens' own models.
@Observable
final class AppModel {

    /// What the app runs against.
    let mode: LaunchMode

    /// Every read and write, through the app's one client.
    let repository: TaskRepository

    /// The Tasks tab.
    let tasks: TasksModel

    /// The Sync tab.
    let sync: SyncModel

    /// Where the screens' models report failures, to be shown here.
    let failureSink: FailureSink

    /// The tab in front.
    var selectedTab: AppTab = .tasks

    /// Counts the requests to show the refused changes, so the Sync tab scrolls to them each time.
    private(set) var refusedChangesRequests = 0

    /// The status of the queue and the network, as the client last reported it.
    private(set) var syncStatus: SyncStatus

    /// The failure shown now; the next one waits until it is dismissed.
    private(set) var failure: FailureAlert?

    /// Whether "All changes synced" is showing, for a moment after a sync.
    private(set) var showsJustSynced = false

    /// Failures reported while another was showing.
    @ObservationIgnored private var waitingFailures: [FailureAlert] = []

    /// The ids of the refused writes already announced.
    @ObservationIgnored private var announcedRejections: Set<Int64>?

    /// The app over `repository`, running as `mode`.
    init(mode: LaunchMode, repository: TaskRepository) {
        self.mode = mode
        self.repository = repository
        syncStatus = repository.syncStatus.value
        let failures = FailureSink()
        failureSink = failures
        tasks = TasksModel(repository: repository, failures: failures)
        sync = SyncModel(repository: repository, failures: failures)
        failures.model = self
    }

    /// Who is signed in.
    var session: any UserSession {
        repository.session
    }

    /// What the app says about syncing now.
    var headline: SyncHeadline {
        let headline = SyncHeadline.of(status: syncStatus, refresh: tasks.refresh)
        return headline == .upToDate && showsJustSynced ? .justSynced : headline
    }

    /// Whether Simulate offline is on: the client's network switched off by the app.
    var isSimulatingOffline: Bool {
        syncStatus.network == .disabled
    }

    // MARK: - Running

    /// Runs for as long as the app does: follows the sync status, signs in when the network allows,
    /// and announces refused writes.
    func run() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.followSyncStatus() }
            group.addTask { await self.session.signInWhenReachable(self.repository.syncStatus) }
            group.addTask { await self.announceRefusedWrites() }
        }
    }

    /// Switches Simulate offline on or off: the client's network access, until the app restarts.
    func setSimulateOffline(_ isOn: Bool) {
        Task { await perform { try await self.repository.setNetworkEnabled(!isOn) } }
    }

    /// Runs `work`, showing its failure, if any, as an alert.
    func perform(_ work: () async throws -> Void) async {
        do {
            try await work()
        } catch is CancellationError {
            return
        } catch {
            report(error)
        }
    }

    /// Opens the Sync tab at its refused changes.
    func openRefusedChanges() {
        selectedTab = .sync
        refusedChangesRequests += 1
    }

    /// Takes the failure shown away, and shows the next one waiting, if any.
    func dismissFailure() {
        failure = waitingFailures.isEmpty ? nil : waitingFailures.removeFirst()
    }

    /// Shows `error` to the user, after any failure already showing.
    func report(_ error: any Error) {
        show(FailureAlert(message: SampleFailure.message(for: error)))
    }

    /// Shows `alert` now, or after the one showing.
    private func show(_ alert: FailureAlert) {
        if failure == nil {
            failure = alert
        } else {
            waitingFailures.append(alert)
        }
    }

    /// Follows the client's sync status, and holds "All changes synced" for a moment after a sync
    /// empties the queue.
    private func followSyncStatus() async {
        do {
            for try await status in repository.syncStatus {
                let wasSyncing = syncStatus.isSyncing && syncStatus.pendingMutationCount > 0
                syncStatus = status
                if wasSyncing, status.pendingMutationCount == 0, !status.isSyncing {
                    showsJustSynced = true
                    try? await Task.sleep(for: .seconds(2.5))
                    showsJustSynced = false
                }
            }
        } catch {
            report(error)
        }
    }

    /// Announces each write the server refuses from now on; the older ones are on the Sync tab.
    private func announceRefusedWrites() async {
        do {
            for try await rejections in repository.rejectedMutations() {
                let ids = Set(rejections.map(\.id))
                if let announced = announcedRejections {
                    for rejection in rejections where !announced.contains(rejection.id) {
                        show(
                            FailureAlert(
                                message: "Couldn’t save “\(Wording.title(rejection.taskTitle))” — the server refused it",
                                opensRefusedChanges: true
                            )
                        )
                    }
                }
                announcedRejections = ids.union(announcedRejections ?? [])
            }
        } catch {
            report(error)
        }
    }
}

/// The app's tabs.
enum AppTab: Hashable {
    case tasks
    case sync
}

/// A failure to show the user, in an alert.
struct FailureAlert: Identifiable, Equatable {

    /// Tells one alert from the next.
    let id = UUID()

    /// What to tell the user.
    let message: String

    /// Whether the alert offers to open the refused changes on the Sync tab.
    var opensRefusedChanges = false
}

/// Where a screen's model reports a failure: the app model, once it exists.
final class FailureSink {

    /// The app model the failures go to.
    weak var model: AppModel?

    /// Shows `error` to the user.
    func report(_ error: any Error) {
        model?.report(error)
    }

    /// Runs `work`, showing its failure, if any.
    func perform(_ work: () async throws -> Void) async {
        do {
            try await work()
        } catch is CancellationError {
            return
        } catch {
            report(error)
        }
    }
}

extension AppModel {

    /// The app as the build's keys and launch arguments call for it, with its one client.
    static func launch(mode: LaunchMode = .current()) -> AppModel {
        switch mode {
        case .project(let url, let publishableKey):
            let session = AnonymousSession(projectURL: url, publishableKey: publishableKey)
            let client = SupabaseOfflineClient(
                supabaseURL: url,
                supabaseKey: publishableKey,
                // The client learns who the user is from this token alone.
                config: SupabaseOfflineClient.Config(accessToken: { await session.accessToken() })
            )
            return AppModel(mode: mode, repository: TaskRepository(client: client, session: session))
        case .demo:
            return demo(mode: mode)
        }
    }

    /// The app in demo mode, on the testing product's in-memory client and `server`; for previews
    /// and tests too.
    static func demo(
        mode: LaunchMode = .demo(reason: .requested),
        server: DemoServer = DemoServer(),
        session: DemoSession = DemoSession()
    ) -> AppModel {
        let client = SupabaseOfflineClient.inMemory(
            config: SupabaseOfflineClient.Config(
                accessToken: { await session.accessToken() },
                // The in-memory client watches no network monitor: say it is online.
                networkAvailability: { AsyncStream { $0.yield(true) } }
            ),
            server: server.fakeServer
        )
        let repository = TaskRepository(client: client, session: session, photoBytes: .bucketDownload)
        return AppModel(mode: mode, repository: repository)
    }
}

extension RejectedMutation {

    /// The title the refused write carried, so the app can say which task it was: its first
    /// row's for an insert, its value for an update.
    var taskTitle: String? {
        var row = body
        if case .array(let rows)? = body { row = rows.first }
        guard case .object(let values)? = row, case .string(let title)? = values["title"] else { return nil }
        return title
    }

    /// Why the server refused it, in words.
    var reason: String {
        serverError.map(Wording.refusalReason) ?? message
    }
}

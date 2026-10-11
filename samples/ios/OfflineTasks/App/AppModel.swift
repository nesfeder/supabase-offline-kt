// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline
import SupabaseOfflineTesting
import SwiftUI

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

    /// The Photos tab.
    let photos: PhotosModel

    /// Where the server's row changes come from, for Live updates.
    let changeSource: any ServerChangeSource

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

    /// What the last write reported, shown above the tab bar for a moment: "Saved", "Saved on this
    /// device", "Added 5 tasks".
    private(set) var notice: String?

    /// How many writes waited when the app launched, read once, until the first status arrives.
    private(set) var pendingAtLaunch: Int?

    /// Settings → Prefer fresh data: the task list asks the server before showing anything.
    var prefersFreshData = false

    /// Settings → Live updates: rows changed elsewhere arrive from Realtime and are applied at once.
    var isLiveUpdatesOn = true

    /// Failures reported while another was showing.
    @ObservationIgnored private var waitingFailures: [FailureAlert] = []

    /// Hides "All changes synced" again.
    @ObservationIgnored private var justSyncedTimer: Task<Void, Never>?

    /// Hides the notice again.
    @ObservationIgnored private var noticeTimer: Task<Void, Never>?

    /// Whether the client has reported its status since launch.
    @ObservationIgnored private var hasStatus = false

    /// The ids of the refused writes already announced.
    @ObservationIgnored private var announcedRejections: Set<Int64>?

    /// The app over `repository`, running as `mode`, with Live updates from `changeSource`.
    init(mode: LaunchMode, repository: TaskRepository, changeSource: any ServerChangeSource) {
        self.mode = mode
        self.repository = repository
        self.changeSource = changeSource
        syncStatus = repository.syncStatus.value
        let failures = FailureSink()
        failureSink = failures
        tasks = TasksModel(repository: repository, failures: failures)
        sync = SyncModel(repository: repository, failures: failures)
        photos = PhotosModel(repository: repository, failures: failures)
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

    /// The Sync tab's badge: the writes waiting and the ones refused. At launch, before the
    /// client's first status, the count read once.
    var syncBadge: Int {
        guard hasStatus else { return pendingAtLaunch ?? 0 }
        return syncStatus.pendingMutationCount + syncStatus.rejectedMutationCount
    }

    /// The read strategy of the task list, as Settings → Prefer fresh data says.
    var listReadStrategy: SequenceReadStrategy {
        TaskRepository.readStrategy(prefersFreshData: prefersFreshData)
    }

    /// What Live updates follows: on, for the signed-in user, unless Simulate offline cuts the app off.
    var liveUpdatesKey: LiveUpdatesKey {
        LiveUpdatesKey(userID: session.userID, isOn: isLiveUpdatesOn && !isSimulatingOffline)
    }

    // MARK: - Running

    /// Runs for as long as the app does: follows the sync status, signs in when the network allows,
    /// and announces refused writes.
    func run() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.readPendingAtLaunch() }
            group.addTask { await self.followSyncStatus() }
            group.addTask { await self.session.signInWhenReachable(self.repository.syncStatus) }
            group.addTask { await self.announceRefusedWrites() }
        }
    }

    // --8<-- [start:live-updates-apply]
    /// Applies the server's row changes as they arrive, for as long as the calling task runs: the
    /// root view runs it while Live updates is on and a user is signed in.
    func followLiveUpdates() async {
        guard liveUpdatesKey.isOn, liveUpdatesKey.userID != nil else { return }
        do {
            for try await change in changeSource.changes(of: [Tables.tasks, Tables.taskPhotos]) {
                try await repository.apply(change)
            }
        } catch is CancellationError {
            return
        } catch {
            report(error)
        }
    }
    // --8<-- [end:live-updates-apply]

    // --8<-- [start:foreground]
    /// Sends the queue as the app comes back to the foreground: the library sends it by itself
    /// when the network comes back and as the session settles, but not on this.
    func syncOnForeground() {
        guard repository.canReachNetwork, session.userID != nil else { return }
        Task { await perform { try await repository.syncPendingMutations() } }
    }
    // --8<-- [end:foreground]

    /// Shows `text` above the tab bar for a few seconds, and reads it out with VoiceOver.
    func showNotice(_ text: String) {
        notice = text
        AccessibilityNotification.Announcement(text).post()
        noticeTimer?.cancel()
        noticeTimer = Task {
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { notice = nil }
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

    /// Reads how many writes wait, once, for the badge until the first status arrives.
    private func readPendingAtLaunch() async {
        do {
            pendingAtLaunch = try await repository.pendingMutationCountNow()
        } catch {
            report(error)
        }
    }

    /// Follows the client's sync status, and holds "All changes synced" for a moment after a sync
    /// empties the queue.
    private func followSyncStatus() async {
        do {
            for try await status in repository.syncStatus {
                let wasSyncing = syncStatus.isSyncing && syncStatus.pendingMutationCount > 0
                syncStatus = status
                hasStatus = true
                if wasSyncing, status.pendingMutationCount == 0, !status.isSyncing { showJustSynced() }
            }
        } catch {
            report(error)
        }
    }

    /// Shows "All changes synced" for a moment.
    private func showJustSynced() {
        showsJustSynced = true
        justSyncedTimer?.cancel()
        justSyncedTimer = Task {
            try? await Task.sleep(for: .seconds(2.5))
            if !Task.isCancelled { showsJustSynced = false }
        }
    }

    /// Announces each write the server refuses from now on; the ones refused before launch, read
    /// once, are on the Sync tab.
    private func announceRefusedWrites() async {
        do {
            announcedRejections = Set(try await repository.rejectedMutationsNow().map(\.id))
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
    case photos
    case sync
}

/// What Live updates depends on: the user, and whether it is on. A change restarts it.
struct LiveUpdatesKey: Hashable {

    /// The signed-in user; `nil` before there is one.
    let userID: String?

    /// Whether Live updates is on and Simulate offline is off.
    let isOn: Bool
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

/// Where a screen's model reports a failure, or what a write did: the app model, once it exists.
final class FailureSink {

    /// The app model the failures go to.
    weak var model: AppModel?

    /// Shows `error` to the user.
    func report(_ error: any Error) {
        model?.report(error)
    }

    /// Tells the user what a write did, above the tab bar for a moment.
    func announce(_ text: String) {
        model?.showNotice(text)
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
            // --8<-- [start:install]
            // The one client the app has, made at launch and shared: it learns who the user is
            // from the token alone, and follows the app's own connectivity signal.
            let client = SupabaseOfflineClient(
                supabaseURL: url,
                supabaseKey: publishableKey,
                config: ClientSettings.config(
                    accessToken: { await session.accessToken() },
                    networkAvailability: ClientSettings.pathMonitorSignal
                )
            )
            // --8<-- [end:install]
            let changes = RealtimeChangeSource(projectURL: url, publishableKey: publishableKey) {
                await session.accessToken()
            }
            return AppModel(mode: mode, repository: TaskRepository(client: client, session: session), changeSource: changes)
        case .demo:
            return demo(mode: mode)
        }
    }

    /// The app in demo mode, on the testing product's in-memory client and `server`, whose time is
    /// `clock` when one is given; for previews and tests too.
    static func demo(
        mode: LaunchMode = .demo(reason: .requested),
        server: DemoServer = DemoServer(),
        session: DemoSession = DemoSession(),
        clock: ManualClock? = nil
    ) -> AppModel {
        let client = SupabaseOfflineClient.inMemory(
            config: ClientSettings.config(
                accessToken: { await session.accessToken() },
                // The in-memory client watches no network monitor: say it is online.
                networkAvailability: { AsyncStream { $0.yield(true) } }
            ),
            server: server.fakeServer,
            clock: clock
        )
        let repository = TaskRepository(client: client, session: session, photoBytes: .bucketDownload)
        return AppModel(mode: mode, repository: repository, changeSource: server)
    }
}

nonisolated extension RejectedMutation {

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

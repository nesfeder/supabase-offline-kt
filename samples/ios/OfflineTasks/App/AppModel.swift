// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import OSLog
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

    /// Uploads the photos picked on this device, as the network allows.
    let photoUploader: PendingPhotoUploader

    /// Where the screens' models report failures, to be shown here.
    let failureSink: FailureSink

    /// The tab in front.
    var selectedTab: AppTab = .tasks

    /// Counts the requests to show the refused changes, so the Sync tab scrolls to them each time.
    private(set) var refusedChangesRequests = 0

    /// The status of the queue and the network, as the client last reported it.
    private(set) var syncStatus: SyncStatus

    /// Where the client's Realtime channel stands, as it last reported it: Live updates' status.
    private(set) var realtimeStatus: RealtimeStatus = .off

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

    /// Settings → Live updates: the client's Realtime channel, which applies rows changed elsewhere
    /// at once; the root view matches the channel to it and to the signed-in user.
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

    /// The app over `repository`, running as `mode`.
    init(mode: LaunchMode, repository: TaskRepository) {
        self.mode = mode
        self.repository = repository
        syncStatus = repository.syncStatus.value
        let failures = FailureSink()
        failureSink = failures
        tasks = TasksModel(repository: repository, failures: failures)
        sync = SyncModel(repository: repository, failures: failures)
        photos = PhotosModel(repository: repository, failures: failures)
        photoUploader = PendingPhotoUploader(repository: repository, failures: failures)
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

    /// What Live updates follows: the signed-in user, while the switch is on. A change of either
    /// has the root view match the client's channel to it again; a network drop, real or Simulate
    /// offline, the client handles itself.
    var liveUpdatesKey: LiveUpdatesKey {
        LiveUpdatesKey(userID: session.userID, isOn: isLiveUpdatesOn)
    }

    // MARK: - Running

    /// Runs for as long as the app does: follows the sync status, signs in when the network allows,
    /// announces refused writes, and uploads the photos picked on this device.
    func run() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.readPendingAtLaunch() }
            group.addTask { await self.followSyncStatus() }
            group.addTask { await self.followRealtimeStatus() }
            group.addTask { await self.session.signInWhenReachable(self.repository.syncStatus) }
            group.addTask { await self.announceRefusedWrites() }
            group.addTask { await self.photoUploader.run() }
        }
    }

    // --8<-- [start:live-updates-switch]
    /// Matches the client's Realtime channel to Live updates and the signed-in user: the root view
    /// runs it whenever either changes. The client joins with the token it is given at the join
    /// and does not join again by itself when the user changes, so a sign-in or sign-out leaves
    /// the channel and, with the switch on, joins it again as the new user; the client reads both
    /// tables again on each join, since Realtime replays nothing.
    func applyLiveUpdates() async {
        await perform {
            try await repository.setLiveUpdates(false)
            if liveUpdatesKey.isOn, liveUpdatesKey.userID != nil { try await repository.setLiveUpdates(true) }
        }
    }
    // --8<-- [end:live-updates-switch]

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

    /// Shows `error` to the user, after any failure already showing, and logs what is safe to log.
    func report(_ error: any Error) {
        if let offline = error as? OfflineError {
            Self.failureLog.error("\(SampleFailure.reportable(offline), privacy: .public)")
        }
        show(FailureAlert(message: SampleFailure.message(for: error)))
    }

    /// Where the app logs its failures, in the unified log.
    private static let failureLog = Logger(subsystem: Bundle.main.bundleIdentifier ?? "OfflineTasks", category: "failures")

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

    /// Follows where the client's Realtime channel stands, for the Sync tab.
    private func followRealtimeStatus() async {
        do {
            for try await status in repository.realtimeStatus { realtimeStatus = status }
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

/// What Live updates depends on: the user, and whether it is on. A change matches the channel to
/// it again.
struct LiveUpdatesKey: Hashable {

    /// The signed-in user; `nil` before there is one.
    let userID: String?

    /// Whether Live updates is on.
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
            return AppModel(mode: mode, repository: TaskRepository(client: client, session: session))
        case .demo:
            return demo(mode: mode)
        }
    }

    /// The app in demo mode, on the testing product's in-memory client and `server`, which reports
    /// its row changes to the client's channel in place of Realtime, whose time is `clock` when
    /// one is given, with the photos waiting to upload in `pendingPhotoFolder`; for previews and
    /// tests too. By default that folder is a new one in the temporary directory, as demo mode
    /// keeps nothing between launches.
    static func demo(
        mode: LaunchMode = .demo(reason: .requested),
        server: DemoServer = DemoServer(),
        session: DemoSession = DemoSession(),
        clock: ManualClock? = nil,
        networkAvailability: @escaping @Sendable () -> AsyncStream<Bool> = { AsyncStream { $0.yield(true) } },
        pendingPhotoFolder: URL = .temporaryDirectory.appending(path: "PendingPhotos-\(DeviceID.make())", directoryHint: .isDirectory)
    ) -> AppModel {
        // --8<-- [start:in-memory-client]
        let client = SupabaseOfflineClient.inMemory(
            config: ClientSettings.config(
                accessToken: { await session.accessToken() },
                // The in-memory client watches no network monitor: by default, say it is online.
                networkAvailability: networkAvailability
            ),
            server: server.fakeServer,
            clock: clock,
            realtime: server.realtime
        )
        // --8<-- [end:in-memory-client]
        let repository = TaskRepository(
            client: client,
            session: session,
            photoBytes: .bucketDownload,
            pendingPhotos: PendingPhotoQueue(folder: pendingPhotoFolder)
        )
        return AppModel(mode: mode, repository: repository)
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

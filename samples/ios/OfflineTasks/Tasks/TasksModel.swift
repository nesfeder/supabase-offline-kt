// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// What the Tasks screen shows.
enum TasksListState: Equatable {

    /// No one is signed in yet, so there is nothing to show and nothing can be added: the first
    /// launch, before the app has reached the network. `isSigningIn` while it is trying.
    case noSession(isSigningIn: Bool)

    /// Nothing is saved on this device for the list asked for yet: `isLoading` while the server is
    /// being asked, `false` when there is no network to ask it with.
    case noDataYet(isLoading: Bool)

    /// The tasks, open and done, newest first; both may be empty.
    case loaded(open: [TaskItem], done: [TaskItem])

    /// Whether New task is offered: every task needs its user.
    var canAddTasks: Bool {
        if case .noSession = self { return false }
        return true
    }
}

/// The Tasks screen: the list its chips and search ask for, a mark on each task whose change
/// waits to sync, where the list came from, and the writes the screen makes. It also holds every
/// task of the user, the list the other screens share.
@Observable
final class TasksModel {

    /// The rows of the list every screen shares, every task; `nil` while nothing is known.
    private(set) var rows: [TaskRow]?

    /// The rows of the list shown, as ``query`` asks for them; `nil` while nothing is known.
    private(set) var visibleRows: [TaskRow]?

    /// How the shown list's refresh from the server stands.
    private(set) var refresh: RefreshState = .idle

    /// Where the shown list came from: the last snapshot's, or the last pull to refresh's.
    private(set) var provenance: ListProvenance?

    /// The chip and the search text the list shows.
    var query = TaskQuery()

    /// The search field's text, which becomes the query's once typing pauses.
    var searchText = "" {
        didSet { scheduleSearch() }
    }

    /// How long typing must pause before the search asks the server.
    static let searchDelay = Duration.milliseconds(400)

    /// The ids of the tasks a queued write touches.
    private(set) var pendingIDs: Set<String> = []

    /// How many photos each task on screen has, by task id, once read.
    private(set) var photoCounts: [String: Int] = [:]

    /// How many tasks were open at launch, read once for the tab's badge until the list is known.
    private(set) var openCountAtLaunch: Int?

    /// Whether a pull to refresh is asking the server.
    private(set) var isRefreshing = false

    /// Whether Mark all done is waiting for the server.
    private(set) var isMarkingAllDone = false

    /// The search waiting for typing to pause.
    @ObservationIgnored private var pendingSearch: Task<Void, Never>?

    /// Pull to refresh's requests that a live read which failed start again.
    @ObservationIgnored private let readAgainRequests = ReadAgainRequests()

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure, or the result of a write, goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// A model over `repository`, reporting failures to `failures`.
    init(repository: TaskRepository, failures: FailureSink) {
        self.repository = repository
        self.failures = failures
    }

    /// The shown list's tasks, newest first; `nil` while nothing is known.
    var tasks: [TaskItem]? {
        // A task queued offline is listed after the server's rows; show it where it sorts.
        visibleRows?.map { $0.task(pendingIDs: pendingIDs) }.sorted { $0.createdAt > $1.createdAt }
    }

    /// The Tasks tab's badge: the open tasks, from the shared list once it is known.
    var openCount: Int? {
        rows.map { $0.filter { !$0.done }.count } ?? openCountAtLaunch
    }

    /// The newest task's id, for Try it's upload through a signed URL.
    var newestTaskID: String? {
        rows?.max { $0.createdAt < $1.createdAt }?.id
    }

    /// The ids of the tasks on screen, for the photo counts' query.
    var visibleTaskIDs: [String] {
        (visibleRows ?? []).map(\.id).sorted()
    }

    /// The screen's state for `session` and the client's `network`.
    func state(session: SessionState, network: NetworkStatus) -> TasksListState {
        let canAskServer = network == .online || network == .unknown
        guard case .signedIn = session else {
            if case .failed = session { return .noSession(isSigningIn: false) }
            return .noSession(isSigningIn: canAskServer)
        }
        guard let tasks else {
            if case .stalled = refresh { return .noDataYet(isLoading: false) }
            return .noDataYet(isLoading: canAskServer)
        }
        return .loaded(open: tasks.filter { !$0.isDone }, done: tasks.filter(\.isDone))
    }

    // MARK: - Reading

    // --8<-- [start:live-read]
    /// Follows every task of the signed-in user, the list the other screens share, until the
    /// calling task is canceled. Before there is a user it asks nothing: the schema refuses every
    /// request without one.
    func observeTasks() async {
        rows = nil
        guard repository.session.userID != nil else { return }
        while !Task.isCancelled {
            do {
                for try await snapshot in repository.taskSnapshots() {
                    rows = snapshot.result
                }
                return
            } catch is CancellationError {
                return
            } catch {
                // The shown list reports a failure; both read the same rows. Read again when it is
                // worth it, so the list does not stop.
                await untilWorthReadingAgain()
            }
        }
    }
    // --8<-- [end:live-read]

    /// Follows the list ``query`` asks for, read with `readStrategy`; the root view starts it
    /// again whenever either changes.
    func observeVisibleTasks(readStrategy: SequenceReadStrategy) async {
        visibleRows = nil
        refresh = .idle
        provenance = nil
        guard repository.session.userID != nil else { return }
        while !Task.isCancelled {
            do {
                for try await snapshot in repository.taskSnapshots(query, readStrategy: readStrategy) {
                    visibleRows = snapshot.result
                    refresh = snapshot.refresh
                    provenance = snapshot.result == nil ? nil : ListProvenance(snapshot)
                }
                return
            } catch is CancellationError {
                return
            } catch {
                // Said in the failure's own words, then read again when it is worth it, so a list
                // that failed (offline, say, with nothing saved for it) does not stay stopped.
                failures.report(error)
                await untilWorthReadingAgain()
            }
        }
    }

    // --8<-- [start:read-again-after-failure]
    /// Returns once a live read that failed is worth starting again: the network's state changed
    /// (back online, or Simulate offline switched off), or the user pulled to refresh. A server's
    /// own error does not pass by itself.
    private func untilWorthReadingAgain() async {
        let network = repository.syncStatus.value.network
        let statuses = repository.syncStatus
        let requests = readAgainRequests
        await withTaskGroup(of: Void.self) { group in
            group.addTask {
                do {
                    for try await status in statuses where status.network != network { return }
                } catch {
                    // The status ends only when the client closes; nothing to read again then.
                }
            }
            group.addTask { await requests.next() }
            await group.next()
            group.cancelAll()
        }
    }
    // --8<-- [end:read-again-after-failure]

    // --8<-- [start:pending-marks]
    /// Follows which tasks have a write waiting in the mutation queue.
    func observePendingTasks() async {
        do {
            for try await mutations in repository.pendingMutations(table: Tables.tasks) {
                pendingIDs = Set(mutations.flatMap(\.rowIDs))
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }
    // --8<-- [end:pending-marks]

    /// Follows how many photos each task on screen has, in one query for all of them.
    func observePhotoCounts(of taskIDs: [String]) async {
        guard !taskIDs.isEmpty, repository.session.userID != nil else {
            photoCounts = [:]
            return
        }
        do {
            for try await snapshot in repository.photoSnapshots(ofTasks: taskIDs) {
                photoCounts = Dictionary(grouping: snapshot.result ?? [], by: \.taskID).mapValues(\.count)
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Reads how many tasks are open, once, for the tab's badge at launch.
    func readOpenCountAtLaunch() async {
        guard repository.session.userID != nil, openCountAtLaunch == nil else { return }
        do {
            openCountAtLaunch = try await repository.openTaskCountAtLaunch()
        } catch {
            // The badge waits for the list instead: nothing the user asked for failed.
            openCountAtLaunch = nil
        }
    }

    /// Moves the search text into the query once typing pauses, so each pause is one request.
    private func scheduleSearch() {
        pendingSearch?.cancel()
        pendingSearch = Task {
            try? await Task.sleep(for: Self.searchDelay)
            guard !Task.isCancelled else { return }
            query.search = searchText
        }
    }

    // MARK: - Writing

    /// Adds a task, sent only straight to the server when `needsConnection`, then files `photos`
    /// for it, to wait on the device and upload behind it. Returns what to tell the user when the
    /// task failed, so the sheet can say it; `nil` once it is saved.
    func addTask(title: String, notes: String, needsConnection: Bool, photos: [EncodedPhoto] = []) async -> String? {
        let notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        let taskID = DeviceID.make()
        let result: NewTaskResult
        do {
            result = try await repository.addTask(
                id: taskID,
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                notes: notes.isEmpty ? nil : notes,
                needsConnection: needsConnection
            )
        } catch {
            return SampleFailure.message(for: error)
        }
        // Filed only once the task is saved: a photo's row is written after its task's insert.
        var filed = 0
        await failures.perform {
            for photo in photos {
                try await repository.filePhoto(photo, forTask: taskID)
                filed += 1
            }
        }
        failures.announce(Wording.newTaskResult(result, photoCount: filed))
        return nil
    }

    /// How many photos task `taskID` has: its rows read so far, and the photos picked on this
    /// device that wait to upload; `nil` while neither is known.
    func photoCount(ofTask taskID: String) -> Int? {
        let waiting = repository.pendingPhotos.photos(ofTask: taskID).count
        guard let stored = photoCounts[taskID] else { return waiting > 0 ? waiting : nil }
        return stored + waiting
    }

    /// Marks `task` done or open again.
    func setDone(_ task: TaskItem, isDone: Bool) {
        Task { await failures.perform { try await repository.setTaskDone(id: task.id, isDone: isDone) } }
    }

    /// Deletes `task`, its photos' files first when online.
    func delete(_ task: TaskItem) {
        // A task of its own, not the view's: leaving the screen cannot cut the delete short.
        Task { await failures.perform { try await repository.deleteTask(id: task.id) } }
    }

    /// Pull to refresh: asks the server for the list again, and says where the answer came from.
    /// Offline, the device's rows answer when they are less than a day old.
    func refreshList() async {
        isRefreshing = true
        defer { isRefreshing = false }
        readAgainRequests.send()
        await failures.perform { provenance = try await repository.refreshTasks(query) }
    }

    /// Marks every task done on the server, then reads the lists again, since the device learns of
    /// a database function's changes only by asking.
    func markAllDone() async {
        isMarkingAllDone = true
        defer { isMarkingAllDone = false }
        await failures.perform {
            try await repository.markAllDone()
            failures.announce("Every task is marked done on the server")
            _ = try await repository.refreshTasks(TaskQuery())
            if !query.isEverything { provenance = try await repository.refreshTasks(query) }
        }
    }
}

/// Requests that the live reads waiting after a failure start again, delivered to every read
/// waiting when one is sent.
final class ReadAgainRequests {

    /// The reads waiting, by an id each.
    private var listeners: [UUID: AsyncStream<Void>.Continuation] = [:]

    /// Returns at the next request, or when the calling task is canceled.
    func next() async {
        let id = UUID()
        let (stream, continuation) = AsyncStream<Void>.makeStream()
        continuation.onTermination = { _ in Task { @MainActor in self.listeners[id] = nil } }
        listeners[id] = continuation
        for await _ in stream { return }
    }

    /// Starts every read waiting now.
    func send() {
        listeners.values.forEach { $0.yield() }
    }
}

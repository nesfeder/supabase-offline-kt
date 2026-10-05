// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// What the Tasks screen shows.
enum TasksListState: Equatable {

    /// No one is signed in yet, so there is nothing to show and nothing can be added: the first
    /// launch, before the app has reached the network. `isSigningIn` while it is trying.
    case noSession(isSigningIn: Bool)

    /// Nothing is saved on this device yet: `isLoading` while the server is being asked, `false`
    /// when there is no network to ask it with.
    case noDataYet(isLoading: Bool)

    /// The tasks, open and done, newest first; both may be empty.
    case loaded(open: [TaskItem], done: [TaskItem])

    /// Whether New task is offered: every task needs its user.
    var canAddTasks: Bool {
        if case .noSession = self { return false }
        return true
    }
}

/// The Tasks screen: the live task list with a mark on each task whose change waits to sync, and
/// the writes the screen makes.
@Observable
final class TasksModel {

    /// The rows of the list query; `nil` while nothing is known.
    private(set) var rows: [TaskRow]?

    /// How the list's refresh from the server stands.
    private(set) var refresh: RefreshState = .idle

    /// The ids of the tasks a queued write touches.
    private(set) var pendingIDs: Set<String> = []

    /// Whether a pull to refresh is asking the server.
    private(set) var isRefreshing = false

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// A model over `repository`, reporting failures to `failures`.
    init(repository: TaskRepository, failures: FailureSink) {
        self.repository = repository
        self.failures = failures
    }

    /// The tasks as the device holds them, newest first; `nil` while nothing is known.
    var tasks: [TaskItem]? {
        // A task queued offline is listed after the server's rows; show it where it sorts.
        rows?.map { $0.task(pendingIDs: pendingIDs) }.sorted { $0.createdAt > $1.createdAt }
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

    /// Follows the signed-in user's task list until the calling task is canceled. Before there is
    /// a user it shows nothing and asks nothing: the schema refuses every request without one.
    func observeTasks() async {
        rows = nil
        refresh = .idle
        guard repository.session.userID != nil else { return }
        do {
            for try await snapshot in repository.taskSnapshots() {
                rows = snapshot.result
                refresh = snapshot.refresh
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

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

    // MARK: - Writing

    /// Adds a task. It shows at once; offline it waits in the queue.
    func addTask(title: String, notes: String) {
        let notes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        Task {
            await failures.perform {
                try await repository.addTask(
                    title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                    notes: notes.isEmpty ? nil : notes
                )
            }
        }
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

    /// Pull to refresh: asks the server for the list again, or says why it cannot.
    func refreshList() async {
        guard repository.canReachNetwork else {
            failures.report(SampleFailure.offlineRefresh)
            return
        }
        isRefreshing = true
        defer { isRefreshing = false }
        await failures.perform { try await repository.refreshTasks() }
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The Sync screen: each change waiting, with Discard; Sync now and its report; refused writes
/// with Dismiss and Retry; conflicting writes with Keep mine and Keep theirs; what the server
/// says; Try it; and the two ways to start again. The
/// network, Simulate offline and the Settings switches are the app model's.
@Observable
final class SyncModel {

    /// The writes waiting, in the order they will be sent.
    private(set) var waiting: [PendingMutation] = []

    /// Writes waiting for `tasks`.
    private(set) var pendingTaskCount = 0

    /// Writes waiting for `task_photos`.
    private(set) var pendingPhotoCount = 0

    /// The writes the server refused, newest first.
    private(set) var rejected: [RejectedMutation] = []

    /// The writes parked because their task changed on the server meanwhile, newest first.
    private(set) var conflicted: [ConflictedMutation] = []

    /// Whether a Sync now the user pressed is running.
    private(set) var isSyncNowRunning = false

    /// What the last Sync now did, until the next one.
    private(set) var lastReport: SyncReport?

    /// What the server last said about the user's tasks: "On the server: 7 of 12 done".
    private(set) var serverSummary: String?

    /// Whether the server is being asked.
    private(set) var isAskingServer = false

    /// What each Try it item did the last time it ran.
    private(set) var tryItResults: [TryItItem: String] = [:]

    /// Whether Start over is running.
    private(set) var isStartingOver = false

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// The stand-in server of demo mode, which Make a conflict edits as another writer would;
    /// `nil` against a project, where a second writer is the tour's recipe.
    @ObservationIgnored let demoServer: DemoServer?

    /// A model over `repository`, reporting failures to `failures`, with demo mode's
    /// `demoServer` when there is one.
    init(repository: TaskRepository, failures: FailureSink, demoServer: DemoServer? = nil) {
        self.repository = repository
        self.failures = failures
        self.demoServer = demoServer
    }

    // MARK: - Reading

    /// Follows the queue, its counts per table and the refused writes, until the calling task is
    /// canceled.
    func observe() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.observeWaiting() }
            group.addTask { await self.observeCount(of: Tables.tasks) }
            group.addTask { await self.observeCount(of: Tables.taskPhotos) }
            group.addTask { await self.observeRejected() }
            group.addTask { await self.observeConflicted() }
        }
    }

    /// Follows every waiting write, of every table.
    private func observeWaiting() async {
        do {
            for try await mutations in repository.pendingMutations() {
                waiting = mutations
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows how many writes wait for `table`.
    private func observeCount(of table: String) async {
        do {
            for try await count in repository.pendingMutationCounts(table: table) {
                if table == Tables.tasks { pendingTaskCount = count } else { pendingPhotoCount = count }
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows the refused writes; the library lists them oldest first.
    private func observeRejected() async {
        do {
            for try await rejections in repository.rejectedMutations() {
                rejected = rejections.reversed()
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    /// Follows the conflicting writes; the library lists them oldest first.
    private func observeConflicted() async {
        do {
            for try await conflicts in repository.conflictedMutations() {
                conflicted = conflicts.reversed()
            }
        } catch is CancellationError {
            return
        } catch {
            failures.report(error)
        }
    }

    // MARK: - The queue

    /// Sends the queue now and keeps what it did for the screen.
    func syncNow() {
        guard !isSyncNowRunning else { return }
        isSyncNowRunning = true
        lastReport = nil
        Task {
            defer { isSyncNowRunning = false }
            await failures.perform { lastReport = try await repository.syncPendingMutations() }
        }
    }

    /// Drops a waiting write without sending it.
    func discard(_ mutation: PendingMutation) {
        Task { await failures.perform { try await repository.discardPendingMutation(id: mutation.id) } }
    }

    /// Forgets a refused write.
    func dismiss(_ rejection: RejectedMutation) {
        Task { await failures.perform { try await repository.dismissRejectedMutation(id: rejection.id) } }
    }

    /// Queues a refused write again.
    func retry(_ rejection: RejectedMutation) {
        Task { await failures.perform { try await repository.retryRejectedMutation(id: rejection.id) } }
    }

    /// Keep mine: the parked write is queued again over the server's row as it is now.
    func keepMine(_ conflict: ConflictedMutation) {
        Task { await failures.perform { try await repository.resolveConflictedMutation(id: conflict.id, keepMine: true) } }
    }

    /// Keep theirs: the parked write is dropped, and the server's row stands.
    func keepTheirs(_ conflict: ConflictedMutation) {
        Task { await failures.perform { try await repository.resolveConflictedMutation(id: conflict.id, keepMine: false) } }
    }

    // MARK: - The server

    /// Asks the server how many of the user's tasks it holds, and how many are done.
    func askServer() {
        guard !isAskingServer else { return }
        isAskingServer = true
        Task {
            defer { isAskingServer = false }
            await failures.perform {
                let week = TaskQuery.lastSevenDays(endingOn: .now)
                async let done = repository.serverDoneCounts(since: week.start)
                async let total = repository.serverTaskCount()
                let counts = try await done
                serverSummary = "On the server: \(counts.all) of \(try await total) done · \(counts.since) this week"
            }
        }
    }

    // MARK: - Try it

    /// Runs Try it's `item`, and keeps what happened, or why it failed, under it.
    func run(_ item: TryItItem, newestTaskID: String?) {
        Task {
            do {
                tryItResults[item] = try await result(of: item, newestTaskID: newestTaskID)
            } catch is CancellationError {
                return
            } catch {
                tryItResults[item] = SampleFailure.message(for: error)
            }
        }
    }

    /// What `item` did.
    private func result(of item: TryItItem, newestTaskID: String?) async throws -> String {
        switch item {
        case .invalidTitle:
            // Applied has the server's answer; a queued write has none yet.
            let outcome = try await repository.queueInvalidTitle()
            return outcome.result == nil
                ? "Queued; the server refuses it when it syncs, and it shows under Refused changes."
                : "Sent; the server took it."
        case .sampleTasks:
            return Wording.outcome(try await repository.addSampleTasks(), applied: "Added five tasks in one insert")
        case .starterList:
            return Wording.outcome(try await repository.importStarterList(), applied: "Imported; again changes nothing")
        case .tasksWithNotes:
            let count = try await repository.tasksWithNotesOnServer()
            return "The server has \(Wording.count(count, "task", "tasks")) with notes."
        case .groupedDelete:
            try await repository.deleteLastWeeksDoneTasks()
            return "Sent at once and applied: last week’s done tasks are gone."
        case .signedUpload:
            guard let taskID = newestTaskID else { return "Add a task first: the photo goes on your newest task." }
            try await repository.attachPhotoThroughSignedURL(taskID: taskID, photo: try await PhotoEncoder.encode(SamplePhoto.jpeg()))
            return "Uploaded through a signed URL, and added to your newest task."
        case .makeConflict:
            return try await makeConflict(taskID: newestTaskID)
        }
    }

    // --8<-- [start:make-conflict]
    /// Make a conflict, in demo mode: the newest task's title edited here offline, the same title
    /// changed on the stand-in server meanwhile, then the network back and the queue sent. The
    /// queued edit was preconditioned on the version this device saw, so it finds the row moved,
    /// and the client's resolver holds it under Conflicting changes.
    private func makeConflict(taskID: String?) async throws -> String {
        guard let server = demoServer else { return TryItItem.makeConflict.call(isDemo: false) }
        guard repository.canReachNetwork else { return "Go online first: the conflict is found when the edit syncs." }
        guard let taskID, let row = await server.rows(of: Tables.tasks).first(where: { $0["id"] == .string(taskID) }),
            case .string(let title)? = row["title"]
        else { return "Add a task and let it sync first: the conflict needs a row the server has." }
        var notes: String?
        if case .string(let text)? = row["notes"] { notes = text }
        try await repository.setNetworkEnabled(false)
        _ = try await repository.updateTaskText(id: taskID, title: "\(title) (this device)", notes: notes)
        await server.changeElsewhere(task: taskID, ["title": .string("\(title) (elsewhere)")])
        try await repository.setNetworkEnabled(true)
        let report = try await repository.syncPendingMutations()
        return report.conflicted.isEmpty && repository.syncStatus.value.conflictedMutationCount == 0
            ? "Synced, and no conflict was held: \(Wording.syncReport(report))."
            : "Both titles changed: it waits under Conflicting changes, for Keep mine or Keep theirs."
    }
    // --8<-- [end:make-conflict]

    // MARK: - Local data

    /// Deletes everything this device holds, unsynced changes included.
    func clearLocalData() {
        Task { await failures.perform { try await repository.clearLocalData() } }
    }

    /// Deletes this user's tasks and photos from the server, clears the device, and continues as a
    /// new anonymous user.
    func startOver() {
        guard !isStartingOver else { return }
        isStartingOver = true
        Task {
            defer { isStartingOver = false }
            await failures.perform { try await repository.startOver() }
        }
    }
}

/// The Sync tab's Try it items: each makes one kind of call and says what happened.
enum TryItItem: CaseIterable, Hashable {

    /// Adds a task whose blank title the server refuses: `insert` of a JSON row.
    case invalidTitle

    /// Adds five tasks in one `insert` of a list.
    case sampleTasks

    /// `upsert` of the starter tasks with `ignoreDuplicates`: twice changes nothing.
    case starterList

    /// Counts the tasks with notes through the generic `filter`, naming its operator.
    case tasksWithNotes

    /// A delete with two conditions on one column, which the queue cannot hold: offline, refused.
    case groupedDelete

    /// Uploads a generated photo through a signed upload URL.
    case signedUpload

    /// Changes the newest task's title here offline and on the stand-in server, then syncs: the
    /// edit waits under Conflicting changes. Demo mode only; against a project, the recipe.
    case makeConflict
}

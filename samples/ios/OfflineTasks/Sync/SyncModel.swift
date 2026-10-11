// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The Sync screen: the changes waiting per table, refused writes with dismiss and retry, Sync
/// now, and the two ways to start again. The network and Simulate offline are the app model's.
@Observable
final class SyncModel {

    /// Writes waiting for `tasks`.
    private(set) var pendingTaskCount = 0

    /// Writes waiting for `task_photos`.
    private(set) var pendingPhotoCount = 0

    /// The writes the server refused, newest first.
    private(set) var rejected: [RejectedMutation] = []

    /// Whether a Sync now the user pressed is running.
    private(set) var isSyncNowRunning = false

    /// What the last Sync now did, until the next one.
    private(set) var lastReport: SyncReport?

    /// Whether Start over is running.
    private(set) var isStartingOver = false

    /// Reads and writes.
    @ObservationIgnored private let repository: TaskRepository

    /// Where a failure goes to be shown.
    @ObservationIgnored private let failures: FailureSink

    /// A model over `repository`, reporting failures to `failures`.
    init(repository: TaskRepository, failures: FailureSink) {
        self.repository = repository
        self.failures = failures
    }

    // MARK: - Reading

    /// Follows the queue's counts per table and the refused writes, until the calling task is
    /// canceled.
    func observe() async {
        await withDiscardingTaskGroup { group in
            group.addTask { await self.observeCount(of: Tables.tasks) }
            group.addTask { await self.observeCount(of: Tables.taskPhotos) }
            group.addTask { await self.observeRejected() }
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

    // MARK: - Actions

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

    /// Forgets a refused write.
    func dismiss(_ rejection: RejectedMutation) {
        Task { await failures.perform { try await repository.dismissRejectedMutation(id: rejection.id) } }
    }

    /// Queues a refused write again.
    func retry(_ rejection: RejectedMutation) {
        Task { await failures.perform { try await repository.retryRejectedMutation(id: rejection.id) } }
    }

    /// Adds a task whose title the server refuses, to show what a refused write looks like:
    /// online it is refused at once and shown as an alert; offline it is refused when it syncs and
    /// listed under the refused changes.
    func queueInvalidTitle() {
        Task { await failures.perform { try await repository.addTask(title: TaskRules.invalidTitle, notes: nil) } }
    }

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

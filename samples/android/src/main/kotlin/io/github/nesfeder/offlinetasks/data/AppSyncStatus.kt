// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

/** Everything the app shows about syncing, in one value that changes as a whole. */
data class AppSyncStatus(
    val network: AppNetworkStatus,
    val pendingMutationCount: Int,
    val rejectedMutationCount: Int,
    /** Writes parked because their row moved on the server, until the user settles them. */
    val conflictedMutationCount: Int = 0,
    /** True while a replay of the mutation queue is running. */
    val isSyncing: Boolean,
    /** When a replay last finished with the queue empty; kept in memory only. */
    val lastSyncedAt: Instant?,
    /**
     * Why the last replay stopped early, cleared by the next clean one: a
     * [NetworkUnavailableException] or a [ServerUnreachableException].
     */
    val lastSyncError: Throwable?,
)

/** What the app knows about reaching the server. */
enum class AppNetworkStatus {
    /** Not known yet. */
    Unknown,

    /** The device has a network. */
    Online,

    /** The device has no network. */
    Offline,

    /** The app switched the network off itself ("Simulate offline"). */
    Disabled,
}

/** The result of one replay of the mutation queue. */
data class TaskSyncReport(
    val syncedCount: Int,
    /** The writes the server refused during this replay. */
    val rejected: List<RejectedTaskMutation>,
    /** Writes still queued, because the network went away or the server failed. */
    val remainingCount: Int,
    /** Of [syncedCount], inserts the server already had from a send whose answer was lost. */
    val alreadyOnServerCount: Int = 0,
    /** Of [remainingCount], writes held back behind an earlier write to another table. */
    val heldBehindOtherTablesCount: Int = 0,
    /** Of [syncedCount], writes whose row had moved on the server, settled and sent again. */
    val conflictsResolvedCount: Int = 0,
    /** Writes whose row had moved or gone, dropped so the server's row stands. */
    val conflictsKeptServerCount: Int = 0,
    /** The writes parked during this replay, for the user to settle on the Sync tab. */
    val conflicted: List<ConflictedTaskMutation> = emptyList(),
)

/** The verbs of a queued write, in PostgREST's vocabulary. */
enum class TaskMutationOperation {
    Insert,
    Upsert,
    Update,
    Delete,
}

/** What became of a write: sent and accepted now, or queued to be sent later. */
sealed interface TaskMutationOutcome {
    /** The server accepted the write before the call returned. */
    data object Applied : TaskMutationOutcome

    /** The write waits in the mutation queue; [pendingMutationId] names it in a later rejection. */
    data class Queued(val pendingMutationId: Long) : TaskMutationOutcome
}

/**
 * A queued write the server refused when it was replayed. The device's copy has gone back to the
 * server's; the app shows it until the user dismisses or retries it.
 *
 * @property rowKeys the ids of the rows the write was for, when known.
 * @property taskTitle the title the write carried, so the app can say which task it was.
 * @property serverError the server's answer, or null when the device refused the write itself.
 * @property createdAt when the write was made, on this device.
 * @property isRetryable false when the write can no longer be sent as it was made.
 */
data class RejectedTaskMutation(
    val id: Long,
    val table: String,
    val operation: TaskMutationOperation,
    val rowKeys: List<String>,
    val taskTitle: String?,
    val serverError: TaskServerError?,
    val message: String,
    val rejectedAt: Instant,
    val createdAt: Instant = rejectedAt,
    val isRetryable: Boolean = true,
)

/**
 * A queued write parked because its row changed on the server since this device last saw it, and
 * the change touched what the write sets. The app shows it until the user keeps one side.
 *
 * @property id names the entry; [TaskRepository.resolveConflictedMutation] takes it.
 * @property rowKeys the ids of the rows the write was for.
 * @property taskTitle the title the write sets, or else the server's, so the app can say which task
 *   it was.
 * @property columns the columns both sides changed, with each side's value.
 * @property changedOnServer every column the write sets that the server changed meanwhile.
 * @property createdAt when the write was made, on this device.
 * @property conflictedAt when it was parked.
 * @property canKeepMine false when this device's change can no longer be sent: the row is gone from
 *   the server, or the write was too long to keep in full.
 */
data class ConflictedTaskMutation(
    val id: Long,
    val table: String,
    val operation: TaskMutationOperation,
    val rowKeys: List<String>,
    val taskTitle: String?,
    val columns: List<ColumnConflict>,
    val changedOnServer: Set<String>,
    val createdAt: Instant,
    val conflictedAt: Instant,
    val canKeepMine: Boolean = true,
)

/**
 * One column both the server and this device changed, with each side's value as text; null where
 * that side holds SQL null.
 */
data class ColumnConflict(val column: String, val theirs: String?, val mine: String?)

/** How the user settles a conflicting change. */
enum class ConflictChoice {
    /** Send this device's change again, over the server's row as it is now. */
    KeepMine,

    /** Drop this device's change: the server's row stands. */
    KeepTheirs,
}

/**
 * One write waiting in the mutation queue, as the Sync tab lists it.
 *
 * @property id names the write; Discard takes it.
 * @property rowKey the id of the row the write is for, when known.
 * @property taskTitle the title the write carries, for an insert or an edit of a task.
 */
data class WaitingChange(
    val id: Long,
    val table: String,
    val operation: TaskMutationOperation,
    val createdAt: Instant,
    val rowKey: String?,
    val taskTitle: String?,
)

/** What became of a new task: on the server, or on this device until the queue sends it. */
sealed interface NewTaskOutcome {
    /** The new task's id, which the app made. */
    val taskId: String

    /** The server stored it; [task] is the row it answered with, its own times included. */
    data class Saved(val task: Task) : NewTaskOutcome {
        override val taskId: String
            get() = task.id
    }

    /** It waits in the mutation queue, and is sent when the server can be reached. */
    data class SavedOnDevice(override val taskId: String) : NewTaskOutcome
}

/**
 * Where a one-shot read's rows came from: the server, or the device's copy when the server could
 * not be reached.
 *
 * @property isFromCache true when the rows are the device's copy.
 * @property fetchedAt when the server last answered this query, or null if it never has.
 * @property hasPendingMutations true when a queued write changes these rows.
 */
data class ReadProvenance(
    val isFromCache: Boolean,
    val fetchedAt: Instant?,
    val hasPendingMutations: Boolean,
)

/** Where applying other devices' changes as they happen stands. */
enum class LiveUpdatesStatus {
    /** Switched off on the Sync tab. */
    Off,

    /** Waiting for a user, or for the app's network to be switched back on. */
    Paused,

    /** Joining the Realtime channel. */
    Connecting,

    /** Changes arrive as they happen. */
    Connected,

    /** The channel could not be joined; it is tried again when the switch or the network moves. */
    Failed,
}

/** The Sync tab's two switches, kept for as long as the app runs. */
data class SyncSettings(
    /** Ask the server before showing the list, rather than show the device's copy first. */
    val preferFreshData: Boolean = false,
    /** Apply other devices' changes as they happen, through Realtime. */
    val liveUpdates: Boolean = true,
)

/** What the server says about the user's tasks, from its database functions and a count. */
data class ServerCounts(val done: Int, val total: Int, val doneThisWeek: Int)

/** The server's answer to a refused write: the HTTP status and the Postgres error fields. */
data class TaskServerError(
    val status: Int,
    /** The Postgres SQLSTATE, such as `23514` for a check constraint. */
    val code: String?,
    val message: String,
    val details: String?,
    val hint: String?,
)

/**
 * The server refused a write that was sent at once, because the network was up and nothing waited
 * ahead of it. The write is abandoned and the device's copy is the server's again. A write that
 * waited in the queue is never thrown to anyone: when the server refuses it on replay, it appears
 * in [TaskRepository.rejectedMutations] instead.
 *
 * @property taskTitle the title the write carried, so the app can say which task it was.
 */
class TaskWriteRefusedException(
    val table: String,
    val operation: TaskMutationOperation,
    val taskTitle: String?,
    val serverError: TaskServerError,
    cause: Throwable? = null,
) : Exception(serverError.message, cause)

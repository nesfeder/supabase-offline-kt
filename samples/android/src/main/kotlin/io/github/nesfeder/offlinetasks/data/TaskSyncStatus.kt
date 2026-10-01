// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

/** Everything the app shows about syncing, in one value that changes as a whole. */
data class TaskSyncStatus(
    val network: TaskNetworkStatus,
    val pendingMutationCount: Int,
    val rejectedMutationCount: Int,
    /** True while a replay of the mutation queue is running. */
    val isSyncing: Boolean,
    /** When a replay last finished with the queue empty; kept in memory only. */
    val lastSyncedAt: Instant?,
    /** Why the last replay stopped early, cleared by the next clean one. */
    val lastSyncError: Throwable?,
)

/** What the app knows about reaching the server. */
enum class TaskNetworkStatus {
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
 * @property rowKey the id of the row the write was for, when known.
 * @property taskTitle the title the write carried, so the app can say which task it was.
 * @property serverError the server's answer, or null when the device refused the write itself.
 */
data class RejectedTaskMutation(
    val id: Long,
    val table: String,
    val operation: TaskMutationOperation,
    val rowKey: String?,
    val taskTitle: String?,
    val serverError: TaskServerError?,
    val message: String,
    val rejectedAt: Instant,
)

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
) : Exception(serverError.message)

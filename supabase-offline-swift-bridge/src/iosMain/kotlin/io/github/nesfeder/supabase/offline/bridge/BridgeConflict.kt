// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.ConflictReply

/**
 * Settles the conflicts a sync pass finds when it replays a guarded write
 * ([BridgeClientOptions.versionColumns]): the app's own resolver, behind a callback.
 */
public interface BridgeConflictResolver {

    /**
     * Decides [conflict] and hands the decision to [answer], once, on any thread. The pass waits
     * for it; no answer within 30 seconds, or [BridgeConflictAnswer.fail], holds the write.
     */
    public fun resolve(conflict: BridgeConflict, answer: BridgeConflictAnswer)
}

/**
 * A conflict a replay found: a queued UPDATE or DELETE of one row whose version on the server is no
 * longer the one the device saw. Rows cross as JSON text.
 *
 * @property mutationId the write's id in the queue.
 * @property table the table the write targets.
 * @property operation [BridgeMutationOperation.UPDATE] or [BridgeMutationOperation.DELETE].
 * @property rowKeyJson the row's key values as the write's filter pins them, as text, a JSON object.
 * @property baseJson the columns the write sets and the version column, as the device last saw the
 *   row, a JSON object.
 * @property localJson the columns the write sets, a JSON object; `null` for a DELETE.
 * @property serverJson the server's row now, in part, a JSON object; `null` when it is gone.
 * @property changedOnServer the columns the write sets whose server value differs from the base.
 * @property conflictingColumns of [changedOnServer], those the write sets to another value.
 */
public class BridgeConflict
internal constructor(
    public val mutationId: Long,
    public val table: String,
    public val operation: BridgeMutationOperation,
    public val rowKeyJson: String,
    public val baseJson: String,
    public val localJson: String?,
    public val serverJson: String?,
    public val changedOnServer: List<String>,
    public val conflictingColumns: List<String>,
)

/** The one-time answer to a [BridgeConflictResolver.resolve] call; later answers are ignored. */
public class BridgeConflictAnswer internal constructor(private val answer: (ConflictReply) -> Unit) {

    /** Sends the write again, preconditioned on the server's row as it is now. */
    public fun keepLocal() {
        answer(ConflictReply.KeepLocal)
    }

    /** Drops the write without sending it; the server's row stands. */
    public fun keepServer() {
        answer(ConflictReply.KeepServer)
    }

    /**
     * Sends [columnsJson], a JSON object of at least one column, as the UPDATE's body instead; any
     * other text holds the write.
     */
    public fun apply(columnsJson: String) {
        answer(ConflictReply.Apply(columnsJson))
    }

    /** Parks the write on the conflicted list. */
    public fun hold() {
        answer(ConflictReply.Hold)
    }

    /** Answers that the app's resolver failed, which holds the write; [message] is not logged. */
    public fun fail(message: String) {
        answer(ConflictReply.Failed(message))
    }
}

/** How [BridgeClient.resolveConflictedMutation] settles an entry of the conflicted list. */
public enum class BridgeConflictDecision {
    /** Queues the write again, preconditioned on the server version the entry recorded. */
    KEEP_LOCAL,

    /** Drops the write; the server's row stands. */
    KEEP_SERVER,

    /** Queues the given columns as the UPDATE's body, preconditioned as [KEEP_LOCAL] is. */
    APPLY,

    /** Keeps the entry parked; the library refuses it with [BridgeErrorKind.INVALID_ARGUMENT]. */
    HOLD,
}

/**
 * A queued write parked on the conflicted list, kept until the app resolves or dismisses it.
 *
 * @property id the write's id, as it had in the queue.
 * @property table the table it targeted.
 * @property operation [BridgeMutationOperation.UPDATE] or [BridgeMutationOperation.DELETE].
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the row's key values as the write's filter pins them, as text, a JSON array
 *   of objects.
 * @property bodyJson the body it sends, as JSON text; `null` for a DELETE or one too large to keep.
 * @property baseJson the columns it sets and the version column as the device saw them, a JSON
 *   object.
 * @property serverJson the server's row when the replay read it, in part, a JSON object; `null`
 *   when it was gone or too large to keep.
 * @property serverVersion the server row's version then, as text; `null` when the row was gone.
 * @property changedOnServer the columns it sets whose server value differed from the base.
 * @property conflictingColumns of [changedOnServer], those it sets to another value.
 * @property message why it was parked, in words.
 * @property conflictedAtEpochMillis when it was parked, in milliseconds since 1970.
 */
public class BridgeConflictedMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: BridgeMutationOperation,
    public val createdAtEpochMillis: Long,
    public val rowKeysJson: String,
    public val bodyJson: String?,
    public val baseJson: String,
    public val serverJson: String?,
    public val serverVersion: String?,
    public val changedOnServer: List<String>,
    public val conflictingColumns: List<String>,
    public val message: String,
    public val conflictedAtEpochMillis: Long,
)

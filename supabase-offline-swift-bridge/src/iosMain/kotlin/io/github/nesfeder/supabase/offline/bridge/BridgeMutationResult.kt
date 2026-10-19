// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * What became of a write ([BridgeClient.mutate]): the server applied it, or it waits in the
 * mutation queue.
 *
 * @property isQueued whether the write waits in the mutation queue.
 * @property applied the server's response when it applied the write; `null` when queued.
 * @property queued the queued write when it waits; `null` when applied.
 */
public class BridgeMutationResult
internal constructor(
    public val isQueued: Boolean,
    public val applied: BridgeQueryResult?,
    public val queued: BridgePendingMutation?,
)

/**
 * One write waiting in the mutation queue.
 *
 * @property id the write's id in the queue, which a later rejection keeps.
 * @property table the table it targets.
 * @property operation what it does.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the primary-key values of the rows it touches, a JSON array of objects.
 * @property bodyJson the body it sends, as JSON text; `null` for a delete.
 * @property baseVersion the row's version a replay is preconditioned on, as text; `null` when the
 *   write is not guarded.
 */
public class BridgePendingMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: BridgeMutationOperation,
    public val createdAtEpochMillis: Long,
    public val rowKeysJson: String,
    public val bodyJson: String?,
    public val baseVersion: String?,
)

/**
 * A queued write the server refused for good on replay, kept until it is dismissed or retried.
 *
 * @property id the write's id, as it had in the queue.
 * @property table the table it targeted.
 * @property operation what it did.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property rowKeysJson the primary-key values of the rows it touches, a JSON array of objects.
 * @property bodyJson the body it sent, as JSON text; `null` for a delete or one too large to keep.
 * @property serverError what the server answered; `null` when the library refused it itself.
 * @property message why it was refused, in words.
 * @property rejectedAtEpochMillis when it was refused, in milliseconds since 1970.
 * @property isRetryable whether [BridgeClient.retryRejectedMutation] can queue it again.
 */
public class BridgeRejectedMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: BridgeMutationOperation,
    public val createdAtEpochMillis: Long,
    public val rowKeysJson: String,
    public val bodyJson: String?,
    public val serverError: BridgeServerError?,
    public val message: String,
    public val rejectedAtEpochMillis: Long,
    public val isRetryable: Boolean,
)

/**
 * PostgREST's answer to a refused write, kept so it survives a restart.
 *
 * @property status the HTTP status.
 * @property code the SQLSTATE or PostgREST code, when sent.
 * @property message the server's message.
 * @property details the server's details, when sent.
 * @property hint the server's hint, when sent.
 */
public class BridgeServerError
internal constructor(
    public val status: Int,
    public val code: String?,
    public val message: String,
    public val details: String?,
    public val hint: String?,
)

/** What a write does. */
public enum class BridgeMutationOperation {
    /** Inserts rows. */
    INSERT,

    /** Inserts rows, or merges them into the rows they conflict with. */
    UPSERT,

    /** Sets columns on the rows a filter selects. */
    UPDATE,

    /** Deletes the rows a filter selects. */
    DELETE,
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A queued write the server refused for good when it was replayed, kept on the rejected list until
 * the app dismisses or retries it ([Offline.rejectedMutations]).
 *
 * Only a write that waited in the mutation queue can end up here. A write sent at once that the
 * server refuses throws the server's error to its caller instead, as supabase-kt's verbs do.
 *
 * @property id the write's id in the queue, the [PendingMutation.id] it had while it waited.
 * @property table the table the write targeted.
 * @property operation what the write did.
 * @property createdAt when the write was queued, by the device's clock.
 * @property rowKeys the primary-key values of the rows the write touches, as for
 *   [PendingMutation.rowKeys].
 * @property body the body the write sent, or `null` for a DELETE, a stored body that can no longer
 *   be read, or one too large to keep on the list (then [isRetryable] is `false`).
 * @property serverError what the server answered, or `null` when the library refused the write
 *   itself because it could no longer be replayed exactly as it was made.
 * @property message why it was refused, in words: the server's message, or the library's reason.
 * @property rejectedAt when it was refused, by the device's clock.
 * @property isRetryable whether [Offline.retryRejectedMutation] can queue it again. It cannot when
 *   its body was too large to keep on the list.
 */
@ConsistentCopyVisibility
public data class RejectedMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: MutationOperation,
    public val createdAt: Instant,
    public val rowKeys: List<JsonObject>,
    public val body: JsonElement?,
    public val serverError: ServerError?,
    public val message: String,
    public val rejectedAt: Instant,
    public val isRetryable: Boolean,
)

/**
 * What the server answered when it refused a write: PostgREST's error fields, kept as data so they
 * survive a restart.
 *
 * @property status the HTTP status.
 * @property code the Postgres SQLSTATE (such as `23505`) or PostgREST code (such as `PGRST204`),
 *   when the server sent one.
 * @property message the server's message.
 * @property details the server's details, when it sent any.
 * @property hint the server's hint, when it sent one.
 */
@ConsistentCopyVisibility
public data class ServerError
internal constructor(
    public val status: Int,
    public val code: String?,
    public val message: String,
    public val details: String?,
    public val hint: String?,
)

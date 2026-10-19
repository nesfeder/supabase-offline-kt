// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A queued write parked because its row moved on the server and the conflict was not settled during
 * the replay, kept on the conflicted list until the app resolves or dismisses it
 * ([Offline.conflictedMutations]).
 *
 * @property id the write's id in the queue, the [PendingMutation.id] it had while it waited.
 * @property table the table the write targeted.
 * @property operation [MutationOperation.UPDATE] or [MutationOperation.DELETE].
 * @property createdAt when the write was queued, by the device's clock.
 * @property rowKeys the primary-key values of the row the write touches, as for
 *   [PendingMutation.rowKeys].
 * @property body the body the write sends, or `null` for a DELETE or one too large to keep on the
 *   list (such an entry cannot be resent with [ConflictResolution.KeepLocal]).
 * @property base the columns the write sets and the version column, as the device last saw the row;
 *   empty when they could not be kept.
 * @property server the server's row when the replay read it, in part: its key columns, the version
 *   column, the columns the write sets and the columns the write's filter names. `null` when it was
 *   gone, or too large to keep.
 * @property serverVersion the server row's version then, as text; `null` when the row was gone. A
 *   write resolved from the list is sent preconditioned on it.
 * @property changedOnServer the columns the write sets whose server value differed from
 *   [ConflictedMutation.base]'s.
 * @property conflictingColumns of [ConflictedMutation.changedOnServer], those the write sets to
 *   another value than the server's.
 * @property message why it was parked, in words.
 * @property conflictedAt when it was parked, by the device's clock.
 */
@ConsistentCopyVisibility
public data class ConflictedMutation
internal constructor(
    public val id: Long,
    public val table: String,
    public val operation: MutationOperation,
    public val createdAt: Instant,
    public val rowKeys: List<JsonObject>,
    public val body: JsonElement?,
    public val base: JsonObject,
    public val server: JsonObject?,
    public val serverVersion: String?,
    public val changedOnServer: Set<String>,
    public val conflictingColumns: Set<String>,
    public val message: String,
    public val conflictedAt: Instant,
)

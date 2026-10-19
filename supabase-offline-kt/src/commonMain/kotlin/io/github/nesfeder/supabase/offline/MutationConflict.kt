// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlinx.serialization.json.JsonObject

/**
 * A conflict a replay found, as a [ConflictResolver] is asked about it: a queued UPDATE or DELETE
 * of one row whose version on the server is no longer the one the device saw when the write was
 * made.
 *
 * @property mutationId the write's [PendingMutation.id].
 * @property table the table the write targets.
 * @property operation [MutationOperation.UPDATE] or [MutationOperation.DELETE].
 * @property rowKey the primary-key values of the row, as the write's filter pins them (as
 *   [PendingMutation.rowKeys] gives them).
 * @property base the columns the write sets and the version column, as the device last saw the row.
 * @property local the columns the write sets, with the values it sets them to; `null` for a DELETE.
 * @property server the server's row as it is now: its key columns, the version column and the
 *   columns the write sets. `null` when it is gone, or hidden from this user by a row-level policy.
 * @property changedOnServer the columns the write sets whose server value differs from [base]'s:
 *   what changed elsewhere. Empty for a DELETE, and for a row that is gone.
 * @property conflictingColumns of [changedOnServer], the columns [local] sets to another value than
 *   the server's: where the two changes disagree.
 */
@ConsistentCopyVisibility
public data class MutationConflict
internal constructor(
    public val mutationId: Long,
    public val table: String,
    public val operation: MutationOperation,
    public val rowKey: JsonObject,
    public val base: JsonObject,
    public val local: JsonObject?,
    public val server: JsonObject?,
    public val changedOnServer: Set<String>,
    public val conflictingColumns: Set<String>,
)

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * What one call of [Offline.syncPendingMutations] did with the current user's queued writes.
 *
 * Every count is the current user's, within the call's scope (one table, or every table): another
 * user's writes are neither sent nor counted.
 *
 * @property syncedCount the writes this call sent and the server accepted.
 * @property rejected the writes the server refused for good during this call, now on the rejected
 *   list ([Offline.rejectedMutations]).
 * @property remainingCount the writes still queued when the call returned. More than zero when the
 *   network could not be reached or the server failed in a way that may pass: then
 *   [SyncStatus.lastSyncError] says why, and the writes are sent by a later pass.
 * @property alreadyOnServerCount of [syncedCount], the inserts whose rows the server already held:
 *   an earlier send whose answer never arrived had in fact reached it, so this send was taken as
 *   that one rather than inserting the rows twice.
 */
@ConsistentCopyVisibility
public data class SyncReport
internal constructor(
    public val syncedCount: Int,
    public val rejected: List<RejectedMutation>,
    public val remainingCount: Int,
    public val alreadyOnServerCount: Int,
)

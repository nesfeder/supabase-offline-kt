// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * What one call of [Offline.syncPendingMutations] did with the current user's queued writes.
 *
 * Every count is the current user's, within the call's scope (one table, or every table): another
 * user's writes are neither sent nor counted.
 *
 * Its counts are this call's own. With [Offline.Config.autoSync] on, a pass the library ran by
 * itself may have sent, refused or parked writes just before; those are counted nowhere here, a
 * refusal is found on [Offline.rejectedMutations] and a parked write on
 * [Offline.conflictedMutations].
 *
 * @property syncedCount the writes this call sent and the server accepted.
 * @property rejected the writes the server refused for good during this call, now on the rejected
 *   list ([Offline.rejectedMutations]) unless local data was cleared since.
 * @property remainingCount the writes still queued when the call returned. More than zero when the
 *   network could not be reached or the server failed in a way that may pass (then
 *   [SyncStatus.lastSyncError] says why, and the writes are sent by a later pass), or when writes
 *   wait behind other tables ([SyncReport.heldBehindOtherTablesCount]).
 * @property alreadyOnServerCount of [SyncReport.syncedCount], the writes the server already held:
 *   inserts whose rows it had (an earlier send whose answer never arrived had in fact reached it,
 *   so this send was taken as that one rather than inserting the rows twice), guarded updates whose
 *   lost answer had landed, and guarded deletes whose row was already gone.
 * @property heldBehindOtherTablesCount of [SyncReport.remainingCount], the writes a call for one
 *   table held back because an earlier write of the user to another table is still queued: no write
 *   is sent ahead of an earlier one, so a call that syncs every table sends them in order. Always
 *   `0` for such a call.
 * @property conflictsResolvedCount of [SyncReport.syncedCount], the guarded writes whose row had
 *   moved on the server, settled by the [ConflictResolver] or the default and sent again: sent, not
 *   necessarily applied, since a resend the rest of its filter no longer reaches syncs as well.
 * @property conflictsKeptServerCount the guarded writes dropped without being sent because the
 *   server's row was let stand ([ConflictResolution.KeepServer]); counted in no other count.
 * @property conflicted the guarded writes parked during this call, now on the conflicted list
 *   ([Offline.conflictedMutations]) unless local data was cleared since.
 */
@ConsistentCopyVisibility
public data class SyncReport
internal constructor(
    public val syncedCount: Int,
    public val rejected: List<RejectedMutation>,
    public val remainingCount: Int,
    public val alreadyOnServerCount: Int,
    public val heldBehindOtherTablesCount: Int,
    public val conflictsResolvedCount: Int,
    public val conflictsKeptServerCount: Int,
    public val conflicted: List<ConflictedMutation>,
)

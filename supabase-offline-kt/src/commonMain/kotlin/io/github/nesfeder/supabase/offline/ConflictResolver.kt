// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Decides a conflict the library found when it replayed a queued write to a table with a version
 * column ([Offline.Config.versionColumns]): the row moved on the server since the device last saw
 * it, or is gone. Set one as [Offline.Config.conflictResolver]; without one, the defaults
 * [ConflictResolution] names apply.
 *
 * An app may implement this interface. It is called during a sync pass for an UPDATE whose row
 * moved with at least one of the write's columns changed on the server (a row moved on other
 * columns only is resent against its new version without asking), for a DELETE of a row that moved,
 * and for an UPDATE whose row is gone; again when a resent write finds the row moved once more, but
 * never after the last of the three sends a write gets in one pass. It runs outside every database
 * transaction, on the library's own threads, while the pass waits for its answer, so it should
 * answer promptly and never wait for a sync pass itself. An answer that throws, or that has not
 * come after 30 seconds, reads as [ConflictResolution.Hold]: the write is parked on
 * [Offline.conflictedMutations].
 */
public fun interface ConflictResolver {

    /**
     * How to settle [conflict].
     *
     * @param conflict what the replay found.
     * @return the resolution.
     */
    @Throws(Throwable::class)
    public suspend fun resolve(conflict: MutationConflict): ConflictResolution
}

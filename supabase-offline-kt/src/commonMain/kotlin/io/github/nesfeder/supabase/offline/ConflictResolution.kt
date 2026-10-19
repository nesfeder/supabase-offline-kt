// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlinx.serialization.json.JsonObject

/**
 * How a conflict is settled: what a [ConflictResolver] answers, and what
 * [Offline.resolveConflictedMutation] takes.
 *
 * Without a resolver, a moved row is settled with [KeepLocal] (an UPDATE's or a DELETE's), and an
 * UPDATE of a row that is gone with [KeepServer]. More cases may be added in a minor release: the
 * app only passes one in.
 */
public sealed interface ConflictResolution {

    /**
     * Sends the write again, preconditioned on the server's row as it is now: an UPDATE sets its
     * columns over the server's (the other columns keep the server's values), a DELETE still
     * deletes. The default for a row that moved. A row that is gone cannot take it, so the write is
     * parked instead.
     */
    public data object KeepLocal : ConflictResolution

    /**
     * Drops the write without sending it; the server's row stands, and the cache shows it. The
     * default for an UPDATE whose row is gone. Counted in [SyncReport.conflictsKeptServerCount].
     */
    public data object KeepServer : ConflictResolution

    /**
     * Sends [columns] as the UPDATE's body instead of the write's own, preconditioned on the
     * server's row as it is now: for a merge the app works out. Only for an UPDATE of a row still
     * on the server; for a DELETE, or a row that is gone, the write is parked instead. Build one
     * with [ConflictResolution.apply].
     *
     * @property columns the columns to set, and their values.
     */
    @ConsistentCopyVisibility
    public data class Apply internal constructor(public val columns: JsonObject) :
        ConflictResolution

    /**
     * Parks the write on [Offline.conflictedMutations] for the app to settle later, with
     * [Offline.resolveConflictedMutation] or [Offline.dismissConflictedMutation]. The pass goes on
     * with the next write.
     */
    public data object Hold : ConflictResolution

    /** Builds the resolutions that carry data. */
    public companion object {

        /**
         * An [Apply] that sends [columns].
         *
         * @param columns the columns to set, and their values.
         * @return the resolution.
         */
        public fun apply(columns: JsonObject): Apply = Apply(columns)
    }
}

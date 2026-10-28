// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop

/*
 * Two ways for the engine's own tests to run one replay pass and read it as a count. The library
 * runs its passes through MutationQueueReplayer.sync and syncWaitingOutOtherSenders. They live in
 * this package so the shipped-queue replay, a stored-format pin, calls them as it always has.
 */

/**
 * Replays the current identity's mutations across every table: one pass of
 * [MutationQueueReplayer.sync].
 *
 * @return The number the server accepted.
 */
internal suspend fun MutationQueueReplayer.syncAllTables(): Int = sync(table = null).syncedCount

/**
 * Replays the current identity's mutations to [table]. A pass that stops on a failure other than
 * connectivity rethrows it; the mutation stays queued.
 *
 * @return The number the server accepted.
 */
internal suspend fun MutationQueueReplayer.syncTable(table: String): Int {
    val report = sync(table = table)
    val stop = report.stop
    if (stop is SyncStop.TransientFailure && !stop.cause.isNetworkOrConnectivityFailure())
        throw stop.cause
    return report.syncedCount
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant

/**
 * Where the library stands with the current user's queued writes and the network, in one value:
 * what an app needs to draw one sync banner ([Offline.syncStatus]).
 *
 * [SyncStatus.network] is current as soon as [Offline.disableNetwork] or [Offline.enableNetwork]
 * returns. The counts are eventually current: they follow each change to the mutation queue shortly
 * after it is made, so a test that reads them straight after a write waits for the value it
 * expects. They are read together: a write moving between the queue and the rejected or conflicted
 * list, refused or parked on replay, retried or resolved, is counted in exactly one of them.
 *
 * @property network whether the library believes it can reach the network; see [NetworkStatus].
 * @property pendingMutationCount the current user's writes waiting in the mutation queue, in every
 *   table.
 * @property rejectedMutationCount the current user's entries on the rejected list.
 * @property isSyncing whether a sync pass is running, started by the app or by the library.
 * @property lastSyncedAt when the current user's queue was last emptied by sending, by a sync pass
 *   or by a write sent at once, by the device's clock; `null` until it happens. Kept in memory
 *   only: a new client starts without it.
 * @property lastSyncError the failure that last stopped a sync pass with writes still queued
 *   ([SyncStopReason.NetworkUnavailable], [SyncStopReason.ServerFailure] or
 *   [SyncStopReason.LocalFailure]): the server's [SyncFailedException.httpStatus] and
 *   [SyncFailedException.code], or a failure the network or the device caused. `null` once a later
 *   pass runs to the end or stops because the session changed user, sending empties the queue, or
 *   local data is cleared. A pass that stops because the session is not ready, network access is
 *   off or an earlier write waits in another table leaves it as it was: no newer answer came for
 *   the writes it held back. It carries no row, filter value or token.
 * @property conflictedMutationCount the current user's entries on the conflicted list
 *   ([Offline.conflictedMutations]).
 */
@ConsistentCopyVisibility
public data class SyncStatus
internal constructor(
    public val network: NetworkStatus,
    public val pendingMutationCount: Int,
    public val rejectedMutationCount: Int,
    public val isSyncing: Boolean,
    public val lastSyncedAt: Instant?,
    public val lastSyncError: SyncFailedException?,
    public val conflictedMutationCount: Int,
)

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.cache.ShownQueueCounts
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncReport as EngineSyncReport
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.syncFailureOf
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps [OfflineClient.syncStatus] current: the network, whether a pass runs, the current user's
 * queued, rejected and conflicted counts, and what the last pass and the last send left.
 *
 * Every change goes through [statusLock], a leaf lock: nothing else is taken while it is held, and
 * [publishFinal] sets [statusFinal] under it, after which the status changes no more. The counts
 * are an in-memory mirror of the queue and the two lists, eventually current: each change to them
 * asks for a count, read in one snapshot under the queue's lock ([MutationQueue.shownQueueCounts]),
 * so a write moving between them is counted once. A failed read is followed again after a wait
 * doubling from [STATUS_RETRY_WAIT].
 *
 * @param scope where the work that keeps the status current runs: the client's, so its close stops
 *   it.
 * @param replayIdentityNow the identity a replay sent now would go out as, as [SessionIdentity]
 *   tells it.
 */
internal class SyncStatusTracker(
    private val scope: CoroutineScope,
    private val networkAccess: NetworkAccess,
    private val mutationQueue: MutationQueue,
    private val engineLogger: EngineLogger,
    private val clock: Clock,
    private val replayIdentityNow: suspend () -> QueueIdentity?,
) {

    /**
     * What [status] reports; its network is set at once, its counts as the queue changes, and
     * [SyncStatus.isQueueCounted] with the first count.
     */
    private val syncStatusState =
        MutableStateFlow(
            SyncStatus(
                network = networkAccess.status,
                pendingMutationCount = 0,
                rejectedMutationCount = 0,
                isSyncing = false,
                lastSyncedAt = null,
                lastSyncError = null,
                conflictedMutationCount = 0,
                isQueueCounted = false,
            )
        )

    /**
     * Held by every change to [syncStatusState], so the client's close can publish the last one.
     */
    private val statusLock = Mutex()

    /** Set, under [statusLock], by [publishFinal]: the status changes no more. */
    private var statusFinal = false

    /** The same flow whether or not the client is open; after its close it keeps its last value. */
    val status: StateFlow<SyncStatus> = syncStatusState.asStateFlow()

    /** Publishes the network as [networkAccess] has it now. */
    suspend fun publishNetwork() {
        publishStatus { status -> status.copy(network = networkAccess.status) }
    }

    /** Applies [change] to the status, unless [publishFinal] has published its last value. */
    private suspend fun publishStatus(change: (SyncStatus) -> SyncStatus) {
        statusLock.withLock { if (!statusFinal) syncStatusState.update(change) }
    }

    /**
     * Publishes the status's last value, with no pass running, after which it changes no more: the
     * work that kept it current is canceled with the client's scope, and a pass running at close
     * would otherwise leave it syncing for good.
     */
    suspend fun publishFinal() {
        statusLock.withLock {
            statusFinal = true
            syncStatusState.update { status -> status.copy(isSyncing = false) }
        }
    }

    /**
     * Starts, at open, the work that keeps [status] current, in [scope] so the client's close stops
     * it. It follows the network, the passes ([passRunning]), and the counts as the queue and the
     * lists change, the first count marking the status counted; after a failed read, again after a
     * wait doubling up to about a minute.
     */
    fun start(passRunning: Flow<Boolean>) {
        scope.launch {
            publishStatus { status -> status.copy(network = networkAccess.status) }
            networkAccess.statusChanges.collect {
                publishStatus { status -> status.copy(network = networkAccess.status) }
            }
        }
        scope.launch {
            passRunning.collect { running ->
                publishStatus { status -> status.copy(isSyncing = running) }
            }
        }
        scope.launch {
            var doublings = 0
            while (true) {
                try {
                    // The flows only say when to count; every count is read in one snapshot under
                    // the queue's lock, so a write moving between the queue and a list is counted
                    // once, and an older snapshot never lands after a newer one
                    merge(
                            mutationQueue.queueChanges(),
                            mutationQueue.shownRejectionsFlow().map {},
                            mutationQueue.shownConflictsFlow().map {},
                        )
                        .conflate()
                        .collect {
                            val counts = queueCountsOrNull() ?: return@collect
                            publishStatus { status ->
                                status.copy(
                                    pendingMutationCount = counts.pending,
                                    rejectedMutationCount = counts.rejected,
                                    conflictedMutationCount = counts.conflicted,
                                    isQueueCounted = true,
                                )
                            }
                        }
                    return@launch
                } catch (failure: Exception) {
                    // Closing ends it. Any other failure is followed again, one that cut a count
                    // short included: `retryWhen` rethrows a flow's failure that canceled the
                    // count in progress, which ended the status's counts for good
                    ensureActive()
                    engineLogger.warning {
                        "The sync status lost track of the queue (${failure::class.simpleName}); " +
                            "following it again"
                    }
                    delay(STATUS_RETRY_WAIT * (1 shl doublings))
                    doublings = minOf(doublings + 1, STATUS_RETRY_DOUBLINGS)
                }
            }
        }
    }

    /**
     * The current user's pending and rejected counts, or `null` when counting fails; the status
     * keeps its last counts then, and says so.
     */
    private suspend fun queueCountsOrNull(): ShownQueueCounts? =
        try {
            mutationQueue.shownQueueCounts()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not count the queued and rejected mutations for the sync status " +
                    "(${failure::class.simpleName})"
            }
            null
        }

    /**
     * Records what a pass that ended with [report] tells the status: a failure that stopped it as
     * [SyncStatus.lastSyncError], built without the failure's message; a pass that ran to the end
     * clears it, and records [SyncStatus.lastSyncedAt] when it left the session's queue empty. A
     * change of user or a clear clears it too, as the writes it held back are another user's or
     * gone. A pass that got no newer answer for the writes, since the session was not ready, access
     * was off, an earlier write waits in another table or another sender has the next one, leaves
     * it as it was.
     */
    suspend fun recordPassEnded(report: EngineSyncReport) {
        when (val stop = report.stop) {
            null ->
                if (report.remainingCount == 0 && sessionQueueIsEmpty()) {
                    recordQueueEmptiedBySending()
                } else {
                    publishStatus { status -> status.copy(lastSyncError = null) }
                }
            is SyncStop.TransientFailure ->
                publishStatus { status -> status.copy(lastSyncError = syncFailureOf(stop.cause)) }
            is SyncStop.HeldBehindEarlierMutations ->
                publishStatus { status ->
                    status.copy(lastSyncError = syncFailureOf(stop.cause))
                }
            SyncStop.SessionChanged,
            SyncStop.LocalDataCleared ->
                publishStatus { status -> status.copy(lastSyncError = null) }
            SyncStop.SessionNotReady,
            SyncStop.IdentityUnknown,
            SyncStop.NetworkDisabled,
            is SyncStop.HeldBehindOtherTables,
            SyncStop.HeadInFlight -> Unit
        }
    }

    /** Whether the settled session has nothing queued; `false` when that cannot be told. */
    private suspend fun sessionQueueIsEmpty(): Boolean {
        val identity = replayIdentityNow() ?: return false
        return try {
            !mutationQueue.hasPendingMutationsOf(identity.storedValue)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not tell whether the queue is empty for the sync status " +
                    "(${failure::class.simpleName})"
            }
            false
        }
    }

    /** Records that sending left the session's queue empty: now, and no error since. */
    suspend fun recordQueueEmptiedBySending() {
        publishStatus { status ->
            status.copy(lastSyncedAt = clock.now(), lastSyncError = null)
        }
    }

    /** Records that local data was cleared: the writes an earlier failure held back are gone. */
    suspend fun recordLocalDataCleared() {
        publishStatus { status -> status.copy(lastSyncError = null) }
    }

    companion object {
        /** The first wait before the status follows the queue again after a failed read. */
        val STATUS_RETRY_WAIT: Duration = 1.seconds

        /** How many times that wait doubles: at most 64 times [STATUS_RETRY_WAIT]. */
        const val STATUS_RETRY_DOUBLINGS: Int = 6
    }
}

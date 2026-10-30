// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.cache.LastSyncedAt
import io.github.nesfeder.supabase.offline.internal.cache.LastSyncedAtRecord
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
import kotlin.time.Instant
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
 * [SyncStatus.lastSyncedAt] is the one part kept on the device, in [lastSyncedAtRecord]: the first
 * counted status carries what the device holds, when it is the cache owner's or the cache has none,
 * and each later record of it is written after it is published. A clear, and a drop of the cache
 * for another identity, take it back.
 *
 * @param scope where the work that keeps the status current runs: the client's, so its close stops
 *   it.
 * @param replayIdentityNow the identity a replay sent now would go out as, as [SessionIdentity]
 *   tells it.
 * @param cacheOwner the identity the cache is recorded as belonging to, or `null` for none.
 */
internal class SyncStatusTracker(
    private val scope: CoroutineScope,
    private val networkAccess: NetworkAccess,
    private val mutationQueue: MutationQueue,
    private val lastSyncedAtRecord: LastSyncedAtRecord,
    private val engineLogger: EngineLogger,
    private val clock: Clock,
    private val replayIdentityNow: suspend () -> QueueIdentity?,
    private val cacheOwner: suspend () -> String?,
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

    /**
     * Whose queue [SyncStatus.lastSyncedAt] was last emptied for, as the queue stores an identity;
     * `null` while it is `null`. Guarded by [statusLock].
     */
    private var lastSyncedOwner: String? = null

    /** The same flow whether or not the client is open; after its close it keeps its last value. */
    val status: StateFlow<SyncStatus> = syncStatusState.asStateFlow()

    /** Publishes the network as [networkAccess] has it now. */
    suspend fun publishNetwork() {
        publishStatus { status -> status.copy(network = networkAccess.status) }
    }

    /**
     * Applies [change] to the status, unless [publishFinal] has published its last value.
     *
     * @return whether it was applied.
     */
    private suspend fun publishStatus(change: (SyncStatus) -> SyncStatus): Boolean =
        statusLock.withLock {
            if (statusFinal) return@withLock false
            syncStatusState.update(change)
            true
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
     * lists change, the first count marking the status counted and carrying the last-synced time
     * the device kept; after a failed read, again after a wait doubling up to about a minute.
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
            // Read once, before the first count, and published with it; a time recorded by a send
            // that got ahead of the first count is newer and stays
            var restored: LastSyncedAt? = restoredLastSyncedAt()
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
                            val restoring = restored
                            restored = null
                            statusLock.withLock {
                                if (statusFinal) return@withLock
                                if (restoring != null && lastSyncedOwner == null) {
                                    lastSyncedOwner = restoring.owner
                                }
                                syncStatusState.update { status ->
                                    status.copy(
                                        pendingMutationCount = counts.pending,
                                        rejectedMutationCount = counts.rejected,
                                        conflictedMutationCount = counts.conflicted,
                                        isQueueCounted = true,
                                        lastSyncedAt = status.lastSyncedAt ?: restoring?.at,
                                    )
                                }
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
     * The last-synced time the device kept, when it is the recorded cache owner's or the cache has
     * no recorded owner yet; another identity's, which a failed drop can leave, is removed and read
     * as none, so one user's time never shows for the next. A failed read is logged and read as
     * none: the status starts without it, as it did before anything was kept.
     */
    private suspend fun restoredLastSyncedAt(): LastSyncedAt? =
        try {
            val kept = lastSyncedAtRecord.load() ?: return null
            val owner = cacheOwner()
            if (owner == null || owner == kept.owner) {
                kept
            } else {
                lastSyncedAtRecord.clear()
                null
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not read when the queue was last emptied (${failure::class.simpleName}); " +
                    "the status starts without it"
            }
            null
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
            null -> {
                val emptiedFor = if (report.remainingCount == 0) identityWithEmptyQueue() else null
                if (emptiedFor != null) {
                    recordQueueEmptiedBySending(emptiedFor)
                } else {
                    publishStatus { status -> status.copy(lastSyncError = null) }
                }
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

    /**
     * The settled session's identity, when it has nothing queued; `null` when it has, or when that
     * cannot be told.
     */
    private suspend fun identityWithEmptyQueue(): QueueIdentity? {
        val identity = replayIdentityNow() ?: return null
        return try {
            identity.takeUnless { mutationQueue.hasPendingMutationsOf(identity.storedValue) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not tell whether the queue is empty for the sync status " +
                    "(${failure::class.simpleName})"
            }
            null
        }
    }

    /**
     * Records that sending left [identity]'s queue empty: now, to the millisecond, and no error
     * since; then keeps the time on the device, outside the status's lock. A failed write is
     * logged: the status has the time, and the next client starts without it.
     */
    suspend fun recordQueueEmptiedBySending(identity: QueueIdentity) {
        val emptied =
            LastSyncedAt(
                owner = identity.storedValue,
                at = Instant.fromEpochMilliseconds(clock.now().toEpochMilliseconds()),
            )
        val published = statusLock.withLock {
            if (statusFinal) return@withLock false
            lastSyncedOwner = emptied.owner
            syncStatusState.update { status ->
                status.copy(lastSyncedAt = emptied.at, lastSyncError = null)
            }
            true
        }
        if (!published) return
        try {
            lastSyncedAtRecord.save(emptied)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not keep when the queue was last emptied (${failure::class.simpleName}); " +
                    "the next client starts without it"
            }
        }
    }

    /**
     * Records that local data was cleared: the writes an earlier failure held back are gone, and so
     * is the record of when the queue was last emptied, with the clear's transaction.
     */
    suspend fun recordLocalDataCleared() {
        statusLock.withLock {
            if (statusFinal) return@withLock
            lastSyncedOwner = null
            syncStatusState.update { status ->
                status.copy(lastSyncedAt = null, lastSyncError = null)
            }
        }
    }

    /**
     * Records that the cache now belongs to [owner]: a last-synced time that was another identity's
     * goes, as its record went with the drop's transaction; one already [owner]'s stays.
     */
    suspend fun recordCacheOwnerChanged(owner: String) {
        statusLock.withLock {
            if (statusFinal || lastSyncedOwner == owner) return@withLock
            lastSyncedOwner = null
            syncStatusState.update { status -> status.copy(lastSyncedAt = null) }
        }
    }

    companion object {
        /** The first wait before the status follows the queue again after a failed read. */
        val STATUS_RETRY_WAIT: Duration = 1.seconds

        /** How many times that wait doubles: at most 64 times [STATUS_RETRY_WAIT]. */
        const val STATUS_RETRY_DOUBLINGS: Int = 6
    }
}

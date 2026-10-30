// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.cache.CacheOwnership
import io.github.nesfeder.supabase.offline.internal.cache.LocalData
import io.github.nesfeder.supabase.offline.internal.db.DatabaseScrub
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AutomaticReplay
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.replayIdentityFor
import io.github.nesfeder.supabase.offline.internal.postgrest.LiveQueries
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/**
 * Acts on the session and on whose the cache is, for [OfflineClient]: starts the passes a settled
 * session, a returning network or an earlier run's queue asks for; keeps the cache the settled
 * identity's, dropping another identity's when the user switches; and clears local data for a
 * sign-out. [SessionIdentity] tells it whom the session speaks for.
 *
 * Locks, outermost first, as [LocalData] lists them all:
 * - a clear ([clearLocalData]) takes the replayer's send lock, after it cancels the passes in
 *   flight; then the file cache's lock; then [LocalData.clearLocalData]'s queue lock and clear
 *   lock, and innermost the cache-owner record's, around its one transaction;
 * - a drop for a new owner ([keepCacheWithTheSession]) takes the send lock alone, stopping no pass
 *   since none of the queue goes; then the file cache's lock and [LocalData.dropCacheFor]'s.
 *
 * Each runs the database's rewrite after its transaction, inside those locks; what an open read
 * held off is finished outside them ([DatabaseScrub]). It keeps nothing in memory of its own.
 *
 * @param scope where its work runs: the client's, so its close stops it.
 * @param autoSync [io.github.nesfeder.supabase.offline.Offline.Config.autoSync], read once when the
 *   client opened.
 * @param cacheOwnerChanged told, once a drop has made the cache another identity's, whose it now
 *   is, so the status takes back what was the identity before's.
 */
internal class SessionCoordinator(
    private val scope: CoroutineScope,
    private val session: SessionIdentity,
    private val autoSync: Boolean,
    private val operationGate: ClientOperationGate,
    private val mutationQueue: MutationQueue,
    private val mutationQueueReplayer: MutationQueueReplayer,
    private val automaticReplay: AutomaticReplay,
    private val cacheOwnership: CacheOwnership,
    private val fileCache: StorageFileCache,
    private val localData: LocalData,
    private val databaseScrub: DatabaseScrub,
    private val liveQueries: LiveQueries,
    private val cacheOwnerChanged: suspend (owner: String) -> Unit,
    private val engineLogger: EngineLogger,
) {

    /**
     * As the session settles into an identity, starts a new offline period, and, with `autoSync`
     * on, a pass when that identity has mutations queued, cutting a backoff short; in [scope] so
     * the client's close stops it. Every new status counts, as a sign-out and sign-in it never saw
     * arrive as a new session of the same identity. See [AutomaticReplay].
     */
    fun replayWhenTheSessionSettles() {
        val settledIdentities =
            session.tokenIdentity?.state?.map { state -> state.settled }
                ?: session.authPluginOrNull()?.sessionStatus?.map(::replayIdentityFor)
                ?: return
        scope.launch {
            settledIdentities.collect { settled ->
                val identity = settled ?: return@collect
                // Whether or not a pass is asked for now: an emptied queue keeps no old period
                automaticReplay.allowAnOfflineAttempt()
                if (autoSync) requestPassUnlessQueueEmpty(identity, onEdge = true)
            }
        }
    }

    /**
     * As the network becomes reachable again (the monitor, the app's signal, or
     * [OfflineClient.enableNetwork]), starts a new offline period, and, with `autoSync` on, a pass
     * when the settled identity has mutations queued, cutting a backoff short. An identity still
     * settling is left to the session trigger. See [AutomaticReplay].
     */
    suspend fun replayAsTheNetworkReturns() {
        // Whether or not a pass is asked for now: an emptied queue keeps no old period
        automaticReplay.allowAnOfflineAttempt()
        if (!autoSync) return
        val identity = session.replayIdentityNow() ?: return
        requestPassUnlessQueueEmpty(identity, onEdge = true)
    }

    /**
     * Starts one pass when a client without Auth opens with mutations an earlier run left queued.
     * Its session is settled from the start, so no other trigger fires for them. Not with
     * `autoSync` off.
     */
    fun replayWhatAnEarlierRunLeft() {
        if (!autoSync || session.identityIsPerUser) return
        // Undispatched, it holds the queue lock from open, so it reads before any write of this run
        // is queued. A write it saw would have its pass race the write's own send
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutationQueue.holdingQueueLock {
                requestPassUnlessQueueEmpty(QueueIdentity.Anonymous, onEdge = false)
            }
        }
    }

    /**
     * Asks for a pass when [identity] has mutations queued, or when that cannot be read. The pass
     * reads the queue itself, so a failed read never loses the trigger that asked.
     *
     * @param onEdge whether a reconnection or a settled session asks, which cuts a backoff short.
     */
    suspend fun requestPassUnlessQueueEmpty(identity: QueueIdentity, onEdge: Boolean) {
        if (!mutationQueue.mayHavePendingMutationsOf(identity.storedValue)) return
        if (onEdge) automaticReplay.requestPassOnEdge() else automaticReplay.requestPass()
    }

    /**
     * Keeps the cache the settled identity's, in [scope] so the client's close stops it: when the
     * session settles, or a read finds the cache another identity's, [settleCacheOwner] runs as a
     * call through the gate. A session that restores or whose refresh failed is not settled.
     */
    fun keepCacheWithTheSession() {
        val settledNow: () -> QueueIdentity? =
            session.tokenIdentity?.let { source -> { source.state.value.settled } }
                ?: session.authPluginOrNull()?.let { auth ->
                    { replayIdentityFor(auth.sessionStatus.value) }
                }
                ?: return
        scope.launch {
            merge(session.sessionChanges().map {}, session.cacheOwnerChecks).conflate().collect {
                val identity = settledNow() ?: return@collect
                try {
                    operationGate.call { settleCacheOwner(identity) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (operationGate.isClosed) return@collect
                    engineLogger.error {
                        "Dropping the cache for the new session did not finish " +
                            "(${failure::class.simpleName}); nothing another identity cached is " +
                            "served, and files left behind go at the next clear or drop"
                    }
                }
            }
        }
    }

    /**
     * Makes the cache [identity]'s. A cache no identity owns is adopted by a user, and by nobody
     * signed in while it holds nothing; anything else another identity cached is dropped — the
     * cached queries and the Storage file cache, never the mutation queue — under
     * [clearLocalData]'s locks in its order, and live queries fetch again once the drop has
     * committed, even when a later step fails. A crash or failure after the rows are dropped and
     * before the files are deleted leaves those files on disk with no row: no call reaches them,
     * and the next clear or drop deletes them.
     */
    private suspend fun settleCacheOwner(identity: QueueIdentity) {
        if (
            cacheOwnership.cacheOwnerClaimingFor(identity.storedValue, claimsForUser = true) ==
                identity.storedValue
        )
            return
        // The send lock alone: no pass is stopped, as none of the queue goes
        var dropped = false
        try {
            mutationQueueReplayer.holdingSends {
                fileCache.clear {
                    localData.dropCacheFor(identity.storedValue)
                    dropped = true
                    // Under the send lock still: no pass records a time for the identity before
                    cacheOwnerChanged(identity.storedValue)
                    databaseScrub.rewriteAfterDelete()
                }
            }
        } finally {
            // To a live read a committed drop, finished or cut short after it, is a change of
            // owner: it fetches again. A drop that failed first changed nothing, and a fetch would
            // only bring the reads back here. Asked first, as finishing the rewrite rethrows a
            // cancellation
            if (dropped) {
                liveQueries.liveTables().forEach { table -> liveQueries.requestRefresh(table) }
            }
            // Canceled too: what the rewrite left goes to the background
            databaseScrub.finishSoon()
        }
        engineLogger.info {
            "The session settled into another identity; the cache was emptied for it"
        }
    }

    /**
     * Clears once, locks outermost first: the replayer's send lock, after it cancels passes in
     * flight; the file cache's lock; the queue's and the clear lock, and innermost the cache-owner
     * record's, around [LocalData.clearLocalData]'s one transaction. Signed out, that transaction
     * records the emptied cache as nobody's; a user's first use claims it, so no user id is written
     * back by the clear. The rewrite runs after that transaction, the cache files go last, and what
     * an open read held off is finished outside the locks; see [DatabaseScrub]. The caller holds
     * the client's gate.
     */
    suspend fun clearLocalData() {
        val owner =
            QueueIdentity.Anonymous.storedValue.takeIf {
                session.identityIsPerUser && session.replayIdentityNow() == QueueIdentity.Anonymous
            }
        try {
            mutationQueueReplayer.clearLocalData {
                fileCache.clear {
                    localData.clearLocalData(owner)
                    databaseScrub.rewriteAfterDelete()
                }
            }
        } finally {
            // To a live read the clear, done or cut short, is what a change of owner is: it
            // fetches again. Asked first, as finishing the rewrite rethrows a cancellation
            liveQueries.liveTables().forEach { table -> liveQueries.requestRefresh(table) }
            // Canceled too: what the rewrite left goes to the background
            databaseScrub.finishSoon()
        }
    }
}

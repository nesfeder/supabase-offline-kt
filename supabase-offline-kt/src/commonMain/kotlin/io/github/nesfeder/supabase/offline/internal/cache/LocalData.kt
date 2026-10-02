// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue

/**
 * Clears local data, or drops the caches for a new owner, each in one transaction that records the
 * owner, and makes every component forget what it held of them. The one place the lock order is
 * stated, outermost first:
 * 1. the replayer's send lock, then the Storage file cache's lock (clears and drops only);
 * 2. a table's write lock ([TableWriteLocks]);
 * 3. the queue lock ([MutationQueue]);
 * 4. the clear lock ([CacheFreshness]);
 * 5. the owner record's and the query-shape records' own locks, always last.
 *
 * A clear or a drop takes 3 and 4 and no table's lock: a write holding one checks the watermarks
 * under 4 instead. The eviction lock and the queue's per-table read locks are taken with none of
 * these held. No lock is held across a request.
 */
internal class LocalData(
    private val queryCacheDao: PayloadWriteCountingDao,
    private val cachedFileDao: CachedFileDao,
    private val mutationDao: MutationDao,
    private val freshness: CacheFreshness,
    private val ownership: CacheOwnership,
    private val mutationQueue: MutationQueue,
    private val queryCache: QueryCache,
    private val queryOverlay: QueryOverlay,
) {

    /**
     * Wipes every cached payload, storage-object row and mutation, the mutation queue's records
     * included, in one transaction, and moves the watermarks so that no response or cache patch
     * started before it lands afterwards. For identity changes: sign-out, account switch, deletion.
     *
     * @param owner the identity the emptied cache belongs to, recorded in the same transaction;
     *   `null` records none, and the next identity to use the cache claims it.
     */
    suspend fun clearLocalData(owner: String? = null) {
        // Under the queue lock, so a write whose operation predates this clear cannot slip its
        // mutation row in between the tick and the deletes (see [MutationQueue.enqueueOwnedBy]);
        // and under the clear lock, so no overlay or payload write that checked the tick before it
        // can land after the deletes.
        mutationQueue.holdingQueueLock {
            freshness.holdingClearLock {
                freshness.markEmptied(freshness.tickNow(), queueCleared = true)
                // One transaction: a clear that fails part-way leaves the queue, the caches and
                // the cache's owner as they were, never a mix the next identity could read.
                ownership.record.emptying(owner) {
                    mutationDao.runInTransaction {
                        mutationDao.deleteAll()
                        cachedFileDao.deleteAll()
                        queryCacheDao.deleteAll()
                        if (owner != null) queryCacheDao.upsert(ownership.record.rowOf(owner))
                    }
                    mutationQueue.forgetRecordsOfClearedQueue()
                }
                queryCache.queryShapes.forgetAll()
                queryCache.forgetParsedPayloads()
                // Counted again once committed: a read between the delete and the commit saw the
                // rows before it
                queryCacheDao.countWriteToEveryTable()
                queryOverlay.forgetKeyIndexes()
                mutationQueue.forgetReadsOfClearedQueue()
            }
        }
    }

    /**
     * Empties the cache for [owner], the identity it now belongs to: every cached query with its
     * shape record, and every Storage row, go in one transaction that records [owner]. The mutation
     * queue and its records stay. No response or cache patch started before it lands afterwards;
     * the files of the Storage cache are its caller's to delete.
     */
    suspend fun dropCacheFor(owner: String) {
        // The locks and order of [clearLocalData], which this is but for the mutation queue
        mutationQueue.holdingQueueLock {
            freshness.holdingClearLock {
                freshness.markEmptied(freshness.tickNow(), queueCleared = false)
                ownership.record.emptying(owner) {
                    mutationDao.runInTransaction {
                        cachedFileDao.deleteAll()
                        queryCacheDao.deleteAllExceptTable(RESERVED_TABLE_NAME)
                        queryCacheDao.deleteWithCacheKeyPrefix(
                            RESERVED_TABLE_NAME,
                            QUERY_SHAPE_CACHE_KEY_PREFIX,
                        )
                        queryCacheDao.upsert(ownership.record.rowOf(owner))
                    }
                }
                queryCache.queryShapes.forgetAll()
                queryCache.forgetParsedPayloads()
                // Counted again once committed, as in clearLocalData
                queryCacheDao.countWriteToEveryTable()
            }
        }
    }
}

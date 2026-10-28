// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.PINNED_FILE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.storage.QueuedUploadFiles

/**
 * Clears local data, or drops the caches for a new owner, in one transaction recording the owner.
 * Locks, outermost first: the replayer's send lock, the file cache's (never with a table lock), a
 * table's snapshot lock ([QueryOverlay.holdingReadersOf]) and write lock ([TableWriteLocks]), the
 * queue's ([MutationQueue]), the clear lock ([CacheFreshness]), then the records' own. A clear
 * takes no table lock: a write holding one checks the watermarks under the clear lock. Eviction's
 * lock goes alone; the queue's per-table read locks and the identity reader's lock are leaves.
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
    /**
     * The queued uploads' files, which [clearLocalData] deletes with their rows; `null` for none.
     */
    private val queuedUploadFiles: QueuedUploadFiles? = null,
) {

    /**
     * Wipes every cached payload, storage-object row and mutation, the mutation queue's records
     * included, in one transaction, and moves the watermarks so that no response or cache patch
     * started before it lands afterward. For identity changes: sign-out, account switch, deletion.
     * Once it has committed, the queued uploads' files go too, under the same locks, so an upload
     * queued after it keeps its file; one being written for an upload on its way into the queue is
     * spared, and its upload is refused by the clear's tick.
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
                queryCache.payloadsEmptied()
                // Counted again once committed: a read between the delete and the commit saw the
                // rows before it
                queryCacheDao.countWriteToEveryTable()
                queryOverlay.forgetKeyIndexes()
                mutationQueue.forgetReadsOfClearedQueue()
                queuedUploadFiles?.deleteAllExcept(emptySet())
            }
        }
    }

    /**
     * Empties the cache for [owner], the identity it now belongs to: every cached query with its
     * shape record, and every Storage row and pin, go in one transaction that records [owner]. The
     * mutation queue and its records stay. No response or cache patch started before it lands
     * afterward; the files of the Storage cache are its caller's to delete.
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
                        queryCacheDao.deleteWithCacheKeyPrefix(
                            RESERVED_TABLE_NAME,
                            PINNED_FILE_CACHE_KEY_PREFIX,
                        )
                        queryCacheDao.upsert(ownership.record.rowOf(owner))
                    }
                }
                queryCache.queryShapes.forgetAll()
                queryCache.payloadsEmptied()
                // Counted again once committed, as in clearLocalData
                queryCacheDao.countWriteToEveryTable()
            }
        }
    }
}

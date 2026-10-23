// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.CachedRowLookup
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.NoVersionColumns
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.VersionColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow

/**
 * The cache and mutation-queue components over one database's tables, as [buildCacheComponents]
 * wires them. A holder only: its owner copies the parts into its own fields, and nothing calls
 * through it.
 *
 * @property queryCacheDao the cached queries' table, every write to it counted; every component
 *   writes through this one.
 * @property freshness the watermarks and the clear lock.
 * @property cacheOwnership whose the caches are.
 * @property mutationQueue the mutation queue.
 * @property queryCache the cached queries.
 * @property queryOverlay the reads served with the queued mutations laid over them.
 * @property cachePatcher patches an online write into the cached queries.
 * @property localData clears local data, or drops the caches for a new owner, in the one lock
 *   order.
 */
internal class CacheComponents(
    val queryCacheDao: PayloadWriteCountingDao,
    val freshness: CacheFreshness,
    val cacheOwnership: CacheOwnership,
    val mutationQueue: MutationQueue,
    val queryCache: QueryCache,
    val queryOverlay: QueryOverlay,
    val cachePatcher: CachePatcher,
    val localData: LocalData,
)

/**
 * Builds the cache and mutation-queue components bottom up over one database's tables, for the
 * client and for the engine's tests alike, so both run the one graph. [LocalData] states their lock
 * order.
 *
 * @param queryCacheDao the cached queries' table; the components share one counting view of it.
 * @param cachedFileDao the Storage file cache's table.
 * @param mutationDao the mutation queue's table.
 * @param queryCacheLimits how large the query cache may grow.
 * @param nowMillis the clock rows are stamped with.
 * @param monotonicTick the tick the watermarks are taken from.
 * @param primaryKeys each table's key columns.
 * @param identityProvider the identity whose queued writes the overlay and counts show, or `null`
 *   for every identity's.
 * @param identityChanges emits when the identity may have changed.
 * @param isCacheOwnedPerIdentity whether the caches belong to one identity at a time (with the Auth
 *   plugin) or for good (without it).
 * @param ownerlessMutationsAreAnonymous whether a queued write with no recorded owner is the
 *   anonymous identity's.
 * @param engineLogger where the components log.
 * @param liveCacheKeysOf the cache keys a live read of a table holds.
 * @param allLiveCacheKeys the cache keys every live read holds, which eviction keeps.
 * @param onQueriesMarkedStale told the queries a write marked stale, so live ones fetch again.
 * @param onCacheNotServed told that a read or write found the caches another identity's.
 * @param versionColumns each table's version column, which guards a queued write's replay.
 * @param backgroundScope where the cache's eviction pass runs, after the response that asked for it
 *   is written: the client's own scope, which closing the client cancels.
 * @param beforePayloadWriteHeard runs before a live reader hears of a payload write; a test delays
 *   it so a reader hears of the write after other changes. `null` for none.
 * @return the components, wired.
 */
internal fun buildCacheComponents(
    queryCacheDao: QueryCacheDao,
    cachedFileDao: CachedFileDao,
    mutationDao: MutationDao,
    queryCacheLimits: QueryCacheLimits,
    nowMillis: () -> Long,
    monotonicTick: () -> Long,
    primaryKeys: PrimaryKeys,
    identityProvider: suspend () -> String?,
    identityChanges: Flow<Any?>,
    isCacheOwnedPerIdentity: Boolean,
    ownerlessMutationsAreAnonymous: Boolean,
    engineLogger: EngineLogger,
    liveCacheKeysOf: (table: String) -> Set<String>,
    allLiveCacheKeys: () -> Set<String>,
    onQueriesMarkedStale: (table: String, cacheKeys: Set<String>) -> Unit,
    onCacheNotServed: () -> Unit,
    versionColumns: VersionColumns = NoVersionColumns,
    backgroundScope: CoroutineScope,
    beforePayloadWriteHeard: (suspend (table: String) -> Unit)? = null,
): CacheComponents {
    val countingQueryCacheDao = PayloadWriteCountingDao(queryCacheDao, beforePayloadWriteHeard)
    val freshness = CacheFreshness(monotonicTick)
    val cacheOwnership =
        CacheOwnership(
            queryCacheDao = countingQueryCacheDao,
            cachedFileDao = cachedFileDao,
            json = EngineJson,
            isCacheOwnedPerIdentity = isCacheOwnedPerIdentity,
            identityProvider = identityProvider,
            onCacheNotServed = onCacheNotServed,
        )
    // The per-table write locks the query cache, the patch and retirement share
    val tableWriteLocks = TableWriteLocks()
    val mutationQueue =
        MutationQueue(
            mutationDao = mutationDao,
            queryCacheDao = countingQueryCacheDao,
            json = EngineJson,
            nowMillis = nowMillis,
            freshness = freshness,
            tableWriteLocks = tableWriteLocks,
            primaryKeys = primaryKeys,
            identityProvider = identityProvider,
            identityChanges = identityChanges,
            ownerlessMutationsAreAnonymous = ownerlessMutationsAreAnonymous,
            liveCacheKeysOf = liveCacheKeysOf,
            engineLogger = engineLogger,
            versionColumns = versionColumns,
        )
    val queryCache =
        QueryCache(
            queryCacheDao = countingQueryCacheDao,
            queryCacheLimits = queryCacheLimits,
            json = EngineJson,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            mutationQueue = mutationQueue,
            tableWriteLocks = tableWriteLocks,
            allLiveCacheKeys = allLiveCacheKeys,
            backgroundScope = backgroundScope,
        )
    val queryOverlay =
        QueryOverlay(
            queryCacheDao = countingQueryCacheDao,
            json = EngineJson,
            queryCache = queryCache,
            freshness = freshness,
            mutationQueue = mutationQueue,
            ownership = cacheOwnership,
            primaryKeys = primaryKeys,
            identityProvider = identityProvider,
            identityChanges = identityChanges,
            engineLogger = engineLogger,
        )
    mutationQueue.readBasesThrough(CachedRowLookup(queryOverlay::cachedRowHolding))
    val cachePatcher =
        CachePatcher(
            queryCacheDao = countingQueryCacheDao,
            json = EngineJson,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
            primaryKeys = primaryKeys,
            liveCacheKeysOf = liveCacheKeysOf,
            onQueriesMarkedStale = onQueriesMarkedStale,
        )
    val localData =
        LocalData(
            queryCacheDao = countingQueryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            freshness = freshness,
            ownership = cacheOwnership,
            mutationQueue = mutationQueue,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
        )
    return CacheComponents(
        queryCacheDao = countingQueryCacheDao,
        freshness = freshness,
        cacheOwnership = cacheOwnership,
        mutationQueue = mutationQueue,
        queryCache = queryCache,
        queryOverlay = queryOverlay,
        cachePatcher = cachePatcher,
        localData = localData,
    )
}

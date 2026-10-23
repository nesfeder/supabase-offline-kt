// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.cache.CacheComponents
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CacheOwnership
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.LocalData
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.cache.QueryOverlay
import io.github.nesfeder.supabase.offline.internal.cache.buildCacheComponents
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.VersionColumns
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.storage.cachedFileKeyOf
import io.github.nesfeder.supabase.offline.internal.storage.cachedFileRowOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okio.Path

/**
 * The cache and mutation-queue components over one set of tables, wired by [buildCacheComponents]
 * as [OfflineClient]'s own are; built by [newTestStore]. Its members after the components are test
 * helpers, built on what production uses.
 *
 * @param identityProvider the identity whose queued writes the overlay and counts show, or `null`
 *   for every identity's.
 * @param identityChanges emits when the identity may have changed.
 * @param versionColumns each table's version column, which guards a queued write's replay.
 * @param backgroundScope where the cache's eviction pass runs after a response is written.
 */
internal class TestStore(
    queryCacheDao: QueryCacheDao,
    /** The Storage file cache's table. */
    val cachedFileDao: CachedFileDao,
    /** The mutation queue's table. */
    val mutationDao: MutationDao,
    queryCacheLimits: QueryCacheLimits,
    /** The clock rows are stamped with; a cached file's fetch time too. */
    val nowMillis: () -> Long,
    monotonicTick: () -> Long,
    /** Each table's key columns, as the components were given them. */
    val primaryKeys: PrimaryKeys,
    private val identityProvider: suspend () -> String?,
    private val identityChanges: Flow<Any?>,
    isCacheOwnedPerIdentity: Boolean,
    ownerlessMutationsAreAnonymous: Boolean,
    engineLogger: EngineLogger,
    liveCacheKeysOf: (table: String) -> Set<String>,
    allLiveCacheKeys: () -> Set<String>,
    onQueriesMarkedStale: (table: String, cacheKeys: Set<String>) -> Unit,
    onCacheNotServed: () -> Unit,
    versionColumns: VersionColumns,
    backgroundScope: CoroutineScope,
) {

    /** The components, which this store copies into its fields. */
    private val components: CacheComponents =
        buildCacheComponents(
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            queryCacheLimits = queryCacheLimits,
            nowMillis = nowMillis,
            monotonicTick = monotonicTick,
            primaryKeys = primaryKeys,
            identityProvider = identityProvider,
            identityChanges = identityChanges,
            isCacheOwnedPerIdentity = isCacheOwnedPerIdentity,
            ownerlessMutationsAreAnonymous = ownerlessMutationsAreAnonymous,
            engineLogger = engineLogger,
            liveCacheKeysOf = liveCacheKeysOf,
            allLiveCacheKeys = allLiveCacheKeys,
            onQueriesMarkedStale = onQueriesMarkedStale,
            onCacheNotServed = onCacheNotServed,
            versionColumns = versionColumns,
            backgroundScope = backgroundScope,
        )

    /** The cached queries' table, every write to it counted, as the components write it. */
    val queryCacheDao: PayloadWriteCountingDao = components.queryCacheDao

    /** The watermarks and the clear lock. */
    val freshness: CacheFreshness = components.freshness

    /** Whose the caches are. */
    val cacheOwnership: CacheOwnership = components.cacheOwnership

    /** The mutation queue. */
    val mutationQueue: MutationQueue = components.mutationQueue

    /** The cached queries. */
    val queryCache: QueryCache = components.queryCache

    /** The reads served with the queued mutations laid over them. */
    val queryOverlay: QueryOverlay = components.queryOverlay

    /** Patches an online write into the cached queries. */
    val cachePatcher: CachePatcher = components.cachePatcher

    /** Clears local data, or drops the caches for a new owner. */
    val localData: LocalData = components.localData

    /* ──────────── Test helpers that read this store's tables and wiring ──────────── */

    /** Adds [entry] as the newest rejection and writes the list, as a refusal does. */
    suspend fun addRejection(entry: StoredRejection) {
        val (row, list) = mutationQueue.rejectedList.rejectedRowWith(add = entry)
        queryCacheDao.upsert(row)
        mutationQueue.rejectedList.rejectedWritten(list)
    }

    /**
     * The pending mutations of [table], or of every table, that [identityProvider] may see, in
     * insertion order, as they change; every identity's while it gives none.
     */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        combine(mutationDao.pendingAllFlow(), identityChanges) { rows, _ ->
                mutationQueue.visibleTo(identityProvider(), rows).filter { row ->
                    table == null || row.table == table
                }
            }
            .distinctUntilChanged()

    /** How many pending mutations of every table [pendingMutationsFlow] shows. */
    suspend fun pendingMutationCount(): Int =
        mutationQueue.visibleTo(identityProvider(), mutationDao.pendingAll()).size

    /** [pendingMutationCount], as it changes. */
    fun pendingMutationCountFlow(): Flow<Int> = pendingMutationsFlow(null).map { rows -> rows.size }

    /** How many pending mutations of [table] [pendingMutationsFlow] shows. */
    suspend fun pendingMutationCountForTable(table: String): Int =
        mutationQueue.visibleTo(identityProvider(), mutationDao.pendingForTable(table)).size

    /** [pendingMutationCountForTable], as it changes. */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        pendingMutationsFlow(table).map { rows -> rows.size }

    /** The payload cached for [table]/[cacheKey] as it changes, whoever's the cache is. */
    fun observePayload(table: String, cacheKey: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return queryCacheDao.observe(table, cacheKey).map { row -> row?.payload }
    }

    /** Deletes the payload cached for [table]/[cacheKey], with its shape record. */
    suspend fun deleteTablePayload(table: String, cacheKey: String) {
        requireUserTable(table)
        queryCache.tableWriteLock(table).withLock {
            queryCacheDao.delete(table, cacheKey)
            queryCache.queryShapes.forget(table, cacheKey)
        }
    }

    /** Drops every cached payload of [table] and advances its watermark, as an emptied table. */
    suspend fun clearTable(table: String) {
        requireUserTable(table)
        queryCache.tableWriteLock(table).withLock {
            freshness.advanceLocalWriteWatermark(table)
            queryCacheDao.deleteTable(table)
            queryCache.queryShapes.forgetTable(table)
        }
    }

    /** Records [file] as the cached copy of [path] in [bucketId], fetched now. */
    suspend fun addStorage(bucketId: String, path: String, file: Path, size: Long?, eTag: String?) {
        cachedFileDao.upsert(cachedFileRowOf(bucketId, path, file, size, eTag, nowMillis()))
    }

    /** The cached file row of [path] in [bucketId], or `null`. */
    suspend fun getStorageByKey(bucketId: String, path: String): CachedFileRow? =
        cachedFileDao.getByKey(cachedFileKeyOf(bucketId, path))

    /** Deletes the cached file row of [path] in [bucketId]. */
    suspend fun deleteStorageByKey(bucketId: String, path: String) {
        cachedFileDao.delete(cachedFileKeyOf(bucketId, path))
    }
}

/* ──────────── Test helpers on a component, for a test store's or a client's ──────────── */

/** Queues an INSERT with no recorded owner, outside the queue lock, as a writer does. */
internal suspend fun MutationQueue.enqueueInsert(
    table: String,
    body: JsonArray,
    filter: FilterDescriptor? = null,
    defaultToNull: Boolean = true,
): Long = MutationWriter(owner = null).enqueueInsert(table, body, filter, defaultToNull).id

/** Queues an UPSERT with no recorded owner; see [enqueueInsert]. */
internal suspend fun MutationQueue.enqueueUpsert(
    table: String,
    bodyJson: JsonArray,
    filter: FilterDescriptor? = null,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    defaultToNull: Boolean = true,
): Long =
    MutationWriter(owner = null)
        .enqueueUpsert(table, bodyJson, filter, onConflict, ignoreDuplicates, defaultToNull)
        .id

/**
 * Queues an UPDATE with no recorded owner, with its base as a writer reads it; see [enqueueInsert].
 */
internal suspend fun MutationQueue.enqueueUpdate(
    table: String,
    body: JsonElement,
    filter: FilterDescriptor? = null,
): Long {
    val base = baseFor(table, filter, (body as? JsonObject)?.keys, identity = null)
    return MutationWriter(owner = null).enqueueUpdate(table, body, filter, base).id
}

/**
 * Queues a DELETE with no recorded owner, with its base as a writer reads it; see [enqueueInsert].
 */
internal suspend fun MutationQueue.enqueueDelete(
    table: String,
    filter: FilterDescriptor? = null,
): Long {
    val base = baseFor(table, filter, emptySet(), identity = null)
    return MutationWriter(owner = null).enqueueDelete(table, filter, base).id
}

/** Removes the rejected mutation [id] under the queue lock, whoever's it is. */
internal suspend fun MutationQueue.dismissRejectedMutation(id: Long): Boolean = holdingQueueLock {
    rejectedList.removeRejection(id) != null
}

/** The payload cached for [table]/[cacheKey], when the cache is the current identity's. */
internal suspend fun QueryCache.getPayload(table: String, cacheKey: String): String? =
    getPayloadWithTimestamp(table, cacheKey)?.first

/** [QueryOverlay.overlayPendingOnResponseReporting]'s payload. */
internal suspend fun QueryOverlay.overlayPendingOnResponse(
    table: String,
    cacheKey: String,
    serverJson: String,
    shape: QueryShape? = null,
): String = overlayPendingOnResponseReporting(table, cacheKey, serverJson, shape).payload

/** [QueryOverlay.overlayCachedPayloadReporting]'s payload. */
internal suspend fun QueryOverlay.overlayCachedPayload(
    table: String,
    cacheKey: String?,
    cachedJson: String,
    shape: QueryShape? = null,
): String = overlayCachedPayloadReporting(table, cacheKey, cachedJson, shape).payload

/** [overlayCachedPayload] for a payload whose query is not known. */
internal suspend fun QueryOverlay.overlayCachedPayload(table: String, cachedJson: String): String =
    overlayCachedPayload(table, cacheKey = null, cachedJson = cachedJson)

/** [QueryOverlay.servedPayloadFlow]'s payloads, each emitted when it changes. */
internal fun QueryOverlay.overlaidPayloadFlow(
    table: String,
    cacheKey: String,
    shape: QueryShape? = null,
): Flow<String?> =
    servedPayloadFlow(table, cacheKey, shape, MutableStateFlow(Unit))
        .map { (served, _) -> served?.payload }
        .distinctUntilChanged()

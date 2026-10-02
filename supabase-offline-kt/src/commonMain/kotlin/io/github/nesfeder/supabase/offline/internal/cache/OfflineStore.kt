// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutationList
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okio.Path

/**
 * Central persistence for offline features:
 *
 * - PostgREST object cache (SELECT payloads) via [QueryCacheDao]
 * - Storage metadata (downloaded files) via [CachedFileDao]
 * - Mutation queue (insert/upsert/update/delete) via [MutationDao]
 */
internal class OfflineStore(
    queryCacheDao: QueryCacheDao,
    private val cachedFileDao: CachedFileDao,
    private val mutationDao: MutationDao,
    private val queryCacheLimits: QueryCacheLimits,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * The single tick source behind the stale-refresh watermark — read both when a local write
     * advances a table's watermark and when a network SELECT records the moment its request went
     * out. Both stamps **must** come from here so they are comparable; see [monotonicTickSource]
     * for why this is monotonic rather than wall-clock. Injectable so tests can drive interleavings
     * deterministically.
     */
    private val monotonicTick: () -> Long = monotonicTickSource(),
) {

    internal val json: Json = EngineJson

    /** The watermarks and the clear lock; see [CacheFreshness]. */
    private val freshness = CacheFreshness(monotonicTick)

    /** The object cache, every write to it counted; see [tableKeyIndexOf]. */
    private val queryCacheDao = PayloadWriteCountingDao(queryCacheDao)

    /** Whose the caches are, and whether the current identity may use them. */
    private val cacheOwnership =
        CacheOwnership(
            queryCacheDao = this.queryCacheDao,
            cachedFileDao = cachedFileDao,
            json = json,
            isCacheOwnedPerIdentity = { isCacheOwnedPerIdentity },
            identityProvider = { identityProvider() },
            onCacheNotServed = { onCacheNotServed() },
        )

    /**
     * The identity the query cache and the Storage file cache belong to; see [CacheOwnerRecord].
     */
    internal val cacheOwner: CacheOwnerRecord
        get() = cacheOwnership.record

    /**
     * Whether the cache belongs to one identity at a time, checked on every read and write: `true`
     * with the Auth plugin. Without it there is one identity, which owns the cache for good. Wired
     * by the client.
     */
    internal var isCacheOwnedPerIdentity: Boolean = false

    /**
     * Told when the cache was not served to, or written for, the current identity. Wired by the
     * client.
     */
    internal var onCacheNotServed: () -> Unit = {}

    /**
     * Told of the cached queries of a table a write marked stale, and of live ones it may have
     * changed without a payload, so their live readers fetch again. Wired by the client.
     */
    internal var onQueriesMarkedStale: (table: String, cacheKeys: Set<String>) -> Unit = { _, _ -> }

    /**
     * Logger for conditions the store recovers from on its own (an overlay that skips a mutation it
     * cannot apply). Wired by the client, like [liveCacheKeysOf]; silent when built directly.
     */
    internal var engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO)

    /**
     * The identity whose queued writes the overlay shows and the counts count — its stored value —
     * or `null` for no filtering (a store driven directly, with no session model). Wired by the
     * client.
     */
    internal var identityProvider: suspend () -> String? = { null }

    /**
     * Emits when the session may have changed, so overlays and counts re-apply [identityProvider].
     */
    internal var identityChanges: Flow<Any?> = flowOf(Unit)

    /** Where the store learns each table's primary key, for de-duplicating overlaid rows. */
    internal var primaryKeys: PrimaryKeys = DefaultPrimaryKeys

    /**
     * Whether mutations an earlier build queued without an owner belong to the anonymous identity:
     * `true` when the client has no Auth plugin, so there is no other identity. Wired by the
     * client.
     */
    internal var ownerlessMutationsAreAnonymous: Boolean = true

    private val tableWriteLocks = TableWriteLocks()

    /** The mutation queue; see [MutationQueue]. */
    private val mutationQueue =
        MutationQueue(
            mutationDao = mutationDao,
            queryCacheDao = this.queryCacheDao,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            tableWriteLocks = tableWriteLocks,
            primaryKeys = PrimaryKeys { table -> primaryKeys.of(table) },
            identityProvider = { identityProvider() },
            identityChanges = flow { emitAll(identityChanges) },
            ownerlessMutationsAreAnonymous = { ownerlessMutationsAreAnonymous },
            liveCacheKeysOf = { table -> liveCacheKeysOf(table) },
            engineLogger = { engineLogger },
        )

    /** The mutation queue's record kept beside the rows: the rejected list. */
    internal val rejectedList: RejectedMutationList
        get() = mutationQueue.rejectedList

    /** The cached queries' rows; see [QueryCache]. */
    private val queryCache =
        QueryCache(
            queryCacheDao = this.queryCacheDao,
            queryCacheLimits = queryCacheLimits,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            mutationQueue = mutationQueue,
            tableWriteLocks = tableWriteLocks,
            allLiveCacheKeys = { allLiveCacheKeys() },
        )

    /** Each cached query's shape, which decides the rows it admits; see [QueryShapeRecords]. */
    internal val queryShapes: QueryShapeRecords
        get() = queryCache.queryShapes

    /** The overlaid reads; see [QueryOverlay]. */
    private val queryOverlay =
        QueryOverlay(
            queryCacheDao = this.queryCacheDao,
            json = json,
            queryCache = queryCache,
            mutationQueue = mutationQueue,
            ownership = cacheOwnership,
            primaryKeys = PrimaryKeys { table -> primaryKeys.of(table) },
            identityProvider = { identityProvider() },
            identityChanges = flow { emitAll(identityChanges) },
            engineLogger = { engineLogger },
        )

    /** The online-write patch of cached queries; see [CachePatcher]. */
    private val cachePatcher =
        CachePatcher(
            queryCacheDao = this.queryCacheDao,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
            primaryKeys = PrimaryKeys { table -> primaryKeys.of(table) },
            liveCacheKeysOf = { table -> liveCacheKeysOf(table) },
            onQueriesMarkedStale = { table, cacheKeys -> onQueriesMarkedStale(table, cacheKeys) },
        )

    /** Clearing and dropping local data, in the one lock order; see [LocalData]. */
    private val localData =
        LocalData(
            queryCacheDao = this.queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            freshness = freshness,
            ownership = cacheOwnership,
            mutationQueue = mutationQueue,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
        )

    /** The write lock for [table]; see [TableWriteLocks]. */
    private suspend fun tableWriteLock(table: String): Mutex = tableWriteLocks.of(table)

    /** Reads the tick source; see [CacheFreshness.tickNow]. */
    internal fun monotonicTickNow(): Long = freshness.tickNow()

    /** See [CacheFreshness.wasClearedSince]. */
    internal fun wasClearedSince(tick: Long): Boolean = freshness.wasClearedSince(tick)

    /** See [CacheOwnership.cacheServesCurrentIdentity]. */
    internal suspend fun cacheServesCurrentIdentity(): Boolean =
        cacheOwnership.cacheServesCurrentIdentity()

    /** See [CacheOwnership.cacheTakesWritesOfCurrentIdentity]. */
    internal suspend fun cacheTakesWritesOfCurrentIdentity(): Boolean =
        cacheOwnership.cacheTakesWritesOfCurrentIdentity()

    /** See [CacheOwnership.cacheOwnerClaimingFor]. */
    internal suspend fun cacheOwnerClaimingFor(identity: String, claimsForUser: Boolean): String? =
        cacheOwnership.cacheOwnerClaimingFor(identity, claimsForUser)

    /**
     * Signatures of a table that currently have a live `selectAsFlow` collector. Wired by
     * [io.github.nesfeder.supabase.offline.OfflineClient] to the client-scoped
     * [io.github.nesfeder.supabase.offline.internal.postgrest.LiveQueries] so [patchCachedQueries]
     * can materialize a server-successful mutation into a real cache row for a cache-less-but-
     * observed signature (the "save then stale UI" fix), and self-GC when nothing observes the
     * table. Defaults to reporting no live signatures so direct construction in tests keeps the
     * conservative "only patch existing rows" behavior unless a set is passed explicitly.
     */
    internal var liveCacheKeysOf: (table: String) -> Set<String> = { _ -> emptySet() }

    /**
     * Signatures of every table with a live reader, which eviction keeps; wired like
     * [liveCacheKeysOf].
     */
    internal var allLiveCacheKeys: () -> Set<String> = { emptySet() }

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    /** See [QueryCache.addPayload]. */
    suspend fun addPayload(
        table: String,
        cacheKey: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
    ): CacheWriteOutcome =
        queryCache.addPayload(
            table = table,
            cacheKey = cacheKey,
            payload = payload,
            authoritativeEmpty = authoritativeEmpty,
            requestStartedAtTick = requestStartedAtTick,
            isTrusted = isTrusted,
            shape = shape,
        )

    /** See [QueryCache.getPayload]. */
    suspend fun getPayload(table: String, cacheKey: String): String? =
        queryCache.getPayload(table, cacheKey)

    /** See [QueryCache.getPayloadWithTimestamp]. */
    suspend fun getPayloadWithTimestamp(table: String, cacheKey: String): Pair<String, Long>? =
        queryCache.getPayloadWithTimestamp(table, cacheKey)

    /** See [QueryCache.observePayload]. */
    fun observePayload(table: String, cacheKey: String): Flow<String?> =
        queryCache.observePayload(table, cacheKey)

    /** See [QueryCache.deleteTablePayload]. */
    suspend fun deleteTablePayload(table: String, cacheKey: String) {
        queryCache.deleteTablePayload(table, cacheKey)
    }

    /** See [QueryCache.clearTable]. */
    suspend fun clearTable(table: String) {
        queryCache.clearTable(table)
    }

    /** See [QueryCache.evictOverBudget]. */
    internal suspend fun evictOverBudget() {
        queryCache.evictOverBudget()
    }

    /** See [LocalData.clearLocalData]. */
    suspend fun clearLocalData(owner: String? = null) {
        localData.clearLocalData(owner)
    }

    /** See [LocalData.dropCacheFor]. */
    suspend fun dropCacheFor(owner: String) {
        localData.dropCacheFor(owner)
    }

    /* ───────────────────────────── Storage metadata ───────────────────────────── */

    suspend fun addStorage(
        bucketId: String,
        path: String,
        file: Path,
        size: Long?,
        eTag: String?,
    ) {
        val row =
            CachedFileRow(
                key = storageKey(bucketId, path),
                bucketId = bucketId,
                path = path,
                localPath = file.toString(),
                sizeBytes = size,
                eTag = eTag,
                lastFetchedEpochMs = nowMillis(),
            )
        cachedFileDao.upsert(row)
    }

    suspend fun getStorageByKey(
        bucketId: String,
        path: String,
    ): CachedFileRow? {
        return cachedFileDao.getByKey(storageKey(bucketId, path))
    }

    suspend fun deleteStorageByKey(
        bucketId: String,
        path: String,
    ) {
        cachedFileDao.delete(storageKey(bucketId, path))
    }

    private fun storageKey(bucketId: String, path: String) = "$bucketId::$path"

    /**
     * The storage rows of one object: its own and those of its image-transform variants.
     *
     * @param bucketId the bucket.
     * @param path the object path.
     * @param variantPrefix [path] followed by the variant separator.
     * @return the rows.
     */
    suspend fun cachedFileRowsForObject(
        bucketId: String,
        path: String,
        variantPrefix: String,
    ): List<CachedFileRow> = cachedFileDao.forObject(bucketId, path, variantPrefix)

    /** Every storage row. */
    suspend fun allCachedFileRows(): List<CachedFileRow> = cachedFileDao.all()

    /** The summed recorded size of every cached file, in bytes. */
    suspend fun totalStorageSizeBytes(): Long = cachedFileDao.totalSizeBytes()

    /**
     * Writes [row] back as it was, replacing whatever row has its key.
     *
     * @param row the row to restore.
     */
    suspend fun restoreCachedFileRow(row: CachedFileRow) {
        cachedFileDao.upsert(row)
    }

    /**
     * Deletes the storage row with [key].
     *
     * @param key the row's key, `"$bucket::$path"`.
     */
    suspend fun deleteCachedFileRow(key: String) {
        cachedFileDao.delete(key)
    }

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    /** See [MutationQueue.enqueueInsert]. */
    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true,
    ): Long = mutationQueue.enqueueInsert(table, body, filter, defaultToNull)

    /** See [MutationQueue.enqueueUpsert]. */
    suspend fun enqueueUpsert(
        table: String,
        bodyJson: JsonArray,
        filter: FilterDescriptor? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean = false,
        defaultToNull: Boolean = true,
    ): Long =
        mutationQueue.enqueueUpsert(
            table,
            bodyJson,
            filter,
            onConflict,
            ignoreDuplicates,
            defaultToNull,
        )

    /** See [MutationQueue.enqueueUpdate]. */
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null,
    ): Long = mutationQueue.enqueueUpdate(table, body, filter)

    /** See [MutationQueue.enqueueDelete]. */
    suspend fun enqueueDelete(table: String, filter: FilterDescriptor? = null): Long =
        mutationQueue.enqueueDelete(table, filter)

    /** See [MutationQueue.enqueueOwnedBy]. */
    suspend fun enqueueOwnedBy(
        identity: String?,
        startedAtTick: Long? = null,
        enqueue: suspend MutationQueue.MutationWriter.() -> Long,
    ): EnqueueOutcome = mutationQueue.enqueueOwnedBy(identity, startedAtTick, enqueue)

    /** See [MutationQueue.pendingMutationsOf]. */
    suspend fun pendingMutationsOf(identity: String, table: String? = null): List<MutationRow> =
        mutationQueue.pendingMutationsOf(identity, table)

    /** See [MutationQueue.hasPendingMutationsOf]. */
    suspend fun hasPendingMutationsOf(identity: String): Boolean =
        mutationQueue.hasPendingMutationsOf(identity)

    /** See [MutationQueue.adoptOwnerlessMutations]. */
    suspend fun adoptOwnerlessMutations(identity: String) {
        mutationQueue.adoptOwnerlessMutations(identity)
    }

    /** See [MutationQueue.isAtHead]. */
    suspend fun isAtHead(id: Long, identity: String): Boolean = mutationQueue.isAtHead(id, identity)

    /** See [MutationQueue.hasEarlierPendingMutation]. */
    suspend fun hasEarlierPendingMutation(id: Long, identity: String): Boolean =
        mutationQueue.hasEarlierPendingMutation(id, identity)

    /** See [MutationQueue.isPending]. */
    suspend fun isPending(id: Long): Boolean = mutationQueue.isPending(id)

    /** See [MutationQueue.completeSentMutation]. */
    suspend fun completeSentMutation(id: Long) {
        mutationQueue.completeSentMutation(id)
    }

    /** See [MutationQueue.recordSchemaCacheMiss]. */
    suspend fun recordSchemaCacheMiss(id: Long): Int = mutationQueue.recordSchemaCacheMiss(id)

    /** See [MutationQueue.abandonSentMutation]. */
    suspend fun abandonSentMutation(id: Long) {
        mutationQueue.abandonSentMutation(id)
    }

    /** See [MutationQueue.discardPendingMutation]. */
    suspend fun discardPendingMutation(id: Long, identity: String?): Boolean =
        mutationQueue.discardPendingMutation(id, identity)

    /** See [MutationQueue.rejectMutation]. */
    suspend fun rejectMutation(
        mutation: MutationRow,
        message: String,
        serverError: StoredServerError?,
    ): StoredRejection = mutationQueue.rejectMutation(mutation, message, serverError)

    /** See [MutationQueue.retryRejectedMutation]. */
    suspend fun retryRejectedMutation(id: Long, identity: String?): Long? =
        mutationQueue.retryRejectedMutation(id, identity)

    /** See [MutationQueue.dismissRejectedMutation]. */
    suspend fun dismissRejectedMutation(id: Long): Boolean =
        mutationQueue.dismissRejectedMutation(id)

    /** See [MutationQueue.pendingMutationsForTable]. */
    // test-only: moves in C3b
    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationQueue.pendingMutationsForTable(table)

    /** See [MutationQueue.allPendingMutations]. */
    suspend fun allPendingMutations(): List<MutationRow> = mutationQueue.allPendingMutations()

    /** See [MutationQueue.pendingMutationsFlow]. */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        mutationQueue.pendingMutationsFlow(table)

    /** See [MutationQueue.pendingMutationCount]. */
    suspend fun pendingMutationCount(): Int = mutationQueue.pendingMutationCount()

    /** See [MutationQueue.pendingMutationCountFlow]. */
    fun pendingMutationCountFlow(): Flow<Int> = mutationQueue.pendingMutationCountFlow()

    /** See [MutationQueue.pendingMutationCountForTable]. */
    suspend fun pendingMutationCountForTable(table: String): Int =
        mutationQueue.pendingMutationCountForTable(table)

    /** See [MutationQueue.pendingMutationCountForTableFlow]. */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        mutationQueue.pendingMutationCountForTableFlow(table)

    /** See [MutationQueue.shownPendingMutationsFlow]. */
    fun shownPendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        mutationQueue.shownPendingMutationsFlow(table)

    /** See [MutationQueue.shownPendingMutationCount]. */
    suspend fun shownPendingMutationCount(table: String?): Int =
        mutationQueue.shownPendingMutationCount(table)

    /** See [MutationQueue.shownQueueCounts]. */
    suspend fun shownQueueCounts(): ShownQueueCounts = mutationQueue.shownQueueCounts()

    /** See [MutationQueue.queueChanges]. */
    fun queueChanges(): Flow<Unit> = mutationQueue.queueChanges()

    /** See [MutationQueue.shownRejectionsFlow]. */
    fun shownRejectionsFlow(): Flow<List<StoredRejection>> = mutationQueue.shownRejectionsFlow()

    /** See [MutationQueue.shownRejection]. */
    suspend fun shownRejection(id: Long, identity: String): StoredRejection? =
        mutationQueue.shownRejection(id, identity)

    /** See [MutationQueue.dismissRejectedMutationOf]. */
    suspend fun dismissRejectedMutationOf(id: Long, identity: String): Boolean =
        mutationQueue.dismissRejectedMutationOf(id, identity)

    /** Deletes the mutation row [id] and what the records hold for it. */
    // test-only: moves in C3b
    suspend fun deleteMutation(id: Long) {
        mutationQueue.abandonSentMutation(id)
    }

    /** See [MutationQueue.claimUnclassifiableWriteReport]. */
    internal fun claimUnclassifiableWriteReport(): Boolean =
        mutationQueue.claimUnclassifiableWriteReport()

    /** See [MutationQueue.markMutationSynced]. */
    suspend fun markMutationSynced(id: Long, table: String) {
        mutationQueue.markMutationSynced(id, table)
    }

    /** See [MutationQueue.markSendUnconfirmed]. */
    suspend fun markSendUnconfirmed(id: Long): Boolean = mutationQueue.markSendUnconfirmed(id)

    /** See [MutationQueue.clearUnconfirmedSend]. */
    suspend fun clearUnconfirmedSend(id: Long) {
        mutationQueue.clearUnconfirmedSend(id)
    }

    /** See [MutationQueue.hasUnconfirmedSend]. */
    suspend fun hasUnconfirmedSend(id: Long): Boolean = mutationQueue.hasUnconfirmedSend(id)

    /** See [MutationQueue.unconfirmedSends]. */
    suspend fun unconfirmedSends(): Set<Long> = mutationQueue.unconfirmedSends()

    /** See [MutationQueue.retireCoveredSyncedMutations]. */
    suspend fun retireCoveredSyncedMutations(table: String, requestStartedAtTick: Long? = null) {
        mutationQueue.retireCoveredSyncedMutations(table, requestStartedAtTick)
    }

    /** See [MutationQueue.retireUnneededSyncedMutations]. */
    suspend fun retireUnneededSyncedMutations() {
        mutationQueue.retireUnneededSyncedMutations()
    }

    /* ────────────────── Overlay pending mutations onto SELECT JSON ────────────────── */

    /** See [QueryOverlay.overlayPendingOnResponse]. */
    suspend fun overlayPendingOnResponse(
        table: String,
        cacheKey: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): String = queryOverlay.overlayPendingOnResponse(table, cacheKey, serverJson, shape)

    /** See [QueryOverlay.overlayPendingOnResponseReporting]. */
    suspend fun overlayPendingOnResponseReporting(
        table: String,
        cacheKey: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay =
        queryOverlay.overlayPendingOnResponseReporting(table, cacheKey, serverJson, shape)

    /** See [QueryOverlay.overlayCachedPayload]. */
    suspend fun overlayCachedPayload(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): String = queryOverlay.overlayCachedPayload(table, cacheKey, cachedJson, shape)

    /** See [QueryOverlay.overlayCachedPayloadReporting]. */
    suspend fun overlayCachedPayloadReporting(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay =
        queryOverlay.overlayCachedPayloadReporting(table, cacheKey, cachedJson, shape)

    /** See [QueryOverlay.overlayCachedPayload]. */
    suspend fun overlayCachedPayload(table: String, cachedJson: String): String =
        queryOverlay.overlayCachedPayload(table, cachedJson)

    /** See [QueryOverlay.overlaidPayloadFlow]. */
    fun overlaidPayloadFlow(
        table: String,
        cacheKey: String,
        shape: QueryShape? = null,
    ): Flow<String?> = queryOverlay.overlaidPayloadFlow(table, cacheKey, shape)

    /** See [QueryOverlay.servedPayloadFlow]. */
    fun servedPayloadFlow(
        table: String,
        cacheKey: String,
        shape: QueryShape? = null,
    ): Flow<ServedPayload?> = queryOverlay.servedPayloadFlow(table, cacheKey, shape)

    /* ──────────────────── Patching cached queries after a write ──────────────────── */

    /** See [CachePatcher.patchCachedQueries]. */
    internal suspend fun patchCachedQueries(
        table: String,
        operation: String,
        bodyJson: String,
        filterJson: String?,
        defaultToNull: Boolean? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean? = null,
        liveCacheKeys: Set<String>? = null,
        startedAtTick: Long? = null,
    ) {
        cachePatcher.patchCachedQueries(
            table = table,
            operation = operation,
            bodyJson = bodyJson,
            filterJson = filterJson,
            defaultToNull = defaultToNull,
            onConflict = onConflict,
            ignoreDuplicates = ignoreDuplicates,
            liveCacheKeys = liveCacheKeys,
            startedAtTick = startedAtTick,
        )
    }

    /** See [CachePatcher.patchReturnedRows]. */
    internal suspend fun patchReturnedRows(
        table: String,
        body: JsonElement,
        responseData: String,
        startedAtTick: Long,
    ) {
        cachePatcher.patchReturnedRows(table, body, responseData, startedAtTick)
    }

    /** See [CachePatcher.markEveryQueryStale]. */
    suspend fun markEveryQueryStale(table: String, startedAtTick: Long) {
        cachePatcher.markEveryQueryStale(table, startedAtTick)
    }

    /** See [CachePatcher.markQueriesAWriteMayReach]. */
    suspend fun markQueriesAWriteMayReach(
        table: String,
        operation: String,
        bodyJson: String,
        narrowing: FilterDescriptor?,
        startedAtTick: Long,
    ) {
        cachePatcher.markQueriesAWriteMayReach(table, operation, bodyJson, narrowing, startedAtTick)
    }
}

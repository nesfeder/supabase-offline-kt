// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
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
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import io.github.nesfeder.supabase.offline.internal.storage.cachedFileKeyOf
import io.github.nesfeder.supabase.offline.internal.storage.cachedFileRowOf
import kotlin.time.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import okio.Path

/**
 * A facade over the store's components, retired in C3b: every member the engine and its tests call,
 * each delegating in one line, to [CacheFreshness], [CacheOwnership], [MutationQueue],
 * [QueryCache], [QueryOverlay], [CachePatcher] or [LocalData], whose KDoc states the lock order.
 * The wiring stays assignable `var`s; each component takes a function that reads the current value
 * when used, so a value may be assigned before or after the first call.
 */
internal class OfflineStore(
    queryCacheDao: QueryCacheDao,
    /** The cached files' rows; read by [StorageFileCache]'s facade constructor. */
    internal val cachedFileDao: CachedFileDao,
    mutationDao: MutationDao,
    queryCacheLimits: QueryCacheLimits,
    /** The clock rows are stamped with; a cached file's fetch time too. */
    internal val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The one tick source of the watermarks and of request starts; see [CacheFreshness]. */
    monotonicTick: () -> Long = monotonicTickSource(),
) {

    /** The engine's [Json]. */
    internal val json: Json = EngineJson

    /* ─────────────────────────────── Wiring ─────────────────────────────── */

    /**
     * Whether the cache belongs to one identity at a time, checked on every read and write: `true`
     * with the Auth plugin. Wired by the client.
     */
    internal var isCacheOwnedPerIdentity: Boolean = false

    /** Told when the cache was not served to, or written for, the current identity. */
    internal var onCacheNotServed: () -> Unit = {}

    /** Told of the cached queries a write marked stale, so their live readers fetch again. */
    internal var onQueriesMarkedStale: (table: String, cacheKeys: Set<String>) -> Unit = { _, _ -> }

    /** Where conditions the store recovers from are logged; silent when built directly. */
    internal var engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO)

    /**
     * The identity whose queued writes the overlay shows and the counts count — its stored value —
     * or `null` for no filtering (a store driven directly, with no session model).
     */
    internal var identityProvider: suspend () -> String? = { null }

    /**
     * Emits when the session may have changed, so overlays and counts re-apply [identityProvider].
     */
    internal var identityChanges: Flow<Any?> = flowOf(Unit)

    /** Each table's primary key, for de-duplicating overlaid rows and telling a keyed INSERT. */
    internal var primaryKeys: PrimaryKeys = DefaultPrimaryKeys

    /**
     * Whether mutations queued without an owner belong to the anonymous identity: `true` when the
     * client has no Auth plugin, so there is no other identity.
     */
    internal var ownerlessMutationsAreAnonymous: Boolean = true

    /**
     * A table's queries with a live reader, which a patch gives a payload and retirement waits for;
     * none when built directly.
     */
    internal var liveCacheKeysOf: (table: String) -> Set<String> = { _ -> emptySet() }

    /** The queries of every table with a live reader, which eviction keeps. */
    internal var allLiveCacheKeys: () -> Set<String> = { emptySet() }

    /* ───────────────────────────── Components ───────────────────────────── */

    /** The object cache, every write to it counted. */
    private val countingQueryCacheDao = PayloadWriteCountingDao(queryCacheDao)

    /** Each table's key columns, read through [primaryKeys] when used. */
    private val currentPrimaryKeys = PrimaryKeys { table -> primaryKeys.of(table) }

    /** [identityChanges] as assigned when a flow is collected. */
    private val currentIdentityChanges: Flow<Any?> = flow { emitAll(identityChanges) }

    private val freshness = CacheFreshness(monotonicTick)

    /** Whose the caches are; read by [StorageFileCache]'s facade constructor. */
    internal val cacheOwnership =
        CacheOwnership(
            queryCacheDao = countingQueryCacheDao,
            cachedFileDao = cachedFileDao,
            json = json,
            isCacheOwnedPerIdentity = { isCacheOwnedPerIdentity },
            identityProvider = { identityProvider() },
            onCacheNotServed = { onCacheNotServed() },
        )

    private val tableWriteLocks = TableWriteLocks()

    private val mutationQueue =
        MutationQueue(
            mutationDao = mutationDao,
            queryCacheDao = countingQueryCacheDao,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            tableWriteLocks = tableWriteLocks,
            primaryKeys = currentPrimaryKeys,
            identityProvider = { identityProvider() },
            identityChanges = currentIdentityChanges,
            ownerlessMutationsAreAnonymous = { ownerlessMutationsAreAnonymous },
            liveCacheKeysOf = { table -> liveCacheKeysOf(table) },
            engineLogger = { engineLogger },
        )

    private val queryCache =
        QueryCache(
            queryCacheDao = countingQueryCacheDao,
            queryCacheLimits = queryCacheLimits,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            mutationQueue = mutationQueue,
            tableWriteLocks = tableWriteLocks,
            allLiveCacheKeys = { allLiveCacheKeys() },
        )

    private val queryOverlay =
        QueryOverlay(
            queryCacheDao = countingQueryCacheDao,
            json = json,
            queryCache = queryCache,
            mutationQueue = mutationQueue,
            ownership = cacheOwnership,
            primaryKeys = currentPrimaryKeys,
            identityProvider = { identityProvider() },
            identityChanges = currentIdentityChanges,
            engineLogger = { engineLogger },
        )

    private val cachePatcher =
        CachePatcher(
            queryCacheDao = countingQueryCacheDao,
            json = json,
            nowMillis = nowMillis,
            freshness = freshness,
            ownership = cacheOwnership,
            queryCache = queryCache,
            queryOverlay = queryOverlay,
            primaryKeys = currentPrimaryKeys,
            liveCacheKeysOf = { table -> liveCacheKeysOf(table) },
            onQueriesMarkedStale = { table, cacheKeys -> onQueriesMarkedStale(table, cacheKeys) },
        )

    private val localData =
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

    /* ────────────────────────── Freshness and ownership ────────────────────────── */

    /** See [CacheFreshness.tickNow]. */
    internal fun monotonicTickNow(): Long = freshness.tickNow()

    /** See [CacheFreshness.wasClearedSince]. */
    internal fun wasClearedSince(tick: Long): Boolean = freshness.wasClearedSince(tick)

    /** The identity the caches belong to; see [CacheOwnerRecord]. */
    // test-only: moves in C3b
    internal val cacheOwner: CacheOwnerRecord
        get() = cacheOwnership.record

    /** See [CacheOwnership.cacheServesCurrentIdentity]. */
    // test-only: moves in C3b
    internal suspend fun cacheServesCurrentIdentity(): Boolean =
        cacheOwnership.cacheServesCurrentIdentity()

    /** See [CacheOwnership.cacheOwnerClaimingFor]. */
    internal suspend fun cacheOwnerClaimingFor(identity: String, claimsForUser: Boolean): String? =
        cacheOwnership.cacheOwnerClaimingFor(identity, claimsForUser)

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    /** See [QueryCache.queryShapes]. */
    // test-only: moves in C3b
    internal val queryShapes: QueryShapeRecords
        get() = queryCache.queryShapes

    /** See [QueryCache.cacheResponse]. */
    suspend fun addPayload(
        table: String,
        cacheKey: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
    ): CacheWriteOutcome =
        queryCache.cacheResponse(
            table = table,
            cacheKey = cacheKey,
            payload = payload,
            authoritativeEmpty = authoritativeEmpty,
            requestStartedAtTick = requestStartedAtTick,
            isTrusted = isTrusted,
            shape = shape,
        )

    /** See [QueryCache.getPayload]. */
    // test-only: moves in C3b
    suspend fun getPayload(table: String, cacheKey: String): String? =
        queryCache.getPayload(table, cacheKey)

    /** See [QueryCache.getPayloadWithTimestamp]. */
    suspend fun getPayloadWithTimestamp(table: String, cacheKey: String): Pair<String, Long>? =
        queryCache.getPayloadWithTimestamp(table, cacheKey)

    /** See [QueryCache.observePayload]. */
    // test-only: moves in C3b
    fun observePayload(table: String, cacheKey: String): Flow<String?> =
        queryCache.observePayload(table, cacheKey)

    /** See [QueryCache.deleteTablePayload]. */
    // test-only: moves in C3b
    suspend fun deleteTablePayload(table: String, cacheKey: String) {
        queryCache.deleteTablePayload(table, cacheKey)
    }

    /** See [QueryCache.clearTable]. */
    // test-only: moves in C3b
    suspend fun clearTable(table: String) {
        queryCache.clearTable(table)
    }

    /** See [QueryCache.evictOverBudget]. */
    // test-only: moves in C3b
    internal suspend fun evictOverBudget() {
        queryCache.evictOverBudget()
    }

    /* ────────────────────────────── Local data ────────────────────────────── */

    /** See [LocalData.clearLocalData]. */
    suspend fun clearLocalData(owner: String? = null) {
        localData.clearLocalData(owner)
    }

    /** See [LocalData.dropCacheFor]. */
    suspend fun dropCacheFor(owner: String) {
        localData.dropCacheFor(owner)
    }

    /* ───────────────────────────── Storage metadata ───────────────────────────── */

    /** Records [file] as the cached copy of [path] in [bucketId], fetched now. */
    // test-only: moves in C3b
    suspend fun addStorage(
        bucketId: String,
        path: String,
        file: Path,
        size: Long?,
        eTag: String?,
    ) {
        cachedFileDao.upsert(cachedFileRowOf(bucketId, path, file, size, eTag, nowMillis()))
    }

    /** The cached file row of [path] in [bucketId], or `null`. */
    // test-only: moves in C3b
    suspend fun getStorageByKey(
        bucketId: String,
        path: String,
    ): CachedFileRow? {
        return cachedFileDao.getByKey(cachedFileKeyOf(bucketId, path))
    }

    /** Deletes the cached file row of [path] in [bucketId]. */
    // test-only: moves in C3b
    suspend fun deleteStorageByKey(
        bucketId: String,
        path: String,
    ) {
        cachedFileDao.delete(cachedFileKeyOf(bucketId, path))
    }

    /** Every storage row. */
    // test-only: moves in C3b
    suspend fun allCachedFileRows(): List<CachedFileRow> = cachedFileDao.all()

    /** The summed recorded size of every cached file, in bytes. */
    // test-only: moves in C3b
    suspend fun totalStorageSizeBytes(): Long = cachedFileDao.totalSizeBytes()

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    /** See [MutationQueue.rejectedList]. */
    internal val rejectedList: RejectedMutationList
        get() = mutationQueue.rejectedList

    /** See [MutationQueue.enqueueInsert]. */
    // test-only: moves in C3b
    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true,
    ): Long = mutationQueue.enqueueInsert(table, body, filter, defaultToNull)

    /** See [MutationQueue.enqueueUpsert]. */
    // test-only: moves in C3b
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
    // test-only: moves in C3b
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null,
    ): Long = mutationQueue.enqueueUpdate(table, body, filter)

    /** See [MutationQueue.enqueueDelete]. */
    // test-only: moves in C3b
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
    // test-only: moves in C3b
    suspend fun dismissRejectedMutation(id: Long): Boolean =
        mutationQueue.dismissRejectedMutation(id)

    /** See [MutationQueue.pendingMutationsForTable]. */
    // test-only: moves in C3b
    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationQueue.pendingMutationsForTable(table)

    /** See [MutationQueue.allPendingMutations]. */
    suspend fun allPendingMutations(): List<MutationRow> = mutationQueue.allPendingMutations()

    /** See [MutationQueue.pendingMutationsFlow]. */
    // test-only: moves in C3b
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        mutationQueue.pendingMutationsFlow(table)

    /** See [MutationQueue.pendingMutationCount]. */
    // test-only: moves in C3b
    suspend fun pendingMutationCount(): Int = mutationQueue.pendingMutationCount()

    /** See [MutationQueue.pendingMutationCountFlow]. */
    // test-only: moves in C3b
    fun pendingMutationCountFlow(): Flow<Int> = mutationQueue.pendingMutationCountFlow()

    /** See [MutationQueue.pendingMutationCountForTable]. */
    // test-only: moves in C3b
    suspend fun pendingMutationCountForTable(table: String): Int =
        mutationQueue.pendingMutationCountForTable(table)

    /** See [MutationQueue.pendingMutationCountForTableFlow]. */
    // test-only: moves in C3b
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

    /** See [MutationQueue.recordReplayed]. */
    suspend fun markMutationSynced(id: Long, table: String) {
        mutationQueue.recordReplayed(id, table)
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
    // test-only: moves in C3b
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
    // test-only: moves in C3b
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
    // test-only: moves in C3b
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
    // test-only: moves in C3b
    suspend fun overlayCachedPayload(table: String, cachedJson: String): String =
        queryOverlay.overlayCachedPayload(table, cachedJson)

    /** See [QueryOverlay.overlaidPayloadFlow]. */
    // test-only: moves in C3b
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

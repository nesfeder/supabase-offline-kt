// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.db.staleStampOf
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutationList
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredServerError
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    /** Ids for the writes a patch overlays, one per write, below any queued mutation's. */
    private val patchIds = AtomicLong(0L)

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

    /**
     * Wipes every cached payload, storage-object row and mutation, the mutation queue's records
     * included, in one transaction, and marks the teardown so that no response or cache patch
     * started before it lands afterwards. For identity changes: sign-out, account switch, deletion.
     *
     * @param owner the identity the emptied cache belongs to, recorded in the same transaction;
     *   `null` records none, and the next identity to use the cache claims it.
     */
    suspend fun clearLocalData(owner: String? = null) {
        // Under the mutation-queue lock, so a write whose operation predates this teardown cannot
        // slip its mutation row in between the tick and the deletes (see [enqueueOwnedBy]); and
        // under the teardown lock, so no overlay or payload write that checked the tick before it
        // can land after the deletes. Neither lock is held across a request, so a teardown never
        // waits on one.
        mutationQueue.holdingQueueLock {
            freshness.holdingClearLock {
                freshness.markEmptied(freshness.tickNow(), queueCleared = true)
                // One transaction: a clear that fails part-way leaves the queue, the caches and
                // the cache's owner as they were, never a mix the next identity could read.
                cacheOwner.emptying(owner) {
                    mutationDao.runInTransaction {
                        mutationDao.deleteAll()
                        cachedFileDao.deleteAll()
                        queryCacheDao.deleteAll()
                        if (owner != null) queryCacheDao.upsert(cacheOwner.rowOf(owner))
                    }
                    mutationQueue.forgetRecordsOfClearedQueue()
                }
                queryShapes.forgetAll()
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
                cacheOwner.emptying(owner) {
                    mutationDao.runInTransaction {
                        cachedFileDao.deleteAll()
                        queryCacheDao.deleteAllExceptTable(RESERVED_TABLE_NAME)
                        queryCacheDao.deleteWithCacheKeyPrefix(
                            RESERVED_TABLE_NAME,
                            QUERY_SHAPE_CACHE_KEY_PREFIX,
                        )
                        queryCacheDao.upsert(cacheOwner.rowOf(owner))
                    }
                }
                queryShapes.forgetAll()
                queryCache.forgetParsedPayloads()
                // Counted again once committed, as in clearLocalData
                queryCacheDao.countWriteToEveryTable()
            }
        }
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

    /** A new id for a write a patch overlays, so a failure is logged once per write. */
    private fun nextPatchId(): Long = patchIds.decrementAndFetch()

    /**
     * Patches a write the server accepted, or a row it sent, into every cached query of [table],
     * and creates a payload for each live query of the table that has none and admits one of its
     * rows. Each query shows only the rows it admits. Rows cached for the table's other queries,
     * read with the same [Projection], stand in for rows the write changes but the query does not
     * hold yet, so a row an UPDATE makes match joins. A patched payload keeps its fetch time and a
     * created one is stale: local rows are not fresh data. A query the patch may leave wrong is
     * marked stale, and its live readers fetch again.
     *
     * Serialized with every other writer of [table]'s cached payloads, and it moves the table's
     * stale-refresh watermark first, so a response already in flight cannot roll the patch back.
     *
     * @param operation one of the [MutationOperation] values.
     * @param bodyJson rows for INSERT and UPSERT, the changed columns for UPDATE, `"{}"` for
     *   DELETE.
     * @param filterJson the encoded [FilterDescriptor] of an UPDATE or DELETE; one that is missing
     *   touches nothing.
     * @param onConflict an UPSERT's conflict columns; `null` matches rows by the table's primary
     *   key.
     * @param liveCacheKeys the table's queries with a live reader; by default [liveCacheKeysOf]'s.
     * @param startedAtTick [monotonicTickNow] as read when the write started; nothing is written if
     *   the cache was cleared or dropped since. `null` takes the tick now.
     */
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
        requireUserTable(table)
        // A clear after this point voids the patch: each write below checks it under the teardown
        // lock
        val guardTick = startedAtTick ?: freshness.tickNow()
        val write =
            MutationRow(
                id = nextPatchId(),
                table = table,
                operation = operation,
                bodyJson = bodyJson,
                filterJson = filterJson,
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull,
                onConflict = onConflict,
                ignoreDuplicates = ignoreDuplicates,
            )
        patchQueries(table, listOf(write), liveCacheKeys, guardTick)
    }

    /**
     * Patches the rows an UPDATE returned into the cached queries of [table], as
     * [patchCachedQueries] does: each returned row that carries the table's primary key sets, in
     * the row with that key, [body]'s columns and then its own. They are what the server wrote,
     * columns it set itself included. Rows without the key, or a response without rows, patch
     * nothing.
     *
     * @param responseData the UPDATE's response body: a JSON array of rows, one object, or empty.
     * @param startedAtTick [monotonicTickNow] as read when the write started.
     */
    internal suspend fun patchReturnedRows(
        table: String,
        body: JsonElement,
        responseData: String,
        startedAtTick: Long,
    ) {
        requireUserTable(table)
        val keyColumns = primaryKeys.of(table)
        val bodyColumns: Map<String, JsonElement> = body as? JsonObject ?: emptyMap()
        val writes =
            returnedRowsOf(json, responseData).mapNotNull { row ->
                val key = primaryKeyOf(row, keyColumns) ?: return@mapNotNull null
                val byKey =
                    FilterDescriptor(
                        conditions =
                            key.map { (column, value) ->
                                FilterDescriptor.Condition(
                                    column,
                                    operatorId = "eq",
                                    value = value.jsonPrimitive.content,
                                )
                            }
                    )
                MutationRow(
                    id = nextPatchId(),
                    table = table,
                    operation = MutationOperation.UPDATE,
                    bodyJson = JsonObject(bodyColumns + row).toString(),
                    filterJson = encodeFilter(byKey, json),
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                )
            }
        if (writes.isNotEmpty())
            patchQueries(table, writes, liveCacheKeys = null, guardTick = startedAtTick)
    }

    /**
     * Overlays [writes], in order, on every cached query of [table] and creates the payloads live
     * queries without one now admit; see [patchCachedQueries]. A query the patch may leave wrong
     * ([patchMayLeaveWrong]) is marked stale and fetched again. A cache that is not the current
     * identity's is left alone.
     */
    private suspend fun patchQueries(
        table: String,
        writes: List<MutationRow>,
        liveCacheKeys: Set<String>?,
        guardTick: Long,
    ) {
        if (!cacheServesCurrentIdentity()) return
        val toFetchAgain = mutableSetOf<String>()
        tableWriteLock(table).withLock {
            freshness.recordLocalMutation(table)
            val cached = queryCacheDao.allForTable(table)
            val uncachedLive =
                (liveCacheKeys ?: liveCacheKeysOf(table)) -
                    cached.mapTo(mutableSetOf()) { row -> row.cacheKey }
            // Only a patch that may write claims an unowned cache for the current identity
            if (cached.isEmpty() && uncachedLive.isEmpty()) return@withLock
            if (!cacheTakesWritesOfCurrentIdentity()) return@withLock
            val parsed = cached.map { row -> row to queryCache.parsedPayloadOf(row) }
            val shapes = queryShapes.shapesOf(table)
            val keyColumns = primaryKeys.of(table)
            val batch = PatchWrites(json)
            // UPDATEs naming one key each, over keys `eq` decides by content: each reaches exactly
            // the rows with its key, found by it
            val keyed = queryOverlay.keyedUpdatesOf(writes, keyColumns, parsed, batch)
            val mayMatter =
                (if (keyed != null) queryOverlay.keyedUpdatesReach(keyed)
                else rowsThatMayMatter(table, writes, primaryKeys, json, unknownMatters = true))
                    ?: { _, _ ->
                        false
                    }
            // Indexed only for a query that may draw on the rows other queries hold
            val known = lazy { KnownRows(parsed, shapes, keyColumns, mayMatter) }
            for ((row, payload) in parsed) {
                val rows = payload.rows ?: continue
                val shape = shapes[row.cacheKey]
                val target =
                    if (writes.any { write -> mayJoinKnownRows(write, shape, batch) }) {
                        known.value.targetFor(
                            row.cacheKey,
                            shape,
                            rows,
                            rowKeys = payload.keysFor(keyColumns),
                        )
                    } else {
                        PatchTarget(rows, shape, emptyList(), emptyList(), keyColumns.toSet())
                    }
                val overlay = queryOverlay.patchOverlayFor(target.admission, batch, keyed)
                val patched = overlay.overlayRows(rows, writes, knownRows = target.sameSelectRows)
                val wrong = patchMayLeaveWrong(writes, target, json, keyed, ::conflictColumnsOf)
                if (patched.visible == rows && (!wrong || isStaleStamp(row.updatedAt))) continue
                // The payload stays as old as what the server last said: a local write is not fresh
                // data, and one it may leave wrong is stale
                val stamp = if (wrong) staleStampOf(row.updatedAt) else row.updatedAt
                val next = row.copy(payload = overlay.encode(patched.visible), updatedAt = stamp)
                if (!writeUnlessCleared(guardTick, next)) return@withLock
                if (wrong) toFetchAgain += row.cacheKey
            }
            toFetchAgain +=
                createLivePayloads(
                    table,
                    uncachedLive,
                    shapes,
                    writes,
                    known,
                    guardTick,
                    batch,
                    keyed,
                )
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /**
     * Whether a patch of [write] reads rows other queries hold for a query of [shape]: an UPSERT
     * matches them by key, and an UPDATE may bring one in only when it sets a column the query's
     * filter reads. INSERT and DELETE never read them.
     */
    private fun mayJoinKnownRows(
        write: MutationRow,
        shape: QueryShape?,
        batch: PatchWrites,
    ): Boolean =
        when (write.operation) {
            MutationOperation.UPSERT -> true
            MutationOperation.UPDATE ->
                batch.patchOf(write)?.let { patch -> mayGainRowsBy(patch, shape) } ?: true
            else -> false
        }

    /** The upsert-rule columns of [mutation]; see [conflictColumnsOf]. */
    private fun conflictColumnsOf(mutation: MutationRow): List<String> =
        conflictColumnsOf(mutation, primaryKeys)

    /**
     * Writes a payload for each of [cacheKeys], queries a live reader observes that have none, of
     * the rows [writes] wrote that the query admits. Nothing is written for a query that would stay
     * empty, and nothing once the cache was cleared or dropped since [guardTick]. A query the rows
     * known here may not show right gets no payload; it is returned, to be fetched again.
     *
     * @param shapes the table's known query shapes, by cache key.
     * @param known the rows the table's other queries hold, indexed on first use.
     * @param batch the writes' bodies and filters, read once.
     * @param keyed see [patchMayLeaveWrong].
     * @return the queries to fetch again.
     */
    private suspend fun createLivePayloads(
        table: String,
        cacheKeys: Set<String>,
        shapes: Map<String, QueryShape?>,
        writes: List<MutationRow>,
        known: Lazy<KnownRows>,
        guardTick: Long,
        batch: PatchWrites,
        keyed: KeyedUpdates?,
    ): Set<String> {
        val toFetchAgain = mutableSetOf<String>()
        for (cacheKey in cacheKeys) {
            // A live query's shape is known while it is read; unknown conditions admit
            val shape = shapes[cacheKey]
            val target = known.value.targetFor(cacheKey, shape, rows = emptyList())
            if (patchMayLeaveWrong(writes, target, json, keyed, ::conflictColumnsOf)) {
                toFetchAgain += cacheKey
                continue
            }
            val admission = target.admission
            val overlay = queryOverlay.patchOverlayFor(admission, batch, keyed)
            val created =
                overlay
                    .overlayRows(emptyList(), writes, knownRows = target.sameSelectRows)
                    .written
                    .filter(admission::admits)
            if (created.isEmpty()) continue
            // Local rows alone are not the query's answer: stale, so a read asks the server when it
            // can
            val row =
                CachedQueryRow(
                    table = table,
                    cacheKey = cacheKey,
                    payload = overlay.encode(created),
                    updatedAt = staleStampOf(nowMillis()),
                )
            // A created payload is not a network response: it holds no synced mutation
            if (!writeUnlessCleared(guardTick, row)) return toFetchAgain
            if (shape != null) queryShapes.remember(table, cacheKey, shape, persist = true)
        }
        return toFetchAgain
    }

    /**
     * Marks every cached query of [table] stale, and has every live one fetch again: a write the
     * cache cannot follow reached the table.
     *
     * @param startedAtTick see [markQueriesAWriteMayReach].
     */
    suspend fun markEveryQueryStale(table: String, startedAtTick: Long) {
        // An UPDATE with no readable part reaches every query, and may add rows to a live one
        markQueriesAWriteMayReach(
            table,
            MutationOperation.UPDATE,
            "{}",
            narrowing = null,
            startedAtTick,
        )
    }

    /**
     * Marks stale the cached queries of [table] an UPDATE or DELETE the cache cannot patch may have
     * reached, and has their live readers fetch again: a query holding a row [narrowing] does not
     * exclude, and, for an UPDATE, one whose filter may admit a row the write changes. With no
     * [narrowing], every query of the table. The payloads stay, so an offline read still has them.
     *
     * @param narrowing conditions the write's filter includes, all of which a row it reaches meets.
     * @param startedAtTick [monotonicTickNow] as read when the write started; nothing is written if
     *   the cache was cleared or dropped since, or is not the current identity's.
     */
    suspend fun markQueriesAWriteMayReach(
        table: String,
        operation: String,
        bodyJson: String,
        narrowing: FilterDescriptor?,
        startedAtTick: Long,
    ) {
        requireUserTable(table)
        if (!cacheTakesWritesOfCurrentIdentity()) return
        val patch =
            (if (operation == MutationOperation.UPDATE) json.parseToJsonElement(bodyJson) else null)
                as? JsonObject
        val toFetchAgain = mutableSetOf<String>()
        tableWriteLock(table).withLock {
            freshness.recordLocalMutation(table)
            val shapes = queryShapes.shapesOf(table)
            val cached = queryCacheDao.allForTable(table)
            for (row in cached) {
                val reached =
                    narrowing == null ||
                        payloadRowsOf(json, row.payload).orEmpty().any { element ->
                            element !is JsonObject ||
                                rowMatch(element, narrowing) != RowMatch.EXCLUDED
                        } ||
                        (patch != null && mayGainRowsBy(patch, shapes[row.cacheKey]))
                if (!reached) continue
                toFetchAgain += row.cacheKey
                if (isStaleStamp(row.updatedAt)) continue
                val next = row.copy(updatedAt = staleStampOf(row.updatedAt))
                if (!writeUnlessCleared(startedAtTick, next)) return@withLock
            }
            // A live query with nothing cached may gain the rows an UPDATE changes
            if (patch != null) {
                toFetchAgain +=
                    liveCacheKeysOf(table) - cached.mapTo(mutableSetOf()) { row -> row.cacheKey }
            }
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /** Writes [row] unless the cache was cleared or dropped since [guardTick]. */
    private suspend fun writeUnlessCleared(guardTick: Long, row: CachedQueryRow): Boolean =
        freshness.writeUnlessCleared(guardTick) { queryCacheDao.upsert(row) }

    private companion object {
        /**
         * The most rows a table's key index reads, beyond which a read finds rows held elsewhere by
         * reading every payload instead.
         */
        const val MAX_KEY_INDEXED_ROWS = 100_000
    }
}

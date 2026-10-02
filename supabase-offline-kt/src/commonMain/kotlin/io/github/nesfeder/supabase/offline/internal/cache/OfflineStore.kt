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
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
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
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.equalityDecidedByContent
import io.github.nesfeder.supabase.offline.internal.postgrest.isPlainColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.Path

/**
 * What [OfflineStore.addPayload] did with the payload it was handed. Lets the select resolver
 * distinguish "the cache took this" from the two reasons it may refuse, and react accordingly.
 */
internal enum class CacheWriteOutcome {
    /** The payload was written to the object cache. */
    WRITTEN,

    /**
     * The producing request started **before** the most recent local write to the table, so the
     * response cannot know about that write and would roll the cache back. The caller should
     * re-fetch (a request issued now starts after the write and converges) rather than treat the
     * response as the current server state.
     */
    REFUSED_STALE,

    /**
     * The payload was an empty array that was not attributed to an authenticated session, so the
     * empty-clobber guard refused it. Unattributed empties never reach the cache: over non-empty
     * rows they erase real data, and into a cold (or already-empty) slot they install a phantom
     * empty that later cache-first reads then serve as truth.
     */
    REFUSED_EMPTY,

    /**
     * The response came back under a session the read's trust policy does not take as speaking for
     * the current identity, such as one still restoring, so nothing of it was written.
     */
    REFUSED_UNTRUSTED,

    /**
     * The producing request started **before** the cache was torn down by
     * [OfflineStore.clearLocalData] — sign-out, account switch, or account deletion. The response
     * belongs to a cache generation that no longer exists and, on an account switch, to a different
     * user entirely. Unlike [REFUSED_STALE] the caller must **not** re-fetch: a follow-up issued
     * during teardown can still carry the outgoing session's JWT and would re-import the very rows
     * teardown removed.
     */
    REFUSED_AFTER_CLEAR,

    /**
     * The cache belongs to another identity than the current session's, or was dropped for a new
     * one after the request started; see [OfflineStore.dropCacheFor]. The caller may ask again,
     * once the cache is the current identity's.
     */
    REFUSED_OTHER_OWNER,
}

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
    private val evictionMutex = Mutex()

    /** The watermarks and the clear lock; see [CacheFreshness]. */
    private val freshness = CacheFreshness(monotonicTick)

    /** The object cache, every write to it counted; see [tableKeyIndexOf]. */
    private val queryCacheDao = PayloadWriteCountingDao(queryCacheDao)

    /** Each cached query's shape, which decides the rows it admits; see [QueryShapeRecords]. */
    internal val queryShapes: QueryShapeRecords = QueryShapeRecords(this.queryCacheDao, json)

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
     * Payloads' rows as parsed, by table and cache key, each with the text it was parsed from, so
     * an unchanged payload is not parsed again; see [parsedPayloadOf].
     */
    private val parsedPayloads =
        MutableStateFlow<Map<Pair<String, String>, ParsedPayload>>(emptyMap())

    /** A payload's [text] and its [rows], `null` when it is not a JSON array. */
    private class ParsedPayload(val text: String, val rows: List<JsonElement>?) {

        /** The key columns [keys] were read for, and each row's [keyOf] under them. */
        @Volatile private var keyed: Pair<List<String>, List<List<String>?>>? = null

        /** Each row's [keyOf] under [keyColumns], `null` for a row without one; read once. */
        fun keysFor(keyColumns: List<String>): List<List<String>?> {
            keyed?.let { (columns, keys) -> if (columns == keyColumns) return keys }
            val keys =
                rows.orEmpty().map { element ->
                    (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
                }
            keyed = keyColumns to keys
            return keys
        }
    }

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

    /**
     * Per-table locks serializing every write to the object cache — [addPayload] and the
     * read-modify-write overlays ([patchCachedQueries], [clearTable], [deleteTablePayload]).
     *
     * The overlay is not atomic on its own: it snapshots every cached payload for the table,
     * patches each in memory, and writes each back **whole**. Two overlays interleaving on one
     * table therefore lose a write — the second snapshots before the first has written, appends its
     * own row, and writes the first's row back out. Nothing replays an online-success overlay (it
     * never enters the mutation queue), so the lost row is simply gone from every reactive observer
     * until some later network read of that signature. In practice, a batch job upserting several
     * rows from four parallel workers left a screen on a blank loading placeholder for minutes
     * after its row was already on the server.
     *
     * The same read-modify-write race exists between an overlay and a fresh network payload: the
     * stale-response watermark refuses a response whose request *predates* the local write, but a
     * response that started after it can still land between the overlay's snapshot and its write
     * and be rolled back by it. Taking the lock in [addPayload] as well closes that side, and keeps
     * the watermark check itself ordered after any overlay in progress.
     *
     * Per table rather than store-wide because the hazard is per table — the overlay reads
     * `allForTable` — and one heavily cached table's overlay can be expensive (every cached
     * signature of that table is parsed and re-serialized per write), so other tables' writes
     * should not queue behind it. Keyed lazily; the map is guarded by its own mutex because it is
     * touched from many coroutines and commonMain has no concurrent map.
     */
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
     * Rolling count of [addPayload] writes, used to run size enforcement only periodically instead
     * of on every network SELECT. Atomic because writes can arrive concurrently from multiple
     * coroutines/threads; exactness isn't required, only a monotonic cadence.
     */
    private val writeCounter = AtomicInt(0)

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

    /**
     * Writes a network SELECT's [payload] for [table]/[cacheKey], unless it must be refused:
     * [CacheWriteOutcome.REFUSED_AFTER_CLEAR] when its request started before [clearLocalData],
     * [CacheWriteOutcome.REFUSED_OTHER_OWNER] when the cache is not the current identity's or was
     * dropped for it after the request started, [CacheWriteOutcome.REFUSED_STALE] when it started
     * before the table's last local write (it would roll that write back), and
     * [CacheWriteOutcome.REFUSED_EMPTY] for an empty payload that is not [authoritativeEmpty]. A
     * payload written with a start tick is recorded as holding the synced mutations so far
     * ([CachedQueryRow.syncedThrough]). Runs under the table's write lock.
     *
     * @param authoritativeEmpty Whether an empty [payload] is the server's real answer (the request
     *   went out signed in), so it may replace cached rows or seed a cold slot.
     * @param requestStartedAtTick [monotonicTickNow] read before the request went out, or `null`
     *   for a writer with no request behind it, which is never refused as stale.
     * @param isTrusted Whether the response speaks for the current identity at all; one that does
     *   not is refused as [CacheWriteOutcome.REFUSED_UNTRUSTED], empty or not.
     * @param shape the query's shape, recorded with a written payload before the table's lock is
     *   released; `null` when not known.
     * @return What happened, so a caller can re-fetch after a stale drop.
     */
    suspend fun addPayload(
        table: String,
        cacheKey: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
    ): CacheWriteOutcome {
        requireUserTable(table)
        // Before the locks: telling the identity may read the stored session. A drop that lands
        // between here and the write is caught by its tick below.
        val ownedByCurrentIdentity = cacheTakesWritesOfCurrentIdentity()
        // The guards and the write are one critical section under the table's write lock, so a
        // response is judged against the watermark *after* any overlay in progress has recorded
        // its write, and never lands between an overlay's snapshot and its write-back (where the
        // overlay would roll it straight back out) — see [tableWriteLocks].
        val outcome =
            tableWriteLock(table).withLock {
                mutationQueue.holdingQueueLock {
                    freshness.holdingClearLock {
                        when {
                            // The cache was wiped for an identity change while this request was
                            // in flight. Its rows belong to the outgoing account; writing them
                            // here undoes the teardown.
                            freshness.predatesQueueClear(requestStartedAtTick) ->
                                CacheWriteOutcome.REFUSED_AFTER_CLEAR

                            // The cache is another identity's, or became the current one's after
                            // this request left: its rows would mix two identities' data.
                            !ownedByCurrentIdentity ||
                                freshness.predatesCacheClear(requestStartedAtTick) ->
                                CacheWriteOutcome.REFUSED_OTHER_OWNER

                            // The response left before the local write landed; keeping the cache
                            // is the only answer that preserves it. The caller re-fetches to
                            // converge on real server state.
                            freshness.isStaleRelativeToLocalWrite(table, requestStartedAtTick) ->
                                CacheWriteOutcome.REFUSED_STALE

                            !isTrusted -> CacheWriteOutcome.REFUSED_UNTRUSTED

                            // Nothing about this emptiness is known to describe the signed-in
                            // user's data, so it is never written — not over cached rows, and not
                            // into a cold or already-empty slot where it would become the answer
                            // every later cache-first read is served.
                            !authoritativeEmpty && isEmptyJsonArrayPayload(payload) ->
                                CacheWriteOutcome.REFUSED_EMPTY

                            else -> {
                                // A response whose request started after the table's last local
                                // write holds every mutation synced so far; a payload with no
                                // request behind it holds none for certain
                                queryCacheDao.upsert(
                                    CachedQueryRow(
                                        table = table,
                                        cacheKey = cacheKey,
                                        payload = payload,
                                        updatedAt = nowMillis(),
                                        syncedThrough =
                                            if (requestStartedAtTick != null)
                                                mutationQueue.latestSyncSequence()
                                            else 0L,
                                    )
                                )
                                // Before the lock is released, so no patch finds the payload
                                // without the shape that says which rows it admits
                                if (shape != null)
                                    queryShapes.remember(table, cacheKey, shape, persist = true)
                                CacheWriteOutcome.WRITTEN
                            }
                        }
                    }
                }
            }

        // Outside the table lock: eviction takes its own mutex and scans every table.
        if (outcome == CacheWriteOutcome.WRITTEN && shouldEnforceSizeAfterWrite(payload)) {
            evictOverBudget()
        }
        return outcome
    }

    /**
     * Whether a size-enforcement pass should run after writing [payload].
     *
     * Enforcement is deliberately kept off the per-write hot path: measuring the cache (a
     * `SUM(LENGTH(payload))` scan) on every network SELECT would tax the dominant write path for no
     * benefit while the cache sits comfortably under its limit. Instead a pass is triggered only
     * when it can matter:
     *
     * - the incoming [payload] is large (>= [ENFORCEMENT_LARGE_PAYLOAD_CHARS]), so a single
     *   oversized write can't blow the budget before the periodic pass comes around; or
     * - a rolling counter reaches [ENFORCEMENT_WRITE_INTERVAL], a cheap periodic backstop that
     *   bounds accumulation from many small writes.
     *
     * Both checks are O(1); the expensive scan runs only when one fires. Returns `false`
     * immediately when size-based eviction is disabled ([QueryCacheLimits.maxPayloadBytes] is
     * `null`).
     *
     * @param payload the JSON payload just written to the cache.
     * @return `true` when [evictOverBudget] should be invoked.
     */
    private fun shouldEnforceSizeAfterWrite(payload: String): Boolean {
        if (queryCacheLimits.maxPayloadBytes == null) return false
        val isLargePayload = payload.length >= ENFORCEMENT_LARGE_PAYLOAD_CHARS
        // Always advance the counter so the cadence reflects the true write count; combine with
        // the large-payload trigger afterwards so both side effects run regardless of order.
        // fetchAndAdd returns the pre-increment value, so +1 yields the just-completed write's
        // ordinal — enforcing on every ENFORCEMENT_WRITE_INTERVAL-th write.
        val cadenceReached = (writeCounter.fetchAndAdd(1) + 1) % ENFORCEMENT_WRITE_INTERVAL == 0
        return isLargePayload || cadenceReached
    }

    /**
     * The payload cached for [table]/[cacheKey], or `null` when none is, or the cache is not the
     * current identity's.
     */
    suspend fun getPayload(table: String, cacheKey: String): String? =
        getPayloadWithTimestamp(table, cacheKey)?.first

    /**
     * The payload cached for [table]/[cacheKey] with the time it was written, or `null` when none
     * is, or the cache is not the current identity's.
     */
    suspend fun getPayloadWithTimestamp(table: String, cacheKey: String): Pair<String, Long>? {
        if (isReservedTable(table) || !cacheServesCurrentIdentity()) return null
        val row = queryCacheDao.get(table, cacheKey) ?: return null
        return row.payload to row.updatedAt
    }

    fun observePayload(table: String, cacheKey: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return queryCacheDao.observe(table, cacheKey).map { row -> row?.payload }
    }

    suspend fun deleteTablePayload(table: String, cacheKey: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            queryCacheDao.delete(table, cacheKey)
            queryShapes.forget(table, cacheKey)
        }
    }

    /**
     * Drop every cached SELECT payload for [table].
     *
     * Advances the table's stale-refresh watermark: an emptied table is local truth just as much as
     * a patched row, so a refresh already in flight must not land on top of it. Unlike
     * [clearLocalData] this is not identity teardown, so a follow-up re-fetch is the right
     * recovery.
     *
     * @param table Table whose cached payloads are removed.
     */
    suspend fun clearTable(table: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            freshness.recordLocalMutation(table)
            queryCacheDao.deleteTable(table)
            queryShapes.forgetTable(table)
        }
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
                parsedPayloads.value = emptyMap()
                // Counted again once committed: a read between the delete and the commit saw the
                // rows before it
                queryCacheDao.countWriteToEveryTable()
                tableKeyIndexes.value = emptyMap()
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
                parsedPayloads.value = emptyMap()
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

    /**
     * Logs a mutation the overlay skipped, once per mutation: the overlay is recomputed on every
     * emission, and a mutation that fails once fails every time. Never the body or filter values.
     */
    private fun logSkippedOverlay(mutation: MutationRow, failure: Exception) {
        if (!mutationQueue.claimOverlaySkipReport(mutation.id)) return
        engineLogger.warning {
            if (mutation.id < 0) {
                "Cache patch skipped an accepted ${mutation.operation} on ${mutation.table}: " +
                    "${failure::class.simpleName}"
            } else {
                "Overlay skipped pending ${mutation.operation} ${mutation.id} on ${mutation.table}: " +
                    "${failure::class.simpleName}"
            }
        }
    }

    /* ────────────────── Overlay pending mutations onto SELECT JSON ────────────────── */

    /**
     * Overlays the current identity's pending mutations of [table] onto a fresh network
     * [serverJson] for the query [cacheKey]. Synced ones are left out: the server already reflects
     * them.
     *
     * @param shape the query's shape; `null` looks up the one recorded for [cacheKey].
     */
    suspend fun overlayPendingOnResponse(
        table: String,
        cacheKey: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): String = overlayPendingOnResponseReporting(table, cacheKey, serverJson, shape).payload

    /** [overlayPendingOnResponse], saying whether a pending mutation changed a row it shows. */
    suspend fun overlayPendingOnResponseReporting(
        table: String,
        cacheKey: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay {
        if (isReservedTable(table)) return ServedOverlay(serverJson, hasPendingMutations = false)
        val pending =
            mutationQueue.visibleTo(
                identityProvider(),
                mutationQueue.pendingMutationsForTable(table),
            )
        if (pending.isEmpty()) return ServedOverlay(serverJson, hasPendingMutations = false)

        return overlayMutations(
                serverJson,
                pending,
                admissionOf(table, cacheKey, shape),
                unconfirmedSends(),
                sameSelectRowsFor(table, cacheKey, shape, pending, ownJson = serverJson),
            )
            .served(pending)
    }

    /**
     * Overlays the current identity's pending mutations, and the synced ones [cachedJson] does not
     * already contain, onto a cached payload served while offline.
     *
     * @param cacheKey The payload's query, or `null` when not known: every synced mutation applies.
     * @param shape the query's shape, recorded with the payload; `null` looks up the one recorded.
     */
    suspend fun overlayCachedPayload(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): String = overlayCachedPayloadReporting(table, cacheKey, cachedJson, shape).payload

    /** [overlayCachedPayload], saying whether a pending mutation changed a row it shows. */
    suspend fun overlayCachedPayloadReporting(
        table: String,
        cacheKey: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): ServedOverlay {
        if (isReservedTable(table)) return ServedOverlay(cachedJson, hasPendingMutations = false)
        if (cacheKey != null && shape != null)
            queryShapes.remember(table, cacheKey, shape, persist = true)
        val visible =
            mutationQueue.visibleTo(identityProvider(), mutationQueue.mutationsOfTable(table))
        // The stored payload's coverage, read only when a synced mutation may be held by it
        val syncedThrough =
            if (cacheKey == null || visible.all { mutation -> mutation.syncedAtMs == null }) 0L
            else queryCacheDao.get(table, cacheKey)?.syncedThrough ?: 0L
        val all = visible.filter { mutation -> belongsOnPayload(mutation, syncedThrough) }
        if (all.isEmpty()) return ServedOverlay(cachedJson, hasPendingMutations = false)

        val admission = cacheKey?.let { admissionOf(table, it, shape) } ?: QueryAdmission.EVERY_ROW
        return overlayFor(admission, unconfirmedSends())
            .overlayReportingChanges(
                cachedJson,
                all,
                sameSelectRowsFor(table, cacheKey, shape, all, ownJson = cachedJson),
            )
            .served(all)
    }

    /**
     * This overlay as served: whether one of [mutations] that is still pending, not synced, changed
     * a row it shows.
     */
    private fun OverlaidPayload.served(mutations: List<MutationRow>): ServedOverlay {
        val pendingIds =
            mutations.mapNotNullTo(HashSet()) { mutation ->
                mutation.id.takeIf { mutation.syncedAtMs == null }
            }
        return ServedOverlay(payload, shownChangedBy.any { id -> id in pendingIds })
    }

    /** [overlayCachedPayload] for a payload whose signature is not known. */
    suspend fun overlayCachedPayload(table: String, cachedJson: String): String =
        overlayCachedPayload(table, cacheKey = null, cachedJson = cachedJson)

    /**
     * The cached payload of [table]/[cacheKey] with the current identity's mutations overlaid, as
     * it changes; `null` while nothing is known. With no payload but mutations that produce rows,
     * the overlay onto an empty list is emitted without being stored. A cache that is not the
     * current identity's counts as no payload. The overlay is recomputed only when the payload
     * served, or the mutations of [table] visible to the identity, change: a write to another
     * table, or another identity's, costs a comparison.
     */
    fun overlaidPayloadFlow(
        table: String,
        cacheKey: String,
        shape: QueryShape? = null,
    ): Flow<String?> =
        servedPayloadFlow(table, cacheKey, shape)
            .map { served -> served?.payload }
            .distinctUntilChanged()

    /**
     * [overlaidPayloadFlow], each payload with what a reader reports of it: when the cached payload
     * under it was fetched, whether it is stale, and whether a pending mutation changed a row it
     * shows. A change to any of these emits, a refresh that fetched the same rows included.
     */
    fun servedPayloadFlow(
        table: String,
        cacheKey: String,
        shape: QueryShape? = null,
    ): Flow<ServedPayload?> {
        if (isReservedTable(table)) return flowOf(null)
        return flow {
            // A payload received before the cache was emptied may reach this reader after the new
            // owner does: until what it receives matches the database, the database is read
            var trustedEmptiedCount = cacheOwner.emptiedCount()
            var last: OverlaidInputs? = null
            var lastOverlaid: ServedOverlay? = null
            combine(
                    queryCacheDao
                        .observe(table, cacheKey)
                        .map { row -> row?.let(::StoredRow) }
                        .distinctUntilChanged(),
                    mutationQueue.mutationsOfTableFlow(table),
                    identityChanges,
                ) { stored, tableMutations, _ ->
                    // Owner first: the count moves before it, so a new owner comes with a new count
                    val served = cacheServesCurrentIdentity()
                    val emptiedCount = cacheOwner.emptiedCount()
                    val current =
                        if (emptiedCount == trustedEmptiedCount) {
                            stored
                        } else {
                            queryCacheDao.get(table, cacheKey)?.let(::StoredRow).also { read ->
                                if (read == stored) trustedEmptiedCount = emptiedCount
                            }
                        }
                    val servedRow = current.takeIf { served }
                    val inputs =
                        OverlaidInputs(
                            servedRow?.content,
                            mutationQueue.visibleTo(identityProvider(), tableMutations.rows),
                        )
                    if (inputs != last) {
                        lastOverlaid =
                            overlaidOrNull(
                                table,
                                cacheKey,
                                shape,
                                inputs.stored,
                                inputs.mutations,
                                tableMutations::bodyOf,
                            )
                        last = inputs
                    }
                    lastOverlaid?.let { overlay ->
                        ServedPayload(
                            payload = overlay.payload,
                            fetchedAtMillis = servedRow?.let { row -> fetchedAtOf(row.updatedAt) },
                            isStale = servedRow == null || isStaleStamp(servedRow.updatedAt),
                            hasPendingMutations = overlay.hasPendingMutations,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect { payload -> emit(payload) }
        }
    }

    /** A cached payload as stored: its [content] and its fetch stamp. */
    private data class StoredRow(val content: StoredPayload, val updatedAt: Long) {
        constructor(row: CachedQueryRow) : this(StoredPayload(row), row.updatedAt)
    }

    /** A cached payload's text and what it holds of the synced mutations. */
    private data class StoredPayload(val payload: String, val syncedThrough: Long) {
        constructor(row: CachedQueryRow) : this(row.payload, row.syncedThrough)
    }

    /**
     * What a reader's overlay is a function of: the payload it is served, `null` for none, and the
     * mutations of its table visible to the identity, in order.
     */
    private data class OverlaidInputs(
        val stored: StoredPayload?,
        val mutations: List<MutationRow>,
    )

    /**
     * [overlaidPayloadFlow]'s value for [stored], the payload the current identity is served
     * (`null` for none), with those of [mutations] it does not hold overlaid.
     */
    private suspend fun overlaidOrNull(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
        stored: StoredPayload?,
        mutations: List<MutationRow>,
        bodyOf: (MutationRow) -> JsonElement,
    ): ServedOverlay? {
        // A live query's shape is known for as long as it is read; it is written once a payload
        // exists
        if (shape != null) queryShapes.remember(table, cacheKey, shape, persist = stored != null)
        val cachedJson = stored?.payload
        val syncedThrough = stored?.syncedThrough ?: 0L
        val applicable = mutations.filter { mutation -> belongsOnPayload(mutation, syncedThrough) }
        if (applicable.isEmpty())
            return cachedJson?.let { json -> ServedOverlay(json, hasPendingMutations = false) }
        val overlaid =
            overlayMutations(
                    cachedJson ?: EMPTY_JSON_ARRAY,
                    applicable,
                    admissionOf(table, cacheKey, shape),
                    unconfirmedSends(),
                    sameSelectRowsFor(table, cacheKey, shape, applicable, ownJson = cachedJson),
                    bodyOf,
                )
                .served(applicable)
        return if (cachedJson == null && isEmptyJsonArrayPayload(overlaid.payload)) null
        else overlaid
    }

    /**
     * Overlays [mutations] onto [serverJson] for a query with [admission], showing only the rows it
     * admits.
     *
     * @param unconfirmedSends mutations whose send may have reached the server; see
     *   [isSyncedOrUnconfirmed].
     * @param knownRows rows the table's other queries hold, read with the same columns: those a
     *   mutation makes start matching join.
     * @param bodyOf a mutation's body, parsed.
     */
    private fun overlayMutations(
        serverJson: String,
        mutations: List<MutationRow>,
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
        knownRows: List<JsonObject> = emptyList(),
        bodyOf: (MutationRow) -> JsonElement = { mutation ->
            json.parseToJsonElement(mutation.bodyJson)
        },
    ): OverlaidPayload =
        overlayFor(admission, unconfirmedSends, bodyOf)
            .overlayReportingChanges(serverJson, mutations, knownRows)

    /**
     * The rows the table's other cached queries hold, read with the same [Projection] as the query
     * cached under [cacheKey], when one of [mutations] may bring such a row into it; see
     * [mayBringRowsIn]. Empty otherwise, and when the query's shape is unknown: then the other
     * payloads are not read at all.
     *
     * @param ownJson the rows the query shows before [mutations], when known.
     */
    private suspend fun sameSelectRowsFor(
        table: String,
        cacheKey: String?,
        shape: QueryShape?,
        mutations: List<MutationRow>,
        ownJson: String?,
    ): List<JsonObject> {
        if (cacheKey == null) return emptyList()
        val known = shape ?: queryShapes.shapeOf(table, cacheKey) ?: return emptyList()
        // The filter's column names as a JSON body writes them, once for every mutation
        val filterColumns = known.conditions.map { condition -> jsonNameOf(condition.column) }
        val keyColumns = primaryKeys.of(table)
        val ownKeys by lazy {
            ownJson?.let { text ->
                parsedTextOf(table, cacheKey, text).keysFor(keyColumns).filterNotNullTo(HashSet())
            } ?: emptySet()
        }
        val bringing = mutations.filter { mutation ->
            mayBringRowsIn(mutation, known, filterColumns, keyColumns) { ownKeys }
        }
        if (bringing.isEmpty()) return emptyList()
        val reaching = mutationsThatReach(bringing, mutations)
        val mayMatter =
            rowsThatMayMatter(table, reaching, unknownMatters = false) ?: return emptyList()
        val projection = projectionOf(known)
        val shapes = queryShapes.shapesOf(table)
        // Upserts on the key need only the rows with their keys: found by key when the index holds
        upsertedKeysOf(reaching, keyColumns)?.let { keys ->
            borrowedByKey(table, cacheKey, projection, shapes, keys, keyColumns)?.let { rows ->
                return rows
            }
        }
        val sameSelect =
            queryCacheDao.allForTable(table).filter { row ->
                row.cacheKey != cacheKey && shapes[row.cacheKey]?.let(::projectionOf) == projection
            }
        if (sameSelect.isEmpty()) return emptyList()
        val parsed = sameSelect.map { row -> row to parsedPayloadOf(row) }
        return KnownRows(parsed, shapes, keyColumns, mayMatter)
            .targetFor(cacheKey, known, rows = emptyList())
            .sameSelectRows
    }

    /**
     * The keys [mutations] upsert, when each is an UPSERT matching rows on [keyColumns]: the only
     * rows held elsewhere they read. `null` when one is another write.
     */
    private fun upsertedKeysOf(
        mutations: List<MutationRow>,
        keyColumns: List<String>,
    ): Set<List<String>>? {
        val keys = LinkedHashSet<List<String>>()
        for (mutation in mutations) {
            if (mutation.operation != MutationOperation.UPSERT) return null
            if (conflictColumnsOf(mutation) != keyColumns) return null
            upsertRowsOf(mutation).forEach { row -> keyOf(row, keyColumns)?.let(keys::add) }
        }
        return keys
    }

    /**
     * For each of [keys], the row with it from the most recently fetched other query of [table]
     * read with [projection], as [KnownRows] gives it; `null` when [tableKeyIndexOf] cannot say.
     * Reads only the payloads that hold one of [keys].
     *
     * @param cacheKey the reading query's cache key; its own payload is never drawn on.
     * @param shapes the table's query shapes, by cache key.
     */
    private suspend fun borrowedByKey(
        table: String,
        cacheKey: String,
        projection: Projection,
        shapes: Map<String, QueryShape?>,
        keys: Set<List<String>>,
        keyColumns: List<String>,
    ): List<JsonObject>? {
        val index = tableKeyIndexOf(table, keyColumns) ?: return null
        val borrowed = mutableListOf<JsonObject>()
        for (key in keys) {
            val holder =
                index.holders[key]?.firstOrNull { held ->
                    held.cacheKey != cacheKey &&
                        shapes[held.cacheKey]?.let(::projectionOf) == projection
                } ?: continue
            // The index is read with the payload writes it counted; this checks the row is there
            val row = queryCacheDao.get(table, holder.cacheKey) ?: return null
            val payload = parsedPayloadOf(row)
            if (payload.keysFor(keyColumns).getOrNull(holder.position) != key) return null
            borrowed += payload.rows?.getOrNull(holder.position) as? JsonObject ?: return null
        }
        return borrowed
    }

    /**
     * [table]'s cached rows by key, as last read, or read now when a payload of the table was
     * written since; `null` when the table holds more rows than [MAX_KEY_INDEXED_ROWS]. Kept only
     * when no payload write landed while it was read.
     */
    private suspend fun tableKeyIndexOf(table: String, keyColumns: List<String>): TableKeyIndex? {
        val writes = queryCacheDao.writesTo(table)
        tableKeyIndexes.value[table]?.let { index ->
            if (index.writes == writes && index.keyColumns == keyColumns) return index
        }
        // Most recently fetched first, as KnownRows orders the copies of one row
        val newestFirst =
            queryCacheDao
                .allForTable(table)
                .map { row -> row to parsedPayloadOf(row) }
                .sortedByDescending { (row, _) -> fetchedAtOf(row.updatedAt) }
        val holders = HashMap<List<String>, MutableList<KeyHolder>>()
        var indexed = 0
        for ((row, payload) in newestFirst) {
            payload.keysFor(keyColumns).forEachIndexed { position, key ->
                if (key == null) return@forEachIndexed
                if (++indexed > MAX_KEY_INDEXED_ROWS) return null
                holders.getOrPut(key) { mutableListOf() } += KeyHolder(row.cacheKey, position)
            }
        }
        val index = TableKeyIndex(writes, keyColumns, holders)
        if (queryCacheDao.writesTo(table) == writes) {
            tableKeyIndexes.update { indexes -> indexes + (table to index) }
        }
        return index
    }

    /**
     * A table's cached rows by key: for each key, the payloads holding a row with it, most recently
     * fetched first.
     *
     * @property writes the table's count of payload writes when it was read.
     * @property keyColumns the key columns it is read under.
     */
    private class TableKeyIndex(
        val writes: Pair<Long, Long>,
        val keyColumns: List<String>,
        val holders: Map<List<String>, List<KeyHolder>>,
    )

    /**
     * A payload holding a row with some key.
     *
     * @property cacheKey the payload's cache key.
     * @property position the row's position in the payload.
     */
    private class KeyHolder(val cacheKey: String, val position: Int)

    /** Each table's [TableKeyIndex], as last read. */
    private val tableKeyIndexes = MutableStateFlow<Map<String, TableKeyIndex>>(emptyMap())

    /**
     * Whether [mutation] may bring a row another query holds into a query of [shape]: an UPSERT of
     * a row the query does not hold and does not exclude on the columns the upsert sets, or an
     * UPDATE setting a column the query's filter names (read without parsing the body) to a value
     * it does not exclude.
     *
     * @param filterColumns the columns [shape]'s filter names, as a JSON body writes them as keys.
     * @param ownKeys the keys of the rows the query holds, under [keyColumns].
     */
    private fun mayBringRowsIn(
        mutation: MutationRow,
        shape: QueryShape,
        filterColumns: List<String>,
        keyColumns: List<String>,
        ownKeys: () -> Set<List<String>>,
    ): Boolean =
        when (mutation.operation) {
            // A row the query holds merges there; one it excludes on what the upsert sets stays
            // out, whatever row it merges with
            MutationOperation.UPSERT -> {
                val columns = conflictColumnsOf(mutation)
                upsertRowsOf(mutation).any { row ->
                    val key = keyOf(row, columns) ?: return@any false
                    shape.admission.admits(row) && (columns != keyColumns || key !in ownKeys())
                }
            }
            MutationOperation.UPDATE ->
                (shape.hasOtherFilters ||
                    filterColumns.any { column -> column in mutation.bodyJson }) &&
                    (patchOf(mutation)?.let(shape.admission::admits) != false)
            else -> false
        }

    /** [column] quoted and escaped as a JSON body writes it as a key. */
    private fun jsonNameOf(column: String): String = JsonPrimitive(column).toString()

    /** An UPDATE's body; `null` when it is not a JSON object. */
    private fun patchOf(mutation: MutationRow): JsonObject? =
        try {
            json.parseToJsonElement(mutation.bodyJson) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    /**
     * The admission of the query cached under [cacheKey]: [shape]'s, or the recorded one's, or
     * every row when neither is known, since unknown conditions admit: so for a payload an earlier
     * build cached, until it is read.
     */
    private suspend fun admissionOf(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
    ): QueryAdmission =
        (shape ?: queryShapes.shapeOf(table, cacheKey))?.admission ?: QueryAdmission.EVERY_ROW

    /**
     * The overlay for a query with [admission], de-duplicating by key the rows the server may
     * already hold.
     */
    private fun overlayFor(
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
        bodyOf: (MutationRow) -> JsonElement = { mutation ->
            json.parseToJsonElement(mutation.bodyJson)
        },
    ): MutationOverlay =
        MutationOverlay(
            json = json,
            admission = admission,
            primaryKeys = primaryKeys,
            mayAlreadyHold = { mutation -> isSyncedOrUnconfirmed(mutation, unconfirmedSends) },
            onMutationFailure = ::logSkippedOverlay,
            bodyOf = bodyOf,
        )

    /**
     * The overlay a patch runs for a query with [admission]: the server holds every row the write
     * made, so a row a refresh already holds is not added twice, and an upsert row with no key to
     * place it by is left out, the query marked stale instead.
     *
     * @param batch the writes' bodies and filters, read once for every query.
     * @param keyed see [MutationOverlay]; only from [keyedUpdatesOf].
     */
    private fun patchOverlayFor(
        admission: QueryAdmission,
        batch: PatchWrites? = null,
        keyed: KeyedUpdates? = null,
    ): MutationOverlay =
        if (batch == null) {
            MutationOverlay(
                json = json,
                admission = admission,
                primaryKeys = primaryKeys,
                mayAlreadyHold = { true },
                placesKeylessUpsertRows = false,
                onMutationFailure = ::logSkippedOverlay,
            )
        } else {
            MutationOverlay(
                json = json,
                admission = admission,
                primaryKeys = primaryKeys,
                mayAlreadyHold = { true },
                placesKeylessUpsertRows = false,
                onMutationFailure = ::logSkippedOverlay,
                bodyOf = batch::bodyOf,
                filterFor = batch::filterOf,
                keyed = keyed,
            )
        }

    /**
     * One patch's writes, each body parsed and each filter decoded once for all the queries it is
     * overlaid on. Keyed by the write's id, which a patch draws fresh for every write.
     */
    private inner class PatchWrites {
        private val bodies = HashMap<Long, JsonElement>()
        private val filters = HashMap<Long, FilterDescriptor?>()

        /** [write]'s body, parsed; throws as parsing it does. */
        fun bodyOf(write: MutationRow): JsonElement =
            bodies.getOrPut(write.id) { json.parseToJsonElement(write.bodyJson) }

        /** [write]'s filter, or `null` when it has none that reads. */
        fun filterOf(write: MutationRow): FilterDescriptor? {
            if (write.id in filters) return filters[write.id]
            return decodeFilterOrNull(write.filterJson, json).also { filters[write.id] = it }
        }

        /** An UPDATE's body; `null` when it is not a JSON object. */
        fun patchOf(write: MutationRow): JsonObject? =
            try {
                bodyOf(write) as? JsonObject
            } catch (_: IllegalArgumentException) {
                null
            }
    }

    /**
     * [writes] as [KeyedUpdates] when each is an UPDATE naming one key under [keyColumns]
     * ([keyNamedBy]) and `eq` on the key is decided by content ([equalityDecidedByContent]) for
     * every value involved: the keys they name or set, and every row [parsed] holds. `null`
     * otherwise.
     */
    private fun keyedUpdatesOf(
        writes: List<MutationRow>,
        keyColumns: List<String>,
        parsed: List<Pair<CachedQueryRow, ParsedPayload>>,
        batch: PatchWrites,
    ): KeyedUpdates? {
        if (keyColumns.isEmpty()) return null
        val keys = HashMap<Long, List<String>>()
        val named = writes.map { write ->
            if (write.operation != MutationOperation.UPDATE) return null
            val filter = batch.filterOf(write) ?: return null
            keys[write.id] = keyNamedBy(filter, keyColumns) ?: return null
            filter.conditions.associate { condition -> condition.column to condition.value }
        }
        val patches = writes.map { write -> batch.patchOf(write) ?: return null }
        val decided = keyColumns.all { column ->
            val values = sequence {
                named.forEach { values -> yield(JsonPrimitive(values.getValue(column))) }
                patches.forEach { patch -> patch[column]?.let { value -> yield(value) } }
                parsed.forEach { (_, payload) ->
                    payload.rows?.forEach { element ->
                        if (element is JsonObject) yield(element[column])
                    }
                }
            }
            equalityDecidedByContent(values, asUuid = true)
        }
        return if (decided) KeyedUpdates(keyColumns, keys) else null
    }

    /** Whether a row, with its key, may matter to [keyed]: it has one of the keys they name. */
    private fun keyedUpdatesReach(keyed: KeyedUpdates): (JsonObject, List<String>) -> Boolean {
        val keys = keyed.keys.values.toHashSet()
        return { _, key -> key in keys }
    }

    /** A new id for a write a patch overlays, so a failure is logged once per write. */
    private fun nextPatchId(): Long = patchIds.decrementAndFetch()

    /**
     * Whether the server may already hold [mutation]'s rows — it replayed, or its send may have
     * reached the server — so a payload's row with the same key may be the mutation's own.
     */
    private fun isSyncedOrUnconfirmed(mutation: MutationRow, unconfirmedSends: Set<Long>): Boolean =
        mutation.syncedAtMs != null || mutation.id in unconfirmedSends

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
            val parsed = cached.map { row -> row to parsedPayloadOf(row) }
            val shapes = queryShapes.shapesOf(table)
            val keyColumns = primaryKeys.of(table)
            val batch = PatchWrites()
            // UPDATEs naming one key each, over keys `eq` decides by content: each reaches exactly
            // the rows with its key, found by it
            val keyed = keyedUpdatesOf(writes, keyColumns, parsed, batch)
            val mayMatter =
                (if (keyed != null) keyedUpdatesReach(keyed)
                else rowsThatMayMatter(table, writes, unknownMatters = true)) ?: { _, _ -> false }
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
                val overlay = patchOverlayFor(target.admission, batch, keyed)
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
     * A table's cached rows, one per key from the most recently fetched payload holding it, with
     * the [Projection] each was read with. A query is given only copies [mayMatter] keeps, and only
     * keys some copy of which may matter are indexed, so [targetFor] costs a query's rows and
     * those.
     *
     * @param keyColumns the table's key columns; rows without them are left out.
     * @param mayMatter whether a row, with its key, may matter to the writes at hand; see
     *   [rowsThatMayMatter]. It is asked of the copy a query would be given, never of another.
     */
    private class KnownRows(
        parsed: List<Pair<CachedQueryRow, ParsedPayload>>,
        shapes: Map<String, QueryShape?>,
        private val keyColumns: List<String>,
        mayMatter: (JsonObject, List<String>) -> Boolean,
    ) {
        /**
         * A row, the query that holds it, its projection (`null` when that is not known), and
         * whether it [matters] to the writes at hand.
         */
        private class Held(
            val cacheKey: String,
            val projection: Projection?,
            val row: JsonObject,
            val matters: Boolean,
        )

        /**
         * Each indexed key's holders, most recently fetched first, keys in the order they first
         * appear.
         */
        private val byKey = LinkedHashMap<List<String>, MutableList<Held>>()

        /** The indexed keys held under each projection, in the order they first appear. */
        private val keysByProjection = HashMap<Projection, LinkedHashSet<List<String>>>()

        init {
            // Which copy a query is given depends on the query, so a key is indexed, with every
            // copy, when any may matter; targetFor keeps a query's copy only if that one matters
            val newestFirst = parsed.sortedByDescending { (row, _) -> fetchedAtOf(row.updatedAt) }
            val indexed = HashSet<List<String>>()
            newestFirst.forEach { (_, payload) ->
                forEachKeyedRow(payload) { key, row -> if (mayMatter(row, key)) indexed += key }
            }
            if (indexed.isNotEmpty()) {
                newestFirst.forEach { (row, payload) ->
                    val projection = shapes[row.cacheKey]?.let(::projectionOf)
                    forEachKeyedRow(payload) { key, candidate ->
                        if (key !in indexed) return@forEachKeyedRow
                        byKey.getOrPut(key) { mutableListOf() } +=
                            Held(row.cacheKey, projection, candidate, mayMatter(candidate, key))
                        if (projection != null)
                            keysByProjection.getOrPut(projection) { LinkedHashSet() } += key
                    }
                }
            }
        }

        /** Each row of [payload] that carries the key, with it. */
        private inline fun forEachKeyedRow(
            payload: ParsedPayload,
            action: (List<String>, JsonObject) -> Unit,
        ) {
            val keys = payload.keysFor(keyColumns)
            payload.rows?.forEachIndexed { index, element ->
                val key = keys[index] ?: return@forEachIndexed
                val row = element as? JsonObject ?: return@forEachIndexed
                action(key, row)
            }
        }

        /**
         * What the patch of the query cached under [cacheKey], holding [rows], may draw on.
         *
         * @param rowKeys [rows]' keys when already read; see [ParsedPayload.keysFor].
         */
        fun targetFor(
            cacheKey: String,
            shape: QueryShape?,
            rows: List<JsonElement>,
            rowKeys: List<List<String>?>? = null,
        ): PatchTarget {
            val projection = shape?.let(::projectionOf)
            val own = HashSet<List<String>>()
            (rowKeys
                    ?: rows.map { element ->
                        (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
                    })
                .forEach { key -> if (key != null) own += key }
            val same = mutableListOf<JsonObject>()
            // Keys another query holds with this projection: given from there, or not at all
            val sameKeys = HashSet<List<String>>()
            if (projection != null) {
                keysByProjection[projection]?.forEach { key ->
                    if (key in own) return@forEach
                    val holder =
                        byKey.getValue(key).firstOrNull { held ->
                            held.cacheKey != cacheKey && held.projection == projection
                        } ?: return@forEach
                    sameKeys += key
                    if (holder.matters) same += holder.row
                }
            }
            val other = mutableListOf<JsonObject>()
            byKey.forEach { (key, holders) ->
                if (key in own || key in sameKeys) return@forEach
                val holder =
                    holders.firstOrNull { held ->
                        held.cacheKey != cacheKey &&
                            (projection == null || held.projection != projection)
                    } ?: return@forEach
                if (holder.matters) other += holder.row
            }
            return PatchTarget(
                rows,
                shape,
                sameSelectRows = same,
                otherSelectRows = other,
                keyColumns = keyColumns.toSet(),
            )
        }
    }

    /**
     * Whether a row other queries of [table] hold may matter to [mutations]: an UPSERT's row shares
     * its conflict key, or an UPDATE's filter matches it. No mutation here reads or changes any
     * other row held aside, so leaving those out changes no overlay and no stale mark. `null` when
     * no row can matter.
     *
     * @param unknownMatters whether a row an UPDATE's filter cannot judge matters too: to a patch,
     *   which marks stale what it cannot judge, it does; to the read overlay, which moves only rows
     *   a filter matches, it does not.
     */
    private fun rowsThatMayMatter(
        table: String,
        mutations: List<MutationRow>,
        unknownMatters: Boolean,
    ): ((JsonObject, List<String>) -> Boolean)? {
        val keyColumns = primaryKeys.of(table)
        val uuidColumns = keyColumns.toSet()
        val filters =
            mutations
                .filter { mutation -> mutation.operation == MutationOperation.UPDATE }
                .mapNotNull { mutation -> decodeFilterOrNull(mutation.filterJson, json) }
                .map { filter -> RowFilter(filter, uuidColumns) }
                .filter { filter -> unknownMatters || filter.canMatch }
        val reached =
            if (unknownMatters) setOf(RowMatch.MATCHES, RowMatch.UNKNOWN)
            else setOf(RowMatch.MATCHES)
        // The keys each set of conflict columns upserts
        val upserted = HashMap<List<String>, HashSet<List<String>>>()
        mutations
            .filter { mutation -> mutation.operation == MutationOperation.UPSERT }
            .forEach { mutation ->
                val columns = conflictColumnsOf(mutation)
                val keys = upserted.getOrPut(columns) { HashSet() }
                upsertRowsOf(mutation).forEach { row -> keyOf(row, columns)?.let(keys::add) }
            }
        // Upserts on the key look a row up by the key it already has; the rest read the row
        val byKey = upserted.remove(keyColumns)?.takeIf { keys -> keys.isNotEmpty() }
        val byOtherColumns = upserted.entries.filter { (_, keys) -> keys.isNotEmpty() }
        if (byKey == null && byOtherColumns.isEmpty() && filters.isEmpty()) return null
        return { row, key ->
            (byKey != null && key in byKey) ||
                byOtherColumns.any { (columns, keys) -> keyOf(row, columns) in keys } ||
                filters.any { filter -> filter.match(row) in reached }
        }
    }

    /**
     * [bringing], and each other UPDATE or UPSERT of [mutations] that sets a column one of those
     * reads (an UPSERT's conflict columns, an UPDATE's filter columns), until no more join: only
     * these can change a row held aside in a way the overlay then reads.
     */
    private fun mutationsThatReach(
        bringing: List<MutationRow>,
        mutations: List<MutationRow>,
    ): List<MutationRow> {
        val reaching = bringing.toMutableList()
        val read = HashSet<String>()
        var readsEveryColumn = false
        fun noteWhatItReads(mutation: MutationRow) {
            when (mutation.operation) {
                MutationOperation.UPSERT ->
                    conflictColumnsOf(mutation).forEach { column -> read += jsonNameOf(column) }
                MutationOperation.UPDATE ->
                    decodeFilterOrNull(mutation.filterJson, json)?.conditions?.forEach { condition
                        ->
                        if (isPlainColumn(condition.column)) read += jsonNameOf(condition.column)
                        else readsEveryColumn = true
                    }
            }
        }
        reaching.forEach(::noteWhatItReads)
        // Both change rows held aside: an UPSERT merges into the one with its key
        var rest = mutations.filter { mutation ->
            (mutation.operation == MutationOperation.UPDATE ||
                mutation.operation == MutationOperation.UPSERT) && mutation !in reaching
        }
        while (rest.isNotEmpty()) {
            // Read without parsing: a column the body sets appears as its quoted name
            val joining = rest.filter { mutation ->
                readsEveryColumn || read.any { name -> name in mutation.bodyJson }
            }
            if (joining.isEmpty()) break
            reaching += joining
            joining.forEach(::noteWhatItReads)
            rest = rest - joining.toSet()
        }
        return reaching
    }

    /**
     * [row]'s rows, parsed once for each text the payload holds. What is kept is dropped whole when
     * it would pass [PARSED_PAYLOAD_TEXT_KEPT] characters, and when the cache is emptied.
     */
    private fun parsedPayloadOf(row: CachedQueryRow): ParsedPayload =
        parsedTextOf(row.table, row.cacheKey, row.payload)

    /** [parsedPayloadOf] for [text], the rows the query cached under [cacheKey] shows. */
    private fun parsedTextOf(table: String, cacheKey: String, text: String): ParsedPayload {
        val key = table to cacheKey
        parsedPayloads.value[key]?.let { kept -> if (kept.text == text) return kept }
        val parsed = ParsedPayload(text, payloadRowsOf(json, text))
        if (text.length <= PARSED_PAYLOAD_TEXT_KEPT) {
            parsedPayloads.update { kept ->
                val total = kept.values.sumOf { held -> held.text.length } + text.length
                (if (total > PARSED_PAYLOAD_TEXT_KEPT) emptyMap() else kept) + (key to parsed)
            }
        }
        return parsed
    }

    /** An UPSERT's body rows; none when the body is not JSON. */
    private fun upsertRowsOf(mutation: MutationRow): List<JsonObject> =
        try {
            when (val body = json.parseToJsonElement(mutation.bodyJson)) {
                is JsonArray -> body.filterIsInstance<JsonObject>()
                is JsonObject -> listOf(body)
                else -> emptyList()
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

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
            val overlay = patchOverlayFor(admission, batch, keyed)
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

    /* ──────────────────────── Size-based eviction (DB) ──────────────────────── */

    /**
     * Evict the oldest cached SELECT payloads until the cache is back within
     * [QueryCacheLimits.maxPayloadBytes].
     *
     * Size is measured **logically** via [QueryCacheDao.totalPayloadBytes] (summed payload length),
     * never the on-disk database file size. SQLite returns pages freed by `DELETE` to its internal
     * freelist rather than to the OS, so the file never shrinks after eviction; driving the loop
     * off file size therefore empties the cache and then spins forever holding [evictionMutex].
     * Summed payload length decreases as rows are deleted, so it terminates.
     *
     * Eviction is **triggered** once the cache exceeds [QueryCacheLimits.maxPayloadBytes] and then
     * **drains** down to a hysteresis floor of `max - evictionHeadroomBytes` (coerced to the
     * ceiling when [QueryCacheLimits.evictionHeadroomBytes] is non-positive or meets/exceeds the
     * ceiling). Draining a little below the ceiling keeps a burst of writes that crosses the limit
     * from re-triggering a pass on every subsequent write.
     *
     * Termination is guaranteed three independent ways:
     * 1. the `currentBytes > evictionFloorBytes` loop condition (the floor is never above the
     *    ceiling),
     * 2. a progress guard that stops the instant a delete batch removes zero rows (the cache is
     *    empty, or nothing more can be evicted), and
     * 3. a hard [MAX_EVICTION_ITERATIONS] cap as a final belt-and-braces backstop.
     *
     * The loop holds [evictionMutex] via [withLock] so concurrent writers serialize rather than
     * racing, and the lock is always released even on cancellation. Does nothing when size-based
     * eviction is disabled ([QueryCacheLimits.maxPayloadBytes] is `null`).
     *
     * Reclaiming the on-disk pages freed by these deletes (`VACUUM`/`auto_vacuum`) is intentionally
     * out of scope: cache correctness doesn't depend on returning bytes to the filesystem.
     */
    internal suspend fun evictOverBudget() {
        val max = queryCacheLimits.maxPayloadBytes ?: return

        // Cheap pre-check outside the lock: eviction triggers only once the cache exceeds the
        // ceiling, so skip acquiring the mutex when already within budget (the common case even
        // on the writes that reach enforcement).
        if (queryCacheDao.totalPayloadBytes() <= max) return

        // Once triggered, drain a little below the ceiling to this hysteresis floor so a burst of
        // writes crossing the limit doesn't re-trigger a pass on every subsequent write. When
        // evictionHeadroomBytes is non-positive or meets/exceeds the ceiling the headroom coerces
        // to
        // zero and the floor is the ceiling, keeping small-cache configs evicting fully.
        val evictionFloorBytes =
            if (queryCacheLimits.evictionHeadroomBytes in 1L until max)
                max - queryCacheLimits.evictionHeadroomBytes
            else max

        evictionMutex.withLock {
            var iterations = 0
            var currentBytes = queryCacheDao.totalPayloadBytes()
            while (currentBytes > evictionFloorBytes && iterations < MAX_EVICTION_ITERATIONS) {
                // Coerce to at least one row so a misconfigured non-positive evictionBatchSize
                // can't stall eviction — a zero-row batch trips the progress guard immediately
                // and would otherwise leave the cache permanently over budget.
                val deleted =
                    queryCacheDao.deleteOldestPayloadRows(
                        limit = queryCacheLimits.evictionBatchSize.coerceAtLeast(1),
                        // A payload a live reader shows stays, or the reader would drop to no data
                        keptCacheKeys = allLiveCacheKeys().take(MAX_KEPT_LIVE_CACHE_KEYS),
                    )
                // Progress guard: no rows removed means the cache can't shrink further, so stop
                // instead of re-measuring the same over-limit size forever.
                if (deleted == 0) break
                iterations++
                currentBytes = queryCacheDao.totalPayloadBytes()
            }
            if (iterations > 0) queryShapes.dropOrphans()
        }
    }

    private companion object {
        /**
         * Run size enforcement once every this many [addPayload] writes. A cheap periodic backstop
         * that bounds cache growth from many small writes without measuring on every write.
         */
        const val ENFORCEMENT_WRITE_INTERVAL = 32

        /**
         * A single payload of at least this many characters triggers an immediate enforcement pass
         * regardless of the write counter, so one oversized write can't overshoot the budget before
         * the periodic pass runs.
         */
        const val ENFORCEMENT_LARGE_PAYLOAD_CHARS = 4 * 1024

        /**
         * Hard upper bound on eviction-loop iterations. The progress guard already terminates the
         * loop on real input; this guarantees termination even against a degenerate store whose
         * measured size never drops despite reported deletions.
         */
        const val MAX_EVICTION_ITERATIONS = 1_000

        /** Live signatures eviction passes over at most, within SQLite's 999 bound variables. */
        const val MAX_KEPT_LIVE_CACHE_KEYS = 500

        /** The characters of payload text whose parsed rows are kept, about a megabyte of text. */
        const val PARSED_PAYLOAD_TEXT_KEPT = 1_000_000

        /**
         * The most rows a table's key index reads, beyond which a read finds rows held elsewhere by
         * reading every payload instead.
         */
        const val MAX_KEY_INDEXED_ROWS = 100_000
    }
}

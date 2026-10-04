// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What [QueryCache.cacheResponse] did with a response, so a reader can tell why it was refused. */
internal enum class CacheWriteOutcome {
    /** The payload was written to the object cache. */
    WRITTEN,

    /**
     * Its request started before the table's last local write, so it would roll that write back.
     * The caller fetches again: a request issued now starts after the write.
     */
    REFUSED_STALE,

    /**
     * An empty answer not known to be the server's real one (the request was not signed in): over
     * cached rows it would erase them, and in a cold slot it would be served as the truth.
     */
    REFUSED_EMPTY,

    /**
     * The response came back under a session the read's trust policy does not take as speaking for
     * the current identity, such as one still restoring, so nothing of it was written.
     */
    REFUSED_UNTRUSTED,

    /**
     * Its request started before local data was cleared, so it belongs to the identity that was
     * cleared. Unlike [REFUSED_STALE] the caller must not fetch again: a request made during the
     * clear may still carry the old session's token.
     */
    REFUSED_AFTER_CLEAR,

    /**
     * The cache belongs to another identity than the current session's, or was dropped for a new
     * one after the request started; see [LocalData.dropCacheFor]. The caller may ask again, once
     * the cache is the current identity's.
     */
    REFUSED_OTHER_OWNER,
}

/**
 * A payload's [text] and its [rows], `null` when it is not a JSON array; parsed once and shared by
 * the reads and patches that need it.
 */
internal class ParsedPayload(val text: String, val rows: List<JsonElement>?) {

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
 * The cached queries: a response written behind its guards, a payload read, size eviction, each
 * query's shape record and parsed rows. Owns the table write locks ([TableWriteLocks]), never taken
 * under the queue or clear lock, and the eviction lock, taken with no other held.
 *
 * @param mutationQueue whose lock and sync sequence a written response is stamped under.
 * @param allLiveCacheKeys the queries with a live reader, which eviction keeps.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class QueryCache(
    private val queryCacheDao: QueryCacheDao,
    private val queryCacheLimits: QueryCacheLimits,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val freshness: CacheFreshness,
    private val ownership: CacheOwnership,
    private val mutationQueue: MutationQueue,
    private val tableWriteLocks: TableWriteLocks,
    private val allLiveCacheKeys: () -> Set<String>,
) {

    /** Each cached query's shape, which decides the rows it admits; see [QueryShapeRecords]. */
    val queryShapes: QueryShapeRecords = QueryShapeRecords(queryCacheDao, json)

    /** Serializes eviction passes. */
    private val evictionMutex = Mutex()

    /**
     * Payloads' rows as parsed, by table and cache key, each with the text it was parsed from, so
     * an unchanged payload is not parsed again; see [parsedPayloadOf].
     */
    private val parsedPayloads =
        AtomicReference<Map<Pair<String, String>, ParsedPayload>>(emptyMap())

    /** Writes since the last size check; see [shouldEnforceSizeAfterWrite]. */
    private val writeCounter = AtomicInt(0)

    /** [table]'s write lock; see [TableWriteLocks]. */
    suspend fun tableWriteLock(table: String): Mutex = tableWriteLocks.of(table)

    /**
     * Writes a network SELECT's [payload] for [table]/[cacheKey] unless a guard refuses it (see
     * [CacheWriteOutcome]); with a start tick it holds the mutations synced so far. The guards and
     * the write are one step under the table's lock, the queue lock and the clear lock, in order.
     *
     * @param authoritativeEmpty whether an empty [payload] is the server's real answer (signed in).
     * @param requestStartedAtTick the request's start tick, or `null` for none: never stale.
     * @param shape the query's shape, recorded with the payload before the lock is released.
     */
    suspend fun cacheResponse(
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
        val ownedByCurrentIdentity = ownership.cacheTakesWritesOfCurrentIdentity()
        // The guards and the write are one critical section under the table's write lock, so a
        // response is judged against the watermark *after* any overlay in progress has recorded
        // its write, and never lands between an overlay's snapshot and its write-back (where the
        // overlay would roll it straight back out) — see [TableWriteLocks].
        val outcome =
            tableWriteLocks.of(table).withLock {
                mutationQueue.holdingQueueLock {
                    freshness.holdingClearLock {
                        when {
                            // The cache was wiped for an identity change while this request was
                            // in flight. Its rows belong to the outgoing account; writing them
                            // here undoes the clear.
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
     * Whether a size check should follow writing [payload]: when it is large
     * ([ENFORCEMENT_LARGE_PAYLOAD_CHARS]), or every [ENFORCEMENT_WRITE_INTERVAL]th write, so the
     * summing scan stays off the per-write path. Never with eviction off.
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
     * The payload cached for [table]/[cacheKey] with the time it was written, or `null` when none
     * is, or the cache is not the current identity's.
     */
    suspend fun getPayloadWithTimestamp(table: String, cacheKey: String): Pair<String, Long>? {
        if (isReservedTable(table) || !ownership.cacheServesCurrentIdentity()) return null
        val row = queryCacheDao.get(table, cacheKey) ?: return null
        return row.payload to row.updatedAt
    }

    /**
     * [row]'s rows, parsed once for each text the payload holds. What is kept is dropped whole when
     * it would pass [PARSED_PAYLOAD_TEXT_KEPT] characters, and when the cache is emptied.
     */
    fun parsedPayloadOf(row: CachedQueryRow): ParsedPayload =
        parsedTextOf(row.table, row.cacheKey, row.payload)

    /** [parsedPayloadOf] for [text], the rows the query cached under [cacheKey] shows. */
    fun parsedTextOf(table: String, cacheKey: String, text: String): ParsedPayload {
        val key = table to cacheKey
        parsedPayloads.load()[key]?.let { kept -> if (kept.text == text) return kept }
        val parsed = ParsedPayload(text, payloadRowsOf(json, text))
        if (text.length <= PARSED_PAYLOAD_TEXT_KEPT) {
            parsedPayloads.update { kept ->
                val total = kept.values.sumOf { held -> held.text.length } + text.length
                (if (total > PARSED_PAYLOAD_TEXT_KEPT) emptyMap() else kept) + (key to parsed)
            }
        }
        return parsed
    }

    /** Forgets every parsed payload, after the cache was emptied. */
    fun forgetParsedPayloads() {
        parsedPayloads.store(emptyMap())
    }

    /**
     * Evicts the payloads fetched longest ago, never one a live reader shows, until the summed
     * payload length is under [QueryCacheLimits.maxPayloadBytes] by its headroom. The length, not
     * the file size, which never shrinks after a delete; a pass also stops when a batch removes
     * nothing, or after [MAX_EVICTION_ITERATIONS]. Does nothing with eviction off.
     */
    suspend fun evictOverBudget() {
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
        /** Run a size check once every this many written responses. */
        const val ENFORCEMENT_WRITE_INTERVAL = 32

        /** A payload of at least this many characters is followed by a size check at once. */
        const val ENFORCEMENT_LARGE_PAYLOAD_CHARS = 4 * 1024

        /** The most batches one eviction pass deletes, whatever the size it measures. */
        const val MAX_EVICTION_ITERATIONS = 1_000

        /** Live signatures eviction passes over at most, within SQLite's 999 bound variables. */
        const val MAX_KEPT_LIVE_CACHE_KEYS = 500

        /** The characters of payload text whose parsed rows are kept, about a megabyte of text. */
        const val PARSED_PAYLOAD_TEXT_KEPT = 1_000_000
    }
}

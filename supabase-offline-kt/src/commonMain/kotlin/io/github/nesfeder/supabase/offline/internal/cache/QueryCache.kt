// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadLength
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.db.staleStampOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowWindow
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
     * Its request started before the table's last local write, so it would roll that write back, or
     * before a server change the cache cannot lay over it exactly. The caller fetches again: a
     * request issued now starts after the write.
     */
    REFUSED_STALE,

    /**
     * An empty answer not known to be the server's real one (the request was not signed in): over
     * cached rows it would erase them, and in a cold slot it would be served as the truth.
     */
    REFUSED_EMPTY,

    /**
     * The response came back under a session the read's trust policy does not take as speaking for
     * the current identity, such as one still restoring, or its body is not a JSON array of rows (a
     * proxy's block page, a server's fallback page), so nothing of it was written.
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
 * What [QueryCache.cacheResponseReporting] did with a response.
 *
 * @property outcome whether it was written, or why not.
 * @property rebasedPayload the rows written in its place when server changes patched in after its
 *   request left changed them ([ResponseRebase]); `null` when it was written as it came, or not
 *   written.
 * @property staleStamp the stale stamp it was written with when those changes could not be laid
 *   over it exactly ([Rebased.isExact]), so the rows are not the server's answer; `null` when it
 *   was written fresh, or not written.
 */
internal class ResponseWrite(
    val outcome: CacheWriteOutcome,
    val rebasedPayload: String? = null,
    val staleStamp: Long? = null,
)

/**
 * Lays the server changes a response's request left before over its rows, as patching them in would
 * have had the response landed first; [CachePatcher] does, with the patch's own overlay.
 */
internal fun interface ResponseRebase {

    /**
     * [answer], the rows a response holds for [table]/[cacheKey], with [writes], whole-row upserts
     * and deletes by key, laid over them in order, and whether that is what the server would answer
     * now. Caller holds [table]'s write lock.
     *
     * @param shape the query's shape; `null` reads the one recorded for [cacheKey].
     * @param window how the query's request orders its rows and whether it reads a window of them.
     */
    suspend fun rebase(
        table: String,
        cacheKey: String,
        shape: QueryShape?,
        window: RowWindow,
        answer: ParsedPayload,
        writes: List<MutationRow>,
    ): Rebased
}

/**
 * What [ResponseRebase.rebase] made of an answer.
 *
 * @property payload what is written in the answer's place: the answer with the changes laid over it
 *   (the answer itself when they change none of its rows), or, when they may leave the query wrong,
 *   the answer as it came without the rows they took out.
 * @property isExact whether [payload] is what the server would answer now: every change removes a
 *   row by its key, or changes one the answer holds in place, and moves no row the server would
 *   place elsewhere. Not when a change may leave the query wrong, shows a row the answer does not
 *   hold (one the server may no longer show this user, or would place elsewhere in its order), or
 *   would move a row in an ordered list or let another row into a window.
 */
internal class Rebased(val payload: ParsedPayload, val isExact: Boolean)

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
 * @param backgroundScope where an eviction pass runs, after the response that asked for it is
 *   written: the client's own scope, which closing the client cancels.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class QueryCache(
    private val queryCacheDao: PayloadWriteCountingDao,
    private val queryCacheLimits: QueryCacheLimits,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val freshness: CacheFreshness,
    private val ownership: CacheOwnership,
    private val mutationQueue: MutationQueue,
    private val tableWriteLocks: TableWriteLocks,
    private val allLiveCacheKeys: () -> Set<String>,
    private val backgroundScope: CoroutineScope,
) {

    /** Each cached query's shape, which decides the rows it admits; see [QueryShapeRecords]. */
    val queryShapes: QueryShapeRecords = QueryShapeRecords(queryCacheDao, json)

    /** Serializes eviction passes. */
    private val evictionMutex = Mutex()

    /** Whether a pass has been asked for and not yet started; see [enforceSizeInBackground]. */
    private val passWanted = AtomicBoolean(false)

    /** Whether a pass runner is queued or running in [backgroundScope]; see [launchPassRunner]. */
    private val passQueued = AtomicBoolean(false)

    /**
     * The summed length of the cached payloads as this process follows it: what the last pass read,
     * moved by every payload written, patched or evicted since. It is never read before a pass
     * needs it; see [payloadBytesWritten].
     */
    private val payloadBytes = AtomicLong(0L)

    /**
     * Whether [payloadBytes] has been read from the table since the process started, or since the
     * cache was last emptied; until it has, the next written response starts a pass.
     */
    @Volatile private var payloadBytesMeasured = false

    /** How many times the cache was emptied; a pass that saw one trusts no count it computed. */
    private val emptyings = AtomicInt(0)

    /** The parsed payloads kept, the least recently used going first; see [parsedPayloadOf]. */
    private val parsedPayloads = AtomicReference(KeptPayloads(emptyMap(), 0L))

    /** Stamps each use of a kept payload, so the least recently used is known. */
    private val uses = AtomicLong(0L)

    /** How many payload texts were parsed; see [payloadParses]. */
    private val parses = AtomicInt(0)

    /** How many payload texts have been parsed so far: a test's measure of what is kept. */
    val payloadParses: Int
        get() = parses.load()

    /**
     * Where a response has the server changes its request left before laid over it: nowhere until
     * [rebaseResponsesThrough] is called, and a response that needs it is refused as stale.
     */
    private val responseRebase = AtomicReference<ResponseRebase?>(null)

    /** Lays server changes over every later response through [rebase]; called once, as built. */
    fun rebaseResponsesThrough(rebase: ResponseRebase) {
        responseRebase.store(rebase)
    }

    /** [table]'s write lock; see [TableWriteLocks]. */
    suspend fun tableWriteLock(table: String): Mutex = tableWriteLocks.of(table)

    /** [cacheResponseReporting]'s outcome alone. */
    suspend fun cacheResponse(
        table: String,
        cacheKey: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
        window: RowWindow = RowWindow.UNORDERED,
    ): CacheWriteOutcome =
        cacheResponseReporting(
                table,
                cacheKey,
                payload,
                authoritativeEmpty,
                requestStartedAtTick,
                isTrusted,
                shape,
                window,
            )
            .outcome

    /**
     * Writes a network SELECT's [payload] for [table]/[cacheKey] unless a guard refuses it (see
     * [CacheWriteOutcome]); with a start tick it holds the mutations synced so far. The server
     * changes patched into the table after the request started ([CacheFreshness.serverWritesSince])
     * are laid over it first, so a row one deleted or changed is not brought back. When they cannot
     * be laid over it exactly ([Rebased.isExact]), it is refused as stale, or on [lastAsk] written
     * stale-stamped, never as fresh. The guards and the write are one step under the table's lock,
     * the queue lock and the clear lock, in order. Only a JSON array is written: a blank body
     * stands for an empty list and is stored as `[]`, and a body of any other shape is refused as
     * untrusted. A written payload may start an eviction pass, in [backgroundScope] after this
     * returns.
     *
     * @param authoritativeEmpty whether an empty [payload] is the server's real answer (signed in).
     * @param requestStartedAtTick the request's start tick, or `null` for none: never stale.
     * @param shape the query's shape, recorded with the payload before the lock is released.
     * @param window how the query's request orders its rows and whether it reads a window of them.
     * @param lastAsk whether the caller asks the server no more after this response, so a table
     *   that changes every round trip still has its reads settle.
     */
    suspend fun cacheResponseReporting(
        table: String,
        cacheKey: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
        window: RowWindow = RowWindow.UNORDERED,
        lastAsk: Boolean = false,
    ): ResponseWrite {
        requireUserTable(table)
        // A blank body stands for an empty list and is stored as one, so a read is served a list
        val rows = if (payload.isBlank()) EMPTY_JSON_ARRAY else payload
        // Before the locks: telling the identity may read the stored session. A drop that lands
        // between here and the write is caught by its tick below.
        val ownedByCurrentIdentity = ownership.cacheTakesWritesOfCurrentIdentity()
        var replacedLength = 0L
        var rebased: Rebased? = null
        var staleStamp: Long? = null
        // The guards and the write are one critical section under the table's write lock, so a
        // response is judged against the watermark *after* any overlay in progress has recorded
        // its write, and never lands between an overlay's snapshot and its write-back (where the
        // overlay would roll it straight back out) — see [TableWriteLocks].
        val outcome =
            tableWriteLocks.of(table).withLock {
                // Under the table's lock, which every server patch logs under, and before the
                // queue and clear locks: laying the changes over reads the table's other payloads
                val laidOver =
                    serverChangesLaidOver(
                        table,
                        cacheKey,
                        rows,
                        requestStartedAtTick,
                        shape,
                        window,
                    )
                rebased = laidOver
                val written = laidOver?.payload?.text ?: rows
                val inexact = laidOver?.isExact == false
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

                            // Not a list of rows: a block page or a fallback page at 200 would
                            // replace a list with a body no read decodes
                            !isTrusted || !isJsonArrayPayload(rows) ->
                                CacheWriteOutcome.REFUSED_UNTRUSTED

                            // A server change patched in after the request left that the cache
                            // cannot lay over these rows exactly: written, they would read as the
                            // server's answer. A request sent now starts after it; on the last ask
                            // they are written stale-stamped below instead
                            inexact && !lastAsk -> CacheWriteOutcome.REFUSED_STALE

                            // Nothing about this emptiness is known to describe the signed-in
                            // user's data, so it is never written — not over cached rows, and not
                            // into a cold or already-empty slot where it would become the answer
                            // every later cache-first read is served.
                            !authoritativeEmpty && isEmptyJsonArrayPayload(rows) ->
                                CacheWriteOutcome.REFUSED_EMPTY

                            else -> {
                                // The length this response replaces, so the count moves by the
                                // difference; one key's row, read before it is written over
                                replacedLength =
                                    queryCacheDao.payloadLengthOf(table, cacheKey) ?: 0L
                                // A response whose request started after the table's last local
                                // write holds every mutation synced so far; a payload with no
                                // request behind it holds none for certain. Server changes laid
                                // over it inexactly leave it stale: the next read asks again
                                queryCacheDao.upsert(
                                    CachedQueryRow(
                                        table = table,
                                        cacheKey = cacheKey,
                                        payload = written,
                                        updatedAt =
                                            if (inexact)
                                                staleStampOf(nowMillis()).also { staleStamp = it }
                                            else nowMillis(),
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

        val laid = rebased?.payload?.takeIf { payload -> payload.rows != null }
        if (outcome == CacheWriteOutcome.WRITTEN) {
            // What the next read or patch reads, kept as parsed here so it parses nothing
            laid?.let { payload -> keepParsed(table, cacheKey, payload) }
            payloadBytesWritten((laid?.text ?: rows).length - replacedLength)
            // Off the read path: a pass takes its own lock and reads every payload's length
            if (sizeMayBeOverBound()) enforceSizeInBackground()
        }
        return ResponseWrite(
            outcome,
            rebasedPayload =
                laid?.text?.takeIf { text -> outcome == CacheWriteOutcome.WRITTEN && text != rows },
            staleStamp = staleStamp,
        )
    }

    /**
     * The server changes patched into [table] after [requestStartedAtTick], laid over [rows], an
     * answer for [cacheKey]; `null` when none were, and the answer is judged and written as it
     * came. Caller holds the table's write lock.
     */
    private suspend fun serverChangesLaidOver(
        table: String,
        cacheKey: String,
        rows: String,
        requestStartedAtTick: Long?,
        shape: QueryShape?,
        window: RowWindow,
    ): Rebased? {
        val writes = freshness.serverWritesSince(table, requestStartedAtTick)
        if (writes.isEmpty()) return null
        // Not a list of rows: one that is not even an array is refused as untrusted first
        val parsed = payloadRowsOf(json, rows)
        val rebase = responseRebase.load()
        if (parsed == null || rebase == null)
            return Rebased(ParsedPayload(rows, parsed), isExact = false)
        return rebase.rebase(table, cacheKey, shape, window, ParsedPayload(rows, parsed), writes)
    }

    /**
     * Moves the running count of cached payload bytes by [delta]: a written payload's length less
     * the length of the one it replaced, or less an evicted one's. The count is exact but for the
     * writes that land while a pass reads the table, which the next pass sets right: a pass sets
     * the count from what it read and what was written meanwhile, and a write the read already saw
     * is counted twice, over or under by what it changed.
     */
    fun payloadBytesWritten(delta: Long) {
        if (delta != 0L) payloadBytes.fetchAndAdd(delta)
    }

    /**
     * Whether the payloads may be over the bound: counted past it, or not yet read this process.
     * Never with eviction off.
     */
    private fun sizeMayBeOverBound(): Boolean {
        val max = queryCacheLimits.maxPayloadBytes ?: return false
        return !payloadBytesMeasured || payloadBytes.load() > max
    }

    /**
     * Asks for a pass, and runs it in [backgroundScope] unless a runner is queued or running
     * already: that one takes the request before it ends, or hands it to a new runner, so a write
     * that arrives during a pass still gets its pass. A failure in a pass stays in the scope.
     */
    private fun enforceSizeInBackground() {
        passWanted.store(true)
        launchPassRunner()
    }

    /**
     * Starts a runner for the pass asked for, unless none is or one is queued or running already. A
     * runner runs [evictOverBudget] once per request, taking the next request after each pass, so
     * writes during a pass are read in the pass after it; when live readers keep the cache over its
     * bound this still ends once the writes do.
     */
    private fun launchPassRunner() {
        if (!passWanted.load()) return
        if (!passQueued.compareAndSet(expectedValue = false, newValue = true)) return
        val runner = backgroundScope.launch {
            try {
                while (passWanted.exchange(false)) evictOverBudget()
            } finally {
                passQueued.store(false)
            }
            // A request that arrived after the last was taken and before the runner was marked
            // done would otherwise wait for the next response
            launchPassRunner()
        }
        // A scope canceled already, as a closed client's is, never runs the pass
        if (runner.isCancelled) passQueued.store(false)
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
     * [row]'s rows, parsed once for each text the payload holds and kept while the kept texts fit
     * in [QueryCacheLimits.parsedPayloadCharsKept] characters; past that the least recently used
     * goes first. A payload longer than the whole bound is parsed each time and keeps nothing out.
     * Everything is dropped when the cache is emptied.
     */
    fun parsedPayloadOf(row: CachedQueryRow): ParsedPayload =
        parsedTextOf(row.table, row.cacheKey, row.payload)

    /** [parsedPayloadOf] for [text], the rows the query cached under [cacheKey] shows. */
    fun parsedTextOf(table: String, cacheKey: String, text: String): ParsedPayload {
        val key = table to cacheKey
        parsedPayloads.load().byKey[key]?.let { kept ->
            if (kept.parsed.text == text) {
                kept.lastUse.store(uses.incrementAndFetch())
                return kept.parsed
            }
        }
        parses.incrementAndFetch()
        return ParsedPayload(text, payloadRowsOf(json, text)).also { parsed -> keep(key, parsed) }
    }

    /**
     * Keeps [parsed] as the rows of the payload a patch just wrote for [table]/[cacheKey], which it
     * made from the rows it holds, so the next read of the payload parses nothing; as
     * [parsedPayloadOf] keeps what it parsed.
     */
    fun keepParsed(table: String, cacheKey: String, parsed: ParsedPayload) {
        keep(table to cacheKey, parsed)
    }

    /** Keeps [parsed] under [key] within the bound, dropping the least recently used to fit. */
    private fun keep(key: Pair<String, String>, parsed: ParsedPayload) {
        val length = parsed.text.length.toLong()
        if (length > queryCacheLimits.parsedPayloadCharsKept) return
        val kept = KeptPayload(parsed, uses.incrementAndFetch())
        parsedPayloads.update { current -> current.with(key, kept, length) }
    }

    /**
     * These payloads with [kept] under [key], and with the least recently used dropped, never
     * [kept], until the texts fit in [QueryCacheLimits.parsedPayloadCharsKept]. A payload kept
     * under the key before is counted no longer.
     */
    private fun KeptPayloads.with(
        key: Pair<String, String>,
        kept: KeptPayload,
        length: Long,
    ): KeptPayloads {
        val bound = queryCacheLimits.parsedPayloadCharsKept
        var chars = chars - (byKey[key]?.parsed?.text?.length ?: 0) + length
        val entries = byKey.toMutableMap()
        entries[key] = kept
        if (chars > bound) {
            // The stamps read once each: readers keep stamping the kept payloads while this sorts
            val byAge =
                entries.entries
                    .map { (other, held) -> Triple(other, held, held.lastUse.load()) }
                    .sortedBy { (_, _, use) -> use }
            for ((other, held, _) in byAge) {
                if (chars <= bound) break
                if (other == key) continue
                entries.remove(other)
                chars -= held.parsed.text.length
            }
        }
        return KeptPayloads(entries, chars)
    }

    /**
     * Forgets every parsed payload and the count of payload bytes, after the cache was emptied: a
     * clear or a drop. The next written response reads the count again.
     */
    fun payloadsEmptied() {
        emptyings.incrementAndFetch()
        payloadBytes.store(0L)
        payloadBytesMeasured = false
        parsedPayloads.store(KeptPayloads(emptyMap(), 0L))
    }

    /**
     * Evicts the payloads fetched longest ago, never one of up to [MAX_KEPT_LIVE_CACHE_KEYS] a live
     * reader shows, until the summed payload length is under [QueryCacheLimits.maxPayloadBytes] by
     * its headroom. The length, not the file size, which never shrinks after a delete. A pass reads
     * every payload's length once, in eviction order, chooses the rows to delete, deletes them in
     * one transaction, and sets the running count from what it read; it reads again only when
     * writes during the pass left the count over the bound, or a chosen row was fetched again
     * meanwhile and left in place, at most [MAX_EVICTION_PASSES] times in all. Does nothing with
     * eviction off, and nothing but set the count when the payloads are within the bound.
     */
    suspend fun evictOverBudget() {
        val max = queryCacheLimits.maxPayloadBytes ?: return
        // Once over the bound, a pass drains to this floor below it, so a burst of writes crossing
        // the bound does not start a pass on every write. A headroom that is not positive, or not
        // below the bound, leaves the floor at the bound, so a small cache still evicts fully.
        val floor =
            if (queryCacheLimits.evictionHeadroomBytes in 1L until max)
                max - queryCacheLimits.evictionHeadroomBytes
            else max

        evictionMutex.withLock {
            repeat(MAX_EVICTION_PASSES) {
                // Counted within the bound by a pass this one queued behind
                if (payloadBytesMeasured && payloadBytes.load() <= max) return
                val emptyingsBefore = emptyings.load()
                val countedBefore = payloadBytes.load()
                // A payload a live reader shows stays, or the reader would drop to no data
                val lengths =
                    queryCacheDao.payloadLengthsOldestFirst(
                        allLiveCacheKeys().take(MAX_KEPT_LIVE_CACHE_KEYS)
                    )
                val total = lengths.sumOf { row -> row.length }
                val chosen =
                    if (total > max) rowsToEvict(lengths, atLeast = total - floor) else emptyList()
                val freed = chosen.sumOf { row -> row.length }
                val deleted = if (chosen.isEmpty()) 0 else deleteInOneTransaction(chosen)
                // What was read describes the cache unless it was emptied meanwhile, or a chosen
                // row was fetched again and left in place, its old length counted as freed
                val measured = emptyings.load() == emptyingsBefore && deleted == chosen.size
                // What was read, less what went, plus what was written meanwhile; a write the read
                // already saw is counted twice, which the next pass sets right
                if (measured) payloadBytes.fetchAndAdd(total - freed - countedBefore)
                // Otherwise the next written response reads the table again
                payloadBytesMeasured = measured && emptyings.load() == emptyingsBefore
                if (chosen.isEmpty()) return
                queryShapes.dropOrphans()
            }
        }
    }

    /**
     * The oldest of [lengths], in their order, whose lengths sum to [atLeast] or more, passing over
     * the payloads live readers show; fewer when the rest are all shown.
     */
    private fun rowsToEvict(lengths: List<PayloadLength>, atLeast: Long): List<PayloadLength> {
        val chosen = mutableListOf<PayloadLength>()
        var freed = 0L
        for (row in lengths) {
            if (freed >= atLeast) break
            if (row.kept) continue
            chosen += row
            freed += row.length
        }
        return chosen
    }

    /**
     * Deletes [rows] in one transaction, by table, [MAX_DELETE_KEYS] to a statement, sparing any
     * fetched again since they were read (see [QueryCacheDao.deletePayloads]). By table, so only
     * the live readers of a table a row went from read their payloads again.
     *
     * @return how many rows were deleted.
     */
    private suspend fun deleteInOneTransaction(rows: List<PayloadLength>): Int {
        // Read oldest first, so the last is the newest fetch among them
        val fetchedAtOrBefore = rows.last().fetchedAt
        var deleted = 0
        queryCacheDao.runInTransaction {
            rows
                .groupBy { row -> row.table }
                .forEach { (table, ofTable) ->
                    ofTable
                        .map { row -> row.rowId }
                        .chunked(MAX_DELETE_KEYS)
                        .forEach { rowIds ->
                            deleted +=
                                queryCacheDao.deletePayloads(table, rowIds, fetchedAtOrBefore)
                        }
                }
        }
        return deleted
    }

    /** A kept parsed payload, stamped with its last use. */
    private class KeptPayload(val parsed: ParsedPayload, use: Long) {
        val lastUse = AtomicLong(use)
    }

    /** The kept parsed payloads by table and cache key, with their texts' summed length. */
    private class KeptPayloads(val byKey: Map<Pair<String, String>, KeptPayload>, val chars: Long)

    internal companion object {
        /**
         * The most times one call of [evictOverBudget] reads the payloads' lengths: once, and again
         * only while writes during the pass keep the count over the bound.
         */
        const val MAX_EVICTION_PASSES = 4

        /** Rows one delete statement names at most, within SQLite's 999 bound variables. */
        const val MAX_DELETE_KEYS = 500

        /** Live signatures a pass passes over at most, within SQLite's 999 bound variables. */
        const val MAX_KEPT_LIVE_CACHE_KEYS = 500
    }
}

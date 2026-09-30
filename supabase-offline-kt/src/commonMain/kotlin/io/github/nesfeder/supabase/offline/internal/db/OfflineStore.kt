// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.cache.PatchTarget
import io.github.nesfeder.supabase.offline.internal.cache.Projection
import io.github.nesfeder.supabase.offline.internal.cache.conflictColumnsOf
import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import io.github.nesfeder.supabase.offline.internal.cache.mayGainRowsBy
import io.github.nesfeder.supabase.offline.internal.cache.patchMayLeaveWrong
import io.github.nesfeder.supabase.offline.internal.cache.payloadRowsOf
import io.github.nesfeder.supabase.offline.internal.cache.projectionOf
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.everyRowCarriesPrimaryKey
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.decodeOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.encode
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.isPlainColumn
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import io.github.nesfeder.supabase.offline.internal.util.EMPTY_JSON_ARRAY
import io.github.nesfeder.supabase.offline.internal.util.isEmptyJsonArrayPayload
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * Builds the default tick source backing the stale-refresh watermark: elapsed nanoseconds since the
 * owning [OfflineStore] was constructed, read from [TimeSource.Monotonic].
 *
 * Deliberately **not** wall-clock. The watermark only ever compares two ticks taken by the same
 * process, and a wall-clock source can step backwards (NTP correction, user clock change) — which
 * would make a response look older or newer than the local write it raced. A monotonic source
 * cannot. Nanosecond granularity keeps a mutation and a request start that land in the same
 * millisecond distinguishable.
 *
 * @return A tick reader whose values increase monotonically for the lifetime of the store.
 */
private fun monotonicTickSource(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeNanoseconds }
}

/**
 * What [OfflineStore.addPayload] did with the payload it was handed. Lets the select resolver
 * distinguish "the cache took this" from the two reasons it may refuse, and react accordingly.
 */
internal enum class AddPayloadOutcome {
    /** The payload was written to the object cache. */
    WRITTEN,

    /**
     * The producing request started **before** the most recent local write to the table, so the
     * response cannot know about that write and would roll the cache back. The caller should
     * re-fetch (a request issued now starts after the write and converges) rather than treat the
     * response as the current server state.
     */
    DROPPED_STALE,

    /**
     * The payload was an empty array that was not attributed to an authenticated session, so the
     * empty-clobber guard refused it. Unattributed empties never reach the cache: over non-empty
     * rows they erase real data, and into a cold (or already-empty) slot they install a phantom
     * empty that later cache-first reads then serve as truth.
     */
    DROPPED_EMPTY,

    /**
     * The response came back under a session the read's trust policy does not take as speaking for
     * the current identity, such as one still restoring, so nothing of it was written.
     */
    DROPPED_UNTRUSTED,

    /**
     * The producing request started **before** the cache was torn down by [OfflineStore.clearAll] —
     * sign-out, account switch, or account deletion. The response belongs to a cache generation
     * that no longer exists and, on an account switch, to a different user entirely. Unlike
     * [DROPPED_STALE] the caller must **not** re-fetch: a follow-up issued during teardown can
     * still carry the outgoing session's JWT and would re-import the very rows teardown removed.
     */
    DROPPED_CACHE_CLEARED,

    /**
     * The cache belongs to another identity than the current session's, or was dropped for a new
     * one after the request started; see [OfflineStore.dropCacheFor]. The caller may ask again,
     * once the cache is the current identity's.
     */
    DROPPED_OTHER_OWNER,
}

/** What [OfflineStore.enqueueOwnedBy] did with a write. */
internal sealed interface EnqueueOutcome {

    /**
     * The write is in the queue.
     *
     * @property id Its mutation id.
     */
    data class Queued(val id: Long) : EnqueueOutcome

    /**
     * The owner records cannot be read, so no owner could be recorded; the write was not queued.
     */
    data object OwnershipUnreadable : EnqueueOutcome

    /** Local data was cleared after the write started; it was not queued. */
    data object LocalDataCleared : EnqueueOutcome
}

/**
 * Central persistence for offline features:
 *
 * - PostgREST object cache (SELECT payloads) via [ObjectDao]
 * - Storage metadata (downloaded files) via [StorageDao]
 * - Mutation queue (insert/upsert/update/delete) via [MutationDao]
 */
internal class OfflineStore(
    private val objectDao: ObjectDao,
    private val storageDao: StorageDao,
    private val mutationDao: MutationDao,
    // Retained for constructor/source compatibility with the platform factories and existing
    // tests. Size-based eviction now measures logical payload bytes via [ObjectDao], not the
    // on-disk file, so these file-oriented providers are no longer consulted by the eviction
    // loop. The path is still resolved by the factories to build the Room database.
    @Suppress("unused") private val dbPathProvider: DbPathProvider,
    private val settings: OfflineCacheSettings,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    @Suppress("unused") private val dbSizeProvider: (Path) -> Long? = ::currentDbSizeBytes,
    /**
     * The single tick source behind the stale-refresh watermark — read both when a local write
     * advances a table's watermark and when a network SELECT records the moment its request went
     * out. Both stamps **must** come from here so they are comparable; see [monotonicTickSource]
     * for why this is monotonic rather than wall-clock. Injectable so tests can drive interleavings
     * deterministically.
     */
    private val monotonicTick: () -> Long = monotonicTickSource(),
) {

    internal val json: Json = OfflineJson
    private val evictionMutex = Mutex()

    /** Each cached query's shape, which decides the rows it admits; see [QueryShapeRecords]. */
    internal val queryShapes: QueryShapeRecords = QueryShapeRecords(objectDao, json)

    /**
     * The identity the query cache and the Storage file cache belong to; see [CacheOwnerRecord].
     */
    internal val cacheOwner: CacheOwnerRecord = CacheOwnerRecord(objectDao, json)

    /**
     * Whether the cache belongs to one identity at a time, checked on every read and write: `true`
     * with the Auth plugin. Without it there is one identity, which owns the cache for good. Wired
     * by the client.
     */
    internal var cacheOwnedPerIdentity: Boolean = false

    /**
     * Told when the cache was not served to, or written for, the current identity. Wired by the
     * client.
     */
    internal var onCacheNotServed: () -> Unit = {}

    /**
     * Told of the cached queries of a table a write marked stale, and of live ones it may have
     * changed without a payload, so their live readers fetch again. Wired by the client.
     */
    internal var onQueriesMarkedStale: (table: String, signatures: Set<String>) -> Unit = { _, _ ->
    }

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
     * cannot apply). Wired by the client, like [liveSignatureProvider]; silent when built directly.
     */
    internal var sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)

    /**
     * The mutation queue's persisted state: the owner of each queued write, the rejected list, the
     * synced-mutation coverage and the unconfirmed sends.
     */
    internal val mutationQueueState: MutationQueueState =
        MutationQueueState(
            objectDao = objectDao,
            json = OfflineJson,
            nowMillis = nowMillis,
            logger = { sdkLogger },
            pendingKeyedInsertIds = {
                mutationDao
                    .pendingAll()
                    .filter { mutation -> isKeyedInsert(mutation) }
                    .map { mutation -> mutation.id }
                    .toSet()
            },
        )

    /**
     * Serializes every change to the mutation queue's bookkeeping together with the mutation rows
     * it describes: an enqueue and its owner, a rejection and the row it removes, a retry, a
     * discard, a completed or retired write. Short critical sections of DAO calls only — never held
     * across a request, and never awaited inside a database transaction (the transactions run
     * inside it, not around it).
     *
     * Lock order, outermost first: the syncer's send lock, a table's write lock, this lock, then
     * [teardownMutex].
     */
    private val mutationQueueMutex = Mutex()

    /**
     * Makes a teardown and every cache write it must not be followed by one step: [clearAll] stamps
     * the teardown tick and deletes under it, and every overlay and payload write checks the tick
     * and writes under it. So a write whose operation started before a teardown can never land
     * after it.
     */
    private val teardownMutex = Mutex()

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

    /** Mutation ids whose overlay failure was already logged, so a failing one is logged once. */
    private val loggedOverlaySkips = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * Whether an unclassifiable dry-run status has been reported yet; see
     * [reportUnclassifiableWriteOnce].
     */
    private val unclassifiableWriteReported = MutableStateFlow(false)

    /**
     * Whether the synced [mutation] still belongs on the payload cached for [signature]: a synced
     * mutation stands in for its write only on payloads that do not already contain it — those no
     * network response wrote after it replayed (see [MutationQueueState.syncedMutationCoverage]). A
     * pending mutation always belongs; so does any mutation when [signature] is not known.
     */
    private fun belongsOnPayload(
        mutation: MutationRow,
        signature: String?,
        coverage: Map<Long, Set<String>>,
    ): Boolean =
        mutation.syncedAtMs == null ||
            signature == null ||
            signature !in coverage[mutation.id].orEmpty()

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
    private val tableWriteLocksMutex = Mutex()
    private val tableWriteLocks = mutableMapOf<String, Mutex>()

    /**
     * The write lock for [table], created on first use.
     *
     * @param table Table whose object-cache writes the lock serializes.
     * @return The table's lock; hold it via `withLock` around the whole read-modify-write.
     */
    private suspend fun tableWriteLock(table: String): Mutex = tableWriteLocksMutex.withLock {
        tableWriteLocks.getOrPut(table) { Mutex() }
    }

    /**
     * Table → tick of the most recent local write that changed what the cache knows: an
     * online-success mutation materialized by [patchCachedQueries] (which also carries the realtime
     * `applyServerRecord` / `applyServerDelete` edges) and a queue replay confirmed by
     * [markMutationSynced]. A network SELECT whose request started before that tick describes a
     * server state that predates the write, so [addPayload] refuses it.
     *
     * In-memory and per-process by design: the watermark only orders events inside one run of the
     * app, needs no schema change, and a fresh process has no in-flight requests left to
     * disqualify. Held in a [MutableStateFlow] purely for its atomic [update] — reads and writes
     * can arrive concurrently from several coroutines, and no lock is wanted on the write path.
     */
    private val lastLocalMutationTick = MutableStateFlow<Map<String, Long>>(emptyMap())

    /**
     * Tick of the most recent identity teardown ([clearAll]), or `0` while the cache has never been
     * wiped in this process.
     *
     * Teardown is the same ordering hazard as a local write, one generation wider: a SELECT that
     * left before sign-out returns the *outgoing* user's rows, and writing them afterwards
     * repopulates a cache that was just emptied precisely so one account's data never shows up
     * under the next. Global rather than per-table because [clearAll] wipes every table at once.
     */
    private val cacheClearedTick = MutableStateFlow(0L)

    /**
     * Tick of the most recent time the cache was emptied: by [clearAll], or by [dropCacheFor] when
     * it passed to another identity. No cache write whose operation started before it lands. Unlike
     * [cacheClearedTick] it leaves queueing alone, since a drop keeps the mutation queue.
     */
    private val cacheDroppedTick = MutableStateFlow(0L)

    /**
     * Reads the shared tick source. Callers that are about to issue a network SELECT capture this
     * **before** the request goes out and hand it back to [addPayload] as `requestStartedAtTick`.
     *
     * @return The current monotonic tick.
     */
    internal fun monotonicTickNow(): Long = monotonicTick()

    /**
     * Advances [table]'s stale-refresh watermark to now, marking every network SELECT already in
     * flight for that table as unable to describe current truth.
     *
     * Recorded at the *start* of the write it guards: the server side of the write has already
     * committed by the time these seams run, so a request that started even slightly earlier may
     * predate it. Over-dropping in that narrow window is safe — the resolver re-fetches — while
     * under-dropping loses the write.
     *
     * @param table Table whose local truth just moved forward.
     */
    private fun recordLocalMutation(table: String, tick: Long = monotonicTick()) {
        lastLocalMutationTick.update { current ->
            val existing = current[table]
            // Never move a watermark backwards: two writes racing here must leave the later tick.
            if (existing != null && existing >= tick) current else current + (table to tick)
        }
    }

    /**
     * Whether a response produced by a request that started at [requestStartedAtTick] is known to
     * predate [table]'s most recent local write.
     *
     * @param table Table the response belongs to.
     * @param requestStartedAtTick Tick captured before the request went out, or `null` for writers
     *   that carry no request-time information (they are never dropped).
     * @return `true` when the response cannot know about the latest local write.
     */
    private fun isStaleRelativeToLocalWrite(table: String, requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        val watermark = lastLocalMutationTick.value[table] ?: return false
        return requestStartedAtTick < watermark
    }

    /**
     * Whether a response produced by a request that started at [requestStartedAtTick] belongs to a
     * cache generation that [clearAll] or [dropCacheFor] has since discarded.
     *
     * @param requestStartedAtTick Tick captured before the request went out, or `null` for writers
     *   that carry no request-time information (they are never dropped).
     * @return `true` when the cache was emptied after the request left.
     */
    private fun predatesCacheTeardown(requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        return requestStartedAtTick < cacheDroppedTick.value
    }

    /**
     * Whether [clearAll] — which empties the mutation queue too — ran after [requestStartedAtTick].
     */
    private fun predatesQueueTeardown(requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        return requestStartedAtTick < cacheClearedTick.value
    }

    /**
     * Whether the cache was cleared, or dropped for another identity, since [tick] was read from
     * [monotonicTickNow]: a write whose request was in flight then must not be overlaid onto it.
     *
     * @param tick A tick read before the operation started.
     * @return `true` when the cache was emptied after [tick].
     */
    internal fun wasClearedSince(tick: Long): Boolean = predatesCacheTeardown(tick)

    /**
     * Whether the cache may be read for the current identity: it is that identity's, or no
     * identity's yet and the identity is a user, who adopts it. Without the Auth plugin there is
     * one identity, and it always may. When it may not, the client is told, so a settled session
     * drops the cache for the new identity.
     */
    internal suspend fun cacheServesCurrentIdentity(): Boolean =
        cacheIsCurrentIdentitys(forWrite = false)

    /**
     * Whether the cache may be written for the current identity, as [cacheServesCurrentIdentity]
     * says; a cache no identity owns is first recorded as that identity's, so what is written into
     * it is never adopted by another.
     */
    internal suspend fun cacheTakesWritesOfCurrentIdentity(): Boolean =
        cacheIsCurrentIdentitys(forWrite = true)

    /** [cacheServesCurrentIdentity], or with [forWrite] [cacheTakesWritesOfCurrentIdentity]. */
    private suspend fun cacheIsCurrentIdentitys(forWrite: Boolean): Boolean {
        if (!cacheOwnedPerIdentity) {
            if (forWrite)
                identityProvider()?.let { identity -> cacheOwner.owner(claimant = identity) }
            return true
        }
        val identity = identityProvider()
        val owner = identity?.let { cacheOwnerClaimingFor(it, claimsForUser = forWrite) }
        val mine =
            identity != null &&
                (owner == identity ||
                    (owner == null && identity != QueueIdentity.Anonymous.storedValue))
        if (!mine) onCacheNotServed()
        return mine
    }

    /**
     * The cache's recorded owner, recording [identity] first when none is and it may claim the
     * cache: a user when [claimsForUser]; nobody signed in only while the cache holds nothing,
     * since rows an earlier build left may be a user's.
     */
    internal suspend fun cacheOwnerClaimingFor(identity: String, claimsForUser: Boolean): String? =
        cacheOwner.owner(
            claimant = identity,
            mayClaim = {
                if (identity != QueueIdentity.Anonymous.storedValue) claimsForUser
                else objectDao.totalPayloadBytes() == 0L && storageDao.all().isEmpty()
            },
        )

    /**
     * Rolling count of [addPayload] writes, used to run size enforcement only periodically instead
     * of on every network SELECT. Atomic because writes can arrive concurrently from multiple
     * coroutines/threads; exactness isn't required, only a monotonic cadence.
     */
    private val writeCounter = AtomicInt(0)

    /**
     * Signatures of a table that currently have a live `selectAsFlow` collector. Wired by
     * [io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl] to the client-scoped
     * [io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator] so
     * [patchCachedQueries] can materialize a server-successful mutation into a real cache row for a
     * cache-less-but- observed signature (the "save then stale UI" fix), and self-GC when nothing
     * observes the table. Defaults to reporting no live signatures so direct construction in tests
     * keeps the conservative "only patch existing rows" behavior unless a set is passed explicitly.
     */
    internal var liveSignatureProvider: (table: String) -> Set<String> = { _ -> emptySet() }

    /**
     * Signatures of every table with a live reader, which eviction keeps; wired like
     * [liveSignatureProvider].
     */
    internal var allLiveSignaturesProvider: () -> Set<String> = { emptySet() }

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    /**
     * Writes a network SELECT's [payload] for [table]/[signature], unless it must be refused:
     * [AddPayloadOutcome.DROPPED_CACHE_CLEARED] when its request started before [clearAll],
     * [AddPayloadOutcome.DROPPED_OTHER_OWNER] when the cache is not the current identity's or was
     * dropped for it after the request started, [AddPayloadOutcome.DROPPED_STALE] when it started
     * before the table's last local write (it would roll that write back), and
     * [AddPayloadOutcome.DROPPED_EMPTY] for an empty payload that is not [authoritativeEmpty]. A
     * payload written with a start tick is recorded as containing the table's synced mutations.
     * Runs under the table's write lock.
     *
     * @param authoritativeEmpty Whether an empty [payload] is the server's real answer (the request
     *   went out signed in), so it may replace cached rows or seed a cold slot.
     * @param requestStartedAtTick [monotonicTickNow] read before the request went out, or `null`
     *   for a writer with no request behind it, which is never refused as stale.
     * @param isTrusted Whether the response speaks for the current identity at all; one that does
     *   not is refused as [AddPayloadOutcome.DROPPED_UNTRUSTED], empty or not.
     * @param shape the query's shape, recorded with a written payload before the table's lock is
     *   released; `null` when not known.
     * @return What happened, so a caller can re-fetch after a stale drop.
     */
    suspend fun addPayload(
        table: String,
        signature: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
        shape: QueryShape? = null,
    ): AddPayloadOutcome {
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
                mutationQueueMutex.withLock {
                    teardownMutex.withLock {
                        when {
                            // The cache was wiped for an identity change while this request was
                            // in flight. Its rows belong to the outgoing account; writing them
                            // here undoes the teardown.
                            predatesQueueTeardown(requestStartedAtTick) ->
                                AddPayloadOutcome.DROPPED_CACHE_CLEARED

                            // The cache is another identity's, or became the current one's after
                            // this request left: its rows would mix two identities' data.
                            !ownedByCurrentIdentity ||
                                predatesCacheTeardown(requestStartedAtTick) ->
                                AddPayloadOutcome.DROPPED_OTHER_OWNER

                            // The response left before the local write landed; keeping the cache
                            // is the only answer that preserves it. The caller re-fetches to
                            // converge on real server state.
                            isStaleRelativeToLocalWrite(table, requestStartedAtTick) ->
                                AddPayloadOutcome.DROPPED_STALE

                            !isTrusted -> AddPayloadOutcome.DROPPED_UNTRUSTED

                            // Nothing about this emptiness is known to describe the signed-in
                            // user's data, so it is never written — not over cached rows, and not
                            // into a cold or already-empty slot where it would become the answer
                            // every later cache-first read is served.
                            !authoritativeEmpty && isEmptyJsonArrayPayload(payload) ->
                                AddPayloadOutcome.DROPPED_EMPTY

                            else -> {
                                writePayloadAndCoverage(
                                    row =
                                        ObjectRow(
                                            table = table,
                                            signature = signature,
                                            payload = payload,
                                            updatedAt = nowMillis(),
                                        ),
                                    coversSyncedMutations = requestStartedAtTick != null,
                                )
                                // Before the lock is released, so no patch finds the payload
                                // without the shape that says which rows it admits
                                if (shape != null)
                                    queryShapes.remember(table, signature, shape, persist = true)
                                AddPayloadOutcome.WRITTEN
                            }
                        }
                    }
                }
            }

        // Outside the table lock: eviction takes its own mutex and scans every table.
        if (outcome == AddPayloadOutcome.WRITTEN && shouldEnforceSizeAfterWrite(payload)) {
            enforceDbSizeLimitIfNeeded()
        }
        return outcome
    }

    /**
     * Writes a cached payload, and records in the same transaction whether it contains the table's
     * synced mutations. A network response whose request started after the table's last local write
     * — the only kind [addPayload] accepts with a start tick — holds every mutation already synced;
     * a payload with no request behind it holds none for certain. Caller holds the table, mutation
     * queue and teardown locks.
     */
    private suspend fun writePayloadAndCoverage(row: ObjectRow, coversSyncedMutations: Boolean) {
        val synced = mutationDao.syncedIdsForTable(row.table)
        val coverage = mutationQueueState.syncedMutationCoverageRowWith { current ->
            if (coversSyncedMutations) {
                current + synced.associateWith { id -> current[id].orEmpty() + row.signature }
            } else {
                current.mapValues { (_, signatures) -> signatures - row.signature }
            }
        }
        if (coverage == null) {
            objectDao.upsert(row)
            return
        }
        mutationDao.runInTransaction {
            objectDao.upsert(row)
            objectDao.upsert(coverage.first)
        }
        mutationQueueState.syncedMutationCoverageWritten(coverage.second)
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
     * immediately when size-based eviction is disabled ([OfflineCacheSettings.maxDbSizeBytes] is
     * `null`).
     *
     * @param payload the JSON payload just written to the cache.
     * @return `true` when [enforceDbSizeLimitIfNeeded] should be invoked.
     */
    private fun shouldEnforceSizeAfterWrite(payload: String): Boolean {
        if (settings.maxDbSizeBytes == null) return false
        val isLargePayload = payload.length >= ENFORCEMENT_LARGE_PAYLOAD_CHARS
        // Always advance the counter so the cadence reflects the true write count; combine with
        // the large-payload trigger afterwards so both side effects run regardless of order.
        // fetchAndAdd returns the pre-increment value, so +1 yields the just-completed write's
        // ordinal — enforcing on every ENFORCEMENT_WRITE_INTERVAL-th write.
        val cadenceReached = (writeCounter.fetchAndAdd(1) + 1) % ENFORCEMENT_WRITE_INTERVAL == 0
        return isLargePayload || cadenceReached
    }

    /**
     * The payload cached for [table]/[signature], or `null` when none is, or the cache is not the
     * current identity's.
     */
    suspend fun getPayload(table: String, signature: String): String? =
        getPayloadWithTimestamp(table, signature)?.first

    /**
     * The payload cached for [table]/[signature] with the time it was written, or `null` when none
     * is, or the cache is not the current identity's.
     */
    suspend fun getPayloadWithTimestamp(table: String, signature: String): Pair<String, Long>? {
        if (isReservedTable(table) || !cacheServesCurrentIdentity()) return null
        val row = objectDao.get(table, signature) ?: return null
        return row.payload to row.updatedAt
    }

    fun observePayload(table: String, signature: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return objectDao.observe(table, signature).map { row -> row?.payload }
    }

    suspend fun deleteTablePayload(table: String, signature: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            objectDao.delete(table, signature)
            queryShapes.forget(table, signature)
        }
    }

    /**
     * Drop every cached SELECT payload for [table].
     *
     * Advances the table's stale-refresh watermark: an emptied table is local truth just as much as
     * a patched row, so a refresh already in flight must not land on top of it. Unlike [clearAll]
     * this is not identity teardown, so a follow-up re-fetch is the right recovery.
     *
     * @param table Table whose cached payloads are removed.
     */
    suspend fun clearTable(table: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            recordLocalMutation(table)
            objectDao.deleteTable(table)
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
    suspend fun clearAll(owner: String? = null) {
        // Under the mutation-queue lock, so a write whose operation predates this teardown cannot
        // slip its mutation row in between the tick and the deletes (see [enqueueOwnedBy]); and
        // under the teardown lock, so no overlay or payload write that checked the tick before it
        // can land after the deletes. Neither lock is held across a request, so a teardown never
        // waits on one.
        mutationQueueMutex.withLock {
            teardownMutex.withLock {
                val tick = monotonicTick()
                // Never move the teardown tick backwards: two teardowns racing here must leave the
                // later one, or the earlier assignment would re-admit responses it disqualified.
                cacheClearedTick.update { existing -> if (existing >= tick) existing else tick }
                cacheDroppedTick.update { existing -> if (existing >= tick) existing else tick }
                // One transaction: a clear that fails part-way must not leave mutations without the
                // owner records in object_cache, where the next identity would adopt them. The
                // mutations go first as well, so even a store without transactions never does.
                cacheOwner.emptying(owner) {
                    mutationDao.runInTransaction {
                        mutationDao.deleteAll()
                        storageDao.deleteAll()
                        objectDao.deleteAll()
                        if (owner != null) objectDao.upsert(cacheOwner.rowOf(owner))
                    }
                    mutationQueueState.resetCaches()
                }
                queryShapes.forgetAll()
                parsedPayloads.value = emptyMap()
                loggedOverlaySkips.value = emptySet()
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
        // The locks and order of [clearAll], which this is but for the mutation queue
        mutationQueueMutex.withLock {
            teardownMutex.withLock {
                val tick = monotonicTick()
                cacheDroppedTick.update { existing -> if (existing >= tick) existing else tick }
                cacheOwner.emptying(owner) {
                    mutationDao.runInTransaction {
                        storageDao.deleteAll()
                        objectDao.deleteAllExceptTable(RESERVED_TABLE_NAME)
                        objectDao.deleteWithSignaturePrefix(
                            RESERVED_TABLE_NAME,
                            QUERY_SHAPE_SIGNATURE_PREFIX,
                        )
                        objectDao.upsert(cacheOwner.rowOf(owner))
                    }
                }
                queryShapes.forgetAll()
                parsedPayloads.value = emptyMap()
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
            StorageRow(
                key = storageKey(bucketId, path),
                bucketId = bucketId,
                path = path,
                localPath = file.toString(),
                sizeBytes = size,
                eTag = eTag,
                lastFetchedEpochMs = nowMillis(),
            )
        storageDao.upsert(row)
    }

    suspend fun getStorageByKey(
        bucketId: String,
        path: String,
    ): StorageRow? {
        return storageDao.getByKey(storageKey(bucketId, path))
    }

    suspend fun deleteStorageByKey(
        bucketId: String,
        path: String,
    ) {
        storageDao.delete(storageKey(bucketId, path))
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
    suspend fun storageRowsForObject(
        bucketId: String,
        path: String,
        variantPrefix: String,
    ): List<StorageRow> = storageDao.forObject(bucketId, path, variantPrefix)

    /** Every storage row. */
    suspend fun allStorageRows(): List<StorageRow> = storageDao.all()

    /** The summed recorded size of every cached file, in bytes. */
    suspend fun totalStorageSizeBytes(): Long = storageDao.totalSizeBytes()

    /**
     * Writes [row] back as it was, replacing whatever row has its key.
     *
     * @param row the row to restore.
     */
    suspend fun restoreStorageRow(row: StorageRow) {
        storageDao.upsert(row)
    }

    /**
     * Deletes the storage row with [key].
     *
     * @param key the row's key, `"$bucket::$path"`.
     */
    suspend fun deleteStorageRow(key: String) {
        storageDao.delete(key)
    }

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    /**
     * Queue an INSERT.
     *
     * @return The mutation's id — the mutation row's autoincrement id, stable across restarts.
     */
    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true,
    ): Long {
        requireUserTable(table)
        // Read before the first INSERT this process queues, so a missing record is started from the
        // INSERTs queued before it only. Under [enqueueOwnedBy] it is read already.
        mutationQueueState.unconfirmedSends()
        return mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.INSERT,
                bodyJson = body.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull,
            )
        )
    }

    /**
     * Queue an UPSERT.
     *
     * @return The mutation's id.
     */
    suspend fun enqueueUpsert(
        table: String,
        bodyJson: JsonArray,
        filter: FilterDescriptor? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean = false,
        defaultToNull: Boolean = true,
    ): Long {
        requireUserTable(table)
        return mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPSERT,
                bodyJson = bodyJson.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                defaultToNull = defaultToNull,
                onConflict = onConflict,
                ignoreDuplicates = ignoreDuplicates,
            )
        )
    }

    /**
     * Enqueue an UPDATE mutation, with an optional [filter] describing the targeted rows.
     *
     * @param deltaColumns Columns of [body] whose written value is a change *relative* to the
     *   snapshot each declaration carries, rather than an absolute to restate on replay. Stored
     *   alongside the body so [MutationQueueReplayer] can rebase them onto the row's current server
     *   value; empty (the default) queues an ordinary absolute UPDATE.
     * @return The mutation's id.
     */
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null,
        deltaColumns: List<NumericDeltaColumn> = emptyList(),
    ): Long {
        requireUserTable(table)
        return mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = body.toString(),
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
                deltaJson = encodeNumericDeltaColumns(deltaColumns, json),
            )
        )
    }

    /**
     * Enqueue a DELETE mutation. [filter] describes which rows are removed.
     *
     * @return The mutation's id.
     */
    suspend fun enqueueDelete(
        table: String,
        filter: FilterDescriptor? = null,
    ): Long {
        requireUserTable(table)
        return mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}", // no body for delete
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null,
            )
        )
    }

    /**
     * Runs [enqueue] and records [identity] as the owner of the write it queues, in one
     * transaction.
     *
     * The queue is kept per identity: each write records who made it, a replay pass sends only the
     * current identity's writes, and another identity's writes neither block it nor are sent by it.
     *
     * The write is refused when [clearAll] has run since [startedAtTick] — it belongs to the
     * identity that was torn down — and when the owner records cannot be read. The teardown check
     * and the insert share [clearAll]'s lock, so no teardown can fall between them.
     *
     * @param identity The stored value of the identity making the write, or `null` to record none
     *   (a store driven directly, with no session model).
     * @param startedAtTick [monotonicTickNow] as read before the write started, or `null` to skip
     *   the teardown check.
     * @param enqueue Inserts the mutation row and returns its id. Must make DAO calls only.
     * @return What happened.
     */
    suspend fun enqueueOwnedBy(
        identity: String?,
        startedAtTick: Long? = null,
        enqueue: suspend OfflineStore.() -> Long,
    ): EnqueueOutcome = mutationQueueMutex.withLock {
        if (predatesQueueTeardown(startedAtTick)) return@withLock EnqueueOutcome.LocalDataCleared
        // Outside the transaction below: the first read of the unconfirmed sends may write.
        mutationQueueState.unconfirmedSends()
        if (identity == null) return@withLock EnqueueOutcome.Queued(enqueue())
        if (mutationQueueState.owners() !is MutationOwners.Known)
            return@withLock EnqueueOutcome.OwnershipUnreadable
        var id = 0L
        var owners: MutationOwners.Known? = null
        mutationDao.runInTransaction {
            id = enqueue()
            val (row, next) =
                checkNotNull(
                    mutationQueueState.ownersRowWith { current -> current + (id to identity) }
                )
            objectDao.upsert(row)
            owners = next
        }
        owners?.let(mutationQueueState::ownersWritten)
        EnqueueOutcome.Queued(id)
    }

    /**
     * The pending mutations [identity] may replay, in insertion order: its own, and those with no
     * recorded owner when [identity] may adopt them (see [mayAdoptOwnerlessMutations]).
     *
     * @param table The table to look in, or `null` for every table.
     * @return The mutations, or `null` when the owner records cannot be read.
     */
    suspend fun pendingMutationsOf(identity: String, table: String? = null): List<MutationRow>? {
        val owners = mutationQueueState.owners() as? MutationOwners.Known ?: return null
        val pending =
            if (table == null) mutationDao.pendingAll() else mutationDao.pendingForTable(table)
        return pending.filter { row -> belongsTo(owners.byId[row.id], identity) }
    }

    /**
     * Records [identity] as the owner of every pending mutation that has none — rows an earlier
     * build queued — when [identity] may adopt them; otherwise leaves them untouched. Also drops
     * owner records of mutations that no longer exist.
     *
     * @return `false` when the owner records cannot be read.
     */
    suspend fun adoptOwnerlessMutations(identity: String): Boolean = mutationQueueMutex.withLock {
        val owners = mutationQueueState.owners() as? MutationOwners.Known ?: return@withLock false
        val unowned =
            if (mayAdoptOwnerlessMutations(identity)) {
                mutationDao.pendingAll().map { row -> row.id }.toSet() - owners.byId.keys
            } else {
                emptySet()
            }
        val stale = owners.byId.keys - mutationDao.allIds().toSet()
        if (unowned.isEmpty() && stale.isEmpty()) return@withLock true
        val (row, next) =
            checkNotNull(
                mutationQueueState.ownersRowWith { current ->
                    (current - stale) + unowned.associateWith { identity }
                }
            )
        objectDao.upsert(row)
        mutationQueueState.ownersWritten(next)
        true
    }

    /**
     * Whether a mutation owned by [owner] — `null` for one an earlier build queued — is
     * [identity]'s.
     */
    private fun belongsTo(owner: String?, identity: String): Boolean =
        owner == identity || (owner == null && mayAdoptOwnerlessMutations(identity))

    /**
     * Whether [identity] may adopt mutations an earlier build queued without an owner. That build
     * replayed only while signed in when the Auth plugin was installed, so its mutations are a
     * signed-in user's: the anonymous identity adopts them only when there is no Auth plugin.
     */
    private fun mayAdoptOwnerlessMutations(identity: String): Boolean =
        identity != QueueIdentity.Anonymous.storedValue || ownerlessMutationsAreAnonymous

    /**
     * Whether [id] is the first of [identity]'s pending mutations, in any table. Reads ids only.
     *
     * @return `false` behind another of [identity]'s mutations, when [id] is not pending, or when
     *   the owner records cannot be read.
     */
    suspend fun isAtHead(id: Long, identity: String): Boolean =
        mutationQueueState.owners() is MutationOwners.Known &&
            mutationDao.isPending(id) &&
            !hasEarlierPendingMutation(id, identity)

    /**
     * Whether any of [identity]'s mutations queued before [id], in any table, is still pending.
     * When the owner records cannot be read, any earlier pending mutation counts.
     */
    suspend fun hasEarlierPendingMutation(id: Long, identity: String): Boolean {
        val earlier = mutationDao.pendingIdsBefore(id)
        val owners =
            mutationQueueState.owners() as? MutationOwners.Known ?: return earlier.isNotEmpty()
        return earlier.any { earlierId -> belongsTo(owners.byId[earlierId], identity) }
    }

    /** Whether the mutation [id] is queued and not yet synced. */
    suspend fun isPending(id: Long): Boolean = mutationDao.isPending(id)

    /**
     * Removes a write sent at the head of the queue once the server accepted it: its mutation row,
     * its owner record, and any rejected entry a crash left behind for it. The caller patches the
     * cache with the server's response, so nothing needs to stand in for the write any more.
     *
     * @param id The sent mutation's id.
     */
    suspend fun completeSentMutation(id: Long) {
        removeMutations(listOf(id), clearRejections = true)
    }

    /**
     * Removes a write sent at the head of the queue that the server refused, or that local data was
     * cleared under: its mutation row and owner record.
     *
     * @param id The mutation's id.
     */
    suspend fun abandonSentMutation(id: Long) {
        removeMutations(listOf(id), clearRejections = false)
    }

    /**
     * Discards the pending mutation [id] of [identity]: it leaves the queue unsent, and the overlay
     * stops showing it.
     *
     * @param id The mutation's id.
     * @param identity The asking identity's stored value, or `null` to skip the ownership check.
     * @return `true` when it was pending and [identity]'s to discard.
     */
    suspend fun discardPendingMutation(id: Long, identity: String?): Boolean {
        if (!mutationDao.isPending(id)) return false
        if (identity != null) {
            val owners = mutationQueueState.owners() as? MutationOwners.Known ?: return false
            if (!belongsTo(owners.byId[id], identity)) return false
        }
        removeMutations(listOf(id), clearRejections = false)
        return true
    }

    /**
     * Deletes the mutation rows [ids] with everything the bookkeeping holds for them — owner,
     * coverage, unconfirmed send and, when [clearRejections], a rejected entry — in one
     * transaction.
     */
    private suspend fun removeMutations(ids: Collection<Long>, clearRejections: Boolean) {
        if (ids.isEmpty()) return
        mutationQueueMutex.withLock {
            val idSet = ids.toSet()
            val owners = mutationQueueState.ownersRowWith { current -> current - idSet }
            val coverage = mutationQueueState.syncedMutationCoverageRowWith { current ->
                current - idSet
            }
            val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current ->
                current - idSet
            }
            val rejected =
                if (clearRejections && idSet.any { id -> mutationQueueState.hasRejection(id) }) {
                    mutationQueueState.rejectedRowWith(removeIds = idSet)
                } else {
                    null
                }
            mutationDao.runInTransaction {
                idSet.forEach { id -> mutationDao.deleteById(id) }
                listOfNotNull(owners?.first, coverage?.first, unconfirmed?.first, rejected?.first)
                    .forEach { row -> objectDao.upsert(row) }
            }
            owners?.let { (_, next) -> mutationQueueState.ownersWritten(next) }
            coverage?.let { (_, next) -> mutationQueueState.syncedMutationCoverageWritten(next) }
            unconfirmed?.let { (_, next) -> mutationQueueState.unconfirmedSendsWritten(next) }
            rejected?.let { (_, next) -> mutationQueueState.rejectedWritten(next) }
            loggedOverlaySkips.update { logged -> logged - idSet }
        }
    }

    /**
     * Moves [mutation] out of the queue and onto the rejected list, in one transaction: the list
     * entry, the mutation row and its owner record change together or not at all.
     *
     * @param mutation The refused write.
     * @param message Why it was refused.
     * @param serverError The server's refusal, or `null` when the engine refused it locally.
     * @return The persisted entry.
     */
    suspend fun rejectMutation(
        mutation: MutationRow,
        message: String,
        serverError: StoredServerError?,
    ): StoredRejection = mutationQueueMutex.withLock {
        val owner = (mutationQueueState.owners() as? MutationOwners.Known)?.byId?.get(mutation.id)
        val entry =
            StoredRejection(
                id = mutation.id,
                table = mutation.table,
                operation = mutation.operation,
                bodyJson = mutation.bodyJson,
                filterJson = mutation.filterJson,
                createdAtMs = mutation.createdAtMs,
                defaultToNull = mutation.defaultToNull,
                onConflict = mutation.onConflict,
                ignoreDuplicates = mutation.ignoreDuplicates,
                deltaJson = mutation.deltaJson,
                owner = owner,
                rejectedAtMs = nowMillis(),
                message = message,
                serverError = serverError,
            )
        val (rejectedRow, list) = mutationQueueState.rejectedRowWith(add = entry)
        val owners = mutationQueueState.ownersRowWith { current -> current - mutation.id }
        val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current ->
            current - mutation.id
        }
        mutationDao.runInTransaction {
            objectDao.upsert(rejectedRow)
            mutationDao.deleteById(mutation.id)
            listOfNotNull(owners?.first, unconfirmed?.first).forEach { row ->
                objectDao.upsert(row)
            }
        }
        mutationQueueState.rejectedWritten(list)
        owners?.let { (_, next) -> mutationQueueState.ownersWritten(next) }
        unconfirmed?.let { (_, next) -> mutationQueueState.unconfirmedSendsWritten(next) }
        list.entries.firstOrNull { stored -> stored.id == mutation.id } ?: entry
    }

    /**
     * Puts the rejected mutation [id] back at the tail of the queue, under a new id, in one
     * transaction with its owner record and the list entry's removal.
     *
     * @param id The rejected mutation's id.
     * @param identity The stored value of the identity asking, or `null` to skip ownership checks.
     * @return The new pending mutation's id, or `null` when there is no such rejection, its body
     *   was too long to keep, it belongs to another identity, or the owner records cannot be read.
     */
    suspend fun retryRejectedMutation(id: Long, identity: String?): Long? =
        mutationQueueMutex.withLock {
            val entry =
                mutationQueueState.rejections().firstOrNull { rejection -> rejection.id == id }
                    ?: return@withLock null
            if (identity != null && entry.owner != null && entry.owner != identity)
                return@withLock null
            if (identity != null && mutationQueueState.owners() !is MutationOwners.Known)
                return@withLock null
            val row = entry.toRequeuedRow(createdAtMs = nowMillis()) ?: return@withLock null
            // Read before the re-queued write exists: it has never been sent under its new id.
            mutationQueueState.unconfirmedSends()
            val (rejectedRow, list) = mutationQueueState.rejectedRowWith(removeId = id)
            var newId = 0L
            var owners: MutationOwners.Known? = null
            mutationDao.runInTransaction {
                newId = mutationDao.insert(row)
                objectDao.upsert(rejectedRow)
                if (identity != null) {
                    val (ownersRow, next) =
                        checkNotNull(
                            mutationQueueState.ownersRowWith { current ->
                                current + (newId to identity)
                            }
                        )
                    objectDao.upsert(ownersRow)
                    owners = next
                }
            }
            mutationQueueState.rejectedWritten(list)
            owners?.let(mutationQueueState::ownersWritten)
            newId
        }

    /**
     * Removes the rejected mutation [id] from the list.
     *
     * @param id The rejected mutation's id.
     * @return `true` when an entry was removed.
     */
    suspend fun dismissRejectedMutation(id: Long): Boolean = mutationQueueMutex.withLock {
        mutationQueueState.removeRejection(id) != null
    }

    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in insertion order across **all tables**, whatever their owner.
     */
    suspend fun allPendingMutations(): List<MutationRow> = mutationDao.pendingAll()

    /**
     * The current identity's pending mutations — of [table], or of every table when it is `null` —
     * in insertion order, re-emitted on each change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending mutation rows.
     */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        combine(mutationDao.pendingAllFlow(), mutationQueueState.ownersFlow(), identityChanges) {
                rows,
                owners,
                _ ->
                visibleTo(identityProvider(), owners, rows).filter { row ->
                    table == null || row.table == table
                }
            }
            .distinctUntilChanged()

    /** The current identity's pending mutations across all tables. */
    suspend fun pendingMutationCount(): Int =
        visibleTo(identityProvider(), mutationQueueState.owners(), mutationDao.pendingAll()).size

    /** Flow of the current identity's pending mutations across all tables. */
    fun pendingMutationCountFlow(): Flow<Int> = pendingMutationsFlow(null).map { rows -> rows.size }

    /** The current identity's pending mutations for a specific [table]. */
    suspend fun pendingMutationCountForTable(table: String): Int =
        visibleTo(
                identityProvider(),
                mutationQueueState.owners(),
                mutationDao.pendingForTable(table),
            )
            .size

    /** Flow of the current identity's pending mutations for a specific [table]. */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        pendingMutationsFlow(table).map { rows -> rows.size }

    /**
     * Deletes the mutation row [id] and its owner record.
     *
     * @param id The mutation's id.
     */
    suspend fun deleteMutation(id: Long) {
        removeMutations(listOf(id), clearRejections = false)
    }

    /**
     * The mutations of [rows] visible to [identity] — its own, and ownerless ones it may adopt — or
     * all of them when there is no identity to filter by, or the owner records cannot be read.
     */
    private fun visibleTo(
        identity: String?,
        owners: MutationOwners,
        rows: List<MutationRow>,
    ): List<MutationRow> {
        if (identity == null || owners !is MutationOwners.Known) return rows
        return rows.filter { row -> belongsTo(owners.byId[row.id], identity) }
    }

    /**
     * Claims the one report, per store, that a write's dry-run status could not be determined.
     *
     * @return `true` the first time only; the caller then logs it at ERROR.
     */
    internal fun claimUnclassifiableWriteReport(): Boolean =
        unclassifiableWriteReported.compareAndSet(expect = false, update = true)

    /**
     * Refuses the engine's reserved state table (see [RESERVED_TABLE_NAME]) as the target of a
     * cache write or a queued write, so no user-facing path can touch the reserved rows.
     *
     * @return [table], when it is an app table.
     */
    private fun requireUserTable(table: String): String {
        require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
        return table
    }

    /**
     * Logs a mutation the overlay skipped, once per mutation: the overlay is recomputed on every
     * emission, and a mutation that fails once fails every time. Never the body or filter values.
     */
    private fun logSkippedOverlay(mutation: MutationRow, failure: Exception) {
        var first = false
        loggedOverlaySkips.update { logged ->
            first = mutation.id !in logged
            logged + mutation.id
        }
        if (!first) return
        sdkLogger.warning {
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
     * [serverJson] for the query [signature]. Synced ones are left out: the server already reflects
     * them.
     *
     * @param shape the query's shape; `null` looks up the one recorded for [signature].
     */
    suspend fun overlayPendingOnResponse(
        table: String,
        signature: String,
        serverJson: String,
        shape: QueryShape? = null,
    ): String {
        if (isReservedTable(table)) return serverJson
        val pending =
            visibleTo(
                identityProvider(),
                mutationQueueState.owners(),
                mutationDao.pendingForTable(table),
            )
        if (pending.isEmpty()) return serverJson

        return overlayMutations(
            serverJson,
            pending,
            admissionOf(table, signature, shape),
            mutationQueueState.unconfirmedSends(),
            sameSelectRowsFor(table, signature, shape, pending, ownJson = serverJson),
        )
    }

    /**
     * Overlays the current identity's pending mutations, and the synced ones [cachedJson] does not
     * already contain, onto a cached payload served while offline.
     *
     * @param signature The payload's query, or `null` when not known: every synced mutation
     *   applies.
     * @param shape the query's shape, recorded with the payload; `null` looks up the one recorded.
     */
    suspend fun overlayCachedPayload(
        table: String,
        signature: String?,
        cachedJson: String,
        shape: QueryShape? = null,
    ): String {
        if (isReservedTable(table)) return cachedJson
        if (signature != null && shape != null)
            queryShapes.remember(table, signature, shape, persist = true)
        val coverage = mutationQueueState.syncedMutationCoverage()
        val all =
            visibleTo(
                    identityProvider(),
                    mutationQueueState.owners(),
                    mutationDao.allForTable(table),
                )
                .filter { mutation -> belongsOnPayload(mutation, signature, coverage) }
        if (all.isEmpty()) return cachedJson

        val admission = signature?.let { admissionOf(table, it, shape) } ?: QueryAdmission.EVERY_ROW
        return overlayFor(admission, mutationQueueState.unconfirmedSends())
            .overlay(
                cachedJson,
                all,
                sameSelectRowsFor(table, signature, shape, all, ownJson = cachedJson),
            )
    }

    /** [overlayCachedPayload] for a payload whose signature is not known. */
    suspend fun overlayCachedPayload(table: String, cachedJson: String): String =
        overlayCachedPayload(table, signature = null, cachedJson = cachedJson)

    /**
     * The cached payload of [table]/[signature] with the current identity's mutations overlaid, as
     * it changes; `null` while nothing is known. With no payload but mutations that produce rows,
     * the overlay onto an empty list is emitted without being stored. A cache that is not the
     * current identity's counts as no payload. Inputs and output are de-duplicated.
     */
    fun overlaidPayloadFlow(
        table: String,
        signature: String,
        shape: QueryShape? = null,
    ): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return flow {
            // A payload received before the cache was emptied may reach this reader after the new
            // owner does: until what it receives matches the database, the database is read
            var trustedEmptiedCount = cacheOwner.emptiedCount()
            combine(
                    observePayload(table, signature).distinctUntilChanged(),
                    mutationDao.allForTableFlow(table).distinctUntilChanged(),
                    mutationQueueState.ownersFlow(),
                    identityChanges,
                    mutationQueueState.syncedMutationCoverageFlow(),
                ) { storedJson, allMutations, owners, _, coverage ->
                    // Owner first: the count moves before it, so a new owner comes with a new count
                    val served = cacheServesCurrentIdentity()
                    val emptiedCount = cacheOwner.emptiedCount()
                    val currentJson =
                        if (emptiedCount == trustedEmptiedCount) {
                            storedJson
                        } else {
                            objectDao.get(table, signature)?.payload.also { current ->
                                if (current == storedJson) trustedEmptiedCount = emptiedCount
                            }
                        }
                    overlaidOrNull(
                        table,
                        signature,
                        shape,
                        currentJson.takeIf { served },
                        allMutations,
                        owners,
                        coverage,
                    )
                }
                .distinctUntilChanged()
                .collect { payload -> emit(payload) }
        }
    }

    /**
     * [overlaidPayloadFlow]'s value for [cachedJson], the payload the current identity is served
     * (`null` for none), with [allMutations] overlaid as [owners] and [coverage] say.
     */
    private suspend fun overlaidOrNull(
        table: String,
        signature: String,
        shape: QueryShape?,
        cachedJson: String?,
        allMutations: List<MutationRow>,
        owners: MutationOwners,
        coverage: Map<Long, Set<String>>,
    ): String? {
        // A live query's shape is known for as long as it is read; it is written once a payload
        // exists
        if (shape != null)
            queryShapes.remember(table, signature, shape, persist = cachedJson != null)
        val mutations =
            visibleTo(identityProvider(), owners, allMutations).filter { mutation ->
                belongsOnPayload(mutation, signature, coverage)
            }
        if (mutations.isEmpty()) return cachedJson
        val overlaid =
            overlayMutations(
                cachedJson ?: EMPTY_JSON_ARRAY,
                mutations,
                admissionOf(table, signature, shape),
                mutationQueueState.unconfirmedSends(),
                sameSelectRowsFor(table, signature, shape, mutations, ownJson = cachedJson),
            )
        return if (cachedJson == null && isEmptyJsonArrayPayload(overlaid)) null else overlaid
    }

    /**
     * Overlays [mutations] onto [serverJson] for a query with [admission], showing only the rows it
     * admits.
     *
     * @param unconfirmedSends mutations whose send may have reached the server; see
     *   [isSyncedOrUnconfirmed].
     * @param knownRows rows the table's other queries hold, read with the same columns: those a
     *   mutation makes start matching join.
     */
    private fun overlayMutations(
        serverJson: String,
        mutations: List<MutationRow>,
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
        knownRows: List<JsonObject> = emptyList(),
    ): String = overlayFor(admission, unconfirmedSends).overlay(serverJson, mutations, knownRows)

    /**
     * The rows the table's other cached queries hold, read with the same [Projection] as the query
     * cached under [signature], when one of [mutations] may bring such a row into it; see
     * [mayBringRowsIn]. Empty otherwise, and when the query's shape is unknown: then the other
     * payloads are not read at all.
     *
     * @param ownJson the rows the query shows before [mutations], when known.
     */
    private suspend fun sameSelectRowsFor(
        table: String,
        signature: String?,
        shape: QueryShape?,
        mutations: List<MutationRow>,
        ownJson: String?,
    ): List<JsonObject> {
        if (signature == null) return emptyList()
        val known = shape ?: queryShapes.shapeOf(table, signature) ?: return emptyList()
        // The filter's column names as a JSON body writes them, once for every mutation
        val filterColumns = known.conditions.map { condition -> jsonNameOf(condition.column) }
        val keyColumns = primaryKeys.of(table)
        val ownKeys by lazy {
            ownJson?.let { text ->
                parsedTextOf(table, signature, text).keysFor(keyColumns).filterNotNullTo(HashSet())
            } ?: emptySet()
        }
        val bringing = mutations.filter { mutation ->
            mayBringRowsIn(mutation, known, filterColumns, keyColumns) { ownKeys }
        }
        if (bringing.isEmpty()) return emptyList()
        val mayMatter =
            rowsThatMayMatter(
                table,
                mutationsThatReach(bringing, mutations),
                unknownMatters = false,
            ) ?: return emptyList()
        val projection = projectionOf(known)
        val shapes = queryShapes.shapesOf(table)
        val sameSelect =
            objectDao.allForTable(table).filter { row ->
                row.signature != signature &&
                    shapes[row.signature]?.let(::projectionOf) == projection
            }
        if (sameSelect.isEmpty()) return emptyList()
        val parsed = sameSelect.map { row -> row to parsedPayloadOf(row) }
        return KnownRows(parsed, shapes, keyColumns, mayMatter)
            .targetFor(signature, known, rows = emptyList())
            .sameSelectRows
    }

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
     * The admission of the query cached under [signature]: [shape]'s, or the recorded one's, or
     * every row when neither is known, since unknown conditions admit: so for a payload an earlier
     * build cached, until it is read.
     */
    private suspend fun admissionOf(
        table: String,
        signature: String,
        shape: QueryShape?,
    ): QueryAdmission =
        (shape ?: queryShapes.shapeOf(table, signature))?.admission ?: QueryAdmission.EVERY_ROW

    /**
     * The overlay for a query with [admission], de-duplicating by key the rows the server may
     * already hold.
     */
    private fun overlayFor(
        admission: QueryAdmission,
        unconfirmedSends: Set<Long>,
    ): MutationOverlay =
        MutationOverlay(
            json = json,
            admission = admission,
            primaryKeys = primaryKeys,
            mayAlreadyHold = { mutation -> isSyncedOrUnconfirmed(mutation, unconfirmedSends) },
            onMutationFailure = ::logSkippedOverlay,
        )

    /**
     * The overlay a patch runs for a query with [admission]: the server holds every row the write
     * made, so a row a refresh already holds is not added twice, and an upsert row with no key to
     * place it by is left out, the query marked stale instead.
     */
    private fun patchOverlayFor(admission: QueryAdmission): MutationOverlay =
        MutationOverlay(
            json = json,
            admission = admission,
            primaryKeys = primaryKeys,
            mayAlreadyHold = { true },
            placesKeylessUpsertRows = false,
            onMutationFailure = ::logSkippedOverlay,
        )

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
     * @param liveSignatures the table's queries with a live reader; by default
     *   [liveSignatureProvider]'s.
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
        liveSignatures: Set<String>? = null,
        startedAtTick: Long? = null,
    ) {
        requireUserTable(table)
        // A clear after this point voids the patch: each write below checks it under the teardown
        // lock
        val guardTick = startedAtTick ?: monotonicTick()
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
        applyPatch(table, listOf(write), liveSignatures, guardTick)
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
                    filterJson = encode(byKey, json),
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                )
            }
        if (writes.isNotEmpty())
            applyPatch(table, writes, liveSignatures = null, guardTick = startedAtTick)
    }

    /**
     * Overlays [writes], in order, on every cached query of [table] and creates the payloads live
     * queries without one now admit; see [patchCachedQueries]. A query the patch may leave wrong
     * ([patchMayLeaveWrong]) is marked stale and fetched again. A cache that is not the current
     * identity's is left alone.
     */
    private suspend fun applyPatch(
        table: String,
        writes: List<MutationRow>,
        liveSignatures: Set<String>?,
        guardTick: Long,
    ) {
        if (!cacheServesCurrentIdentity()) return
        val toFetchAgain = mutableSetOf<String>()
        tableWriteLock(table).withLock {
            recordLocalMutation(table)
            val cached = objectDao.allForTable(table)
            val uncachedLive =
                (liveSignatures ?: liveSignatureProvider(table)) -
                    cached.mapTo(mutableSetOf()) { row -> row.signature }
            // Only a patch that may write claims an unowned cache for the current identity
            if (cached.isEmpty() && uncachedLive.isEmpty()) return@withLock
            if (!cacheTakesWritesOfCurrentIdentity()) return@withLock
            val parsed = cached.map { row -> row to parsedPayloadOf(row) }
            val shapes = queryShapes.shapesOf(table)
            val keyColumns = primaryKeys.of(table)
            val mayMatter =
                rowsThatMayMatter(table, writes, unknownMatters = true) ?: { _, _ -> false }
            val known = KnownRows(parsed, shapes, keyColumns, mayMatter)
            for ((row, payload) in parsed) {
                val rows = payload.rows ?: continue
                val shape = shapes[row.signature]
                val target =
                    if (writes.any { write -> mayJoinKnownRows(write, shape) }) {
                        known.targetFor(
                            row.signature,
                            shape,
                            rows,
                            rowKeys = payload.keysFor(keyColumns),
                        )
                    } else {
                        PatchTarget(rows, shape, emptyList(), emptyList(), keyColumns.toSet())
                    }
                val overlay = patchOverlayFor(target.admission)
                val patched = overlay.overlayRows(rows, writes, knownRows = target.sameSelectRows)
                val wrong = patchMayLeaveWrong(writes, target, json, ::conflictColumnsOf)
                if (patched.visible == rows && (!wrong || isStaleStamp(row.updatedAt))) continue
                // The payload stays as old as what the server last said: a local write is not fresh
                // data, and one it may leave wrong is stale
                val stamp = if (wrong) staleStampOf(row.updatedAt) else row.updatedAt
                val next = row.copy(payload = overlay.encode(patched.visible), updatedAt = stamp)
                if (!writeUnlessTornDown(guardTick, next)) return@withLock
                if (wrong) toFetchAgain += row.signature
            }
            toFetchAgain +=
                createLivePayloads(table, uncachedLive, shapes, writes, known, guardTick)
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /**
     * Whether a patch of [write] reads rows other queries hold for a query of [shape]: an UPSERT
     * matches them by key, and an UPDATE may bring one in only when it sets a column the query's
     * filter reads. INSERT and DELETE never read them.
     */
    private fun mayJoinKnownRows(write: MutationRow, shape: QueryShape?): Boolean =
        when (write.operation) {
            MutationOperation.UPSERT -> true
            MutationOperation.UPDATE ->
                patchOf(write)?.let { patch -> mayGainRowsBy(patch, shape) } ?: true
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
        parsed: List<Pair<ObjectRow, ParsedPayload>>,
        shapes: Map<String, QueryShape?>,
        private val keyColumns: List<String>,
        mayMatter: (JsonObject, List<String>) -> Boolean,
    ) {
        /**
         * A row, the query that holds it, its projection (`null` when that is not known), and
         * whether it [matters] to the writes at hand.
         */
        private class Held(
            val signature: String,
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
                    val projection = shapes[row.signature]?.let(::projectionOf)
                    forEachKeyedRow(payload) { key, candidate ->
                        if (key !in indexed) return@forEachKeyedRow
                        byKey.getOrPut(key) { mutableListOf() } +=
                            Held(row.signature, projection, candidate, mayMatter(candidate, key))
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
         * What the patch of the query cached under [signature], holding [rows], may draw on.
         *
         * @param rowKeys [rows]' keys when already read; see [ParsedPayload.keysFor].
         */
        fun targetFor(
            signature: String,
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
                            held.signature != signature && held.projection == projection
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
                        held.signature != signature &&
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
                .mapNotNull { mutation -> decodeOrNull(mutation.filterJson, json) }
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
                    decodeOrNull(mutation.filterJson, json)?.conditions?.forEach { condition ->
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
    private fun parsedPayloadOf(row: ObjectRow): ParsedPayload =
        parsedTextOf(row.table, row.signature, row.payload)

    /** [parsedPayloadOf] for [text], the rows the query cached under [signature] shows. */
    private fun parsedTextOf(table: String, signature: String, text: String): ParsedPayload {
        val key = table to signature
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
     * Writes a payload for each of [signatures], queries a live reader observes that have none, of
     * the rows [writes] wrote that the query admits. Nothing is written for a query that would stay
     * empty, and nothing once the cache was cleared or dropped since [guardTick]. A query the rows
     * known here may not show right gets no payload; it is returned, to be fetched again.
     *
     * @param shapes the table's known query shapes, by cache key.
     * @param known the rows the table's other queries hold.
     * @return the queries to fetch again.
     */
    private suspend fun createLivePayloads(
        table: String,
        signatures: Set<String>,
        shapes: Map<String, QueryShape?>,
        writes: List<MutationRow>,
        known: KnownRows,
        guardTick: Long,
    ): Set<String> {
        val toFetchAgain = mutableSetOf<String>()
        for (signature in signatures) {
            // A live query's shape is known while it is read; unknown conditions admit
            val shape = shapes[signature]
            val target = known.targetFor(signature, shape, rows = emptyList())
            if (patchMayLeaveWrong(writes, target, json, ::conflictColumnsOf)) {
                toFetchAgain += signature
                continue
            }
            val admission = target.admission
            val overlay = patchOverlayFor(admission)
            val created =
                overlay
                    .overlayRows(emptyList(), writes, knownRows = target.sameSelectRows)
                    .written
                    .filter(admission::admits)
            if (created.isEmpty()) continue
            // Local rows alone are not the query's answer: stale, so a read asks the server when it
            // can
            val row =
                ObjectRow(
                    table = table,
                    signature = signature,
                    payload = overlay.encode(created),
                    updatedAt = staleStampOf(nowMillis()),
                )
            // A created payload is not a network response: it contains no synced mutation
            val written = mutationQueueMutex.withLock {
                teardownMutex.withLock {
                    if (predatesCacheTeardown(guardTick)) {
                        false
                    } else {
                        writePayloadAndCoverage(row, coversSyncedMutations = false)
                        true
                    }
                }
            }
            if (!written) return toFetchAgain
            if (shape != null) queryShapes.remember(table, signature, shape, persist = true)
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
            recordLocalMutation(table)
            val shapes = queryShapes.shapesOf(table)
            val cached = objectDao.allForTable(table)
            for (row in cached) {
                val reached =
                    narrowing == null ||
                        payloadRowsOf(json, row.payload).orEmpty().any { element ->
                            element !is JsonObject ||
                                rowMatch(element, narrowing) != RowMatch.EXCLUDED
                        } ||
                        (patch != null && mayGainRowsBy(patch, shapes[row.signature]))
                if (!reached) continue
                toFetchAgain += row.signature
                if (isStaleStamp(row.updatedAt)) continue
                val next = row.copy(updatedAt = staleStampOf(row.updatedAt))
                if (!writeUnlessTornDown(startedAtTick, next)) return@withLock
            }
            // A live query with nothing cached may gain the rows an UPDATE changes
            if (patch != null) {
                toFetchAgain +=
                    liveSignatureProvider(table) -
                        cached.mapTo(mutableSetOf()) { row -> row.signature }
            }
        }
        if (toFetchAgain.isNotEmpty()) onQueriesMarkedStale(table, toFetchAgain)
    }

    /**
     * Writes [row] unless the cache was cleared or dropped since [guardTick], as one step with the
     * teardown.
     *
     * @return `false` when a teardown voided the write.
     */
    private suspend fun writeUnlessTornDown(guardTick: Long, row: ObjectRow): Boolean =
        teardownMutex.withLock {
            if (predatesCacheTeardown(guardTick)) {
                false
            } else {
                objectDao.upsert(row)
                true
            }
        }

    /**
     * Marks mutation [id] replayed, clears its unconfirmed send and any stale rejected entry in the
     * same transaction, and advances [table]'s stale-refresh watermark so a refresh that started
     * before the replay cannot land after it.
     */
    suspend fun markMutationSynced(id: Long, table: String) {
        recordLocalMutation(table)
        mutationQueueMutex.withLock {
            // Its outcome is known now. And a crash between recording a rejection and removing its
            // mutation row leaves both: once the write has synced, the stale entry must go, or a
            // retry would re-send an accepted write.
            val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current -> current - id }
            val rejected =
                if (mutationQueueState.hasRejection(id))
                    mutationQueueState.rejectedRowWith(removeId = id)
                else null
            mutationDao.runInTransaction {
                mutationDao.markSynced(id = id, syncedAtMs = nowMillis())
                listOfNotNull(unconfirmed?.first, rejected?.first).forEach { row ->
                    objectDao.upsert(row)
                }
            }
            unconfirmed?.let { (_, next) -> mutationQueueState.unconfirmedSendsWritten(next) }
            rejected?.let { (_, next) -> mutationQueueState.rejectedWritten(next) }
        }
    }

    /**
     * Records, before its request goes out, that the send of mutation [id] has started — when it is
     * an INSERT whose rows all carry the table's primary key, the only kind whose replay can
     * tolerate finding its rows already on the server. It then replays as an upsert that ignores
     * duplicates, until [clearUnconfirmedSend] or its removal clears the record.
     *
     * @return `true` when this call added the record; `false` when there was one already, or none
     *   is kept for this mutation.
     */
    suspend fun markSendUnconfirmed(id: Long): Boolean {
        val mutation = mutationDao.byId(id) ?: return false
        if (!isKeyedInsert(mutation)) return false
        return updateUnconfirmedSends { current -> current + id }
    }

    /**
     * Whether [mutation] is an INSERT whose rows all carry its table's primary key: the only kind
     * whose replay can take rows already on the server as its own.
     */
    private fun isKeyedInsert(mutation: MutationRow): Boolean {
        if (mutation.operation != MutationOperation.INSERT) return false
        val body =
            try {
                json.parseToJsonElement(mutation.bodyJson) as? JsonArray
            } catch (_: IllegalArgumentException) {
                // A body that no longer parses is refused at replay; there is nothing to record.
                null
            } ?: return false
        return everyRowCarriesPrimaryKey(body, primaryKeys.of(mutation.table))
    }

    /**
     * Clears the record that the send of mutation [id] may have reached the server: it never did.
     */
    suspend fun clearUnconfirmedSend(id: Long) {
        updateUnconfirmedSends { current -> current - id }
    }

    /**
     * Whether mutation [id]'s send started and its outcome is unknown, so the server may hold its
     * rows.
     */
    suspend fun hasUnconfirmedSend(id: Long): Boolean = id in mutationQueueState.unconfirmedSends()

    /** Applies [changes] to the unconfirmed sends. @return `true` when they changed. */
    private suspend fun updateUnconfirmedSends(changes: (Set<Long>) -> Set<Long>): Boolean =
        mutationQueueMutex.withLock {
            val (row, next) =
                mutationQueueState.unconfirmedSendsRowWith(changes) ?: return@withLock false
            objectDao.upsert(row)
            mutationQueueState.unconfirmedSendsWritten(next)
            true
        }

    /**
     * Retires [table]'s synced mutations that no cached query needs any more — every cached payload
     * already contains them and no live query is waiting for its first — and those older than a
     * day. Skipped when [requestStartedAtTick] predates the table's last local write.
     */
    suspend fun retireCoveredSyncedMutations(table: String, requestStartedAtTick: Long? = null) {
        if (isReservedTable(table)) return
        if (isStaleRelativeToLocalWrite(table, requestStartedAtTick)) return
        tableWriteLock(table).withLock {
            val synced = mutationDao.allForTable(table).filter { row -> row.syncedAtMs != null }
            if (synced.isEmpty()) return@withLock
            val agedOut = syncedRetentionCutoffMs()
            val (aged, retained) =
                synced.partition { mutation -> (mutation.syncedAtMs ?: Long.MAX_VALUE) < agedOut }

            val cachedSignatures = objectDao.signaturesForTable(table).toSet()
            val retirable =
                if ((liveSignatureProvider(table) - cachedSignatures).isNotEmpty()) {
                    emptyList()
                } else {
                    val coverage = mutationQueueState.syncedMutationCoverage()
                    retained.filter { mutation ->
                        coverage[mutation.id].orEmpty().containsAll(cachedSignatures)
                    }
                }
            removeMutations(
                (aged + retirable).map { mutation -> mutation.id },
                clearRejections = false,
            )
        }
    }

    /**
     * Retires every synced mutation older than [SYNCED_RETENTION_MS], whatever still depends on it.
     *
     * A cached query that is never refreshed again — a detail screen visited once — would otherwise
     * keep a synced mutation, and its overlay, for as long as the query stays cached. After the
     * bound such a payload shows the server state it was fetched with, without the write, until it
     * is refreshed.
     */
    suspend fun retireAgedSyncedMutations() {
        val aged = mutationDao.syncedBefore(syncedRetentionCutoffMs())
        removeMutations(aged.map { mutation -> mutation.id }, clearRejections = false)
    }

    private fun syncedRetentionCutoffMs(): Long = nowMillis() - SYNCED_RETENTION_MS

    /* ──────────────────────── Size-based eviction (DB) ──────────────────────── */

    /**
     * Evict the oldest cached SELECT payloads until the cache is back within
     * [OfflineCacheSettings.maxDbSizeBytes].
     *
     * Size is measured **logically** via [ObjectDao.totalPayloadBytes] (summed payload length),
     * never the on-disk database file size. SQLite returns pages freed by `DELETE` to its internal
     * freelist rather than to the OS, so the file never shrinks after eviction; driving the loop
     * off file size therefore empties the cache and then spins forever holding [evictionMutex].
     * Summed payload length decreases as rows are deleted, so it terminates.
     *
     * Eviction is **triggered** once the cache exceeds [OfflineCacheSettings.maxDbSizeBytes] and
     * then **drains** down to a hysteresis floor of `max - minRetainedBytes` (coerced to the
     * ceiling when [OfflineCacheSettings.minRetainedBytes] is non-positive or meets/exceeds the
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
     * eviction is disabled ([OfflineCacheSettings.maxDbSizeBytes] is `null`).
     *
     * Reclaiming the on-disk pages freed by these deletes (`VACUUM`/`auto_vacuum`) is intentionally
     * out of scope: cache correctness doesn't depend on returning bytes to the filesystem.
     */
    internal suspend fun enforceDbSizeLimitIfNeeded() {
        val max = settings.maxDbSizeBytes ?: return

        // Cheap pre-check outside the lock: eviction triggers only once the cache exceeds the
        // ceiling, so skip acquiring the mutex when already within budget (the common case even
        // on the writes that reach enforcement).
        if (objectDao.totalPayloadBytes() <= max) return

        // Once triggered, drain a little below the ceiling to this hysteresis floor so a burst of
        // writes crossing the limit doesn't re-trigger a pass on every subsequent write. When
        // minRetainedBytes is non-positive or meets/exceeds the ceiling the headroom coerces to
        // zero and the floor is the ceiling, keeping small-cache configs evicting fully.
        val evictionFloorBytes =
            if (settings.minRetainedBytes in 1L until max) max - settings.minRetainedBytes else max

        evictionMutex.withLock {
            var iterations = 0
            var currentBytes = objectDao.totalPayloadBytes()
            while (currentBytes > evictionFloorBytes && iterations < MAX_EVICTION_ITERATIONS) {
                // Coerce to at least one row so a misconfigured non-positive evictionBatchSize
                // can't stall eviction — a zero-row batch trips the progress guard immediately
                // and would otherwise leave the cache permanently over budget.
                val deleted =
                    objectDao.deleteOldestPayloadRows(
                        limit = settings.evictionBatchSize.coerceAtLeast(1),
                        // A payload a live reader shows stays, or the reader would drop to no data
                        keptSignatures = allLiveSignaturesProvider().take(MAX_KEPT_LIVE_SIGNATURES),
                    )
                // Progress guard: no rows removed means the cache can't shrink further, so stop
                // instead of re-measuring the same over-limit size forever.
                if (deleted == 0) break
                iterations++
                currentBytes = objectDao.totalPayloadBytes()
            }
            if (iterations > 0) queryShapes.dropOrphans()
        }
    }

    private companion object {
        /**
         * How long a synced mutation may stand in for its write on cached queries that have not
         * been refreshed since it replayed: a day, by the device clock.
         */
        const val SYNCED_RETENTION_MS = 24L * 60 * 60 * 1000

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
        const val MAX_KEPT_LIVE_SIGNATURES = 500

        /** The characters of payload text whose parsed rows are kept, about a megabyte of text. */
        const val PARSED_PAYLOAD_TEXT_KEPT = 1_000_000
    }
}

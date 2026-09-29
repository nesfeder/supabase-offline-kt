@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.cache.indexOfKey
import io.github.nesfeder.supabase.offline.internal.cache.payloadRowsOf
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.everyRowCarriesPrimaryKey
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.encode
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.admissionOf
import io.github.nesfeder.supabase.offline.internal.util.EMPTY_JSON_ARRAY
import io.github.nesfeder.supabase.offline.internal.util.isEmptyJsonArrayPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/**
 * Builds the default tick source backing the stale-refresh watermark: elapsed nanoseconds since
 * the owning [OfflineStore] was constructed, read from [TimeSource.Monotonic].
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
     * The producing request started **before** the cache was torn down by [OfflineStore.clearAll]
     * — sign-out, account switch, or account deletion. The response belongs to a cache generation
     * that no longer exists and, on an account switch, to a different user entirely. Unlike
     * [DROPPED_STALE] the caller must **not** re-fetch: a follow-up issued during teardown can
     * still carry the outgoing session's JWT and would re-import the very rows teardown removed.
     */
    DROPPED_CACHE_CLEARED,
}

/** What [OfflineStore.enqueueOwnedBy] did with a write. */
internal sealed interface EnqueueOutcome {

    /**
     * The write is in the queue.
     *
     * @property id Its mutation id.
     */
    data class Queued(val id: Long) : EnqueueOutcome

    /** The owner records cannot be read, so no owner could be recorded; the write was not queued. */
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
    @Suppress("unused")
    private val dbPathProvider: DbPathProvider,
    private val settings: OfflineCacheSettings,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    @Suppress("unused")
    private val dbSizeProvider: (Path) -> Long? = ::currentDbSizeBytes,
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

    /**
     * Logger for conditions the store recovers from on its own (an overlay that skips a mutation it
     * cannot apply). Wired by the client, like [liveSignatureProvider]; silent when built directly.
     */
    internal var sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)

    /**
     * The mutation queue's persisted state: the owner of each queued write, the rejected list, the
     * synced-mutation coverage and the unconfirmed sends.
     */
    internal val mutationQueueState: MutationQueueState = MutationQueueState(
        objectDao = objectDao,
        json = OfflineJson,
        nowMillis = nowMillis,
        logger = { sdkLogger },
        pendingKeyedInsertIds = {
            mutationDao.pendingAll().filter { mutation -> isKeyedInsert(mutation) }.map { mutation -> mutation.id }.toSet()
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
     * the teardown tick and deletes under it, and every overlay and payload write checks the tick and
     * writes under it. So a write whose operation started before a teardown can never land after it.
     */
    private val teardownMutex = Mutex()

    /**
     * The identity whose queued writes the overlay shows and the counts count — its stored value —
     * or `null` for no filtering (a store driven directly, with no session model). Wired by the
     * client.
     */
    internal var identityProvider: suspend () -> String? = { null }

    /** Emits when the session may have changed, so overlays and counts re-apply [identityProvider]. */
    internal var identityChanges: Flow<Any?> = flowOf(Unit)

    /** Where the store learns each table's primary key, for de-duplicating overlaid rows. */
    internal var primaryKeys: PrimaryKeys = DefaultPrimaryKeys

    /**
     * Whether mutations an earlier build queued without an owner belong to the anonymous identity:
     * `true` when the client has no Auth plugin, so there is no other identity. Wired by the client.
     */
    internal var ownerlessMutationsAreAnonymous: Boolean = true

    /** Mutation ids whose overlay failure was already logged, so a failing one is logged once. */
    private val loggedOverlaySkips = MutableStateFlow<Set<Long>>(emptySet())

    /** Whether an unclassifiable dry-run status has been reported yet; see [reportUnclassifiableWriteOnce]. */
    private val unclassifiableWriteReported = MutableStateFlow(false)

    /**
     * Whether the synced [mutation] still belongs on the payload cached for [signature]: a synced
     * mutation stands in for its write only on payloads that do not already contain it — those no
     * network response wrote after it replayed (see [MutationQueueState.syncedMutationCoverage]).
     * A pending mutation always belongs; so does any mutation when [signature] is not known.
     */
    private fun belongsOnPayload(mutation: MutationRow, signature: String?, coverage: Map<Long, Set<String>>): Boolean =
        mutation.syncedAtMs == null || signature == null || signature !in coverage[mutation.id].orEmpty()

    /**
     * Per-table locks serializing every write to the object cache — [addPayload] and the
     * read-modify-write overlays ([patchCachedQueries], [clearTable],
     * [deleteTablePayload]).
     *
     * The overlay is not atomic on its own: it snapshots every cached payload for the table,
     * patches each in memory, and writes each back **whole**. Two overlays interleaving on one
     * table therefore lose a write — the second snapshots before the first has written, appends
     * its own row, and writes the first's row back out. Nothing replays an online-success
     * overlay (it never enters the mutation queue), so the lost row is simply gone from every
     * reactive observer until some later network read of that signature. In practice, a batch
     * job upserting several rows from four parallel workers left a screen on a blank loading
     * placeholder for minutes after its row was already on the server.
     *
     * The same read-modify-write race exists between an overlay and a fresh network payload:
     * the stale-response watermark refuses a response whose request *predates* the local write,
     * but a response that started after it can still land between the overlay's snapshot and
     * its write and be rolled back by it. Taking the lock in [addPayload] as well closes that
     * side, and keeps the watermark check itself ordered after any overlay in progress.
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
    private suspend fun tableWriteLock(table: String): Mutex =
        tableWriteLocksMutex.withLock { tableWriteLocks.getOrPut(table) { Mutex() } }

    /**
     * Table → tick of the most recent local write that changed what the cache knows: an
     * online-success mutation materialized by [patchCachedQueries] (which also carries the
     * realtime `applyServerRecord` / `applyServerDelete` edges) and a queue replay confirmed by
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
     * Tick of the most recent identity teardown ([clearAll]), or `0` while the cache has never
     * been wiped in this process.
     *
     * Teardown is the same ordering hazard as a local write, one generation wider: a SELECT that
     * left before sign-out returns the *outgoing* user's rows, and writing them afterwards
     * repopulates a cache that was just emptied precisely so one account's data never shows up
     * under the next. Global rather than per-table because [clearAll] wipes every table at once.
     */
    private val cacheClearedTick = MutableStateFlow(0L)

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
     * cache generation that [clearAll] has since discarded.
     *
     * @param requestStartedAtTick Tick captured before the request went out, or `null` for writers
     *   that carry no request-time information (they are never dropped).
     * @return `true` when the cache was torn down after the request left.
     */
    private fun predatesCacheTeardown(requestStartedAtTick: Long?): Boolean {
        if (requestStartedAtTick == null) return false
        return requestStartedAtTick < cacheClearedTick.value
    }

    /**
     * Whether [clearAll] has run since [tick] was read from [monotonicTickNow].
     *
     * A write captures a tick before its request goes out; if the cache was torn down while the
     * request was in flight, the write belongs to the outgoing identity and must neither be overlaid
     * onto the cache nor queued.
     *
     * @param tick A tick read before the operation started.
     * @return `true` when a teardown happened after [tick].
     */
    internal fun wasClearedSince(tick: Long): Boolean = predatesCacheTeardown(tick)

    /**
     * Rolling count of [addPayload] writes, used to run size enforcement only periodically
     * instead of on every network SELECT. Atomic because writes can arrive concurrently from
     * multiple coroutines/threads; exactness isn't required, only a monotonic cadence.
     */
    private val writeCounter = AtomicInt(0)

    /**
     * Signatures of a table that currently have a live `selectAsFlow` collector. Wired by
     * [io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl] to the client-scoped
     * [io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator] so [patchCachedQueries]
     * can materialize a server-successful mutation into a real cache row for a cache-less-but-
     * observed signature (the "save then stale UI" fix), and self-GC when nothing observes the
     * table. Defaults to reporting no live signatures so direct construction in tests keeps the
     * conservative "only patch existing rows" behavior unless a set is passed explicitly.
     */
    internal var liveSignatureProvider: (table: String) -> Set<String> = { _ -> emptySet() }

    /** Signatures of every table with a live reader, which eviction keeps; wired like [liveSignatureProvider]. */
    internal var allLiveSignaturesProvider: () -> Set<String> = { emptySet() }

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    /**
     * Writes a network SELECT's [payload] for [table]/[signature], unless it must be refused:
     * [AddPayloadOutcome.DROPPED_CACHE_CLEARED] when its request started before [clearAll],
     * [AddPayloadOutcome.DROPPED_STALE] when it started before the table's last local write (it would
     * roll that write back), and [AddPayloadOutcome.DROPPED_EMPTY] for an empty payload that is not
     * [authoritativeEmpty]. A payload written with a start tick is recorded as containing the table's
     * synced mutations. Runs under the table's write lock.
     *
     * @param authoritativeEmpty Whether an empty [payload] is the server's real answer (the request
     *   went out signed in), so it may replace cached rows or seed a cold slot.
     * @param requestStartedAtTick [monotonicTickNow] read before the request went out, or `null` for
     *   a writer with no request behind it, which is never refused as stale.
     * @param isTrusted Whether the response speaks for the current identity at all; one that does not
     *   is refused as [AddPayloadOutcome.DROPPED_UNTRUSTED], empty or not.
     * @return What happened, so a caller can re-fetch after a stale drop.
     */
    suspend fun addPayload(
        table: String,
        signature: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
        isTrusted: Boolean = true,
    ): AddPayloadOutcome {
        requireUserTable(table)
        // The guards and the write are one critical section under the table's write lock, so a
        // response is judged against the watermark *after* any overlay in progress has recorded
        // its write, and never lands between an overlay's snapshot and its write-back (where the
        // overlay would roll it straight back out) — see [tableWriteLocks].
        val outcome = tableWriteLock(table).withLock { mutationQueueMutex.withLock { teardownMutex.withLock {
            when {
                // The cache was wiped for an identity change while this request was in flight.
                // Its rows belong to the outgoing account; writing them here undoes the teardown.
                predatesCacheTeardown(requestStartedAtTick) -> AddPayloadOutcome.DROPPED_CACHE_CLEARED

                // The response left before the local write landed; keeping the cache is the only
                // answer that preserves it. The caller re-fetches to converge on real server state.
                isStaleRelativeToLocalWrite(table, requestStartedAtTick) -> AddPayloadOutcome.DROPPED_STALE

                !isTrusted -> AddPayloadOutcome.DROPPED_UNTRUSTED

                // Nothing about this emptiness is known to describe the signed-in user's data, so
                // it is never written — not over cached rows, and not into a cold or already-empty
                // slot where it would become the answer every later cache-first read is served.
                !authoritativeEmpty && isEmptyJsonArrayPayload(payload) -> AddPayloadOutcome.DROPPED_EMPTY

                else -> {
                    writePayloadAndCoverage(
                        row = ObjectRow(table = table, signature = signature, payload = payload, updatedAt = nowMillis()),
                        coversSyncedMutations = requestStartedAtTick != null,
                    )
                    AddPayloadOutcome.WRITTEN
                }
            }
        } } }

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
     * Enforcement is deliberately kept off the per-write hot path: measuring the cache
     * (a `SUM(LENGTH(payload))` scan) on every network SELECT would tax the dominant write
     * path for no benefit while the cache sits comfortably under its limit. Instead a pass is
     * triggered only when it can matter:
     *
     * - the incoming [payload] is large (>= [ENFORCEMENT_LARGE_PAYLOAD_CHARS]), so a single
     *   oversized write can't blow the budget before the periodic pass comes around; or
     * - a rolling counter reaches [ENFORCEMENT_WRITE_INTERVAL], a cheap periodic backstop that
     *   bounds accumulation from many small writes.
     *
     * Both checks are O(1); the expensive scan runs only when one fires. Returns `false`
     * immediately when size-based eviction is disabled ([OfflineCacheSettings.maxDbSizeBytes]
     * is `null`).
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
        val cadenceReached =
            (writeCounter.fetchAndAdd(1) + 1) % ENFORCEMENT_WRITE_INTERVAL == 0
        return isLargePayload || cadenceReached
    }

    suspend fun getPayload(table: String, signature: String): String? {
        if (isReservedTable(table)) return null
        return objectDao.get(table, signature)?.payload
    }

    suspend fun getPayloadWithTimestamp(table: String, signature: String): Pair<String, Long>? {
        if (isReservedTable(table)) return null
        val row = objectDao.get(table, signature) ?: return null
        return row.payload to row.updatedAt
    }

    fun observePayload(table: String, signature: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return objectDao.observe(table, signature)
            .map { row -> row?.payload }
    }

    suspend fun deleteTablePayload(table: String, signature: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            objectDao.delete(table, signature)
        }
    }

    /**
     * Drop every cached SELECT payload for [table].
     *
     * Advances the table's stale-refresh watermark: an emptied table is local truth just as much
     * as a patched row, so a refresh already in flight must not land on top of it. Unlike
     * [clearAll] this is not identity teardown, so a follow-up re-fetch is the right recovery.
     *
     * @param table Table whose cached payloads are removed.
     */
    suspend fun clearTable(table: String) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            recordLocalMutation(table)
            objectDao.deleteTable(table)
        }
    }

    /**
     * Wipes every cached payload, storage-object row and mutation, the mutation queue's records
     * included, in one transaction, and marks the teardown so that no response or cache patch started
     * before it lands afterwards. For identity changes: sign-out, account switch, deletion.
     */
    suspend fun clearAll() {
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
                // One transaction: a clear that fails part-way must not leave mutations without the
                // owner records in object_cache, where the next identity would adopt them. The
                // mutations go first as well, so even a store without transactions never does.
                mutationDao.runInTransaction {
                    mutationDao.deleteAll()
                    storageDao.deleteAll()
                    objectDao.deleteAll()
                }
                mutationQueueState.resetCaches()
                loggedOverlaySkips.value = emptySet()
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
        val row = StorageRow(
            key = storageKey(bucketId, path),
            bucketId = bucketId,
            path = path,
            localPath = file.toString(),
            sizeBytes = size,
            eTag = eTag,
            lastFetchedEpochMs = nowMillis()
        )
        storageDao.upsert(row)
    }

    suspend fun getStorageByKey(
        bucketId: String,
        path: String
    ): StorageRow? {
        return storageDao.getByKey(storageKey(bucketId, path))
    }

    suspend fun deleteStorageByKey(
        bucketId: String,
        path: String
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
    suspend fun storageRowsForObject(bucketId: String, path: String, variantPrefix: String): List<StorageRow> =
        storageDao.forObject(bucketId, path, variantPrefix)

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
        defaultToNull: Boolean = true
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
                defaultToNull = defaultToNull
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
        defaultToNull: Boolean = true
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
                ignoreDuplicates = ignoreDuplicates
            )
        )
    }

    /**
     * Enqueue an UPDATE mutation, with an optional [filter] describing the targeted rows.
     *
     * @param deltaColumns Columns of [body] whose written value is a change *relative* to the
     *   snapshot each declaration carries, rather than an absolute to restate on replay. Stored
     *   alongside the body so [MutationQueueReplayer] can rebase them onto the row's current
     *   server value; empty (the default) queues an ordinary absolute UPDATE.
     * @return The mutation's id.
     */
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null,
        deltaColumns: List<NumericDeltaColumn> = emptyList()
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
                deltaJson = encodeNumericDeltaColumns(deltaColumns, json)
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
        filter: FilterDescriptor? = null
    ): Long {
        requireUserTable(table)
        return mutationDao.insert(
            MutationRow(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}", // no body for delete
                filterJson = filter?.let { encode(it, json) },
                createdAtMs = nowMillis(),
                syncedAtMs = null
            )
        )
    }

    /**
     * Runs [enqueue] and records [identity] as the owner of the write it queues, in one transaction.
     *
     * The queue is kept per identity: each write records who made it, a replay pass sends only the
     * current identity's writes, and another identity's writes neither block it nor are sent by it.
     *
     * The write is refused when [clearAll] has run since [startedAtTick] — it belongs to the identity
     * that was torn down — and when the owner records cannot be read. The teardown check and the
     * insert share [clearAll]'s lock, so no teardown can fall between them.
     *
     * @param identity The stored value of the identity making the write, or `null` to record none (a
     *   store driven directly, with no session model).
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
        if (predatesCacheTeardown(startedAtTick)) return@withLock EnqueueOutcome.LocalDataCleared
        // Outside the transaction below: the first read of the unconfirmed sends may write.
        mutationQueueState.unconfirmedSends()
        if (identity == null) return@withLock EnqueueOutcome.Queued(enqueue())
        if (mutationQueueState.owners() !is MutationOwners.Known) return@withLock EnqueueOutcome.OwnershipUnreadable
        var id = 0L
        var owners: MutationOwners.Known? = null
        mutationDao.runInTransaction {
            id = enqueue()
            val (row, next) = checkNotNull(mutationQueueState.ownersRowWith { current -> current + (id to identity) })
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
        val pending = if (table == null) mutationDao.pendingAll() else mutationDao.pendingForTable(table)
        return pending.filter { row -> belongsTo(owners.byId[row.id], identity) }
    }

    /**
     * Records [identity] as the owner of every pending mutation that has none — rows an earlier build
     * queued — when [identity] may adopt them; otherwise leaves them untouched. Also drops owner
     * records of mutations that no longer exist.
     *
     * @return `false` when the owner records cannot be read.
     */
    suspend fun adoptOwnerlessMutations(identity: String): Boolean = mutationQueueMutex.withLock {
        val owners = mutationQueueState.owners() as? MutationOwners.Known ?: return@withLock false
        val unowned = if (mayAdoptOwnerlessMutations(identity)) {
            mutationDao.pendingAll().map { row -> row.id }.toSet() - owners.byId.keys
        } else {
            emptySet()
        }
        val stale = owners.byId.keys - mutationDao.allIds().toSet()
        if (unowned.isEmpty() && stale.isEmpty()) return@withLock true
        val (row, next) = checkNotNull(
            mutationQueueState.ownersRowWith { current -> (current - stale) + unowned.associateWith { identity } },
        )
        objectDao.upsert(row)
        mutationQueueState.ownersWritten(next)
        true
    }

    /**
     * Whether a mutation owned by [owner] — `null` for one an earlier build queued — is [identity]'s.
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
        val owners = mutationQueueState.owners() as? MutationOwners.Known ?: return earlier.isNotEmpty()
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
     * coverage, unconfirmed send and, when [clearRejections], a rejected entry — in one transaction.
     */
    private suspend fun removeMutations(ids: Collection<Long>, clearRejections: Boolean) {
        if (ids.isEmpty()) return
        mutationQueueMutex.withLock {
            val idSet = ids.toSet()
            val owners = mutationQueueState.ownersRowWith { current -> current - idSet }
            val coverage = mutationQueueState.syncedMutationCoverageRowWith { current -> current - idSet }
            val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current -> current - idSet }
            val rejected = if (clearRejections && idSet.any { id -> mutationQueueState.hasRejection(id) }) {
                mutationQueueState.rejectedRowWith(removeIds = idSet)
            } else {
                null
            }
            mutationDao.runInTransaction {
                idSet.forEach { id -> mutationDao.deleteById(id) }
                listOfNotNull(owners?.first, coverage?.first, unconfirmed?.first, rejected?.first).forEach { row -> objectDao.upsert(row) }
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
        val entry = StoredRejection(
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
        val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current -> current - mutation.id }
        mutationDao.runInTransaction {
            objectDao.upsert(rejectedRow)
            mutationDao.deleteById(mutation.id)
            listOfNotNull(owners?.first, unconfirmed?.first).forEach { row -> objectDao.upsert(row) }
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
     * @return The new pending mutation's id, or `null` when there is no such rejection, its body was
     *   too long to keep, it belongs to another identity, or the owner records cannot be read.
     */
    suspend fun retryRejectedMutation(id: Long, identity: String?): Long? = mutationQueueMutex.withLock {
        val entry = mutationQueueState.rejections().firstOrNull { rejection -> rejection.id == id } ?: return@withLock null
        if (identity != null && entry.owner != null && entry.owner != identity) return@withLock null
        if (identity != null && mutationQueueState.owners() !is MutationOwners.Known) return@withLock null
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
                val (ownersRow, next) = checkNotNull(mutationQueueState.ownersRowWith { current -> current + (newId to identity) })
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
    suspend fun dismissRejectedMutation(id: Long): Boolean =
        mutationQueueMutex.withLock { mutationQueueState.removeRejection(id) != null }

    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in insertion order across **all tables**, whatever their owner.
     */
    suspend fun allPendingMutations(): List<MutationRow> =
        mutationDao.pendingAll()

    /**
     * The current identity's pending mutations — of [table], or of every table when it is `null` —
     * in insertion order, re-emitted on each change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending mutation rows.
     */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        combine(mutationDao.pendingAllFlow(), mutationQueueState.ownersFlow(), identityChanges) { rows, owners, _ ->
            visibleTo(identityProvider(), owners, rows).filter { row -> table == null || row.table == table }
        }.distinctUntilChanged()

    /**
     * The current identity's pending mutations across all tables.
     */
    suspend fun pendingMutationCount(): Int =
        visibleTo(identityProvider(), mutationQueueState.owners(), mutationDao.pendingAll()).size

    /**
     * Flow of the current identity's pending mutations across all tables.
     */
    fun pendingMutationCountFlow(): Flow<Int> =
        pendingMutationsFlow(null).map { rows -> rows.size }

    /**
     * The current identity's pending mutations for a specific [table].
     */
    suspend fun pendingMutationCountForTable(table: String): Int =
        visibleTo(identityProvider(), mutationQueueState.owners(), mutationDao.pendingForTable(table)).size

    /**
     * Flow of the current identity's pending mutations for a specific [table].
     */
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
    private fun visibleTo(identity: String?, owners: MutationOwners, rows: List<MutationRow>): List<MutationRow> {
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
            "Overlay skipped pending ${mutation.operation} ${mutation.id} on ${mutation.table}: " +
                "${failure::class.simpleName}"
        }
    }

    /* ────────────────── Overlay pending mutations onto SELECT JSON ────────────────── */

    /**
     * Overlays the current identity's pending mutations of [table] onto a fresh network [serverJson]
     * for the query [signature]. Synced ones are left out: the server already reflects them.
     */
    suspend fun overlayPendingOnResponse(
        table: String,
        signature: String,
        serverJson: String
    ): String {
        if (isReservedTable(table)) return serverJson
        val pending = visibleTo(identityProvider(), mutationQueueState.owners(), mutationDao.pendingForTable(table))
        if (pending.isEmpty()) return serverJson

        return overlayMutations(serverJson, pending, signature, mutationQueueState.unconfirmedSends())
    }

    /**
     * Overlays the current identity's pending mutations, and the synced ones [cachedJson] does not
     * already contain, onto a cached payload served while offline.
     *
     * @param signature The payload's query, or `null` when not known: every synced mutation applies.
     */
    suspend fun overlayCachedPayload(
        table: String,
        signature: String?,
        cachedJson: String
    ): String {
        if (isReservedTable(table)) return cachedJson
        val coverage = mutationQueueState.syncedMutationCoverage()
        val all = visibleTo(identityProvider(), mutationQueueState.owners(), mutationDao.allForTable(table))
            .filter { mutation -> belongsOnPayload(mutation, signature, coverage) }
        if (all.isEmpty()) return cachedJson

        val admission = signature?.let(::admissionOf) ?: QueryAdmission.EVERY_ROW
        return overlayFor(admission, mutationQueueState.unconfirmedSends()).overlay(cachedJson, all)
    }

    /** [overlayCachedPayload] for a payload whose signature is not known. */
    suspend fun overlayCachedPayload(table: String, cachedJson: String): String =
        overlayCachedPayload(table, signature = null, cachedJson = cachedJson)

    /**
     * The cached payload of [table]/[signature] with the current identity's mutations overlaid, as it
     * changes; `null` while nothing is known. With no payload but mutations that produce rows, the
     * overlay onto an empty list is emitted without being stored. Inputs and output are
     * de-duplicated, so an unrelated write does not re-emit.
     */
    fun overlaidPayloadFlow(table: String, signature: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return combine(
            observePayload(table, signature).distinctUntilChanged(),
            mutationDao.allForTableFlow(table).distinctUntilChanged(),
            mutationQueueState.ownersFlow(),
            identityChanges,
            mutationQueueState.syncedMutationCoverageFlow(),
        ) { cachedJson, allMutations, owners, _, coverage ->
            val mutations = visibleTo(identityProvider(), owners, allMutations)
                .filter { mutation -> belongsOnPayload(mutation, signature, coverage) }
            val unconfirmed = mutationQueueState.unconfirmedSends()
            when {
                cachedJson != null ->
                    if (mutations.isEmpty()) cachedJson else overlayMutations(cachedJson, mutations, signature, unconfirmed)

                mutations.isNotEmpty() ->
                    overlayMutations(EMPTY_JSON_ARRAY, mutations, signature, unconfirmed)
                        .takeUnless { overlaid -> isEmptyJsonArrayPayload(overlaid) }

                else -> null
            }
        }.distinctUntilChanged()
    }

    /**
     * Overlays [mutations] onto [serverJson] for the query cached under [signature], showing only the
     * rows that query admits.
     *
     * @param unconfirmedSends mutations whose send may have reached the server; see [isSyncedOrUnconfirmed].
     */
    private fun overlayMutations(
        serverJson: String,
        mutations: List<MutationRow>,
        signature: String,
        unconfirmedSends: Set<Long>,
    ): String = overlayFor(admissionOf(signature), unconfirmedSends).overlay(serverJson, mutations)

    /** The overlay for a query with [admission], de-duplicating by key the rows the server may already hold. */
    private fun overlayFor(admission: QueryAdmission, unconfirmedSends: Set<Long>): MutationOverlay = MutationOverlay(
        json = json,
        admission = admission,
        primaryKeys = primaryKeys,
        mayAlreadyHold = { mutation -> isSyncedOrUnconfirmed(mutation, unconfirmedSends) },
        onMutationFailure = ::logSkippedOverlay,
    )

    /**
     * Whether the server may already hold [mutation]'s rows — it replayed, or its send may have
     * reached the server — so a payload's row with the same key may be the mutation's own.
     */
    private fun isSyncedOrUnconfirmed(mutation: MutationRow, unconfirmedSends: Set<Long>): Boolean =
        mutation.syncedAtMs != null || mutation.id in unconfirmedSends

    /**
     * Patches a write the server accepted, or a row it sent, into every cached query of [table], and
     * creates a payload for each live query of the table that has none and admits one of its rows.
     * Each query shows only the rows it admits. Rows cached for the table's other queries stand in for
     * rows the write changes but the query does not hold yet, so a row an UPDATE makes match joins.
     *
     * Serialized with every other writer of [table]'s cached payloads, and it moves the table's
     * stale-refresh watermark first, so a response already in flight cannot roll the patch back.
     *
     * @param operation one of the [MutationOperation] values.
     * @param bodyJson rows for INSERT and UPSERT, the changed columns for UPDATE, `"{}"` for DELETE.
     * @param filterJson the encoded [FilterDescriptor] of an UPDATE or DELETE; one that is missing touches nothing.
     * @param onConflict an UPSERT's conflict columns; `null` matches rows by the table's primary key.
     * @param liveSignatures the table's queries with a live reader; by default [liveSignatureProvider]'s.
     * @param startedAtTick [monotonicTickNow] as read when the write started; nothing is written if
     *   [clearAll] ran since. `null` takes the tick now.
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
        // A clear after this point voids the patch: each write below checks it under the teardown lock
        val guardTick = startedAtTick ?: monotonicTick()
        val write = MutationRow(
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
     * Patches the rows an UPDATE returned into the cached queries of [table], as [patchCachedQueries]
     * does: each returned row that carries the table's primary key sets, in the row with that key,
     * [body]'s columns and then its own. They are what the server wrote, columns it set itself
     * included. Rows without the key, or a response without rows, patch nothing.
     *
     * @param responseData the UPDATE's response body: a JSON array of rows, one object, or empty.
     * @param startedAtTick [monotonicTickNow] as read when the write started.
     */
    internal suspend fun patchReturnedRows(table: String, body: JsonElement, responseData: String, startedAtTick: Long) {
        requireUserTable(table)
        val keyColumns = primaryKeys.of(table)
        val bodyColumns: Map<String, JsonElement> = body as? JsonObject ?: emptyMap()
        val writes = returnedRowsOf(json, responseData).mapNotNull { row ->
            val key = primaryKeyOf(row, keyColumns) ?: return@mapNotNull null
            val byKey = FilterDescriptor(
                conditions = key.map { (column, value) -> FilterDescriptor.Condition(column, operatorId = "eq", value = value.jsonPrimitive.content) },
            )
            MutationRow(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = JsonObject(bodyColumns + row).toString(),
                filterJson = encode(byKey, json),
                createdAtMs = nowMillis(),
                syncedAtMs = null,
            )
        }
        if (writes.isNotEmpty()) applyPatch(table, writes, liveSignatures = null, guardTick = startedAtTick)
    }

    /**
     * Overlays [writes], in order, on every cached query of [table] and creates the payloads live
     * queries without one now admit; see [patchCachedQueries].
     */
    private suspend fun applyPatch(table: String, writes: List<MutationRow>, liveSignatures: Set<String>?, guardTick: Long) {
        tableWriteLock(table).withLock {
            recordLocalMutation(table)
            val cached = objectDao.allForTable(table)
            val parsed = cached.map { row -> row to payloadRowsOf(json, row.payload) }
            val keyColumns = primaryKeys.of(table)
            val known = rowsByKey(parsed, keyColumns)
            val now = nowMillis()
            for ((row, rows) in parsed) {
                if (rows == null) continue
                val overlay = overlayFor(admissionOf(row.signature), unconfirmedSends = emptySet())
                val elsewhere = known.filter { knownRow -> indexOfKey(rows, knownRow, keyColumns) == null }
                val patched = overlay.overlayRows(rows, writes, knownRows = elsewhere)
                if (patched.visible == rows) continue
                if (!writeUnlessTornDown(guardTick, row.copy(payload = overlay.encode(patched.visible), updatedAt = now))) return@withLock
            }
            val uncachedLive = (liveSignatures ?: liveSignatureProvider(table)) - cached.mapTo(mutableSetOf()) { row -> row.signature }
            createLivePayloads(table, uncachedLive, writes, known, now, guardTick)
        }
    }

    /**
     * The rows of [parsed] payloads that carry the whole [keyColumns], one per key, taken from the most
     * recently written payload that holds it.
     */
    private fun rowsByKey(parsed: List<Pair<ObjectRow, List<JsonElement>?>>, keyColumns: List<String>): List<JsonObject> {
        val seen = mutableListOf<JsonObject>()
        parsed.sortedByDescending { (row, _) -> row.updatedAt }.forEach { (_, rows) ->
            rows.orEmpty().forEach { element ->
                val row = element as? JsonObject
                if (row != null && primaryKeyOf(row, keyColumns) != null && indexOfKey(seen, row, keyColumns) == null) seen += row
            }
        }
        return seen
    }

    /**
     * Writes a payload for each of [signatures], queries a live reader observes that have none, of the
     * rows [writes] wrote that the query admits. Nothing is written for a query that would stay empty,
     * and nothing once [clearAll] has run since [guardTick].
     *
     * @param known rows cached for the table's other queries, the full rows an UPDATE or UPSERT changes.
     */
    private suspend fun createLivePayloads(
        table: String,
        signatures: Set<String>,
        writes: List<MutationRow>,
        known: List<JsonObject>,
        now: Long,
        guardTick: Long,
    ) {
        for (signature in signatures) {
            val overlay = overlayFor(admissionOf(signature), unconfirmedSends = emptySet())
            val admission = admissionOf(signature)
            val created = overlay.overlayRows(emptyList(), writes, knownRows = known).written.filter(admission::admits)
            if (created.isEmpty()) continue
            val row = ObjectRow(table = table, signature = signature, payload = overlay.encode(created), updatedAt = now)
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
            if (!written) return
        }
    }

    /**
     * Marks every cached query of [table] stale after a write the cache cannot patch: a response already
     * in flight is refused, and a read with a cache duration fetches again. The payloads stay, so an
     * offline read without a duration still has them.
     *
     * @param startedAtTick [monotonicTickNow] as read when the write started; nothing is written if
     *   [clearAll] ran since.
     */
    suspend fun markCachedQueriesStale(table: String, startedAtTick: Long) {
        requireUserTable(table)
        tableWriteLock(table).withLock {
            recordLocalMutation(table)
            for (row in objectDao.allForTable(table)) {
                if (row.updatedAt == STALE_UPDATED_AT) continue
                if (!writeUnlessTornDown(startedAtTick, row.copy(updatedAt = STALE_UPDATED_AT))) return@withLock
            }
        }
    }

    /**
     * Writes [row] unless [clearAll] has run since [guardTick], as one step with the teardown.
     *
     * @return `false` when a teardown voided the write.
     */
    private suspend fun writeUnlessTornDown(guardTick: Long, row: ObjectRow): Boolean = teardownMutex.withLock {
        if (predatesCacheTeardown(guardTick)) {
            false
        } else {
            objectDao.upsert(row)
            true
        }
    }

    /**
     * Marks mutation [id] replayed, clears its unconfirmed send and any stale rejected entry in the same
     * transaction, and advances [table]'s stale-refresh watermark so a refresh that started before
     * the replay cannot land after it.
     */
    suspend fun markMutationSynced(id: Long, table: String) {
        recordLocalMutation(table)
        mutationQueueMutex.withLock {
            // Its outcome is known now. And a crash between recording a rejection and removing its
            // mutation row leaves both: once the write has synced, the stale entry must go, or a
            // retry would re-send an accepted write.
            val unconfirmed = mutationQueueState.unconfirmedSendsRowWith { current -> current - id }
            val rejected = if (mutationQueueState.hasRejection(id)) mutationQueueState.rejectedRowWith(removeId = id) else null
            mutationDao.runInTransaction {
                mutationDao.markSynced(id = id, syncedAtMs = nowMillis())
                listOfNotNull(unconfirmed?.first, rejected?.first).forEach { row -> objectDao.upsert(row) }
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
        val body = try {
            json.parseToJsonElement(mutation.bodyJson) as? JsonArray
        } catch (_: IllegalArgumentException) {
            // A body that no longer parses is refused at replay; there is nothing to record.
            null
        } ?: return false
        return everyRowCarriesPrimaryKey(body, primaryKeys.of(mutation.table))
    }

    /** Clears the record that the send of mutation [id] may have reached the server: it never did. */
    suspend fun clearUnconfirmedSend(id: Long) {
        updateUnconfirmedSends { current -> current - id }
    }

    /** Whether mutation [id]'s send started and its outcome is unknown, so the server may hold its rows. */
    suspend fun hasUnconfirmedSend(id: Long): Boolean = id in mutationQueueState.unconfirmedSends()

    /** Applies [changes] to the unconfirmed sends. @return `true` when they changed. */
    private suspend fun updateUnconfirmedSends(changes: (Set<Long>) -> Set<Long>): Boolean = mutationQueueMutex.withLock {
        val (row, next) = mutationQueueState.unconfirmedSendsRowWith(changes) ?: return@withLock false
        objectDao.upsert(row)
        mutationQueueState.unconfirmedSendsWritten(next)
        true
    }

    /**
     * Retires [table]'s synced mutations that no cached query needs any more — every cached payload
     * already contains them and no live query is waiting for its first — and those older than a day.
     * Skipped when [requestStartedAtTick] predates the table's last local write.
     */
    suspend fun retireCoveredSyncedMutations(table: String, requestStartedAtTick: Long? = null) {
        if (isReservedTable(table)) return
        if (isStaleRelativeToLocalWrite(table, requestStartedAtTick)) return
        tableWriteLock(table).withLock {
            val synced = mutationDao.allForTable(table).filter { row -> row.syncedAtMs != null }
            if (synced.isEmpty()) return@withLock
            val agedOut = syncedRetentionCutoffMs()
            val (aged, retained) = synced.partition { mutation -> (mutation.syncedAtMs ?: Long.MAX_VALUE) < agedOut }

            val cachedSignatures = objectDao.signaturesForTable(table).toSet()
            val retirable = if ((liveSignatureProvider(table) - cachedSignatures).isNotEmpty()) {
                emptyList()
            } else {
                val coverage = mutationQueueState.syncedMutationCoverage()
                retained.filter { mutation -> coverage[mutation.id].orEmpty().containsAll(cachedSignatures) }
            }
            removeMutations((aged + retirable).map { mutation -> mutation.id }, clearRejections = false)
        }
    }

    /**
     * Retires every synced mutation older than [SYNCED_RETENTION_MS], whatever still depends on it.
     *
     * A cached query that is never refreshed again — a detail screen visited once — would otherwise
     * keep a synced mutation, and its overlay, for as long as the query stays cached. After the bound
     * such a payload shows the server state it was fetched with, without the write, until it is
     * refreshed.
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
     * never the on-disk database file size. SQLite returns pages freed by `DELETE` to its
     * internal freelist rather than to the OS, so the file never shrinks after eviction; driving
     * the loop off file size therefore empties the cache and then spins forever holding
     * [evictionMutex]. Summed payload length decreases as rows are deleted, so it terminates.
     *
     * Eviction is **triggered** once the cache exceeds [OfflineCacheSettings.maxDbSizeBytes] and
     * then **drains** down to a hysteresis floor of `max - minRetainedBytes` (coerced to the
     * ceiling when [OfflineCacheSettings.minRetainedBytes] is non-positive or meets/exceeds the
     * ceiling). Draining a little below the ceiling keeps a burst of writes that crosses the
     * limit from re-triggering a pass on every subsequent write.
     *
     * Termination is guaranteed three independent ways:
     * 1. the `currentBytes > evictionFloorBytes` loop condition (the floor is never above the
     *    ceiling),
     * 2. a progress guard that stops the instant a delete batch removes zero rows (the cache is
     *    empty, or nothing more can be evicted), and
     * 3. a hard [MAX_EVICTION_ITERATIONS] cap as a final belt-and-braces backstop.
     *
     * The loop holds [evictionMutex] via [withLock] so concurrent writers serialize rather than
     * racing, and the lock is always released even on cancellation. Does nothing when
     * size-based eviction is disabled ([OfflineCacheSettings.maxDbSizeBytes] is `null`).
     *
     * Reclaiming the on-disk pages freed by these deletes (`VACUUM`/`auto_vacuum`) is
     * intentionally out of scope: cache correctness doesn't depend on returning bytes to the
     * filesystem.
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
                val deleted = objectDao.deleteOldestPayloadRows(
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
        }
    }

    private companion object {
        /** The `updated_at` of a cached payload known to be stale: older than any cache duration. */
        const val STALE_UPDATED_AT = 0L

        /**
         * How long a synced mutation may stand in for its write on cached queries that have not been
         * refreshed since it replayed: a day, by the device clock.
         */
        const val SYNCED_RETENTION_MS = 24L * 60 * 60 * 1000

        /**
         * Run size enforcement once every this many [addPayload] writes. A cheap periodic
         * backstop that bounds cache growth from many small writes without measuring on every
         * write.
         */
        const val ENFORCEMENT_WRITE_INTERVAL = 32

        /**
         * A single payload of at least this many characters triggers an immediate enforcement
         * pass regardless of the write counter, so one oversized write can't overshoot the
         * budget before the periodic pass runs.
         */
        const val ENFORCEMENT_LARGE_PAYLOAD_CHARS = 4 * 1024

        /**
         * Hard upper bound on eviction-loop iterations. The progress guard already terminates
         * the loop on real input; this guarantees termination even against a degenerate store
         * whose measured size never drops despite reported deletions.
         */
        const val MAX_EVICTION_ITERATIONS = 1_000

        /** Live signatures eviction passes over at most, within SQLite's 999 bound variables. */
        const val MAX_KEPT_LIVE_SIGNATURES = 500
    }
}
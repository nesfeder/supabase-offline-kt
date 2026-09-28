@file:OptIn(ExperimentalTime::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine.encode
import io.github.nesfeder.supabase.offline.internal.postgrest.parseSignatureFilters
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
import kotlinx.serialization.json.JsonPrimitive
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

    /** Another identity's writes are pending; the write was not queued. */
    data object QueueOwnedByAnotherIdentity : EnqueueOutcome

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

    /** The outbox's persisted state: the rejected-mutation list and the queue owner. */
    internal val outboxState: OutboxStateStore = OutboxStateStore(objectDao, OfflineJson, nowMillis)

    /**
     * Serializes every decision about who owns the queue with the write that depends on it: an
     * enqueue checks the owner and inserts its row, a sweep checks the owner before replaying, a
     * retry re-queues a rejected write. Short critical sections only — never held across a request.
     */
    private val queueOwnershipMutex = Mutex()

    /**
     * `(table, signature)` → the tick at which the request that produced the cached payload started,
     * for every payload a network response wrote in this process. A synced mutation is still needed
     * by any cached payload whose request started before it replayed (or that no network response
     * wrote in this process at all); see [clearSyncedMutationsForTable].
     *
     * In memory by design: after a restart every cached payload counts as not refreshed, which only
     * keeps synced mutations longer, never shorter.
     */
    private val networkRefreshTicks = MutableStateFlow<Map<Pair<String, String>, Long>>(emptyMap())

    /** Mutation id → the tick at which it was marked synced in this process. */
    private val syncedMutationTicks = MutableStateFlow<Map<Long, Long>>(emptyMap())

    /**
     * Per-table locks serializing every write to the object cache — [addPayload] and the
     * read-modify-write overlays ([applyLocalMutationToCache], [clearTable],
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
     * online-success mutation materialized by [applyLocalMutationToCache] (which also carries the
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
     * [io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator] so [applyLocalMutationToCache]
     * can materialize a server-successful mutation into a real cache row for a cache-less-but-
     * observed signature (the "save then stale UI" fix), and self-GC when nothing observes the
     * table. Defaults to reporting no live signatures so direct construction in tests keeps the
     * conservative "only patch existing rows" behavior unless a set is passed explicitly.
     */
    internal var liveSignatureProvider: (table: String) -> Set<String> = { _ -> emptySet() }

    /* ────────────────────────── Object cache (SELECT payloads) ────────────────────────── */

    /**
     * Write [payload] into the object cache for [table]/[signature].
     *
     * Teardown guard (checked before everything else): a payload whose producing request started
     * before [clearAll] wiped the cache is refused as [AddPayloadOutcome.DROPPED_CACHE_CLEARED].
     * Sign-out, account switch and account deletion all clear the cache so one identity's rows
     * never surface under the next; a refresh still in flight at that moment answers for the
     * *outgoing* session and would put them straight back.
     *
     * Stale-response guard (checked next, before anything below): a payload whose producing
     * request started before [table]'s last local write is refused outright as
     * [AddPayloadOutcome.DROPPED_STALE]. A network SELECT that left before a local mutation
     * landed returns the pre-mutation server state; writing it here rolls the cache back over
     * [applyLocalMutationToCache]'s patch, and nothing replays that patch — online-success
     * mutations never enter the pending queue — so the row simply disappears until the next
     * refresh. The guard sits in front of the empty-clobber logic rather than beside it because
     * the two answer different questions: *is this response current* precedes *is this emptiness
     * real*. An in-flight refresh's pre-insert `[]` is stale first and authoritative second, so it
     * is dropped even when [authoritativeEmpty] is set — while an empty from a request that
     * started *after* the last local write still replaces cached rows, exactly as before.
     *
     * Empty-clobber guard: **an unattributed empty never persists.** Unless [authoritativeEmpty]
     * is set, an empty (`[]` / blank) payload is refused as [AddPayloadOutcome.DROPPED_EMPTY]
     * whatever the slot currently holds — non-empty rows, an earlier empty, or nothing at all.
     * The dominant cause of a *spurious* empty SELECT response is a cold-start auth race: a
     * refresh fires before the supabase-kt session has finished restoring, the request goes out
     * without a usable JWT, RLS filters every row, and the server returns `200 OK` with body
     * `[]`. Over cached rows that empty erases real data and every reactive `selectAsFlow`
     * observer re-emits an empty list — a loading placeholder that never resolves, or a list
     * screen that goes blank, until the subscription is recreated.
     *
     * The cold slot is the same bug one step earlier and was the gap this rule closes: with
     * nothing cached yet, an RLS-shaped `[]` used to be written with a fresh `updatedAt`, and
     * every later cache-first read served that phantom empty as the user's data. An
     * already-empty slot is refused for the same reason — rewriting it only refreshes the
     * timestamp that keeps the phantom alive past its cache duration.
     *
     * [authoritativeEmpty] is how a *genuine* server empty propagates: the select resolver sets
     * it when the request that produced [payload] went out with an authenticated session, so an
     * empty result is the server's real answer — every row matching the query is gone (e.g.
     * deleted from another device), or the user genuinely has no rows yet. It writes into a cold
     * slot too, so a legitimately-empty first read still seeds the cache and collectors observe
     * an empty list instead of waiting forever. Empties from a still-restoring or signed-out
     * session stay blocked. Explicit local deletions still propagate through
     * [applyLocalMutationToCache] and [clearTable] / [deleteTablePayload], all of which bypass
     * this guard.
     *
     * @param table Table the SELECT payload belongs to.
     * @param signature Canonical signature of the SELECT that produced [payload].
     * @param payload Raw JSON payload to cache.
     * @param authoritativeEmpty Whether an empty [payload] is the server's authoritative answer
     *   (the producing request carried an authenticated session) and may replace a non-empty
     *   cached payload. Has no effect on non-empty payloads.
     * @param requestStartedAtTick [monotonicTickNow] as read immediately before the producing
     *   request went out. `null` for writers with no request behind them, which are never
     *   dropped as stale.
     * @return What happened to the payload, so a network caller can re-fetch after a stale drop.
     */
    suspend fun addPayload(
        table: String,
        signature: String,
        payload: String,
        authoritativeEmpty: Boolean = false,
        requestStartedAtTick: Long? = null,
    ): AddPayloadOutcome {
        requireUserTable(table)
        // The guards and the write are one critical section under the table's write lock, so a
        // response is judged against the watermark *after* any overlay in progress has recorded
        // its write, and never lands between an overlay's snapshot and its write-back (where the
        // overlay would roll it straight back out) — see [tableWriteLocks].
        val outcome = tableWriteLock(table).withLock {
            when {
                // The cache was wiped for an identity change while this request was in flight.
                // Its rows belong to the outgoing account; writing them here undoes the teardown.
                predatesCacheTeardown(requestStartedAtTick) -> AddPayloadOutcome.DROPPED_CACHE_CLEARED

                // The response left before the local write landed; keeping the cache is the only
                // answer that preserves it. The caller re-fetches to converge on real server state.
                isStaleRelativeToLocalWrite(table, requestStartedAtTick) -> AddPayloadOutcome.DROPPED_STALE

                // Nothing about this emptiness is known to describe the signed-in user's data, so
                // it is never written — not over cached rows, and not into a cold or already-empty
                // slot where it would become the answer every later cache-first read is served.
                !authoritativeEmpty && isEmptyJsonArrayPayload(payload) -> AddPayloadOutcome.DROPPED_EMPTY

                else -> {
                    objectDao.upsert(
                        row = ObjectRow(
                            table = table,
                            signature = signature,
                            payload = payload,
                            updatedAt = nowMillis()
                        )
                    )
                    // Only a network response says which synced mutations the payload covers; a
                    // write with no request behind it covers none.
                    networkRefreshTicks.update { ticks ->
                        if (requestStartedAtTick == null) {
                            ticks - (table to signature)
                        } else {
                            ticks + ((table to signature) to requestStartedAtTick)
                        }
                    }
                    AddPayloadOutcome.WRITTEN
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
     * Wipes the entire offline cache — every cached SELECT payload, every cached
     * storage-object metadata row, and every queued mutation (pending or synced).
     *
     * Used by identity-clearing flows (logout, account switch, account deletion) so
     * one user's cached rows and un-synced writes never carry over to the next user
     * or survive on disk after the account is gone.
     *
     * Stamps the teardown tick **before** deleting, so a network SELECT already in flight is
     * disqualified even if its response is processed while the deletes are still running. Without
     * it the wipe is only as durable as the slowest refresh that raced it: the response lands
     * afterwards, [addPayload] writes the outgoing account's rows back, and the next user sees
     * them. That is the guard's whole point, so it is recorded first and never rolled back.
     */
    suspend fun clearAll() {
        // Under the ownership lock, so a write whose request predates this teardown cannot slip
        // its queue row in between the tick and the deletes — see [enqueueOwnedBy].
        queueOwnershipMutex.withLock {
            val tick = monotonicTick()
            // Never move the teardown tick backwards: two teardowns racing here must leave the later
            // one, or the earlier assignment would re-admit responses the later wipe disqualified.
            cacheClearedTick.update { existing -> if (existing >= tick) existing else tick }
            // The object-cache wipe takes the reserved rows with it: the rejected list and the queue
            // owner belong to the identity being torn down.
            objectDao.deleteAll()
            storageDao.deleteAll()
            mutationDao.deleteAll()
            networkRefreshTicks.value = emptyMap()
            syncedMutationTicks.value = emptyMap()
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

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    /**
     * Queue an INSERT.
     *
     * @return The mutation's id — the queue row's autoincrement id, stable across restarts.
     */
    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true
    ): Long {
        requireUserTable(table)
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
     *   alongside the body so [OfflineMutationSyncer] can rebase them onto the row's current
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
     * Runs [enqueue] only if the queue may take a write from [identity], recording [identity] as the
     * queue's owner.
     *
     * The queue holds one identity's writes at a time, because a replay can only be sent under one
     * session. A write from [identity] is taken when nothing is pending, or when what is pending is
     * already [identity]'s; a queue whose pending writes carry no recorded owner (written before
     * owners were recorded) is adopted. It is refused while another identity's writes are pending —
     * queueing it would send one account's write under the other's session, whichever replays.
     *
     * It is also refused when [clearAll] has run since [startedAtTick]: the write belongs to the
     * identity that was torn down. The check and the insert share [clearAll]'s lock, so no teardown
     * can fall between them.
     *
     * @param identity The stored value of the identity making the write, or `null` to skip the
     *   ownership check (a store driven directly, with no session model).
     * @param startedAtTick [monotonicTickNow] as read before the write's request went out, or
     *   `null` to skip the teardown check.
     * @param enqueue Inserts the queue row and returns its id.
     * @return What happened.
     */
    suspend fun enqueueOwnedBy(
        identity: String?,
        startedAtTick: Long? = null,
        enqueue: suspend OfflineStore.() -> Long,
    ): EnqueueOutcome = queueOwnershipMutex.withLock {
        when {
            predatesCacheTeardown(startedAtTick) -> EnqueueOutcome.LocalDataCleared
            identity != null && !claimQueueLocked(identity) -> EnqueueOutcome.QueueOwnedByAnotherIdentity
            else -> EnqueueOutcome.Queued(enqueue())
        }
    }

    /**
     * Whether a sweep made as [identity] may replay the pending queue. A queue with no recorded
     * owner is adopted by [identity]: it was written before owners were recorded, and replaying it
     * under the current session is what the engine always did.
     *
     * @param identity The stored value of the identity the sweep would replay as.
     * @return `false` when another identity's writes are pending.
     */
    suspend fun mayReplayAs(identity: String): Boolean =
        queueOwnershipMutex.withLock { claimQueueLocked(identity) }

    private suspend fun claimQueueLocked(identity: String): Boolean {
        val owner = outboxState.queueOwner()
        if (owner == identity) return true
        if (owner != null && mutationDao.countPending() > 0) return false
        outboxState.setQueueOwner(identity)
        return true
    }

    /**
     * Whether any mutation queued before [id], in any table, is still pending.
     *
     * @param id A queued mutation's id.
     * @return `true` when an earlier write has not replayed yet.
     */
    suspend fun hasPendingMutationBefore(id: Long): Boolean = mutationDao.hasPendingBefore(id)

    /**
     * Moves [mutation] out of the queue and onto the rejected list.
     *
     * The list entry is written first and the queue row deleted second, so a crash in between
     * leaves the write pending (it replays, is refused again, and replaces its own entry) rather
     * than lost.
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
    ): StoredRejection {
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
            owner = outboxState.queueOwner(),
            rejectedAtMs = nowMillis(),
            message = message,
            serverError = serverError,
        )
        outboxState.addRejection(entry)
        mutationDao.deleteById(mutation.id)
        syncedMutationTicks.update { ticks -> ticks - mutation.id }
        return entry
    }

    /**
     * Puts the rejected mutation [id] back at the tail of the queue, under a new id.
     *
     * @param id The rejected mutation's id.
     * @param identity The stored value of the identity asking, or `null` to skip ownership checks.
     * @return The new pending mutation's id, or `null` when there is no such rejection, or it — or
     *   the pending queue — belongs to another identity.
     */
    suspend fun retryRejectedMutation(id: Long, identity: String?): Long? =
        queueOwnershipMutex.withLock {
            val entry = outboxState.rejections().firstOrNull { rejection -> rejection.id == id }
                ?: return@withLock null
            if (identity != null) {
                val rejectedOwner = entry.owner
                if (rejectedOwner != null && rejectedOwner != identity) return@withLock null
                if (!claimQueueLocked(identity)) return@withLock null
            }
            val newId = mutationDao.insert(entry.toRequeuedRow(createdAtMs = nowMillis()))
            outboxState.removeRejection(id)
            newId
        }

    /**
     * Removes the rejected mutation [id] from the list.
     *
     * @param id The rejected mutation's id.
     * @return `true` when an entry was removed.
     */
    suspend fun dismissRejectedMutation(id: Long): Boolean = outboxState.removeRejection(id) != null

    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in insertion order across **all tables**.
     * Used by [OfflineMutationSyncer.syncAllTables].
     */
    suspend fun allPendingMutations(): List<MutationRow> =
        mutationDao.pendingAll()

    /**
     * Every pending mutation — of [table], or of every table when it is `null` — in insertion
     * order, re-emitted on each queue change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending queue rows.
     */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        mutationDao.pendingAllFlow()
            .map { rows -> if (table == null) rows else rows.filter { row -> row.table == table } }
            .distinctUntilChanged()

    /**
     * Total number of queued mutations across all tables.
     */
    suspend fun pendingMutationCount(): Int =
        mutationDao.countPending()

    /**
     * Flow of total number of queued mutations across all tables.
     */
    fun pendingMutationCountFlow(): Flow<Int> =
        mutationDao.countPendingFlow()

    /**
     * Number of queued mutations for a specific [table].
     */
    suspend fun pendingMutationCountForTable(table: String): Int =
        mutationDao.countPendingForTable(table)

    /**
     * Flow of number of queued mutations for a specific [table].
     */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        mutationDao.countPendingForTableFlow(table)

    suspend fun deleteMutation(id: Long) {
        mutationDao.deleteById(id)
    }

    /**
     * Refuses the engine's reserved state table (see [RESERVED_TABLE_NAME]) as the target of a
     * cache write or a queued write, so no user-facing path can touch the reserved rows.
     */
    private fun requireUserTable(table: String) {
        require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
    }

    /** Logs a mutation the overlay skipped. Never the mutation's body or filter values. */
    private fun logSkippedOverlay(mutation: MutationRow, failure: Exception) {
        sdkLogger.warning {
            "Overlay skipped pending ${mutation.operation} ${mutation.id} on ${mutation.table}: " +
                "${failure::class.simpleName}"
        }
    }

    /* ────────────────── Overlay pending mutations onto SELECT JSON ────────────────── */

    /**
     * Apply client-side mutations to fresh network data for [table].
     *
     * For fresh data we only want to overlay *pending* (unsynced) mutations,
     * since the server already reflects anything that has been successfully
     * synced.
     *
     * @param table     Table whose pending mutations should be overlaid.
     * @param signature Canonical signature of the SELECT that produced [serverJson]; used to gate
     *                  INSERT/UPSERT-appended rows to those the cached query actually admits.
     * @param serverJson Raw JSON payload the mutations are overlaid onto.
     */
    suspend fun applyPendingMutations(
        table: String,
        signature: String,
        serverJson: String
    ): String {
        if (isReservedTable(table)) return serverJson
        val pending = mutationDao.pendingForTable(table)
        if (pending.isEmpty()) return serverJson

        return OfflineMutationOverlay.applyPendingMutations(
            json = json,
            serverJson = serverJson,
            pending = pending,
            targetFilters = parseSignatureFilters(signature),
            onMutationFailure = ::logSkippedOverlay,
        )
    }

    /**
     * Apply both pending and already-synced mutations to cached data for
     * [table].
     *
     * When we fall back to cached SELECT payloads because the network is
     * unavailable, those payloads might be older than some mutations that
     * have since been synced. We need to re-apply both pending and synced
     * mutations on top of the cached snapshot so the UI reflects the latest
     * known state.
     */
    suspend fun applyAllMutationsForOfflineFallback(
        table: String,
        cachedJson: String
    ): String {
        if (isReservedTable(table)) return cachedJson
        val all = mutationDao.allForTable(table)
        if (all.isEmpty()) return cachedJson

        return OfflineMutationOverlay.applyPendingMutations(
            json = json,
            serverJson = cachedJson,
            pending = all,
            onMutationFailure = ::logSkippedOverlay,
        )
    }

    /**
     * Reactive overlay substrate for [table]/[signature], the in-memory replacement for the old
     * fabricated empty-array cache seed.
     *
     * Combines the cached SELECT payload with the table's mutations and emits:
     * - **payload present** → the payload with all mutations overlaid (today's semantics);
     * - **payload absent, mutations present** → the mutations overlaid onto an in-memory `[]`
     *   substrate, so a queued-offline INSERT is visible immediately — but nothing is written to
     *   Room (queued-write visibility is preserved without persisting fabricated data). If the
     *   overlay still yields an empty array (e.g. only an UPDATE/DELETE that matches nothing), the
     *   flow reports `null` (no data yet) rather than a phantom empty;
     * - **payload absent, no mutations** → `null`, the typed "no data yet" state.
     *
     * A `null` emission means "we know nothing for this signature yet"; a non-null `"[]"` means the
     * server genuinely returned no rows. Callers that render an instant empty state opt in via the
     * `WithState` read variant and map the `null`/no-data case to `emptyList()`.
     *
     * ### Emission dedup
     * Every cached payload lives in one Room table (`object_cache`) and every queued mutation in
     * another (`mutation_cache`), and Room invalidation is table-granular — so without dedup, any
     * write for *any* feature re-emits every live observer app-wide, and each downstream repository
     * re-decodes an identical payload just to discard it (its own `distinctUntilChanged` sits
     * *after* the decode). Both combine inputs and the overlaid output are therefore deduped by
     * value here: a refresh that rewrites an identical payload (fresh `updatedAt`, same JSON) and a
     * mutation-queue write for an unrelated table both stop at this seam instead of fanning out as
     * decode work. A genuinely-changed payload is a different string and always propagates.
     *
     * @param table Table whose cache and mutations are observed.
     * @param signature Canonical signature of the cached SELECT; used to scope the mutation overlay
     *   to the rows this query admits.
     * @return A flow of overlaid payload JSON, or `null` when no data is available yet.
     */
    fun cachePayloadWithOverlayFlow(table: String, signature: String): Flow<String?> {
        if (isReservedTable(table)) return flowOf(null)
        return combine(
            observePayload(table, signature).distinctUntilChanged(),
            mutationDao.allForTableFlow(table).distinctUntilChanged(),
        ) { cachedJson, mutations ->
            when {
                cachedJson != null ->
                    if (mutations.isEmpty()) cachedJson else overlayMutations(cachedJson, mutations, signature)

                mutations.isNotEmpty() ->
                    overlayMutations(EMPTY_JSON_ARRAY, mutations, signature)
                        .takeUnless { overlaid -> isEmptyJsonArrayPayload(overlaid) }

                else -> null
            }
        }.distinctUntilChanged()
    }

    /**
     * Overlays [mutations] onto [serverJson] for the query identified by [signature].
     *
     * @param serverJson Payload (or the in-memory `[]` substrate) to overlay onto.
     * @param mutations Mutations to apply.
     * @param signature Canonical signature of the query; the seam through which the query-aware
     *   overlay scopes INSERT/UPSERT-appended rows to those this query admits.
     * @return The overlaid payload JSON.
     */
    private fun overlayMutations(
        serverJson: String,
        mutations: List<MutationRow>,
        signature: String,
    ): String = OfflineMutationOverlay.applyPendingMutations(
        json = json,
        serverJson = serverJson,
        pending = mutations,
        targetFilters = parseSignatureFilters(signature),
        onMutationFailure = ::logSkippedOverlay,
    )

    /**
     * Apply a server-successful mutation to every cached SELECT payload for [table] using the
     * same overlay logic used for pending offline mutations. Honors
     * [MutationStrategy.QueueIfOffline][io.github.nesfeder.supabase.offline.MutationStrategy.QueueIfOffline]'s
     * documented "UI reflects immediately" promise on the online-success path — without it,
     * reactive `observe*` flows emit stale payloads until something forces a re-fetch.
     *
     * The [MutationRow] constructed below is **ephemeral**: it is never persisted to
     * `mutation_cache`. It exists only as a data carrier for the pure
     * [OfflineMutationOverlay.applyPendingMutations] function. The only database write is
     * `objectDao.upsert(...)` on the affected SELECT payload. There is therefore no cleanup
     * lifecycle, unlike the offline path where real queue entries get wiped by
     * [clearSyncedMutationsForTable] after the next successful network SELECT.
     *
     * **Materialize-on-write.** After patching existing cached rows, for every live
     * [liveSignatures] signature of [table] that has *no* cached row, an INSERT/UPSERT creates a
     * real row from the applied mutation result (the overlay onto an empty list — e.g.
     * `[insertedRow]`, never a bare seed). This makes a first-time save visible to a live
     * cache-less subscriber (the fix for the "save then stale UI" bug) without the old fabricated
     * empty-array intermediate state, and it self-GCs: with no live subscriber, nothing is
     * materialized and nothing is written.
     *
     * @param table Table name whose cached SELECT payloads should be patched.
     * @param operation One of [MutationOperation.INSERT], [UPSERT][MutationOperation.UPSERT],
     *   [UPDATE][MutationOperation.UPDATE], or [DELETE][MutationOperation.DELETE].
     * @param bodyJson Raw JSON body string (array for INSERT/UPSERT, object for UPDATE,
     *   `"{}"` for DELETE).
     * @param filterJson Optional encoded [FilterDescriptor]. INSERT/UPSERT typically null;
     *   UPDATE/DELETE carry their filter so the overlay knows which rows to patch.
     * @param defaultToNull Mirrors the insert/upsert builder option; only meaningful for
     *   INSERT/UPSERT.
     * @param onConflict Mirrors the upsert builder option; only meaningful for UPSERT.
     * @param ignoreDuplicates Mirrors the upsert builder option; only meaningful for UPSERT.
     * **Serialized per table.** The whole snapshot → patch → write-back runs under the table's
     * write lock ([tableWriteLocks]), together with every other writer of that table's cached
     * payloads. Without it two concurrent overlays on one table lose a write: each snapshots
     * before the other has written, and the later write-back carries the earlier row back out
     * of the cache — a batch job's parallel workers did exactly this to a row a screen was
     * displaying. The watermark is recorded inside the lock too, so a refresh response is
     * always judged against a watermark that already reflects the overlay it raced.
     *
     * @param liveSignatures Signatures with a live collector to materialize INSERT/UPSERT results
     *   into when they have no cached row. `null` (the default) reads the wired-in
     *   [liveSignatureProvider], so ordinary mutation-verb callers need not thread it.
     */
    internal suspend fun applyLocalMutationToCache(
        table: String,
        operation: String,
        bodyJson: String,
        filterJson: String?,
        defaultToNull: Boolean? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean? = null,
        liveSignatures: Set<String>? = null,
    ) {
        requireUserTable(table)
        applyLocalMutationToCacheLocked(
            table = table,
            operation = operation,
            bodyJson = bodyJson,
            filterJson = filterJson,
            defaultToNull = defaultToNull,
            onConflict = onConflict,
            ignoreDuplicates = ignoreDuplicates,
            liveSignatures = liveSignatures,
        )
    }

    private suspend fun applyLocalMutationToCacheLocked(
        table: String,
        operation: String,
        bodyJson: String,
        filterJson: String?,
        defaultToNull: Boolean?,
        onConflict: String?,
        ignoreDuplicates: Boolean?,
        liveSignatures: Set<String>?,
    ) = tableWriteLock(table).withLock {
        // Disqualify every network SELECT already in flight for this table before touching a row:
        // their responses describe the server as it was before this mutation committed, and
        // nothing would replay this patch if one of them landed on top of it.
        recordLocalMutation(table)

        val rows = objectDao.allForTable(table)

        // UPSERT with an onConflict clause but no caller-supplied filter is the common case
        // (an upsert keyed on a natural or composite key). The overlay's UPSERT path
        // treats a null descriptor as an INSERT (append), which would leave a duplicate row
        // in the cache. Synthesize a filter from (onConflict cols × values in body) so the
        // overlay merges into the matching existing row instead.
        val effectiveFilterJson = filterJson ?: synthesizeFilterFromOnConflict(
            operation = operation,
            bodyJson = bodyJson,
            onConflict = onConflict,
        )

        val syntheticMutation = MutationRow(
            table = table,
            operation = operation,
            bodyJson = bodyJson,
            filterJson = effectiveFilterJson,
            createdAtMs = nowMillis(),
            syncedAtMs = null,
            defaultToNull = defaultToNull,
            onConflict = onConflict,
            ignoreDuplicates = ignoreDuplicates,
        )

        val now = nowMillis()
        for (row in rows) {
            // Gate INSERT/UPSERT appends on this specific cached query's own filters, so an online
            // insert never lands in a signature whose exhaustive WHERE clause excludes it.
            val updated = OfflineMutationOverlay.applyPendingMutations(
                json = json,
                serverJson = row.payload,
                pending = listOf(syntheticMutation),
                targetFilters = parseSignatureFilters(row.signature),
                onMutationFailure = ::logSkippedOverlay,
            )
            if (updated != row.payload) {
                objectDao.upsert(row.copy(payload = updated, updatedAt = now))
            }
        }

        materializeForLiveSignatures(
            table = table,
            operation = operation,
            syntheticMutation = syntheticMutation,
            existingSignatures = rows.mapTo(mutableSetOf()) { row -> row.signature },
            liveSignatures = liveSignatures ?: liveSignatureProvider(table),
            now = now,
        )
    }

    /**
     * Materialize-on-write: create a real cache row for each live [liveSignatures] signature of
     * [table] that has no cached row, from [syntheticMutation] overlaid onto an empty list. Only
     * row-producing operations (INSERT/UPSERT) materialize — an UPDATE/DELETE onto an empty list
     * yields nothing to show. A materialized result that is still empty is skipped so no phantom
     * empty row is persisted.
     *
     * @param table Table being mutated.
     * @param operation The mutation operation.
     * @param syntheticMutation Ephemeral overlay carrier for the applied mutation.
     * @param existingSignatures Signatures that already have a cached row (already patched above).
     * @param liveSignatures Signatures with a live collector.
     * @param now Timestamp to stamp on any created row.
     */
    private suspend fun materializeForLiveSignatures(
        table: String,
        operation: String,
        syntheticMutation: MutationRow,
        existingSignatures: Set<String>,
        liveSignatures: Set<String>,
        now: Long,
    ) {
        if (operation != MutationOperation.INSERT && operation != MutationOperation.UPSERT) return

        for (signature in liveSignatures) {
            if (signature in existingSignatures) continue
            val created = OfflineMutationOverlay.applyPendingMutations(
                json = json,
                serverJson = EMPTY_JSON_ARRAY,
                pending = listOf(syntheticMutation),
                onMutationFailure = ::logSkippedOverlay,
            )
            if (isEmptyJsonArrayPayload(created)) continue
            // A materialized payload is not a network response: it covers no synced mutation.
            networkRefreshTicks.update { ticks -> ticks - (table to signature) }
            objectDao.upsert(
                ObjectRow(
                    table = table,
                    signature = signature,
                    payload = created,
                    updatedAt = now,
                ),
            )
        }
    }

    /**
     * For an UPSERT with `onConflict = "col_a,col_b"`, build a filter `col_a = value_a AND
     * col_b = value_b` by extracting those columns' values from [bodyJson]. Returns null for
     * any non-UPSERT, any missing input, or bodies the overlay can't handle (non-object rows,
     * non-primitive values on the conflict keys).
     *
     * Only called when the caller didn't already provide a filter descriptor.
     */
    private fun synthesizeFilterFromOnConflict(
        operation: String,
        bodyJson: String,
        onConflict: String?,
    ): String? {
        if (operation != MutationOperation.UPSERT) return null
        if (onConflict.isNullOrBlank()) return null

        val row: JsonObject = runCatching {
            when (val parsed = json.parseToJsonElement(bodyJson)) {
                is JsonObject -> parsed
                is JsonArray -> parsed.firstOrNull() as? JsonObject
                else -> null
            }
        }.getOrNull() ?: return null

        val columns = onConflict.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (columns.isEmpty()) return null

        val conditions = columns.map { column ->
            val value = row[column] as? JsonPrimitive ?: return null
            FilterDescriptor.Condition(
                column = column,
                operatorId = "eq",
                value = value.content,
            )
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = conditions,
        )
        return FilterEngine.encode(descriptor, json)
    }

    /**
     * Mark a mutation as synced at the current time.
     *
     * Also advances [table]'s stale-refresh watermark. The queue-replay path never writes the
     * object cache itself — a queued mutation is visible through
     * [cachePayloadWithOverlayFlow]'s overlay until [clearSyncedMutationsForTable] retires it
     * after the next successful SELECT — so this is where a replay establishes new server truth.
     * Without the stamp, a refresh that started before the replay could land afterwards, write the
     * pre-replay payload, and retire the overlay in the same pass, erasing the write from both
     * places at once.
     *
     * @param id Identifier of the mutation that replayed successfully.
     * @param table Table the mutation belongs to, whose watermark this replay advances.
     */
    suspend fun markMutationSynced(id: Long, table: String) {
        val tick = monotonicTick()
        recordLocalMutation(table, tick)
        syncedMutationTicks.update { ticks -> ticks + (id to tick) }
        mutationDao.markSynced(id = id, syncedAtMs = nowMillis())
    }

    /**
     * Retires the synced mutations of [table] that no cached query still needs.
     *
     * A synced mutation stays in the queue so the overlay can keep showing it on cached payloads
     * fetched before it replayed — those payloads do not contain it. It is retired only once nothing
     * depends on that any more: every cached payload of [table] was written by a network response
     * whose request started after the mutation was marked synced, and no live query of [table] is
     * waiting for its first payload (it reads the mutation through the overlay meanwhile). A refresh
     * of one query therefore retires nothing another cached query still needs, and a table that
     * nothing caches or observes — a write-only table — retires its synced mutations straight away.
     *
     * A mutation synced before this process started counts as covered by any payload a network
     * response wrote in this process; after a restart the cached payloads count as not refreshed,
     * which only keeps mutations longer.
     *
     * Also re-checks the stale-refresh watermark against [requestStartedAtTick] first, because the
     * accepting SELECT and this retirement are not one atomic step. A queue replay can complete
     * in the gap between them: [addPayload] sees no watermark and writes the pre-replay payload,
     * [markMutationSynced] then stamps and flags the mutation synced, and retiring it here would
     * erase the write from the cache and the overlay in the same pass — the exact double loss the
     * watermark exists to prevent. Skipping the retirement leaves the mutation row in place, so
     * [cachePayloadWithOverlayFlow] keeps showing the write until the next SELECT (which starts
     * after the replay) brings back real server state and retires it then.
     *
     * @param table Table whose synced mutations are retired.
     * @param requestStartedAtTick Tick captured before the SELECT that prompts this retirement went
     *   out. `null` for callers with no request behind them (the end of a replay pass).
     */
    suspend fun clearSyncedMutationsForTable(table: String, requestStartedAtTick: Long? = null) {
        if (isReservedTable(table)) return
        if (isStaleRelativeToLocalWrite(table, requestStartedAtTick)) return
        tableWriteLock(table).withLock {
            val synced = mutationDao.allForTable(table).filter { row -> row.syncedAtMs != null }
            if (synced.isEmpty()) return@withLock

            val cachedSignatures = objectDao.signaturesForTable(table).toSet()
            if ((liveSignatureProvider(table) - cachedSignatures).isNotEmpty()) return@withLock

            val refreshTicks = networkRefreshTicks.value
            val syncTicks = syncedMutationTicks.value
            val retirable = synced.filter { mutation ->
                val syncedAtTick = syncTicks[mutation.id] ?: Long.MIN_VALUE
                cachedSignatures.all { signature ->
                    val refreshedAtTick = refreshTicks[table to signature]
                    refreshedAtTick != null && refreshedAtTick >= syncedAtTick
                }
            }
            retirable.forEach { mutation -> mutationDao.deleteById(mutation.id) }
            syncedMutationTicks.update { ticks -> ticks - retirable.map { mutation -> mutation.id }.toSet() }
        }
    }

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
    }
}
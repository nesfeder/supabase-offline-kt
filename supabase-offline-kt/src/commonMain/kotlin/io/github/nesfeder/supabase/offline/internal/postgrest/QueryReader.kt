@file:OptIn(SupabaseExperimental::class, ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflineReadResult
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.db.AddPayloadOutcome
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.util.EMPTY_JSON_ARRAY
import io.github.nesfeder.supabase.offline.internal.util.isEmptyJsonArrayPayload
import io.github.nesfeder.supabase.offline.internal.util.isNetworkOrConnectivityFailure
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.HeadersBuilder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Thrown into a reactive read's refresh loop when a response came back under a session the trust
 * policy does not take as the user's, so the loop keeps trying instead of settling on it.
 */
internal class UntrustedResponseException(table: String) :
    RuntimeException("SELECT $table answered under a session that is not settled; will retry")

/**
 * One network SELECT and what became of it.
 *
 * @property response what the server answered.
 * @property sessionBefore the session the request went out under.
 * @property sessionAfter the session when it answered.
 * @property outcome what the cache did with it.
 */
private data class FetchedResponse(
    val response: PostgrestResult,
    val sessionBefore: RequestSession,
    val sessionAfter: RequestSession,
    val outcome: AddPayloadOutcome,
) {
    /** Whether the server's own body held no rows. */
    val isEmpty: Boolean get() = isEmptyJsonArrayPayload(response.data)

    /** Whether the response was refused because local data was cleared while it was in flight. */
    val wasRefusedAfterClear: Boolean get() = outcome == AddPayloadOutcome.DROPPED_CACHE_CLEARED
}

/**
 * Answers one-shot and reactive SELECTs from the network, the query cache, or both, as the read
 * strategy says, writing trusted responses through to the cache and overlaying queued mutations.
 *
 * @param requestSession the session a request sent now goes out under; read around every request.
 * @param trustPolicy which responses may replace what the cache holds.
 * @param refreshCoordinator shares one refresh per live query and tracks which queries are live.
 * @param accelerator wake-ups (the session settled, connectivity back) that cut a retry's wait short.
 */
internal class QueryReader(
    private val delegate: PostgrestQueryBuilderWrapper,
    private val store: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
    private val trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    private val refreshCoordinator: RefreshCoordinator,
    private val accelerator: Flow<Unit> = emptyFlow(),
) {

    /**
     * A one-shot SELECT answered from the network, the cache, or both, as [readStrategy] says. A read
     * whose response is not a row list goes to the network as it is, whatever the strategy.
     */
    suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        val shape = delegate.shapeOf(columns, request)
        val slot = cacheSlotOf(columns, request)
        val table = slot.table
        if (shape is ReadShape.Uncacheable) {
            sdkLogger.debug { "SELECT $table asks for ${shape.kind}; bypassing the cache" }
            return delegate.select(columns, request)
        }
        sdkLogger.debug { "SELECT $table via ${readStrategy::class.simpleName}" }
        return when (readStrategy) {
            is ReadStrategy.NetworkFirst -> {
                // The cache answers a transport failure, so upstream's waits before one would only delay it
                val retries = if (hasFreshCache(slot, readStrategy.cacheDuration)) Retries.TRANSPORT_FAILS_FAST else Retries.UPSTREAM
                try {
                    fetchWaitingOutRestore(slot, columns, request, retries)
                } catch (exception: Exception) {
                    if (!exception.isNetworkOrConnectivityFailure()) throw exception
                    val cached = readCached(slot, readStrategy.cacheDuration)
                    if (cached != null) {
                        sdkLogger.warning { "SELECT $table network failed, falling back to cache" }
                        cached
                    } else {
                        sdkLogger.error { "SELECT $table network failed and no cache available" }
                        throw exception
                    }
                }
            }
            ReadStrategy.NetworkOnly -> {
                fetchWaitingOutRestore(slot, columns, request, Retries.UPSTREAM)
            }
            is ReadStrategy.CacheFirst -> {
                val cached = readCached(slot, readStrategy.cacheDuration)
                if (cached != null) {
                    sdkLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    fetchWaitingOutRestore(slot, columns, request, Retries.UPSTREAM)
                }
            }
            is ReadStrategy.CacheOnly -> {
                val cached = readCached(slot, readStrategy.cacheDuration)
                if (cached != null) {
                    sdkLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    sdkLogger.error { "SELECT $table no cached data available" }
                    throw NoSuchElementException("No valid cached data found for this query")
                }
            }
        }
    }

    /**
     * A network SELECT, with pending mutations overlaid, that asks again when its response did not
     * speak for the user: after the session stops restoring (waiting at most
     * [RESTORE_WINDOW_RETRY_WAITS] backoff steps), or at once when it settled into another identity
     * mid-request. Once the steps run out it returns the last response, which was not cached.
     */
    private suspend fun fetchWaitingOutRestore(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        retries: Retries,
    ): PostgrestResult {
        val table = slot.table
        var sessionBefore = requestSession()
        var stepsTaken = 0
        while (true) {
            val fetched = fetchAndCache(slot, columns, request, sessionBefore, retries)
            // A response refused after a clear is answered with the emptied cache, and never asked again
            if (fetched.wasRefusedAfterClear) return buildResult(EMPTY_JSON_ARRAY, delegate.postgrest)
            if (!trustPolicy.oneShotAsksAgain(fetched.sessionBefore, fetched.sessionAfter, fetched.isEmpty) ||
                fetched.outcome == AddPayloadOutcome.WRITTEN
            ) {
                return overlaidResponse(slot, fetched)
            }
            if (stepsTaken == RESTORE_WINDOW_RETRY_WAITS) {
                sdkLogger.warning { "SELECT $table session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; serving the response uncached" }
                return overlaidResponse(slot, fetched)
            }
            sdkLogger.warning { "SELECT $table answered while the session was not settled; waiting for the session" }
            if (fetched.sessionBefore is RequestSession.Restoring) {
                // Waiting is cheap; a request sent while still restoring would come back the same
                do {
                    stepsTaken++
                    awaitRestoreWindowBackoff(defaultRefreshBackoffMillis(stepsTaken))
                    sessionBefore = requestSession()
                } while (sessionBefore is RequestSession.Restoring && stepsTaken < RESTORE_WINDOW_RETRY_WAITS)
            } else {
                // It settled into another identity mid-request: ask again as that one
                stepsTaken++
                sessionBefore = requestSession()
            }
        }
    }

    /**
     * Waits [millis] for the next restore-window step, waking early on an [accelerator] edge. An
     * accelerator that completes without emitting must not cut the wait to nothing.
     */
    private suspend fun awaitRestoreWindowBackoff(millis: Long) {
        withTimeoutOrNull(millis) {
            accelerator.firstOrNull() ?: awaitCancellation()
        }
    }

    /**
     * A reactive read of the query as [OfflineReadResult]s. Every collection registers the query as
     * live while it runs; `CacheFirstThenNetwork` collections of one query share one refresh. A read
     * whose response is not a row list emits the network's answer once and completes.
     */
    fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<OfflineReadResult> = flow {
        val shape = delegate.shapeOf(columns, request)
        if (shape is ReadShape.Uncacheable) {
            sdkLogger.debug { "SELECT ${delegate.table} asks for ${shape.kind}; bypassing the cache" }
            emit(OfflineReadResult(result = delegate.select(columns, request), refresh = RefreshState.Idle))
        } else {
            emitAll(cachedReadFlow(columns, readStrategy, request))
        }
    }

    /** A reactive read of a row list, from the cache and the network as [readStrategy] says. */
    private fun cachedReadFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<OfflineReadResult> {
        val slot = cacheSlotOf(columns, request)
        val base = when (readStrategy) {
            FlowReadStrategy.CacheFirstThenNetwork -> flow {
                emitAll(
                    refreshCoordinator.sharedReadFlow(slot.table, slot.cacheKey) {
                        cacheFirstThenNetworkWithState(slot, columns, request)
                    },
                )
            }
            FlowReadStrategy.CacheOnly -> cacheOnlyWithState(slot)
            FlowReadStrategy.NetworkFirstThenCache -> networkFirstThenCacheWithState(slot, columns, request)
        }
        return base
            .onStart { refreshCoordinator.registerLiveSignature(slot.table, slot.cacheKey) }
            .onCompletion { refreshCoordinator.unregisterLiveSignature(slot.table, slot.cacheKey) }
    }

    /** The cache and the overlay as they change, never the network; the refresh is always idle. */
    private fun cacheOnlyWithState(slot: CacheSlot): Flow<OfflineReadResult> =
        store.overlaidPayloadFlow(slot.table, slot.cacheKey).map { payload ->
            OfflineReadResult(
                result = payload?.let { json -> buildResult(json, delegate.postgrest) },
                refresh = RefreshState.Idle,
            )
        }

    /**
     * The cache and the overlay as they change, while a refresh retried until it succeeds runs
     * alongside, and again on each refresh request for the table. The shared upstream of one query.
     */
    private fun cacheFirstThenNetworkWithState(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> {
        val refreshState = MutableStateFlow<RefreshState>(RefreshState.InFlight)
        val payloads = cacheFirstWithParallelRefresh(
            cache = store.overlaidPayloadFlow(slot.table, slot.cacheKey),
            refresh = { refreshFromNetwork(slot, columns, request) },
            accelerator = accelerator,
            onRefreshState = { state -> refreshState.value = state },
            refreshRequests = refreshCoordinator.refreshRequestsFor(slot.table),
        )
        // One result per distinct payload: a refresh-state change re-emits the same result instance
        val results = payloads
            .distinctUntilChanged()
            .map { payload -> payload?.let { json -> buildResult(json, delegate.postgrest) } }
        return combine(results, refreshState) { result, state ->
            OfflineReadResult(result = result, refresh = state)
        }
    }

    /**
     * The refresh of a reactive read: a network SELECT written through to the cache. A response the
     * trust policy does not take as settled throws [UntrustedResponseException], so the loop retries.
     */
    private suspend fun refreshFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ) {
        val fetched = fetchAndCache(slot, columns, request, requestSession(), Retries.UPSTREAM)
        if (fetched.wasRefusedAfterClear) return
        if (trustPolicy.refreshRetries(fetched.sessionBefore, fetched.sessionAfter, fetched.isEmpty)) {
            sdkLogger.warning { "SELECT ${slot.table} answered while the session was not settled; will retry" }
            throw UntrustedResponseException(slot.table)
        }
    }

    /**
     * The network first, then the cache as it changes. The bootstrap waits out a restoring session
     * like a one-shot read, as nothing retries it later; see [bootstrapFromNetwork] for its failures.
     */
    private fun networkFirstThenCacheWithState(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> =
        store.overlaidPayloadFlow(slot.table, slot.cacheKey)
            .onStart { bootstrapFromNetwork(slot, columns, request) }
            .map { payload ->
                OfflineReadResult(
                    result = payload?.let { json -> buildResult(json, delegate.postgrest) },
                    refresh = RefreshState.Idle,
                )
            }

    /**
     * The network bootstrap of a network-first reactive read. A transport failure falls back to the
     * cached payload, at once, as a one-shot `NetworkFirst` read does; with none cached, or on a
     * server's own error, it fails the flow.
     */
    private suspend fun bootstrapFromNetwork(slot: CacheSlot, columns: Columns, request: SelectRequestBuilder.() -> Unit) {
        val retries = if (hasFreshCache(slot, cacheDuration = null)) Retries.TRANSPORT_FAILS_FAST else Retries.UPSTREAM
        try {
            fetchWaitingOutRestore(slot, columns, request, retries)
        } catch (exception: Exception) {
            if (!exception.isNetworkOrConnectivityFailure() || !hasFreshCache(slot, cacheDuration = null)) throw exception
            sdkLogger.warning { "SELECT ${slot.table} network failed, falling back to cache" }
        }
    }

    /**
     * Sends the SELECT and offers its response to the cache, which refuses one that could roll back a
     * newer local write (then this asks once more, since that request starts after the write), one
     * that left before local data was cleared, an untrusted one, and an empty one that is not
     * authoritative. A written one retires the synced mutations every cached query now holds.
     */
    private suspend fun fetchAndCache(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        sessionBeforeFirstRequest: RequestSession,
        retries: Retries,
    ): FetchedResponse {
        val (table, cacheKey) = slot
        var sessionBefore = sessionBeforeFirstRequest
        var refetchBudget = STALE_RESPONSE_REFETCH_BUDGET
        while (true) {
            val requestStartedAtTick = store.monotonicTickNow()
            val response = send(columns, request, retries)
            sdkLogger.debug { "SELECT $table network response: ${response.data}" }
            val sessionAfter = requestSession()
            val isEmpty = isEmptyJsonArrayPayload(response.data)
            // An empty response goes through the store's empty guard, a non-empty one through the trust check
            val outcome = store.addPayload(
                table = table,
                signature = cacheKey,
                payload = response.data,
                authoritativeEmpty = trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = true),
                requestStartedAtTick = requestStartedAtTick,
                isTrusted = isEmpty || trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = false),
            )
            when (outcome) {
                AddPayloadOutcome.DROPPED_CACHE_CLEARED ->
                    sdkLogger.info { "SELECT $table response predates clearing local data; discarding" }
                AddPayloadOutcome.DROPPED_STALE -> if (refetchBudget > 0) {
                    refetchBudget--
                    sdkLogger.info { "SELECT $table response predates a local write; re-fetching once" }
                    sessionBefore = requestSession()
                    continue
                }
                // The tick goes along, so a replay that landed after this request left still blocks it
                AddPayloadOutcome.WRITTEN -> store.retireCoveredSyncedMutations(table, requestStartedAtTick)
                else -> Unit
            }
            return FetchedResponse(response, sessionBefore, sessionAfter, outcome)
        }
    }

    /**
     * What a one-shot read serves for [fetched]: the server's rows, or the cache when they were
     * refused as older than a local write, with pending mutations overlaid.
     */
    private suspend fun overlaidResponse(slot: CacheSlot, fetched: FetchedResponse): PostgrestResult {
        val (table, cacheKey) = slot
        val base = if (fetched.outcome == AddPayloadOutcome.DROPPED_STALE) {
            sdkLogger.warning { "SELECT $table response still predates a local write; serving cached state" }
            store.getPayload(table, cacheKey) ?: fetched.response.data
        } else {
            fetched.response.data
        }
        val overlaid = store.overlayPendingOnResponse(table = table, signature = cacheKey, serverJson = base)
        return if (overlaid == fetched.response.data) fetched.response else buildResult(overlaid, delegate.postgrest)
    }

    /**
     * Sends the SELECT. With [Retries.TRANSPORT_FAILS_FAST] a transport failure is thrown at once, and
     * a 503 or 520 is retried here on upstream's schedule, as supabase-kt would, unless the read turned
     * retries off.
     */
    private suspend fun send(columns: Columns, request: SelectRequestBuilder.() -> Unit, retries: Retries): PostgrestResult {
        if (retries == Retries.UPSTREAM) return delegate.select(columns, request)
        val maxRetries = if (newRequestBuilder().apply(request).retry) delegate.postgrest.config.maxRetries else 0
        var attempt = 0
        while (true) {
            try {
                return delegate.select(columns) {
                    request()
                    noRetry()
                    if (attempt > 0) headers.append(RETRY_COUNT_HEADER, attempt.toString())
                }
            } catch (failure: PostgrestRestException) {
                if (attempt >= maxRetries || failure.statusCode !in UPSTREAM_RETRIED_STATUSES) throw failure
                delay(upstreamRetryDelay(attempt))
                attempt++
            }
        }
    }

    /** The cached payload of the query with its mutations overlaid, or `null` when none is cached or it is older than [cacheDuration]. */
    private suspend fun readCached(slot: CacheSlot, cacheDuration: Duration?): PostgrestResult? {
        val (table, cacheKey) = slot
        val (payload, updatedAt) = store.getPayloadWithTimestamp(table, cacheKey) ?: return null
        if (!isFresh(updatedAt, cacheDuration)) return null
        val overlaid = store.overlayCachedPayload(table = table, signature = cacheKey, cachedJson = payload)
        return buildResult(overlaid, delegate.postgrest)
    }

    /** Whether [readCached] would answer: a payload is cached and not older than [cacheDuration]. */
    private suspend fun hasFreshCache(slot: CacheSlot, cacheDuration: Duration?): Boolean {
        val (_, updatedAt) = store.getPayloadWithTimestamp(slot.table, slot.cacheKey) ?: return false
        return isFresh(updatedAt, cacheDuration)
    }

    /** Whether a payload written at [updatedAt] is within [cacheDuration]; any age is when it is `null`. */
    private fun isFresh(updatedAt: Long, cacheDuration: Duration?): Boolean =
        cacheDuration == null || nowMillis() - updatedAt <= cacheDuration.inWholeMilliseconds

    /** Where the query is cached; see [cacheSlotOf]. */
    private fun cacheSlotOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): CacheSlot = cacheSlotOf(
        table = delegate.table,
        columns = columns,
        defaultSchema = delegate.postgrest.config.defaultSchema,
        builderFactory = ::newRequestBuilder,
        request = request,
    )

    /** A fresh SELECT builder for this table, as supabase-kt makes one. */
    private fun newRequestBuilder(): SelectRequestBuilder =
        SelectRequestBuilder(delegate.schema, delegate.postgrest.config.propertyConversionMethod)

    private fun buildResult(data: String, postgrest: Postgrest): PostgrestResult =
        PostgrestResult(data, HeadersBuilder().build(), postgrest)

    /** How a network SELECT meets a failure supabase-kt would retry. */
    private enum class Retries {

        /** As supabase-kt's own configuration says. */
        UPSTREAM,

        /** A transport failure at once, for a read the cache answers then; see [send]. */
        TRANSPORT_FAILS_FAST,
    }

    private companion object {
        /** The statuses supabase-kt retries a `GET` on: service unavailable, and Cloudflare's unknown error. */
        val UPSTREAM_RETRIED_STATUSES = setOf(503, 520)

        /** The header supabase-kt numbers a retried attempt in. */
        const val RETRY_COUNT_HEADER = "x-retry-count"

        /** supabase-kt's wait before retry [attempt] (from 0): 1 s doubling, at most 30 s. */
        fun upstreamRetryDelay(attempt: Int): Duration =
            minOf((1_000L shl minOf(attempt, 5)).milliseconds, 30.seconds)

        /**
         * Follow-up requests one [fetchAndCache] may send after the cache refuses a response as older
         * than a local write. One converges, since it starts after that write.
         */
        const val STALE_RESPONSE_REFETCH_BUDGET = 1

        /**
         * Backoff steps a one-shot read waits through for a restoring session: 300 + 900 + 2400 ms,
         * the gaps between the refresh loop's fast retries. A read that starts signed in never waits.
         */
        const val RESTORE_WINDOW_RETRY_WAITS = FAST_LADDER_ATTEMPTS - 1
    }
}

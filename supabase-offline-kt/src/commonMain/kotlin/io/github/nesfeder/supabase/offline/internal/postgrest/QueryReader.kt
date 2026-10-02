// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class, ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.OfflineReadResult
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.EMPTY_JSON_ARRAY
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.isEmptyJsonArrayPayload
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.ktor.http.HeadersBuilder
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
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
    val outcome: CacheWriteOutcome,
) {
    /** Whether the server's own body held no rows. */
    val isEmpty: Boolean
        get() = isEmptyJsonArrayPayload(response.data)

    /** Whether the response was refused because local data was cleared while it was in flight. */
    val wasRefusedAfterClear: Boolean
        get() = outcome == CacheWriteOutcome.REFUSED_AFTER_CLEAR

    /**
     * Whether the cache holds this answer, or a newer local write it was refused for: not when it
     * was refused as not the user's, as another identity's, or as an empty it may not take.
     */
    val isHeldByTheCache: Boolean
        get() =
            outcome == CacheWriteOutcome.WRITTEN ||
                outcome == CacheWriteOutcome.REFUSED_STALE ||
                outcome == CacheWriteOutcome.REFUSED_AFTER_CLEAR
}

/**
 * A network read's answer, as a caller gets it.
 *
 * @property result what the caller is answered with.
 * @property wasCached whether the cache holds it, so a reactive read showing the cache shows it.
 */
private class NetworkAnswer(val result: PostgrestResult, val wasCached: Boolean)

/**
 * Answers one-shot and reactive SELECTs from the network, the query cache, or both, as the read
 * strategy says, writing trusted responses through to the cache and overlaying queued mutations.
 *
 * @param requestSession the session a request sent now goes out under; read around every request.
 * @param trustPolicy which responses may replace what the cache holds.
 * @param liveQueries shares one refresh per live query and tracks which queries are live.
 * @param wakeUps wake-ups (the session settled, connectivity back) that cut a retry's wait short.
 */
internal class QueryReader(
    private val delegate: RemoteTable,
    private val store: OfflineStore,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
    private val trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    private val liveQueries: LiveQueries,
    private val wakeUps: Flow<Unit> = emptyFlow(),
) {

    /**
     * A one-shot SELECT answered from the network, the cache, or both, as [readStrategy] says. A
     * read whose response is not a row list goes to the network as it is, whatever the strategy.
     */
    suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult {
        // The probe's build of the request gives the cache slot too: the app's lambda runs once
        var built: CacheSlot? = null
        val shape =
            delegate.shapeOf(columns, request) { builder -> built = cacheSlotOf(columns, builder) }
        val slot = built ?: cacheSlotOf(columns, request)
        val table = slot.table
        if (shape is ReadShape.Uncacheable) {
            if (readStrategy is ReadStrategy.CacheOnly) throw cacheCannotAnswer(shape)
            engineLogger.debug { "SELECT $table asks for ${shape.kind}; bypassing the cache" }
            return delegate.select(columns, request)
        }
        engineLogger.debug { "SELECT $table via ${readStrategy::class.simpleName}" }
        return when (readStrategy) {
            is ReadStrategy.NetworkFirst -> {
                // The cache answers a transport failure, so upstream's waits before one would only
                // delay it
                val retries =
                    if (hasFreshCache(slot, readStrategy.maxAge)) Retries.TRANSPORT_FAILS_FAST
                    else Retries.UPSTREAM
                try {
                    fetchWaitingOutRestore(slot, columns, request, retries)
                } catch (exception: Exception) {
                    if (!exception.isNetworkOrConnectivityFailure()) throw exception
                    val cached = readCached(slot, readStrategy.maxAge, servesStale = true)
                    if (cached != null) {
                        engineLogger.warning {
                            "SELECT $table network failed, falling back to cache"
                        }
                        cached
                    } else {
                        engineLogger.error { "SELECT $table network failed and no cache available" }
                        throw exception
                    }
                }
            }
            ReadStrategy.NetworkOnly -> {
                fetchWaitingOutRestore(slot, columns, request, Retries.UPSTREAM)
            }
            is ReadStrategy.CacheFirst -> {
                val cached = readCached(slot, readStrategy.maxAge, servesStale = false)
                if (cached != null) {
                    engineLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    cacheFirstFromNetwork(slot, columns, request, readStrategy.maxAge)
                }
            }
            is ReadStrategy.CacheOnly -> {
                val cached = readCached(slot, readStrategy.maxAge, servesStale = true)
                if (cached != null) {
                    engineLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    engineLogger.error { "SELECT $table no cached data available" }
                    throw CacheMissException("No valid cached data found for this query")
                }
            }
        }
    }

    /**
     * A cache-first read the cache did not answer: the network, and, when that cannot be reached, a
     * stale payload within [cacheDuration] — stale means fetch again when the network allows, not
     * unusable.
     */
    private suspend fun cacheFirstFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
    ): PostgrestResult {
        // A stale payload answers a transport failure, so upstream's waits before one would only
        // delay it
        val retries =
            if (hasFreshCache(slot, cacheDuration)) Retries.TRANSPORT_FAILS_FAST
            else Retries.UPSTREAM
        return try {
            fetchWaitingOutRestore(slot, columns, request, retries)
        } catch (exception: Exception) {
            if (!exception.isNetworkOrConnectivityFailure()) throw exception
            val stale = readCached(slot, cacheDuration, servesStale = true) ?: throw exception
            engineLogger.warning { "SELECT ${slot.table} network failed, serving the stale cache" }
            stale
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
    ): PostgrestResult = fetchWaitingOutRestoreAnswer(slot, columns, request, retries).result

    /**
     * [fetchWaitingOutRestore]'s answer, and whether the cache holds it: `false` when the cache
     * refused it as not the user's, or as an empty answer it may not take.
     */
    private suspend fun fetchWaitingOutRestoreAnswer(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        retries: Retries,
    ): NetworkAnswer {
        val table = slot.table
        var sessionBefore = requestSession()
        var stepsTaken = 0
        while (true) {
            val fetched = fetchAndCache(slot, columns, request, sessionBefore, retries)
            // A response refused after a clear is answered with the emptied cache, and never asked
            // again
            if (fetched.wasRefusedAfterClear)
                return NetworkAnswer(
                    buildResult(EMPTY_JSON_ARRAY, delegate.postgrest),
                    wasCached = true,
                )
            if (
                !trustPolicy.oneShotAsksAgain(
                    fetched.sessionBefore,
                    fetched.sessionAfter,
                    fetched.isEmpty,
                ) || fetched.outcome == CacheWriteOutcome.WRITTEN
            ) {
                return NetworkAnswer(overlaidResponse(slot, fetched), fetched.isHeldByTheCache)
            }
            if (stepsTaken == RESTORE_WINDOW_RETRY_WAITS) {
                engineLogger.warning {
                    "SELECT $table session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; serving the response uncached"
                }
                return NetworkAnswer(overlaidResponse(slot, fetched), wasCached = false)
            }
            engineLogger.warning {
                "SELECT $table answered while the session was not settled; waiting for the session"
            }
            if (fetched.sessionBefore is RequestSession.Restoring) {
                // Waiting is cheap; a request sent while still restoring would come back the same
                do {
                    stepsTaken++
                    awaitRestoreWindowBackoff(refreshBackoff(stepsTaken))
                    sessionBefore = requestSession()
                } while (
                    sessionBefore is RequestSession.Restoring &&
                        stepsTaken < RESTORE_WINDOW_RETRY_WAITS
                )
            } else {
                // It settled into another identity mid-request: ask again as that one
                stepsTaken++
                sessionBefore = requestSession()
            }
        }
    }

    /**
     * Waits [backoff] for the next restore-window step, waking early on an [wakeUps] edge. An
     * accelerator that completes without emitting must not cut the wait to nothing.
     */
    private suspend fun awaitRestoreWindowBackoff(backoff: Duration) {
        withTimeoutOrNull(backoff) {
            wakeUps.firstOrNull() ?: awaitCancellation()
        }
    }

    /**
     * A reactive read of the query as [OfflineReadResult]s. Every collection registers the query as
     * live while it runs; `CacheFirstThenNetwork` collections of one query share one refresh. A
     * read whose response is not a row list emits the network's answer once and completes.
     */
    fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> = flow {
        var built: CacheSlot? = null
        val shape =
            delegate.shapeOf(columns, request) { builder -> built = cacheSlotOf(columns, builder) }
        if (shape is ReadShape.Uncacheable) {
            if (readStrategy == FlowReadStrategy.CacheOnly) throw cacheCannotAnswer(shape)
            engineLogger.debug {
                "SELECT ${delegate.table} asks for ${shape.kind}; bypassing the cache"
            }
            emit(
                OfflineReadResult(
                    result = delegate.select(columns, request),
                    refresh = RefreshState.Idle,
                )
            )
        } else {
            emitAll(
                cachedReadFlow(
                    columns,
                    readStrategy,
                    request,
                    built ?: cacheSlotOf(columns, request),
                )
            )
        }
    }

    /** A reactive read of a row list, from the cache and the network as [readStrategy] says. */
    private fun cachedReadFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
        slot: CacheSlot,
    ): Flow<OfflineReadResult> {
        val base =
            when (readStrategy) {
                FlowReadStrategy.CacheFirstThenNetwork ->
                    flow {
                        emitAll(
                            liveQueries.sharedReadFlow(slot.table, slot.cacheKey) {
                                cacheFirstThenNetworkWithState(slot, columns, request)
                            }
                        )
                    }
                FlowReadStrategy.CacheOnly -> cacheOnlyWithState(slot)
                FlowReadStrategy.NetworkFirstThenCache ->
                    networkFirstThenCacheWithState(slot, columns, request)
            }
        return base
            .onStart { liveQueries.registerLiveQuery(slot.table, slot.cacheKey) }
            .onCompletion { liveQueries.unregisterLiveQuery(slot.table, slot.cacheKey) }
    }

    /** The cache and the overlay as they change, never the network; the refresh is always idle. */
    private fun cacheOnlyWithState(slot: CacheSlot): Flow<OfflineReadResult> =
        store.overlaidPayloadFlow(slot.table, slot.cacheKey, slot.shape).map { payload ->
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
        val refreshState = MutableStateFlow<RefreshState>(RefreshState.Refreshing)
        val payloads =
            cacheFirstWithParallelRefresh(
                cache = store.overlaidPayloadFlow(slot.table, slot.cacheKey, slot.shape),
                refresh = { refreshFromNetwork(slot, columns, request) },
                wakeUps = wakeUps,
                onRefreshState = { state -> refreshState.value = state },
                refreshRequests = liveQueries.refreshRequestsFor(slot.table, slot.cacheKey),
            )
        // One result per distinct payload: a refresh-state change re-emits the same result instance
        val results =
            payloads.distinctUntilChanged().map { payload ->
                payload?.let { json -> buildResult(json, delegate.postgrest) }
            }
        return combine(results, refreshState) { result, state ->
            OfflineReadResult(result = result, refresh = state)
        }
    }

    /**
     * The refresh of a reactive read: a network SELECT written through to the cache. A response the
     * trust policy does not take as settled, or one the cache refused while it passed to another
     * identity, throws [UntrustedResponseException], so the loop retries. A transport failure fails
     * at once: the loop is what retries it.
     */
    private suspend fun refreshFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ) {
        val fetched =
            fetchAndCache(slot, columns, request, requestSession(), Retries.TRANSPORT_FAILS_FAST)
        if (fetched.wasRefusedAfterClear) return
        if (
            fetched.outcome == CacheWriteOutcome.REFUSED_OTHER_OWNER ||
                trustPolicy.refreshRetries(
                    fetched.sessionBefore,
                    fetched.sessionAfter,
                    fetched.isEmpty,
                )
        ) {
            engineLogger.warning {
                if (fetched.outcome == CacheWriteOutcome.REFUSED_OTHER_OWNER) {
                    "SELECT ${slot.table} answered while the cache passed to another identity; will retry"
                } else {
                    "SELECT ${slot.table} answered while the session was not settled; will retry"
                }
            }
            throw UntrustedResponseException(slot.table)
        }
    }

    /**
     * The network first, then the cache as it changes. The bootstrap waits out a restoring session
     * like a one-shot read, as nothing retries it later; see [bootstrapFromNetwork] for its
     * failures.
     */
    private fun networkFirstThenCacheWithState(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<OfflineReadResult> = flow {
        val unconfirmedBy = bootstrapFromNetwork(slot, columns, request)
        val cache = store.overlaidPayloadFlow(slot.table, slot.cacheKey, slot.shape)
        if (unconfirmedBy == null) {
            emitAll(
                cache.map { payload -> OfflineReadResult(resultOf(payload), RefreshState.Idle) }
            )
        } else {
            emitAll(unconfirmedCache(slot, columns, request, cache, unconfirmedBy))
        }
    }

    /**
     * The cache a network-first read shows when its bootstrap could not confirm it,
     * [RefreshState.Stalled] by [cause], while a refresh retried as a cache-first read's runs until
     * it succeeds; then [RefreshState.Idle]. The bootstrap counts as the first attempt, so the next
     * waits.
     */
    private fun unconfirmedCache(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cache: Flow<String?>,
        cause: Exception,
    ): Flow<OfflineReadResult> {
        val refreshState = MutableStateFlow<RefreshState>(RefreshState.Stalled(cause))
        var bootstrapCounted = false
        val payloads =
            cacheFirstWithParallelRefresh(
                cache = cache,
                refresh = {
                    if (!bootstrapCounted) {
                        bootstrapCounted = true
                        throw cause
                    }
                    refreshFromNetwork(slot, columns, request)
                },
                wakeUps = wakeUps,
                // Not confirmed until a refresh succeeds: an attempt in flight changes nothing
                onRefreshState = { state ->
                    if (state !is RefreshState.Refreshing) refreshState.value = state
                },
            )
        return combine(payloads.distinctUntilChanged().map(::resultOf), refreshState) {
            result,
            state ->
            OfflineReadResult(result = result, refresh = state)
        }
    }

    /** The result a reactive read shows for [payload]; `null` while nothing is known. */
    private fun resultOf(payload: String?): PostgrestResult? = payload?.let { json ->
        buildResult(json, delegate.postgrest)
    }

    /**
     * The network bootstrap of a network-first reactive read. A transport failure falls back to the
     * cached payload, at once, as a one-shot `NetworkFirst` read does; with none cached, or on a
     * server's own error, it fails the flow.
     *
     * @return why the cache it leaves is not the server's answer — the transport failure, or an
     *   answer the cache did not take as the user's — or `null` when it is.
     */
    private suspend fun bootstrapFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Exception? {
        val retries =
            if (hasFreshCache(slot, cacheDuration = null)) Retries.TRANSPORT_FAILS_FAST
            else Retries.UPSTREAM
        return try {
            val answer = fetchWaitingOutRestoreAnswer(slot, columns, request, retries)
            if (answer.wasCached) null else UntrustedResponseException(slot.table)
        } catch (exception: Exception) {
            if (
                !exception.isNetworkOrConnectivityFailure() ||
                    !hasFreshCache(slot, cacheDuration = null)
            )
                throw exception
            engineLogger.warning { "SELECT ${slot.table} network failed, falling back to cache" }
            exception
        }
    }

    /**
     * Sends the SELECT and offers its response to the cache, which refuses one that could roll back
     * a newer local write (then this asks once more, since that request starts after the write),
     * one that left before local data was cleared, an untrusted one, and an empty one that is not
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
            val response = send(slot, columns, request, retries)
            engineLogger.debug { "SELECT $table network response: ${response.data}" }
            val sessionAfter = requestSession()
            val isEmpty = isEmptyJsonArrayPayload(response.data)
            // An empty response goes through the store's empty guard, a non-empty one through the
            // trust check
            val outcome =
                store.addPayload(
                    table = table,
                    cacheKey = cacheKey,
                    payload = response.data,
                    authoritativeEmpty =
                        trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = true),
                    requestStartedAtTick = requestStartedAtTick,
                    isTrusted =
                        isEmpty ||
                            trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = false),
                    shape = slot.shape,
                )
            when (outcome) {
                CacheWriteOutcome.REFUSED_AFTER_CLEAR ->
                    engineLogger.info {
                        "SELECT $table response predates clearing local data; discarding"
                    }
                CacheWriteOutcome.REFUSED_STALE ->
                    if (refetchBudget > 0) {
                        refetchBudget--
                        engineLogger.info {
                            "SELECT $table response predates a local write; re-fetching once"
                        }
                        sessionBefore = requestSession()
                        continue
                    }
                // The tick goes along, so a replay that landed after this request left still blocks
                // it
                CacheWriteOutcome.WRITTEN ->
                    store.retireCoveredSyncedMutations(table, requestStartedAtTick)
                else -> Unit
            }
            return FetchedResponse(response, sessionBefore, sessionAfter, outcome)
        }
    }

    /**
     * What a one-shot read serves for [fetched]: the server's rows, or the cache when they were
     * refused as older than a local write, with pending mutations overlaid.
     */
    private suspend fun overlaidResponse(
        slot: CacheSlot,
        fetched: FetchedResponse,
    ): PostgrestResult {
        val (table, cacheKey) = slot
        val base =
            if (fetched.outcome == CacheWriteOutcome.REFUSED_STALE) {
                engineLogger.warning {
                    "SELECT $table response still predates a local write; serving cached state"
                }
                store.getPayload(table, cacheKey) ?: fetched.response.data
            } else {
                fetched.response.data
            }
        val overlaid =
            store.overlayPendingOnResponse(
                table = table,
                cacheKey = cacheKey,
                serverJson = base,
                shape = slot.shape,
            )
        return if (overlaid == fetched.response.data) fetched.response
        else buildResult(overlaid, delegate.postgrest)
    }

    /**
     * Sends the SELECT. With [Retries.TRANSPORT_FAILS_FAST] a transport failure is thrown at once,
     * and a 503 or 520 is retried here on upstream's schedule, as supabase-kt would, unless the
     * read turned retries off.
     */
    private suspend fun send(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        retries: Retries,
    ): PostgrestResult {
        if (retries == Retries.UPSTREAM) return delegate.select(columns, request)
        val maxRetries = if (slot.allowsRetries) delegate.postgrest.config.maxRetries else 0
        var attempt = 0
        while (true) {
            try {
                return delegate.select(columns) {
                    request()
                    noRetry()
                    if (attempt > 0) headers.append(RETRY_COUNT_HEADER, attempt.toString())
                }
            } catch (failure: PostgrestRestException) {
                if (attempt >= maxRetries || failure.statusCode !in UPSTREAM_RETRIED_STATUSES)
                    throw failure
                delay(upstreamRetryDelay(attempt))
                attempt++
            }
        }
    }

    /**
     * The cached payload of the query with its mutations overlaid, or `null` when none is cached,
     * it was fetched longer than [cacheDuration] ago, or it is stale and not [servesStale].
     */
    private suspend fun readCached(
        slot: CacheSlot,
        cacheDuration: Duration?,
        servesStale: Boolean,
    ): PostgrestResult? {
        val (table, cacheKey) = slot
        val (payload, updatedAt) = store.getPayloadWithTimestamp(table, cacheKey) ?: return null
        if (!servesStale && isStaleStamp(updatedAt)) return null
        if (!isFresh(fetchedAtOf(updatedAt), cacheDuration)) return null
        val overlaid =
            store.overlayCachedPayload(
                table = table,
                cacheKey = cacheKey,
                cachedJson = payload,
                shape = slot.shape,
            )
        return buildResult(overlaid, delegate.postgrest)
    }

    /**
     * Whether a fallback to the cache would answer: a payload is cached, stale or not, fetched
     * within [cacheDuration].
     */
    private suspend fun hasFreshCache(slot: CacheSlot, cacheDuration: Duration?): Boolean {
        val (_, updatedAt) =
            store.getPayloadWithTimestamp(slot.table, slot.cacheKey) ?: return false
        return isFresh(fetchedAtOf(updatedAt), cacheDuration)
    }

    /**
     * Whether a payload fetched at [fetchedAt] is within [cacheDuration]; any age is when it is
     * `null`.
     */
    private fun isFresh(fetchedAt: Long, cacheDuration: Duration?): Boolean =
        cacheDuration == null || nowMillis() - fetchedAt <= cacheDuration.inWholeMilliseconds

    /** Where the query is cached; see [cacheSlotOf]. */
    private fun cacheSlotOf(columns: Columns, request: SelectRequestBuilder.() -> Unit): CacheSlot =
        cacheSlotOf(
            table = delegate.table,
            columns = columns,
            defaultSchema = delegate.postgrest.config.defaultSchema,
            builderFactory = ::newRequestBuilder,
            request = request,
        )

    /** Where the query [builder] describes is cached, its request already run on it. */
    private fun cacheSlotOf(columns: Columns, builder: SelectRequestBuilder): CacheSlot =
        cacheSlotOf(
            table = delegate.table,
            columns = columns,
            defaultSchema = delegate.postgrest.config.defaultSchema,
            builder = builder,
        )

    /** A fresh SELECT builder for this table, as supabase-kt makes one. */
    private fun newRequestBuilder(): SelectRequestBuilder =
        SelectRequestBuilder(delegate.schema, delegate.postgrest.config.propertyConversionMethod)

    /**
     * What a cache-only read of [shape] fails with: the cache holds lists of rows, and it sends
     * nothing.
     */
    private fun cacheCannotAnswer(shape: ReadShape.Uncacheable): CacheMissException =
        CacheMissException(
            "A cache-only read cannot answer ${shape.kind}: the cache holds lists of rows. Read the list and take " +
                "the rows you need, or read with a strategy that may use the network."
        )

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
        /**
         * The statuses supabase-kt retries a `GET` on: service unavailable, and Cloudflare's
         * unknown error. This and the two below copy supabase-kt's schedule;
         * RetryScheduleContractTest fails when upstream's changes.
         */
        val UPSTREAM_RETRIED_STATUSES = setOf(503, 520)

        /** The header supabase-kt numbers a retried attempt in. */
        const val RETRY_COUNT_HEADER = "x-retry-count"

        /** supabase-kt's wait before retry [attempt] (from 0): 1 s doubling, at most 30 s. */
        fun upstreamRetryDelay(attempt: Int): Duration =
            minOf((1_000L shl minOf(attempt, 5)).milliseconds, 30.seconds)

        /**
         * Follow-up requests one [fetchAndCache] may send after the cache refuses a response as
         * older than a local write. One converges, since it starts after that write.
         */
        const val STALE_RESPONSE_REFETCH_BUDGET = 1

        /**
         * Backoff steps a one-shot read waits through for a restoring session: 300 + 900 + 2400 ms,
         * the gaps between the refresh loop's fast retries. A read that starts signed in never
         * waits.
         */
        const val RESTORE_WINDOW_RETRY_WAITS = FAST_RETRY_COUNT - 1
    }
}

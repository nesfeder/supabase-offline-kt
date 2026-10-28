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
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.TransportClosedException
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.EMPTY_JSON_ARRAY
import io.github.nesfeder.supabase.offline.internal.cache.OverlaidCachedPayload
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryOverlay
import io.github.nesfeder.supabase.offline.internal.cache.ServedPayload
import io.github.nesfeder.supabase.offline.internal.cache.isEmptyJsonArrayPayload
import io.github.nesfeder.supabase.offline.internal.cache.isJsonArrayPayload
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.db.isStaleStamp
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlin.reflect.KClass
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Thrown into a reactive read's refresh loop when a response came back under a session the trust
 * policy does not take as the user's, so the loop keeps trying instead of settling on it.
 */
internal class UntrustedResponseException(table: String) :
    RuntimeException("SELECT $table answered under a session that is not settled; will retry")

/**
 * Thrown into a reactive read's refresh loop when a response was not a list of rows (a proxy's
 * block page, a server's fallback page, at `200`): a failure, not a wait, so the read reads
 * [RefreshState.Stalled] by it once the fast retries are spent, and is still retried.
 */
internal class NotARowListException(table: String) :
    RuntimeException("SELECT $table answered $NOT_A_ROW_LIST")

/** How a body that is not a list of rows is described in logs and failures. */
private const val NOT_A_ROW_LIST = "with something other than a list of rows"

/**
 * Thrown into a reactive read's refresh loop when the cache refused a response as older than the
 * table's last local write, and the one follow-up request was refused too: the loop keeps asking on
 * its schedule instead of settling on a cache the server has not confirmed.
 */
internal class StaleResponseException(table: String) :
    RuntimeException("SELECT $table answered before the table's last local write; will retry")

/**
 * One network SELECT and what became of it.
 *
 * @property response what the server answered.
 * @property sessionBefore the session the request went out under.
 * @property sessionAfter the session when it answered.
 * @property outcome what the cache did with it.
 * @property receivedAtMillis when it arrived, by the reader's clock.
 * @property isRowList whether the body is a list of rows the cache may hold; see
 *   [isRowListResponse].
 */
private data class FetchedResponse(
    val response: PostgrestResult,
    val sessionBefore: RequestSession,
    val sessionAfter: RequestSession,
    val outcome: CacheWriteOutcome,
    val receivedAtMillis: Long,
    val isRowList: Boolean,
) {
    /** Whether the server's own body held no rows: an empty list, not a body of another shape. */
    val isEmpty: Boolean
        get() = isRowList && isEmptyJsonArrayPayload(response.data)

    /** Whether the response was refused because local data was cleared while it was in flight. */
    val wasRefusedAfterClear: Boolean
        get() = outcome == CacheWriteOutcome.REFUSED_AFTER_CLEAR

    /** Whether the cache refused the response as older than the table's last local write. */
    val wasRefusedStale: Boolean
        get() = outcome == CacheWriteOutcome.REFUSED_STALE

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
 * Whether [response] is a list of rows the cache may hold: a JSON array, or a blank body standing
 * for an empty one, under a JSON media type or none. A proxy's block page, or a self-hosted
 * server's fallback page, answers `200` with HTML; stored, it would replace a cached list with a
 * body no read decodes, and offline every read of the query would fail on it.
 */
private fun isRowListResponse(response: PostgrestResult): Boolean {
    val contentType = response.headers[HttpHeaders.ContentType]
    if (contentType != null && !isJsonMediaType(contentType)) return false
    return response.data.isBlank() || isJsonArrayPayload(response.data)
}

/** Whether [contentType] names JSON, whatever its parameters; a malformed value does not. */
private fun isJsonMediaType(contentType: String): Boolean =
    try {
        ContentType.parse(contentType).match(ContentType.Application.Json)
    } catch (_: BadContentTypeFormatException) {
        false
    }

/**
 * A single-object [response] as the list read of the same query is answered, one row long: `null`
 * unless its body is one JSON object under a JSON media type (PostgREST names it
 * `application/vnd.pgrst.object+json`) or none.
 */
private fun oneRowListOf(response: PostgrestResult): String? {
    val contentType = response.headers[HttpHeaders.ContentType]
    if (contentType != null && !isJsonFamilyMediaType(contentType)) return null
    val body = response.data.trim()
    if (!body.startsWith('{')) return null
    val isObject =
        try {
            EngineJson.parseToJsonElement(body) is JsonObject
        } catch (_: SerializationException) {
            false
        }
    return if (isObject) "[$body]" else null
}

/** Whether [contentType] is `application/json` or a `+json` type; a malformed value is not. */
private fun isJsonFamilyMediaType(contentType: String): Boolean =
    try {
        val type = ContentType.parse(contentType)
        type.contentType == ContentType.Application.TYPE &&
            (type.contentSubtype == "json" || type.contentSubtype.endsWith("+json"))
    } catch (_: BadContentTypeFormatException) {
        false
    }

/**
 * The one row of a cached [payload], as the JSON object `single()` answers with; `null` when the
 * payload holds no row, several, or a row that is not an object.
 */
private fun soleRowOf(payload: String): String? {
    val rows =
        try {
            EngineJson.parseToJsonElement(payload)
        } catch (_: SerializationException) {
            return null
        }
    val row = (rows as? JsonArray)?.singleOrNull()
    return if (row is JsonObject) row.toString() else null
}

/**
 * A network read's answer, as a caller gets it.
 *
 * @property result what the caller is answered with.
 * @property wasCached whether the cache holds it, so a reactive read showing the cache shows it.
 * @property wasRefusedStale whether the cache kept a newer local write instead: it holds something,
 *   but not the server's answer.
 */
private class NetworkAnswer(
    val result: PostgrestResult,
    val wasCached: Boolean,
    val wasRefusedStale: Boolean = false,
)

/**
 * Answers one-shot and reactive SELECTs from the network, the query cache, or both, as the read
 * strategy says, writing trusted responses through to the cache and overlaying queued mutations.
 *
 * @param requestSession the session a request sent now goes out under; read around every request.
 * @param trustPolicy which responses may replace what the cache holds.
 * @param liveQueries shares one refresh per live query and tracks which queries are live.
 * @param wakeUps wake-ups (the session settled, connectivity back) that cut a retry's wait short.
 * @param networkAccess whether the network is known down, or access to it off: a reactive read's
 *   refresh is [RefreshState.Paused] then.
 */
internal class QueryReader(
    private val delegate: RemoteTable,
    private val queryCache: QueryCache,
    private val queryOverlay: QueryOverlay,
    private val mutationQueue: MutationQueue,
    private val freshness: CacheFreshness,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val requestSession: () -> RequestSession = { RequestSession.WithoutAuth },
    private val trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    private val liveQueries: LiveQueries,
    private val wakeUps: Flow<Unit> = emptyFlow(),
    private val networkAccess: NetworkAccess = NetworkAccess(),
) {

    /**
     * A one-shot SELECT answered from the network, the cache, or both, as [readStrategy] says. A
     * single object is cached as its list, one row long ([selectSingleObject]); a read of any other
     * shape that is not a row list goes to the network as it is, whatever the strategy, once the
     * session has settled.
     */
    suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult {
        // The probe's build of the request gives the cache slot too: the app's lambda runs once
        var built: CacheSlot? = null
        val shape =
            delegate.shapeOf(columns, request) { builder ->
                requireOneValuePerTopLevelParameter(builder)
                requireReadablePatternLists(builder)
                requireReadableEscapedValues(builder)
                built = cacheSlotOf(columns, builder)
            }
        val slot = built ?: cacheSlotOf(columns, request)
        val table = slot.table
        when (shape) {
            is ReadShape.Uncacheable -> {
                if (readStrategy is ReadStrategy.CacheOnly) throw cacheCannotAnswer(shape.kind)
                return selectUncacheable(columns, request, shape)
            }
            ReadShape.SingleObject ->
                return selectSingleObject(slot, columns, request, readStrategy)
            ReadShape.RowList -> Unit
        }
        engineLogger.debug { "SELECT $table via ${readStrategy::class.simpleName}" }
        return when (readStrategy) {
            is ReadStrategy.NetworkFirst -> {
                val retries = retriesFor(cacheAnswers = hasFreshCache(slot, readStrategy.maxAge))
                try {
                    fetchWaitingOutRestore(
                        slot,
                        columns,
                        request,
                        retries,
                        cacheServes = { updatedAt ->
                            isFresh(fetchedAtOf(updatedAt), readStrategy.maxAge)
                        },
                    ) {
                        readCached(slot, readStrategy.maxAge, servesStale = true)
                    }
                } catch (exception: Exception) {
                    if (!exception.isNetworkOrConnectivityFailure()) throw exception
                    val cached = readCached(slot, readStrategy.maxAge, servesStale = true)
                    if (cached != null) {
                        engineLogger.info { "SELECT $table network failed, falling back to cache" }
                        cached
                    } else {
                        engineLogger.info { "SELECT $table network failed and no cache available" }
                        throw exception
                    }
                }
            }
            ReadStrategy.NetworkOnly -> {
                fetchWaitingOutRestore(
                    slot,
                    columns,
                    request,
                    retriesFor(cacheAnswers = false),
                    cacheServes = null,
                )
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
                    engineLogger.info { "SELECT $table no cached data available" }
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
        val retries = retriesFor(cacheAnswers = hasFreshCache(slot, cacheDuration))
        return try {
            fetchWaitingOutRestore(
                slot,
                columns,
                request,
                retries,
                cacheServes = { updatedAt -> isFresh(fetchedAtOf(updatedAt), cacheDuration) },
            ) {
                readCached(slot, cacheDuration, servesStale = true)
            }
        } catch (exception: Exception) {
            if (!exception.isNetworkOrConnectivityFailure()) throw exception
            val stale = readCached(slot, cacheDuration, servesStale = true) ?: throw exception
            engineLogger.info { "SELECT ${slot.table} network failed, serving the stale cache" }
            stale
        }
    }

    /**
     * A network SELECT, with pending mutations overlaid, that asks again when its response did not
     * speak for the user: after the session stops restoring or its failed refresh lands (waiting at
     * most [RESTORE_WINDOW_RETRY_WAITS] backoff steps), or at once when it settled into another
     * identity mid-request. Once the steps run out it returns what [cacheWhenUnsettled] gives, or,
     * with nothing from it, the last response, which was not cached.
     *
     * @param cacheServes whether a cached payload with this fetch stamp answers in the server's
     *   place when the server's answer is not a list of rows: the strategy's `maxAge`; `null` for a
     *   read the cache never answers.
     * @param cacheWhenUnsettled the cached payload the read serves instead of a response the
     *   session never settled for, or `null` when it has none to serve.
     */
    private suspend fun fetchWaitingOutRestore(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        retries: Retries,
        cacheServes: ((updatedAt: Long) -> Boolean)?,
        cacheWhenUnsettled: suspend () -> PostgrestResult? = { null },
    ): PostgrestResult =
        fetchWaitingOutRestoreAnswer(
                slot,
                columns,
                request,
                retries,
                cacheServes,
                cacheWhenUnsettled,
            )
            .result

    /**
     * [fetchWaitingOutRestore]'s answer, and whether the cache holds it: `false` when the cache
     * refused it as not the user's, as not a list of rows, or as an empty answer it may not take.
     */
    private suspend fun fetchWaitingOutRestoreAnswer(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        retries: Retries,
        cacheServes: ((updatedAt: Long) -> Boolean)?,
        cacheWhenUnsettled: suspend () -> PostgrestResult? = { null },
    ): NetworkAnswer {
        val table = slot.table
        val window = RestoreWindow()
        var sessionBefore = requestSession()
        while (true) {
            val fetched = fetchAndCache(slot, columns, request, sessionBefore, retries)
            // A response refused after a clear is answered with the emptied cache, and never asked
            // again
            if (fetched.wasRefusedAfterClear)
                return NetworkAnswer(
                    provenancedResult(
                        EMPTY_JSON_ARRAY,
                        delegate.postgrest,
                        ResultProvenance(
                            isFromCache = true,
                            fetchedAtMillis = null,
                            hasPendingMutations = false,
                        ),
                    ),
                    wasCached = true,
                )
            if (
                !trustPolicy.oneShotAsksAgain(
                    fetched.sessionBefore,
                    fetched.sessionAfter,
                    fetched.isEmpty,
                ) || fetched.outcome == CacheWriteOutcome.WRITTEN
            ) {
                return NetworkAnswer(
                    overlaidResponse(slot, fetched, cacheServes),
                    fetched.isHeldByTheCache,
                    fetched.wasRefusedStale,
                )
            }
            if (window.isSpent)
                return unsettledAnswer(slot, fetched, cacheServes, cacheWhenUnsettled)
            engineLogger.info {
                "SELECT $table answered while the session was not settled; waiting for the session"
            }
            sessionBefore =
                if (isUnsettledStoredSession(fetched.sessionBefore)) {
                    // Waiting is cheap; a request sent while the session is still not settled would
                    // come back the same. Once the steps are spent, the one request the caller is
                    // owed goes out, and is answered as it is
                    window.awaitSettledSession()
                } else {
                    // It settled into another identity mid-request: ask again as that one
                    window.step()
                    requestSession()
                }
        }
    }

    /**
     * What a one-shot read answers when the session did not settle within the restore window:
     * [cacheWhenUnsettled]'s payload, from the cache, or [fetched], the last response, uncached.
     */
    private suspend fun unsettledAnswer(
        slot: CacheSlot,
        fetched: FetchedResponse,
        cacheServes: ((updatedAt: Long) -> Boolean)?,
        cacheWhenUnsettled: suspend () -> PostgrestResult?,
    ): NetworkAnswer {
        val cached = cacheWhenUnsettled()
        if (cached != null) {
            engineLogger.warning {
                "SELECT ${slot.table} session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; serving the cache"
            }
            return NetworkAnswer(cached, wasCached = true)
        }
        engineLogger.warning {
            "SELECT ${slot.table} session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; serving the response uncached"
        }
        return NetworkAnswer(overlaidResponse(slot, fetched, cacheServes), wasCached = false)
    }

    /**
     * The bounded wait a read gives a stored session that is restoring, or whose refresh failed, to
     * settle: [RESTORE_WINDOW_RETRY_WAITS] backoff steps (300, 900 and 2,400 ms), each cut short by
     * a [wakeUps] edge, which the session settling emits. One per read; a read that asks again at
     * once, the session having settled since or as another identity, spends a step too, so every
     * pass through the read spends one and the read asks a bounded number of times.
     */
    private inner class RestoreWindow {
        private var stepsTaken = 0

        /** Whether the steps are spent: the read sends once more, or serves what it has. */
        val isSpent: Boolean
            get() = stepsTaken >= RESTORE_WINDOW_RETRY_WAITS

        /** Spends a step without waiting. */
        fun step() {
            stepsTaken++
        }

        /**
         * Waits, a step at a time, while the session is restoring or its refresh has failed, until
         * it settles or the steps are spent; a session already settled spends a step at once.
         *
         * @return the session now.
         */
        suspend fun awaitSettledSession(): RequestSession {
            var session = requestSession()
            if (!isUnsettledStoredSession(session)) {
                step()
                return session
            }
            while (isUnsettledStoredSession(session) && !isSpent) {
                stepsTaken++
                awaitRestoreWindowBackoff(refreshBackoff(stepsTaken))
                session = requestSession()
            }
            return session
        }
    }

    /**
     * A read whose response the cache cannot hold, sent to the network as it is: once, after the
     * session has settled. A stored session that is restoring, or whose refresh failed, would
     * answer it on the anon key (a `single()` of a row only the user may read fails, a `count` is
     * 0), so the read waits out the restore window first, and sends anyway when the session does
     * not settle within it.
     */
    private suspend fun selectUncacheable(
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        shape: ReadShape.Uncacheable,
    ): PostgrestResult {
        val table = delegate.table
        engineLogger.debug { "SELECT $table asks for ${shape.kind}; bypassing the cache" }
        if (!sessionSettledBeforeSending(table, shape.kind)) warnSendingUnsettled(table)
        return networkResult(delegate.select(columns, request))
    }

    /**
     * Waits out the restore window ([RestoreWindow]) before a read that is sent once, when the
     * session is restoring or its refresh has failed: a request sent meanwhile would carry the anon
     * key.
     *
     * @param kind what the read asks for, as a phrase for the log.
     * @return whether the session is settled now: `false` when the window ran out first.
     */
    private suspend fun sessionSettledBeforeSending(table: String, kind: String): Boolean {
        if (!isUnsettledStoredSession(requestSession())) return true
        engineLogger.info {
            "SELECT $table asks for $kind while the session is not settled; waiting for the session"
        }
        return !isUnsettledStoredSession(RestoreWindow().awaitSettledSession())
    }

    /** Logs that a read sent once goes out under a session that did not settle in the window. */
    private fun warnSendingUnsettled(table: String) {
        engineLogger.warning {
            "SELECT $table session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; sending the request as it is"
        }
    }

    /**
     * A one-shot read of one row as an object, `single()` or supabase-kt 3.8's `maybeSingle()`. It
     * asks for the rows the list read of the same query does, so the cache holds a successful
     * answer as that list, one row long, under the list's key ([cacheSingleObject]). The cache
     * answers it with that list, the queued writes laid over it, when the list holds exactly one
     * row ([cachedSingleObject]): under `CacheOnly`, under `CacheFirst` before the network, and
     * under `NetworkFirst` and `CacheFirst` when the network cannot be reached, or when the session
     * does not settle within the restore window. No row, or several, answer nothing: the network's
     * failure is thrown, or [CacheMissException] under `CacheOnly`. The server's own answer is
     * returned as it came.
     */
    private suspend fun selectSingleObject(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        readStrategy: ReadStrategy,
    ): PostgrestResult {
        val table = slot.table
        engineLogger.debug {
            "SELECT $table asks for a single object via ${readStrategy::class.simpleName}"
        }
        return when (readStrategy) {
            is ReadStrategy.CacheOnly -> {
                val cached = cachedSingleObject(slot, readStrategy.maxAge, servesStale = true)
                if (cached != null) {
                    engineLogger.info { "SELECT $table served one row from cache" }
                    cached
                } else {
                    engineLogger.info { "SELECT $table no cached row answers a single object" }
                    throw CacheMissException(NO_CACHED_SINGLE_ROW)
                }
            }
            is ReadStrategy.CacheFirst -> {
                val cached = cachedSingleObject(slot, readStrategy.maxAge, servesStale = false)
                if (cached != null) {
                    engineLogger.info { "SELECT $table served one row from cache" }
                    cached
                } else {
                    singleObjectFromNetwork(slot, columns, request, readStrategy.maxAge, true)
                }
            }
            is ReadStrategy.NetworkFirst ->
                singleObjectFromNetwork(slot, columns, request, readStrategy.maxAge, true)
            ReadStrategy.NetworkOnly ->
                singleObjectFromNetwork(slot, columns, request, cacheDuration = null, false)
        }
    }

    /**
     * A single-object read sent to the network once, after the session has settled, its answer
     * offered to the cache. With [fallsBackToTheCache], a transport failure, or a session that did
     * not settle within the restore window, is answered from the cache when its list within
     * [cacheDuration], marked stale or not, holds exactly one row.
     */
    private suspend fun singleObjectFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        fallsBackToTheCache: Boolean,
    ): PostgrestResult {
        val table = slot.table
        val fallback: suspend () -> PostgrestResult? = {
            if (fallsBackToTheCache) cachedSingleObject(slot, cacheDuration, servesStale = true)
            else null
        }
        if (!sessionSettledBeforeSending(table, SINGLE_OBJECT)) {
            val cached = fallback()
            if (cached != null) {
                engineLogger.warning {
                    "SELECT $table session did not settle after $RESTORE_WINDOW_RETRY_WAITS waits; serving the cache"
                }
                return cached
            }
            warnSendingUnsettled(table)
        }
        val retries =
            retriesFor(cacheAnswers = fallsBackToTheCache && hasFreshCache(slot, cacheDuration))
        val sessionBefore = requestSession()
        val requestStartedAtTick = freshness.tickNow()
        val response =
            try {
                send(slot, columns, request, retries)
            } catch (exception: Exception) {
                if (!exception.isNetworkOrConnectivityFailure()) throw exception
                val cached = fallback()
                if (cached == null) {
                    engineLogger.info { "SELECT $table network failed and no cached row answers" }
                    throw exception
                }
                engineLogger.info { "SELECT $table network failed, serving one row from cache" }
                return cached
            }
        cacheSingleObject(slot, response, sessionBefore, requestSession(), requestStartedAtTick)
        return networkResult(response)
    }

    /**
     * Offers a single-object [response] to the cache as the list read of the same query is
     * answered, one row long, through every guard a list response passes: the trust policy, the
     * cache's owner, the table's watermark and a clear of local data. A blank body (supabase-kt
     * 3.8's `maybeSingle()` answers no row and several rows alike with one) or a body that is not
     * one JSON object is not offered.
     */
    private suspend fun cacheSingleObject(
        slot: CacheSlot,
        response: PostgrestResult,
        sessionBefore: RequestSession,
        sessionAfter: RequestSession,
        requestStartedAtTick: Long,
    ) {
        val payload = oneRowListOf(response) ?: return
        val outcome =
            queryCache.cacheResponse(
                table = slot.table,
                cacheKey = slot.cacheKey,
                payload = payload,
                requestStartedAtTick = requestStartedAtTick,
                isTrusted = trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = false),
                shape = slot.shape,
            )
        when (outcome) {
            // The tick goes along, so a replay that landed after this request left still blocks it
            CacheWriteOutcome.WRITTEN ->
                mutationQueue.retireCoveredSyncedMutations(slot.table, requestStartedAtTick)
            CacheWriteOutcome.REFUSED_STALE ->
                engineLogger.info {
                    "SELECT ${slot.table} single object predates a local write; not cached"
                }
            else -> Unit
        }
    }

    /**
     * The payload of [slot] as a single object, from the cache: its list within [cacheDuration]
     * (not marked stale unless [servesStale]), with the queued writes laid over it, when it holds
     * exactly one row; `null` when none is cached or it holds no row or several.
     */
    private suspend fun cachedSingleObject(
        slot: CacheSlot,
        cacheDuration: Duration?,
        servesStale: Boolean,
    ): PostgrestResult? {
        val cached = overlaidCached(slot, cacheDuration, servesStale) ?: return null
        val row = soleRowOf(cached.overlay.payload)
        if (row == null) {
            engineLogger.info { "SELECT ${slot.table} cached rows are not one row" }
            return null
        }
        return provenancedResult(
            row,
            delegate.postgrest,
            ResultProvenance(
                isFromCache = true,
                fetchedAtMillis = fetchedAtOf(cached.updatedAt),
                hasPendingMutations = cached.overlay.hasPendingMutations,
                pendingRowKeys = cached.overlay.pendingRowKeys,
            ),
        )
    }

    /**
     * How a read's request meets a transport failure: at once when the cache answers one, or when
     * the network is known down and upstream's waits could only delay the failure; otherwise as
     * supabase-kt's configuration says. Known down is a hint: the request is still sent.
     */
    private fun retriesFor(cacheAnswers: Boolean): Retries =
        if (cacheAnswers || networkAccess.isKnownUnavailable) Retries.TRANSPORT_FAILS_FAST
        else Retries.UPSTREAM

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
     * A reactive read of the query as [QuerySnapshot]s. Every collection registers the query as
     * live while it runs; `CacheFirstThenNetwork` collections of one query share one refresh. A
     * read whose response is not a row list emits the network's answer once, after the session has
     * settled, and completes; a single object's answer is cached as a one-shot read's is.
     */
    fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<QuerySnapshot> = flow {
        var built: CacheSlot? = null
        val shape =
            delegate.shapeOf(columns, request) { builder ->
                requireOneValuePerTopLevelParameter(builder)
                requireReadablePatternLists(builder)
                requireReadableEscapedValues(builder)
                built = cacheSlotOf(columns, builder)
            }
        if (shape != ReadShape.RowList) {
            if (readStrategy == FlowReadStrategy.CacheOnly) {
                throw if (shape is ReadShape.Uncacheable) cacheCannotAnswer(shape.kind)
                else CacheMissException(NO_REACTIVE_SINGLE_ROW)
            }
            val result =
                if (shape is ReadShape.Uncacheable) selectUncacheable(columns, request, shape)
                else
                    singleObjectFromNetwork(
                        built ?: cacheSlotOf(columns, request),
                        columns,
                        request,
                        cacheDuration = null,
                        fallsBackToTheCache = false,
                    )
            emit(
                QuerySnapshot(
                    result = result,
                    isFromCache = false,
                    fetchedAt = result.fetchedAtInstant(),
                    hasPendingMutations = false,
                    refresh = RefreshState.Idle,
                    pendingRowKeys = emptySet(),
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
    ): Flow<QuerySnapshot> {
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

    /**
     * The cache and the overlay as they change, never the network: always from the cache, and the
     * refresh always idle.
     */
    private fun cacheOnlyWithState(slot: CacheSlot): Flow<QuerySnapshot> =
        snapshotsOf(
            queryOverlay.servedPayloadFlow(
                slot.table,
                slot.cacheKey,
                slot.shape,
                MutableStateFlow(ReadProgress(RefreshState.Idle, confirmedFetchedAt = null)),
            )
        )

    /**
     * The cache and the overlay as they change, while a refresh retried until it succeeds runs
     * alongside, and again on each refresh request for the table. The shared upstream of one query.
     */
    private fun cacheFirstThenNetworkWithState(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<QuerySnapshot> {
        val progress = MutableStateFlow(ReadProgress(RefreshState.Refreshing, null))
        var confirmedByRefresh: Long? = null
        val failures = RefreshFailureReport(slot.table)
        val payloads =
            cacheFirstWithParallelRefresh(
                cache =
                    queryOverlay.servedPayloadFlow(slot.table, slot.cacheKey, slot.shape, progress),
                refresh = {
                    failures.reporting { refreshFromNetwork(slot, columns, request) }
                    confirmedByRefresh = fetchedAtOfCached(slot)
                },
                wakeUps = wakeUps,
                onRefreshState = { state ->
                    progress.value = progress.value.reached(state, confirmedByRefresh)
                },
                refreshRequests = liveQueries.refreshRequestsFor(slot.table, slot.cacheKey),
                networkAccess = networkAccess,
                isWaiting = ::isAWait,
            )
        return snapshotsOf(payloads)
    }

    /**
     * Whether a refresh's failure is a wait, not a fault: the answer came under a session still
     * settling, or before the table's last local write, or the client is closing. The refresh is
     * retried as for any failure, but reports [RefreshState.Refreshing] (or [RefreshState.Paused]),
     * never [RefreshState.Stalled].
     */
    private fun isAWait(failure: Exception): Boolean =
        failure is UntrustedResponseException ||
            failure is StaleResponseException ||
            failure is TransportClosedException

    /**
     * Logs a reactive read's refresh failures that are not the network's, which the retry that
     * follows would otherwise hide: the first of a kind, and again each time the kind changes,
     * never one per attempt. A connectivity failure, or a wait, is expected and shows in the read's
     * refresh state instead.
     */
    private inner class RefreshFailureReport(private val table: String) {
        /** The kind last logged since the last success; `null` after one. */
        private var lastLogged: KClass<out Exception>? = null

        /** Runs one refresh attempt, logging its failure as this report says. */
        suspend fun reporting(refresh: suspend () -> Unit) {
            try {
                refresh()
                lastLogged = null
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                val expected = failure.isNetworkOrConnectivityFailure() || isAWait(failure)
                if (!expected && failure::class != lastLogged) {
                    lastLogged = failure::class
                    engineLogger.warning {
                        "SELECT $table refresh failed (${failure::class.simpleName}); " +
                            "it is tried again"
                    }
                }
                throw failure
            }
        }
    }

    /**
     * Where a reactive read's refresh stands, and the fetch time of the cached payload its last
     * successful refresh confirmed, `null` before one has.
     */
    private data class ReadProgress(val refresh: RefreshState, val confirmedFetchedAt: Long?) {
        /** This progress once the refresh reports [state], [confirmed] by it when it succeeded. */
        fun reached(state: RefreshState, confirmed: Long?): ReadProgress =
            ReadProgress(
                state,
                if (state == RefreshState.Idle) confirmed ?: confirmedFetchedAt
                else confirmedFetchedAt,
            )
    }

    /**
     * [payloads], each read with the read's progress ([QueryOverlay.servedPayloadFlow]), as
     * snapshots: one result per distinct payload, so a change of anything else re-emits the same
     * result instance.
     */
    private fun snapshotsOf(
        payloads: Flow<Pair<ServedPayload?, ReadProgress>>
    ): Flow<QuerySnapshot> = flow {
        // Each collection its own: the results it built are its alone
        val results = ResultsByPayload()
        emitAll(
            payloads
                .map { (served, progress) ->
                    snapshotOf(results, served, progress.confirmedFetchedAt, progress.refresh)
                }
                .distinctUntilChanged()
        )
    }

    /**
     * The snapshot of [served]: from the cache unless a refresh confirmed a payload fetched no
     * earlier than it ([confirmedFetchedAt]) and it is not stale. An idle refresh whose confirmed
     * payload is gone (local data cleared, the cache dropped) is shown refreshing: the read fetches
     * again.
     */
    private fun snapshotOf(
        results: ResultsByPayload,
        served: ServedPayload?,
        confirmedFetchedAt: Long?,
        refresh: RefreshState,
    ): QuerySnapshot {
        val fetchedAt = served?.fetchedAtMillis
        val confirmed =
            served != null &&
                !served.isStale &&
                fetchedAt != null &&
                confirmedFetchedAt != null &&
                fetchedAt >= confirmedFetchedAt
        val awaitsConfirmed =
            refresh == RefreshState.Idle &&
                confirmedFetchedAt != null &&
                !confirmed &&
                served?.isStale != true
        return QuerySnapshot(
            result = served?.let { payload -> results.resultOf(payload.payload) },
            isFromCache = !confirmed,
            fetchedAt = fetchedAt?.let(Instant::fromEpochMilliseconds),
            hasPendingMutations = served?.hasPendingMutations == true,
            refresh = if (awaitsConfirmed) RefreshState.Refreshing else refresh,
            pendingRowKeys = served?.pendingRowKeys.orEmpty(),
        )
    }

    /** The result of a reactive read's last payload, built once for as long as it is current. */
    private inner class ResultsByPayload {
        private var lastPayload: String? = null
        private var lastResult: PostgrestResult? = null

        /** The result for [payload]: the last one when the payload is unchanged. */
        fun resultOf(payload: String): PostgrestResult {
            lastResult
                ?.takeIf { lastPayload == payload }
                ?.let {
                    return it
                }
            return buildResult(payload, delegate.postgrest).also { result ->
                lastPayload = payload
                lastResult = result
            }
        }
    }

    /**
     * When the payload cached for [slot] was fetched, when it is cached and not stale; `null`
     * otherwise.
     */
    private suspend fun fetchedAtOfCached(slot: CacheSlot): Long? {
        val (_, updatedAt) =
            queryCache.getPayloadWithTimestamp(slot.table, slot.cacheKey) ?: return null
        return if (isStaleStamp(updatedAt)) null else fetchedAtOf(updatedAt)
    }

    /**
     * The refresh of a reactive read: a network SELECT written through to the cache. A response the
     * trust policy does not take as settled, one the cache refused while it passed to another
     * identity, or one that left before local data was cleared, throws
     * [UntrustedResponseException]; one still refused as older than a local write after the one
     * follow-up request throws [StaleResponseException]. Either way the loop asks again, on its
     * schedule, and the read shows the cache as it is now. A transport failure fails at once: the
     * loop is what retries it.
     */
    private suspend fun refreshFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ) {
        val fetched =
            fetchAndCache(slot, columns, request, requestSession(), Retries.TRANSPORT_FAILS_FAST)
        if (fetched.wasRefusedAfterClear) throw UntrustedResponseException(slot.table)
        // A failure, not a wait: the report logs it once, and the loop stalls on it in time
        if (!fetched.isRowList) throw NotARowListException(slot.table)
        if (fetched.wasRefusedStale) {
            engineLogger.info {
                "SELECT ${slot.table} response still predates a local write; will retry"
            }
            throw StaleResponseException(slot.table)
        }
        if (
            fetched.outcome == CacheWriteOutcome.REFUSED_OTHER_OWNER ||
                trustPolicy.refreshRetries(
                    fetched.sessionBefore,
                    fetched.sessionAfter,
                    fetched.isEmpty,
                )
        ) {
            engineLogger.info {
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
     * failures. A bootstrap that could not confirm the cache leaves it [RefreshState.Stalled] by
     * its cause ([RefreshState.Paused] when the network is known down, [RefreshState.Refreshing]
     * while the session settles), with a refresh retried as a cache-first read's until it succeeds;
     * it counts as the first attempt, so the next waits. Then, as a cache-first read does, it
     * fetches again on each refresh request for the table; an attempt in flight changes nothing it
     * reports.
     */
    private fun networkFirstThenCacheWithState(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Flow<QuerySnapshot> = flow {
        val unconfirmedBy = bootstrapFromNetwork(slot, columns, request)
        val progress =
            MutableStateFlow(
                if (unconfirmedBy == null) ReadProgress(RefreshState.Idle, fetchedAtOfCached(slot))
                else ReadProgress(waitingStateFor(unconfirmedBy), null)
            )
        var bootstrapCounted = false
        var confirmedByRefresh: Long? = null
        val failures = RefreshFailureReport(slot.table)
        val payloads =
            cacheFirstWithParallelRefresh(
                cache =
                    queryOverlay.servedPayloadFlow(slot.table, slot.cacheKey, slot.shape, progress),
                refresh = {
                    if (!bootstrapCounted) {
                        bootstrapCounted = true
                        if (unconfirmedBy != null) throw unconfirmedBy
                    } else {
                        failures.reporting { refreshFromNetwork(slot, columns, request) }
                        confirmedByRefresh = fetchedAtOfCached(slot)
                    }
                },
                wakeUps = wakeUps,
                onRefreshState = { state ->
                    if (state !is RefreshState.Refreshing)
                        progress.value = progress.value.reached(state, confirmedByRefresh)
                },
                refreshRequests = liveQueries.refreshRequestsFor(slot.table, slot.cacheKey),
                networkAccess = networkAccess,
                isWaiting = ::isAWait,
            )
        emitAll(snapshotsOf(payloads))
    }

    /**
     * How a network-first read reports a cache its bootstrap could not confirm because of [cause]:
     * paused when the network is known down, refreshing while the session settles or while the
     * answer predates a local write, otherwise stalled by it.
     */
    private fun waitingStateFor(cause: Exception): RefreshState =
        when {
            cause.isNetworkOrConnectivityFailure() && networkAccess.isKnownUnavailable ->
                RefreshState.Paused
            isAWait(cause) -> RefreshState.Refreshing
            else -> RefreshState.Stalled(cause)
        }

    /**
     * The network bootstrap of a network-first reactive read. A transport failure falls back to the
     * cached payload, at once, as a one-shot `NetworkFirst` read does; with none cached, or on a
     * server's own error, it fails the flow.
     *
     * @return why the cache it leaves is not the server's answer — the transport failure, an answer
     *   the cache did not take as the user's, or one it refused as older than a local write — or
     *   `null` when it is.
     */
    private suspend fun bootstrapFromNetwork(
        slot: CacheSlot,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): Exception? {
        val retries = retriesFor(cacheAnswers = hasFreshCache(slot, cacheDuration = null))
        return try {
            val answer =
                fetchWaitingOutRestoreAnswer(
                    slot,
                    columns,
                    request,
                    retries,
                    cacheServes = { true },
                )
            when {
                // A newer local write kept the cache; what it holds is not the server's answer
                answer.wasRefusedStale -> StaleResponseException(slot.table)
                answer.wasCached -> null
                else -> UntrustedResponseException(slot.table)
            }
        } catch (exception: Exception) {
            if (
                !exception.isNetworkOrConnectivityFailure() ||
                    !hasFreshCache(slot, cacheDuration = null)
            )
                throw exception
            engineLogger.info { "SELECT ${slot.table} network failed, falling back to cache" }
            exception
        }
    }

    /**
     * Sends the SELECT and offers its response to the cache, which refuses one that could roll back
     * a newer local write (then this asks once more, since that request starts after the write; a
     * reactive read's loop keeps asking after that), one that left before local data was cleared,
     * an untrusted one, one that is not a list of rows ([isRowListResponse]), and an empty one that
     * is not authoritative. A written one retires the synced mutations every cached query now
     * holds.
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
            val requestStartedAtTick = freshness.tickNow()
            val sent = send(slot, columns, request, retries)
            engineLogger.debug { "SELECT $table network response: ${sent.data}" }
            val sessionAfter = requestSession()
            // Only a list of rows is trusted; an empty one goes through the query cache's empty
            // guard, a non-empty one through the trust check. A blank body stands for an empty
            // list, and is served and stored as one
            val isRowList = isRowListResponse(sent)
            val response =
                if (sent.data.isBlank())
                    PostgrestResult(EMPTY_JSON_ARRAY, sent.headers, delegate.postgrest)
                else sent
            val isEmpty = isRowList && isEmptyJsonArrayPayload(response.data)
            val outcome =
                queryCache.cacheResponse(
                    table = table,
                    cacheKey = cacheKey,
                    payload = response.data,
                    authoritativeEmpty =
                        trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = true),
                    requestStartedAtTick = requestStartedAtTick,
                    isTrusted =
                        isRowList &&
                            (isEmpty ||
                                trustPolicy.mayCache(sessionBefore, sessionAfter, isEmpty = false)),
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
                    mutationQueue.retireCoveredSyncedMutations(table, requestStartedAtTick)
                else -> Unit
            }
            return FetchedResponse(
                response,
                sessionBefore,
                sessionAfter,
                outcome,
                nowMillis(),
                isRowList,
            )
        }
    }

    /**
     * What a one-shot read serves for [fetched]: the server's rows, or the cache when they were
     * refused as older than a local write, or when they are not a list of rows and the cache holds
     * rows [cacheServes] takes (`null` never does), with pending mutations overlaid.
     */
    private suspend fun overlaidResponse(
        slot: CacheSlot,
        fetched: FetchedResponse,
        cacheServes: ((updatedAt: Long) -> Boolean)?,
    ): PostgrestResult {
        val (table, cacheKey) = slot
        val cached =
            if (fetched.outcome == CacheWriteOutcome.REFUSED_STALE) {
                engineLogger.warning {
                    "SELECT $table response still predates a local write; serving cached state"
                }
                queryOverlay.cachedPayloadOverlaid(table, cacheKey, slot.shape)
            } else if (!fetched.isRowList && cacheServes != null) {
                engineLogger.warning {
                    "SELECT $table answered $NOT_A_ROW_LIST; serving cached state if any"
                }
                queryOverlay.cachedPayloadOverlaid(table, cacheKey, slot.shape, cacheServes)
            } else {
                null
            }
        val overlaid =
            cached?.overlay
                ?: queryOverlay.overlayPendingOnResponseReporting(
                    table = table,
                    cacheKey = cacheKey,
                    serverJson = fetched.response.data,
                    shape = slot.shape,
                )
        val provenance =
            ResultProvenance(
                isFromCache = cached != null,
                fetchedAtMillis =
                    cached?.let { payload -> fetchedAtOf(payload.updatedAt) }
                        ?: fetched.receivedAtMillis,
                hasPendingMutations = overlaid.hasPendingMutations,
                pendingRowKeys = overlaid.pendingRowKeys,
            )
        // The server's own headers stay with its own rows: a count, for one
        val headers = if (cached == null) fetched.response.headers else Headers.Empty
        return provenancedResult(overlaid.payload, delegate.postgrest, provenance, headers)
    }

    /** [result], the server's answer to this read just now, stamped as such. */
    private fun networkResult(result: PostgrestResult): PostgrestResult =
        provenancedResult(
            result.data,
            delegate.postgrest,
            ResultProvenance(
                isFromCache = false,
                fetchedAtMillis = nowMillis(),
                hasPendingMutations = false,
            ),
            result.headers,
        )

    /** When this result's rows were fetched, as its stamp says. */
    private fun PostgrestResult.fetchedAtInstant(): Instant? = fetchedAtStamped(this)

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
     * The cached payload of the query with its mutations overlaid, read in one step with them, or
     * `null` when none is cached, it was fetched longer than [cacheDuration] ago, or it is stale
     * and not [servesStale].
     */
    private suspend fun readCached(
        slot: CacheSlot,
        cacheDuration: Duration?,
        servesStale: Boolean,
    ): PostgrestResult? {
        val cached = overlaidCached(slot, cacheDuration, servesStale) ?: return null
        return provenancedResult(
            cached.overlay.payload,
            delegate.postgrest,
            ResultProvenance(
                isFromCache = true,
                fetchedAtMillis = fetchedAtOf(cached.updatedAt),
                hasPendingMutations = cached.overlay.hasPendingMutations,
                pendingRowKeys = cached.overlay.pendingRowKeys,
            ),
        )
    }

    /**
     * The payload cached for [slot] with its mutations overlaid, as [readCached] reads it; `null`
     * as it says.
     */
    private suspend fun overlaidCached(
        slot: CacheSlot,
        cacheDuration: Duration?,
        servesStale: Boolean,
    ): OverlaidCachedPayload? =
        queryOverlay.cachedPayloadOverlaid(slot.table, slot.cacheKey, slot.shape) { updatedAt ->
            (servesStale || !isStaleStamp(updatedAt)) &&
                isFresh(fetchedAtOf(updatedAt), cacheDuration)
        }

    /**
     * Whether a fallback to the cache would answer: a payload is cached, stale or not, fetched
     * within [cacheDuration].
     */
    private suspend fun hasFreshCache(slot: CacheSlot, cacheDuration: Duration?): Boolean {
        val (_, updatedAt) =
            queryCache.getPayloadWithTimestamp(slot.table, slot.cacheKey) ?: return false
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
     * What a cache-only read of a shape the cache cannot hold, asking for [kind], fails with: the
     * cache holds lists of rows, and it sends nothing.
     */
    private fun cacheCannotAnswer(kind: String): CacheMissException =
        CacheMissException(
            "A cache-only read cannot answer $kind: the cache holds lists of rows. Read the list and take " +
                "the rows you need, or read with a strategy that may use the network."
        )

    private fun buildResult(data: String, postgrest: Postgrest): PostgrestResult =
        PostgrestResult(data, Headers.Empty, postgrest)

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

        /** What a single-object read asks for, as a phrase for the log. */
        const val SINGLE_OBJECT = "a single object"

        /** What a cache-only single-object read fails with when no cached row answers it. */
        const val NO_CACHED_SINGLE_ROW =
            "No cached row answers this single-object read: its list is not cached, or holds no row or more than one"

        /** What a reactive cache-only single-object read fails with: the flow follows lists. */
        const val NO_REACTIVE_SINGLE_ROW =
            "A reactive cache-only read cannot answer a single object: read it once with ReadStrategy.CacheOnly, " +
                "or read the list as a flow and take the row you need."

        /**
         * Backoff steps a one-shot read waits through for a session that is restoring or whose
         * refresh failed: 300 + 900 + 2400 ms, the gaps between the refresh loop's fast retries. A
         * read that starts signed in never waits.
         */
        const val RESTORE_WINDOW_RETRY_WAITS = FAST_RETRY_COUNT - 1
    }
}

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
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kotlinx.coroutines.awaitCancellation
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
import kotlin.time.ExperimentalTime

/**
 * Thrown by the parallel-refresh side of [QueryReader.selectAsFlow] when a network SELECT
 * comes back empty from a request that did **not** carry the user's JWT (see
 * [AuthSessionState.RESTORING] and [AuthSessionState.UNAUTHENTICATED] for why such a response says
 * nothing about the user's data). Private to the SDK and treated as a retryable signal by
 * [cacheFirstWithParallelRefresh], so the self-healing loop attempts the refresh again — and
 * crucially keeps reporting [RefreshState.InFlight] / [RefreshState.Stalled] rather than settling
 * to [RefreshState.Idle] over a cache slot nothing was ever written to.
 *
 * Without this signal a 200-with-empty response is "success" from the SDK's perspective and the
 * retry loop never fires. The cache-write guard in
 * [io.github.nesfeder.supabase.offline.internal.db.OfflineStore.addPayload] still refuses to persist the empty, so
 * nothing is corrupted — but the subscription would sit at "no data" until it is recreated.
 * Throwing here recovers within seconds instead.
 */
internal class TransientEmptyResponseException(table: String) :
    RuntimeException("Empty SELECT response for $table while auth session not ready; will retry")

/**
 * Coarse auth-session snapshot the resolver reads once per network SELECT, *before* the request
 * goes out. It drives both empty-response decisions — whether an empty payload may replace
 * non-empty cached rows, and whether it should instead be retried as an auth artifact — from one
 * value, so the two can never disagree about the session the request was made under.
 *
 * ### The RLS-shaped empty
 * This is the condition the whole mechanism exists for, told once here rather than at every seam
 * that references it. supabase-kt resolves a request's access token from `currentSessionOrNull()`,
 * which is non-null **only** while the session status is `Authenticated`. Every other status sends
 * the anon key instead, and a user-scoped table under RLS then matches no rows — so PostgREST
 * answers `200 OK` with body `[]` for data that plainly exists. Written to the cache, that response
 * makes reactive observers re-emit an empty list (a loading placeholder that never resolves, or a
 * list screen that goes blank); returned to a one-shot caller, it renders "no
 * data" on a screen that has data. Neither is distinguishable on the wire from a genuinely empty
 * table — only the session state at request time tells them apart, which is what this enum carries.
 */
internal enum class AuthSessionState {
    /**
     * supabase-kt has not finished restoring the persisted session (`SessionStatus.Initializing`) —
     * the cold-start race. The request may go out on the anon key, so an empty response is treated
     * as the artifact described above: kept out of the cache, and retried on both read paths. The
     * parallel-refresh path raises [TransientEmptyResponseException] into its unbounded
     * self-healing loop; the one-shot path runs a bounded ladder of its own, because a blocking
     * caller cannot wait forever, before falling back to returning the empty response.
     */
    RESTORING,

    /**
     * The request carried the user's JWT, so an empty response is the server's authoritative
     * answer and may replace non-empty cached rows — how a delete performed on another device
     * converges here.
     */
    AUTHENTICATED,

    /**
     * No user JWT on the wire (signed out, or token refresh currently failing). The response is
     * RLS-shaped for the same reason as [RESTORING] and equally uninformative, so it never reaches
     * the cache; identity teardown (`SupabaseOfflineClient.clearAllCache`) owns removing cached
     * data on sign-out.
     *
     * The two paths diverge on what to do next. A live subscription keeps retrying — a failing
     * token refresh recovers on its own and the auth accelerator edge wakes the loop the moment it
     * does, and reporting a settled [RefreshState.Idle] over a slot nothing was written to would be
     * a lie. A one-shot caller does **not** retry: it is blocking a screen, and no amount of
     * waiting turns a signed-out session into a signed-in one.
     */
    UNAUTHENTICATED,
}

/**
 * What one [QueryReader.fetchAndCache] call did, beyond the [PostgrestResult] the
 * caller ultimately serves.
 *
 * A single `PostgrestResult` cannot answer the questions the restore-window ladder and the flow
 * path's retry both have to ask, because `fetchAndCache` may legitimately hand back
 * something other than the server's own body: a stale-refused response is answered from the cache,
 * a teardown-refused one with a synthesized [EMPTY_JSON_ARRAY], and every accepted response can
 * have pending mutations overlaid on top. Classifying "was this an auth-shaped empty" on that
 * derived payload gets both directions wrong — a pending insert hides a raw `[]` behind an overlaid
 * row, and a teardown synthesis fabricates an emptiness the server never sent.
 *
 * @property result Payload to serve the caller — cache-substituted and overlay-applied as usual.
 * @property rawServerPayload Body PostgREST actually returned on the **final** round-trip of this
 *   call (a stale-drop re-fetch makes the follow-up the final one). This, not [result], is what an
 *   emptiness check must read. On a teardown refusal there is a real server body too, and it is
 *   reported here even though [result] is the synthesized empty.
 * @property authStateAtRequest Session snapshot that governed that same final round-trip — the
 *   value passed in, or the fresh one a stale-drop re-fetch took for itself.
 * @property cacheCleared Whether the response was refused as predating a [OfflineStore.clearAll].
 *   Callers must not retry on it: a follow-up issued during sign-out can still carry the outgoing
 *   session's JWT and re-import the rows teardown just removed.
 */
private data class NetworkSelectResponse(
    val result: PostgrestResult,
    val rawServerPayload: String,
    val authStateAtRequest: AuthSessionState,
    val cacheCleared: Boolean,
) {
    /**
     * Whether the server's own body carried no rows. The single classification both retry paths
     * share; each combines it with its own auth predicate ([AuthSessionState.RESTORING] for the
     * bounded one-shot ladder, "anything but [AuthSessionState.AUTHENTICATED]" for the unbounded
     * flow-path loop).
     */
    val emptyServerResponse: Boolean get() = isEmptyJsonArrayPayload(rawServerPayload)
}

/**
 * Resolves [ReadStrategy] during select operations by deciding whether to
 * fetch from network, cache, or both, while respecting [ReadStrategy] options.
 *
 * Supports both suspend (one-shot) and Flow (reactive) APIs.
 */
internal class QueryReader(
    private val delegate: PostgrestQueryBuilderWrapper,
    private val store: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /**
     * Snapshot of the auth session, read once before each network SELECT. Drives whether an
     * empty response replaces cached rows ([AuthSessionState.AUTHENTICATED]), is retried as
     * the cold-start artifact ([AuthSessionState.RESTORING]), or is simply kept out of the
     * cache ([AuthSessionState.UNAUTHENTICATED]). Defaults to always-`AUTHENTICATED` so
     * callers (and tests) that don't wire this in treat every response as the server's
     * final answer and never enter the restore-window retry.
     *
     * The intended wiring is [io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl]'s mapping of
     * `supabase.auth.sessionStatus.value` — `Initializing` → `RESTORING`, `Authenticated` →
     * `AUTHENTICATED`, everything else → `UNAUTHENTICATED`.
     */
    private val authSessionState: () -> AuthSessionState = { AuthSessionState.AUTHENTICATED },
    /**
     * Client-scoped coordinator providing single-flight refresh sharing per `(table, signature)`
     * and the live-signature registry that backs materialize-on-write. Every `selectAsFlow`
     * collection registers its signature while active so a first-time save is visible to a
     * cache-less-but-observed subscription.
     */
    private val refreshCoordinator: RefreshCoordinator,
    /**
     * Internal edge-triggered recovery accelerator (auth session restored, connectivity regained)
     * that short-circuits the current backoff sleep — the flow path's self-healing refresh loop and
     * the one-shot restore-window ladder both wake on it. Defaults to an empty flow: recovery still
     * happens on each ladder's own schedule, so this is a hint only.
     */
    private val accelerator: Flow<Unit> = emptyFlow(),
) {

    suspend fun select(
        columns: Columns,
        readStrategy: ReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): PostgrestResult {
        val table = delegate.table
        sdkLogger.debug { "SELECT $table via ${readStrategy::class.simpleName}" }
        return when (readStrategy) {
            is ReadStrategy.NetworkFirst -> {
                try {
                    fetchWaitingOutRestore(table, columns, request)
                } catch (exception: Exception) {
                    if (!exception.isNetworkOrConnectivityFailure()) throw exception
                    val cached = readCached(table, columns, request, readStrategy.cacheDuration)
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
                fetchWaitingOutRestore(table, columns, request)
            }
            is ReadStrategy.CacheFirst -> {
                val cached = readCached(table, columns, request, readStrategy.cacheDuration)
                if (cached != null) {
                    sdkLogger.info { "SELECT $table served from cache" }
                    cached
                } else {
                    fetchWaitingOutRestore(table, columns, request)
                }
            }
            is ReadStrategy.CacheOnly -> {
                val cached = readCached(table, columns, request, readStrategy.cacheDuration)
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
     * Network SELECT that waits out the auth session-restore window instead of serving its
     * RLS-shaped empty — see [AuthSessionState] for what that empty is and why it is
     * indistinguishable from a real one on the wire.
     *
     * The reactive path handles this by raising [TransientEmptyResponseException] into
     * [cacheFirstWithParallelRefresh]'s unbounded self-healing loop. A one-shot caller has no such
     * loop, so a bounded equivalent lives here.
     *
     * ### Wait, then check, then spend a request
     * Retrying on a fixed schedule would fire requests into a session that is demonstrably still
     * restoring — each one guaranteed to come back RLS-shaped again. Instead the ladder separates
     * *waiting* from *attempting*: it sleeps the shared fast-ladder steps
     * ([defaultRefreshBackoffMillis]: 300 / 900 / 2400 ms) and re-reads [authSessionState] at every
     * wake, and only issues another request once the session has actually moved on — or once the
     * ladder is spent and the caller is owed a final answer. The wall-clock bound is unchanged at
     * ~3.6s, but the common all-restoring case costs two round-trips rather than four.
     *
     * Re-reading at each boundary is also what makes a *missed* [accelerator] edge harmless. The
     * production accelerator is a cold `drop(1)` flow, so an auth-ready transition that lands
     * between two collections of it is simply not delivered; the next step boundary observes the
     * new state anyway and fires immediately.
     *
     * Each attempt hands its snapshot to [fetchAndCache] rather than letting that function
     * take its own, so the authoritative-empty decision and the retry decision can never disagree
     * about the session a request was made under. Emptiness is classified on
     * [NetworkSelectResponse.rawServerPayload] — the body the server actually sent — so a pending
     * mutation overlaid on the result cannot mask a `[]` that still needs retrying, and a
     * teardown-synthesized empty ([NetworkSelectResponse.cacheCleared]) is returned untouched
     * rather than retried.
     *
     * Only [AuthSessionState.RESTORING] enters the ladder. [AuthSessionState.UNAUTHENTICATED]
     * empties are equally RLS-shaped, but a blocking caller must not sit waiting on a sign-in that
     * may never come.
     *
     * ### What an exhausted ladder costs
     * The terminal fallback is deliberately today's behavior — the `[]` is returned rather than
     * raised as a new exception type, because every one-shot call site is written against "a SELECT
     * returns rows or throws a network error". Cache correctness is owned separately by
     * [OfflineStore.addPayload], which never persists an unattributed empty, so an exhausted ladder
     * cannot poison a cache slot.
     *
     * It can still cost a render, though, and the cost is worst exactly where it is least
     * necessary: a `NetworkFirst` read whose ladder exhausts returns the empty even when a perfectly
     * good cached payload is sitting right there, because only a *thrown* connectivity failure
     * routes into that strategy's cache fallback. Falling back to cache on exhaustion the way a
     * network failure does would be strictly better for the caller; it is deliberately not done
     * here, since it changes what a `NetworkFirst` read means beyond the scope of this guard.
     *
     * ### Not covered
     * Reads built with `.single()` / `.maybeSingle()` never see a `200 []` — PostgREST answers a
     * zero-row `single()` with a 406 `RestException`, which throws straight out of the delegate
     * before any of this runs, so a `.single()` / `.maybeSingle()` read is not protected by this
     * ladder. (A list read is, even when its filter targets a single id.) Extending the ladder to
     * classify those exceptions is a separate change.
     *
     * A connectivity failure likewise throws out of the first attempt before any emptiness check,
     * so an offline caller falls through to its strategy's cache handling immediately instead of
     * waiting out a ladder that cannot help it.
     *
     * @param table Table being selected from.
     * @param columns Columns projection for the request.
     * @param request PostgREST request builder for the query.
     * @return The first response that is not a restore-window empty, or the last attempt's response.
     */
    private suspend fun fetchWaitingOutRestore(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ): PostgrestResult {
        // Snapshot before the request, and hand the same value down: the write-attribution decision
        // and the retry decision must see one session, not two.
        var authStateAtRequest = authSessionState()
        var stepsTaken = 0

        while (true) {
            val response = fetchAndCache(
                table = table,
                columns = columns,
                request = request,
                authStateAtRequest = authStateAtRequest,
            )

            // A teardown refusal answers with a synthesized empty, not the server's. Retrying it
            // is the one thing the teardown contract forbids.
            if (response.cacheCleared) return response.result

            val restoreWindowEmpty =
                response.authStateAtRequest == AuthSessionState.RESTORING && response.emptyServerResponse
            if (!restoreWindowEmpty) return response.result

            if (stepsTaken == RESTORE_WINDOW_RETRY_WAITS) {
                // Tolerated, self-recovering condition — never an error level. Logged distinctly
                // from the wait below so release logs separate "gave up" from "recovered late".
                sdkLogger.warning {
                    "SELECT $table restore-window ladder exhausted after " +
                        "$RESTORE_WINDOW_RETRY_WAITS waits; serving the empty response"
                }
                return response.result
            }

            sdkLogger.warning {
                "SELECT $table empty response while auth session restoring; waiting for the session"
            }

            // Walk the ladder until the session moves on or the steps run out. Waiting is cheap;
            // a request issued while still restoring is not, and would come back RLS-shaped again.
            do {
                stepsTaken++
                awaitRestoreWindowBackoff(defaultRefreshBackoffMillis(stepsTaken))
                authStateAtRequest = authSessionState()
            } while (
                authStateAtRequest == AuthSessionState.RESTORING &&
                stepsTaken < RESTORE_WINDOW_RETRY_WAITS
            )
        }
    }

    /**
     * Waits [millis] for the next restore-window step boundary, waking early on the first
     * [accelerator] edge (auth session restored, connectivity regained) so a session that finishes
     * restoring mid-sleep is re-checked immediately instead of after the full step.
     *
     * @param millis Milliseconds to wait before the next boundary.
     */
    private suspend fun awaitRestoreWindowBackoff(millis: Long) {
        withTimeoutOrNull(millis) {
            // An accelerator that completes without ever emitting — the default empty flow, used by
            // callers and tests that don't wire recovery edges in — must not collapse the wait to
            // zero. Suspend instead and let the timeout end it.
            accelerator.firstOrNull() ?: awaitCancellation()
        }
    }

    /**
     * Reactive read that surfaces per-query read+refresh state in-band as [OfflineReadResult]: the
     * background [RefreshState] and the typed "no data yet" state (a `null` result) that replaces
     * the old empty-array cache seed.
     *
     * Every collection registers its signature on the [refreshCoordinator] while active, so a
     * first-time save materializes into a real cache row for a cache-less-but-observed
     * subscription. `CacheFirstThenNetwork` collections additionally share a single-flight refresh
     * loop per signature.
     */
    fun selectAsFlow(
        columns: Columns,
        readStrategy: FlowReadStrategy,
        request: SelectRequestBuilder.() -> Unit
    ): Flow<OfflineReadResult> {
        val table = delegate.table
        val signature = getSignature(table, columns, request)
        val base = when (readStrategy) {
            FlowReadStrategy.CacheFirstThenNetwork -> flow {
                emitAll(
                    refreshCoordinator.sharedReadFlow(table, signature) {
                        cacheFirstThenNetworkWithState(table, columns, request, signature)
                    },
                )
            }
            FlowReadStrategy.CacheOnly -> cacheOnlyWithState(signature, table)
            FlowReadStrategy.NetworkFirstThenCache ->
                networkFirstThenCacheWithState(table, columns, request, signature)
        }
        return base
            .onStart { refreshCoordinator.registerLiveSignature(table, signature) }
            .onCompletion { refreshCoordinator.unregisterLiveSignature(table, signature) }
    }

    /**
     * Emit only cached values (with pending-mutation overlay) and never hit the network. No refresh
     * runs, so [RefreshState.Idle] is reported throughout and a cache-less signature stays at the
     * typed no-data state until data arrives.
     */
    private fun cacheOnlyWithState(signature: String, table: String): Flow<OfflineReadResult> =
        store.cachePayloadWithOverlayFlow(table, signature).map { payload ->
            OfflineReadResult(
                result = payload?.let { json -> buildResult(json, delegate.postgrest) },
                refresh = RefreshState.Idle,
            )
        }

    /**
     * Emit cached values immediately while a self-healing network refresh runs in parallel, and
     * surface the refresh lifecycle as [OfflineReadResult]. Delegates to
     * [cacheFirstWithParallelRefresh] for the channelFlow mechanics so the parallel-emit-and-refresh
     * semantic can be unit-tested in isolation without a real [OfflineStore]. This is the
     * per-signature upstream shared for single-flight by the coordinator.
     */
    private fun cacheFirstThenNetworkWithState(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        signature: String,
    ): Flow<OfflineReadResult> {
        val refreshState = MutableStateFlow<RefreshState>(RefreshState.InFlight)
        val forwardedPayload = cacheFirstWithParallelRefresh(
            cache = store.cachePayloadWithOverlayFlow(table, signature),
            refresh = { refreshFromNetwork(table, columns, request) },
            accelerator = accelerator,
            onRefreshState = { state -> refreshState.value = state },
        )

        // Build the PostgrestResult once per *distinct* payload, not once per emission: a
        // refresh-state transition (InFlight -> Idle, the retry ladder) must reach consumers,
        // but it re-emits the previous result *instance* rather than re-wrapping the same JSON.
        // Repositories that dedupe on the result before decoding therefore see a stable value;
        // the payload-level distinctUntilChanged is belt-and-suspenders on top of the store's
        // own dedup, guarding this seam against future upstream changes.
        val results = forwardedPayload
            .distinctUntilChanged()
            .map { payload -> payload?.let { json -> buildResult(json, delegate.postgrest) } }

        return combine(results, refreshState) { result, state ->
            OfflineReadResult(
                result = result,
                refresh = state,
            )
        }
    }

    /**
     * The refresh side of [cacheFirstThenNetworkWithState]: run the network SELECT (writing its
     * payload through to the cache), snapshotting the auth session once *before* the request so
     * both consumers of that fact agree.
     *
     * When the request did not carry the user's JWT and the response is empty, throw
     * [TransientEmptyResponseException] so the self-healing loop keeps retrying. The condition is
     * "the snapshot was anything but [AuthSessionState.AUTHENTICATED]", not "the snapshot was
     * [AuthSessionState.RESTORING]": both a restoring session and a signed-out / refresh-failing
     * one produce the same uninformative RLS-shaped empty, and the store refuses to persist either.
     * Treating only the restoring case as retryable would leave the
     * [AuthSessionState.UNAUTHENTICATED] one reporting [RefreshState.Idle] over a cache slot
     * nothing was ever written to — a subscription that looks settled and healthy while showing an
     * empty screen, with no retry left to fix it when the token refresh recovers. Retrying instead
     * costs a capped-backoff request every 60s at worst and heals the instant the auth accelerator
     * edge lands.
     *
     * When the session *was* authenticated, the same snapshot marks the payload authoritative, so a
     * genuinely-empty result replaces the cached rows — this is how a delete performed on another
     * device converges here instead of rendering stale forever.
     *
     * Emptiness is read from [NetworkSelectResponse.rawServerPayload] rather than the served
     * result, so a stale-refused response answered from cache (or a teardown-synthesized empty) is
     * classified on what the server actually sent.
     */
    private suspend fun refreshFromNetwork(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
    ) {
        // Snapshot the session state *before* the request so a session that finishes restoring
        // mid-flight doesn't spuriously suppress a legit-empty response.
        val response = fetchAndCache(
            table = table,
            columns = columns,
            request = request,
            applyOverlay = false,
            authStateAtRequest = authSessionState(),
        )
        if (response.cacheCleared) return
        if (response.authStateAtRequest != AuthSessionState.AUTHENTICATED && response.emptyServerResponse) {
            sdkLogger.warning {
                "SELECT $table empty response while auth session not ready; will retry"
            }
            throw TransientEmptyResponseException(table)
        }
    }

    /**
     * Perform a network request first and wait for it to complete, then observe the cache. If the
     * network request fails, the flow fails — that blocking IS the intended semantic for
     * [FlowReadStrategy.NetworkFirstThenCache]; the caller explicitly opted in to waiting on the
     * network before the first emission. Reports [RefreshState.Idle] since the network already
     * completed by the time any value is emitted.
     *
     * The bootstrap goes through [fetchWaitingOutRestore] like the one-shot reads do.
     * This strategy has no self-healing loop behind it — the single bootstrap request *is* the
     * refresh — so without the ladder a cold-start subscription would bootstrap from an RLS-shaped
     * empty, write nothing to the cache, and then sit on the typed no-data state forever while
     * reporting [RefreshState.Idle]. The bounded ladder is the only retry this path gets.
     */
    private fun networkFirstThenCacheWithState(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        signature: String,
    ): Flow<OfflineReadResult> =
        store.cachePayloadWithOverlayFlow(table, signature)
            .onStart {
                // Perform network request first to bootstrap the cache. Any network errors will be
                // thrown and terminate the flow. The result is discarded — the cache write is the
                // point, and the flow below reads it back.
                fetchWaitingOutRestore(
                    table = table,
                    columns = columns,
                    request = request,
                )
            }
            .map { payload ->
                OfflineReadResult(
                    result = payload?.let { json -> buildResult(json, delegate.postgrest) },
                    refresh = RefreshState.Idle,
                )
            }

    /**
     * Runs the network SELECT and writes its payload through to the cache.
     *
     * An empty response is marked authoritative (allowed to replace a non-empty cached
     * payload) only when the session is [AuthSessionState.AUTHENTICATED] on **both sides** of
     * the round-trip: [authStateAtRequest] before, and a fresh read after the response.
     * supabase-kt attaches the user JWT *inside* the select call — after the pre-request
     * snapshot — so a sign-out or refresh-failure landing in that gap sends the request out
     * on the anon key and RLS shapes an empty; the post-response read catches exactly that
     * transition. Empties from a restoring or unauthenticated session are RLS-shaped
     * artifacts and stay blocked by the store's clobber guard.
     *
     * ### Stale-response re-fetch
     * The tick read immediately before each request is handed to [OfflineStore.addPayload], which
     * refuses a response that left before the table's last local write. On that refusal this
     * issues **one** follow-up request and no more: the follow-up starts after the write that
     * disqualified the first, so it clears the same watermark by construction and cannot loop.
     * Should a second write land inside that window too, the attempt budget is spent and the cache
     * simply keeps local truth — the caller is served the cached payload and the next natural
     * refresh converges. A bounded budget per call is the whole storm guard: however fast mutations
     * arrive, **one invocation of this function** issues at most two requests.
     *
     * That is a per-invocation bound, not a per-operation one. A caller that retries this function
     * — the restore-window ladder in [fetchWaitingOutRestore], or the flow path's
     * self-healing loop — multiplies it by its own attempt budget. The one-shot worst case is
     * therefore four requests (two ladder attempts, each allowed one stale re-fetch), reached only
     * when a mutation burst races an auth restore; the flow path's loop is unbounded in attempts by
     * design and paced by its backoff schedule rather than by this budget.
     *
     * A refused payload — stale **or** empty — also suppresses
     * [OfflineStore.clearSyncedMutationsForTable] for that pass. Retiring the overlay while
     * refusing the payload that was supposed to replace it would drop a replayed mutation out of
     * both the queue and the cache at once; that is just as true when the refusal is the
     * empty-clobber guard as when it is the watermark, since neither wrote the server state the
     * overlay was standing in for. The request tick is handed to the retirement too, so a replay
     * that lands in the gap *between* the accepted write and the retirement is caught by the same
     * predicate rather than slipping past it.
     *
     * ### Teardown
     * A response that predates a [OfflineStore.clearAll] is refused outright and, unlike a stale
     * drop, is **not** re-fetched: sign-out and account switch are exactly when a follow-up could
     * still carry the outgoing session's JWT and re-import the rows teardown just removed. The
     * caller is answered with an empty result, which is what the emptied cache now holds, and
     * [NetworkSelectResponse.cacheCleared] tells retrying callers not to try again either.
     *
     * @param authStateAtRequest The pre-request auth-session snapshot — taken at call entry
     *   (the default) or passed in by a caller that already snapshotted it for its own retry
     *   decision, so both decisions see the same value. A follow-up request after a stale drop
     *   takes its own fresh snapshot, since it is a genuinely new round-trip; that effective
     *   snapshot is what [NetworkSelectResponse.authStateAtRequest] reports back.
     * @return The served result plus the raw round-trip facts a retrying caller needs to classify
     *   it — see [NetworkSelectResponse].
     */
    private suspend fun fetchAndCache(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        applyOverlay: Boolean = true,
        authStateAtRequest: AuthSessionState = authSessionState(),
    ): NetworkSelectResponse {
        val signature = getSignature(table, columns, request)
        var authState = authStateAtRequest
        var refetchBudget = STALE_RESPONSE_REFETCH_BUDGET

        while (true) {
            val requestStartedAtTick = store.monotonicTickNow()
            val networkResult = delegate.select(columns, request)
            sdkLogger.debug { "SELECT $table network response: ${networkResult.data}" }

            val outcome = store.addPayload(
                table = table,
                signature = signature,
                payload = networkResult.data,
                authoritativeEmpty = authState == AuthSessionState.AUTHENTICATED &&
                    authSessionState() == AuthSessionState.AUTHENTICATED,
                requestStartedAtTick = requestStartedAtTick,
            )

            if (outcome == AddPayloadOutcome.DROPPED_CACHE_CLEARED) {
                sdkLogger.info {
                    "SELECT $table response predates cache teardown; discarding"
                }
                // No re-fetch and no overlay: the queue was wiped with the cache, and a follow-up
                // request during sign-out can still carry the outgoing JWT. The server's own body
                // still travels back so a caller cannot mistake this synthesized empty for one.
                return NetworkSelectResponse(
                    result = buildResult(EMPTY_JSON_ARRAY, delegate.postgrest),
                    rawServerPayload = networkResult.data,
                    authStateAtRequest = authState,
                    cacheCleared = true,
                )
            }

            if (outcome == AddPayloadOutcome.DROPPED_STALE && refetchBudget > 0) {
                refetchBudget--
                sdkLogger.info {
                    "SELECT $table response predates a local write; re-fetching once"
                }
                authState = authSessionState()
                continue
            }

            val staleDrop = outcome == AddPayloadOutcome.DROPPED_STALE
            if (staleDrop) {
                sdkLogger.warning {
                    "SELECT $table response still predates a local write; serving cached state"
                }
            }

            // A refused stale response says nothing current; serve what the cache knows instead
            // (it already carries the local write that disqualified the response).
            val basePayload = if (staleDrop) {
                store.getPayload(table, signature) ?: networkResult.data
            } else {
                networkResult.data
            }

            val finalJson = if (applyOverlay) {
                // Overlay *pending* mutations on fresh network result.
                store.applyPendingMutations(
                    table = table,
                    signature = signature,
                    serverJson = basePayload
                )
            } else {
                basePayload
            }

            // A payload the cache refused — stale or unattributed-empty — did not replace the
            // server state the overlay was standing in for, so the overlay must survive it.
            val refusedPayload = staleDrop || outcome == AddPayloadOutcome.DROPPED_EMPTY
            if (!refusedPayload) {
                // Clear already-synced mutations for this table. The request tick goes along so a
                // replay that completed since the payload was accepted still blocks retirement.
                store.clearSyncedMutationsForTable(table, requestStartedAtTick)
            }

            val result = if (finalJson == networkResult.data) {
                networkResult
            } else {
                buildResult(finalJson, delegate.postgrest)
            }
            return NetworkSelectResponse(
                result = result,
                rawServerPayload = networkResult.data,
                authStateAtRequest = authState,
                cacheCleared = false,
            )
        }
    }

    private suspend fun readCached(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit,
        cacheDuration: Duration?,
        applyOverlay: Boolean = true
    ): PostgrestResult? {
        val signature = getSignature(table, columns, request)
        val cached = store.getPayloadWithTimestamp(table, signature) ?: return null
        val (payload, updatedAt) = cached

        if (cacheDuration != null) {
            val now = nowMillis()
            val age = now - updatedAt
            if (age > cacheDuration.inWholeMilliseconds) {
                return null // Cache expired
            }
        }

        val finalJson = if (applyOverlay) {
            store.applyAllMutationsForOfflineFallback(
                table = table,
                signature = signature,
                cachedJson = payload
            )
        } else {
            payload
        }
        return buildResult(finalJson, delegate.postgrest)
    }

    private fun getSignature(
        table: String,
        columns: Columns,
        request: SelectRequestBuilder.() -> Unit
    ): String {
        return cacheKeyOf(
            table = table,
            columns = columns,
            builderFactory = {
                SelectRequestBuilder(delegate.schema, delegate.postgrest.config.propertyConversionMethod)
            },
            request = request
        )
    }

    private fun buildResult(data: String, postgrest: Postgrest): PostgrestResult {
        val headers: Headers = HeadersBuilder().build()
        return PostgrestResult(data, headers, postgrest)
    }

    private companion object {
        /**
         * Follow-up network requests a single [fetchAndCache] call may issue after the
         * store refuses a response as predating a local write. One is enough to converge — the
         * follow-up starts after that write — and a fixed budget makes a mutation burst cost a
         * bounded number of requests instead of a retry storm.
         */
        const val STALE_RESPONSE_REFETCH_BUDGET = 1

        /**
         * Step boundaries the restore-window ladder may wait through before it owes the caller a
         * final answer. Derived from [FAST_LADDER_ATTEMPTS] so the two ladders stay the same shape:
         * *n* attempts have *n − 1* gaps between them, which under [defaultRefreshBackoffMillis]
         * are 300 + 900 + 2400 ms ≈ 3.6s — long enough for a normal session restore, short enough
         * not to strand a screen.
         *
         * A read that starts under an [AuthSessionState.AUTHENTICATED] snapshot never enters the
         * ladder at all, so the ordinary signed-in path pays nothing. A read that starts *during*
         * the restore window and is genuinely empty does pay the wait — that is the unavoidable
         * cost of the two being indistinguishable on the wire, and it is bounded here rather than
         * left to an unbounded loop, because a one-shot caller is blocking a screen.
         */
        const val RESTORE_WINDOW_RETRY_WAITS = FAST_LADDER_ATTEMPTS - 1
    }
}

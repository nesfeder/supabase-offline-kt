// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Number of attempts in the fast retry ladder before a refresh is reported as
 * [RefreshState.Stalled].
 *
 * Internal rather than private because the one-shot restore-window ladder in [QueryReader] derives
 * its wait budget from this: the two ladders are deliberately the same shape, and deriving one from
 * the other keeps them from drifting apart if this is retuned.
 */
internal const val FAST_RETRY_COUNT: Int = 4

/**
 * Backoff sleep applied after the [failures]-th consecutive failed refresh attempt.
 *
 * The first three delays are the historical fast ladder (attempts 1–4); once the fast ladder is
 * exhausted the schedule continues with capped exponential backoff so a subscription under
 * sustained bad conditions keeps retrying cheaply forever instead of giving up.
 *
 * Schedule (delay *after* failure N): 300, 900, 2400, then 5s, 15s, 60s, 60s, …
 *
 * @param failures Number of consecutive failures so far (1-based).
 * @return How long to sleep before the next attempt.
 */
internal fun refreshBackoff(failures: Int): Duration =
    when (failures) {
        1 -> 300.milliseconds
        2 -> 900.milliseconds
        3 -> 2_400.milliseconds
        4 -> 5_000.milliseconds
        5 -> 15_000.milliseconds
        else -> 60_000.milliseconds
    }

/**
 * Combinator that forwards emissions from [cache] to the downstream collector while running a
 * self-healing network [refresh] in parallel. Used by
 * [QueryReader][io.github.nesfeder.supabase.offline.internal.postgrest.QueryReader] to refresh the
 * cache from the network without blocking cached emissions.
 *
 * Why this exists as a separate helper: the original implementation wrapped the network refresh in
 * `onStart { coroutineScope { launch { ... } } }`. [kotlinx.coroutines.coroutineScope] suspends
 * until all its children complete, so `onStart` blocked downstream collection on the network
 * round-trip — the opposite of the intended "fire in parallel" semantic. Using [channelFlow] with
 * two independent [launch] blocks actually runs them concurrently.
 *
 * ### Self-healing refresh
 * The refresh retries for the **life of the subscription**: the fast ladder for the first
 * [FAST_RETRY_COUNT] attempts (300/900/2400ms between them), then capped exponential backoff (5s,
 * 15s, 60s, 60s, …). The first success ends the loop permanently — a broken network never tears
 * down the flow, and cached emissions keep flowing in parallel regardless. This replaces the old
 * give-up-after-a-fixed-budget behavior that pinned first-run subscriptions on empty caches until
 * process restart.
 *
 * ### Recovery accelerator
 * [wakeUps] is an optional edge-triggered wake-up (auth restored / connectivity regained, built by
 * [refreshWakeUps]). An edge that lands while the loop is sleeping cancels the current backoff
 * delay and retries immediately. Edges are conflated, so a burst buys exactly one immediate
 * attempt, and — being strict transitions — a persistently-failing refresh can never hot-loop. The
 * accelerator is a hint only: with an empty [wakeUps] the loop still recovers on its capped
 * schedule.
 *
 * ### State reporting
 * [onRefreshState] observes the refresh lifecycle: [RefreshState.Refreshing] at start,
 * [RefreshState.Stalled] once the fast ladder exhausts (carrying that attempt's cause), and
 * [RefreshState.Idle] on the first success. A refresh that fails for want of a network known to be
 * down ([networkAccess]) is [RefreshState.Paused] instead, and one made while network access is off
 * waits for it, sending nothing. This is how the resolver surfaces per-query refresh status in-band
 * on `selectAsFlow`.
 *
 * Cancellation-correct: real cancellation of the collecting coroutine rethrows immediately (via
 * [ensureActive] between attempts) rather than being folded into the retry loop, while a
 * timeout-flavored [kotlinx.coroutines.CancellationException] thrown by an inner `withTimeout` with
 * the caller still active remains a retryable failure.
 *
 * @param cache Flow of cached values to forward downstream as they arrive.
 * @param refresh Suspend operation that refreshes the cache (typically a network SELECT whose side
 *   effect is a `QueryCache.cacheResponse` that triggers a new [cache] emission).
 * @param wakeUps Edge-triggered wake-ups that short-circuit the current backoff sleep.
 * @param onRefreshState Callback invoked on each refresh lifecycle transition.
 * @param backoff Backoff schedule; injectable so tests can shrink or reshape delays.
 * @param refreshRequests Each emission after the first success runs the refresh again.
 * @param networkAccess whether the network is known down, or access to it off.
 */
internal fun <T> cacheFirstWithParallelRefresh(
    cache: Flow<T>,
    refresh: suspend () -> Unit,
    wakeUps: Flow<Unit> = emptyFlow(),
    onRefreshState: (RefreshState) -> Unit = {},
    backoff: (failures: Int) -> Duration = ::refreshBackoff,
    refreshRequests: Flow<Unit> = emptyFlow(),
    networkAccess: NetworkAccess = NetworkAccess(),
): Flow<T> = channelFlow {
    // Forward cache emissions immediately — not gated on the network.
    launch {
        try {
            cache.collect { value -> send(value) }
        } catch (failure: Throwable) {
            // The collector's cancellation rethrows; the cache's own failure, a cancellation
            // included, ends the flow after the values it sent
            if (failure is CancellationException && !isActive) throw failure
            this@channelFlow.close(failure)
        }
    }
    // In parallel, refresh the cache with self-healing backoff, and again on each later request.
    launch {
        refreshUntilSuccess(
            refresh = refresh,
            wakeUps = wakeUps,
            onRefreshState = onRefreshState,
            backoff = backoff,
            networkAccess = networkAccess,
        )
        refreshRequests.conflate().collect {
            refreshUntilSuccess(
                refresh = refresh,
                wakeUps = wakeUps,
                onRefreshState = onRefreshState,
                backoff = backoff,
                networkAccess = networkAccess,
            )
        }
    }
}

/**
 * Runs [refresh] until it succeeds, retrying with [backoff] backoff for the life of the calling
 * coroutine. Reports lifecycle transitions through [onRefreshState] and short-circuits the current
 * sleep whenever [wakeUps] emits.
 *
 * @param refresh The refresh operation to retry until it succeeds.
 * @param wakeUps Edge wake-ups that cancel the current backoff sleep.
 * @param onRefreshState Lifecycle callback ([RefreshState.Refreshing] / [RefreshState.Paused] /
 *   [RefreshState.Stalled] / [RefreshState.Idle]).
 * @param backoff Backoff schedule keyed by consecutive-failure count.
 * @param networkAccess whether the network is known down ([RefreshState.Paused] rather than a
 *   failure), or access to it off (nothing is tried until it is back on).
 */
internal suspend fun refreshUntilSuccess(
    refresh: suspend () -> Unit,
    wakeUps: Flow<Unit>,
    onRefreshState: (RefreshState) -> Unit,
    backoff: (failures: Int) -> Duration,
    networkAccess: NetworkAccess = NetworkAccess(),
): Unit = coroutineScope {
    var reported: RefreshState = RefreshState.Refreshing
    val report = { state: RefreshState ->
        reported = state
        onRefreshState(state)
    }
    report(RefreshState.Refreshing)
    // Conflated so a burst of edges buys exactly one immediate re-attempt; buffered for the whole
    // loop lifetime so an edge that lands between attempts (not just during a sleep) is not missed.
    val bufferedWakeUps = Channel<Unit>(Channel.CONFLATED)
    val wakeUpCollector = launch {
        wakeUps.collect { bufferedWakeUps.trySend(Unit) }
    }
    try {
        var failures = 0
        while (true) {
            if (networkAccess.isDisabled) {
                // Nothing is sent while network access is off: wait for it, paused, not failing
                if (reported != RefreshState.Paused) report(RefreshState.Paused)
                networkAccess.awaitEnabled()
                report(RefreshState.Refreshing)
            }
            val failure =
                try {
                    refresh()
                    null
                } catch (error: Exception) {
                    // Rethrow real cancellation of this coroutine; treat everything else as
                    // retryable.
                    currentCoroutineContext().ensureActive()
                    error
                }

            if (failure == null) {
                report(RefreshState.Idle)
                return@coroutineScope
            }

            failures++
            if (failure.isNetworkOrConnectivityFailure() && networkAccess.isKnownUnavailable) {
                // The network is known down: waiting for it, not failing. Still retried on the
                // capped schedule, as a monitor can be wrong
                if (reported != RefreshState.Paused) report(RefreshState.Paused)
            } else if (failures >= FAST_RETRY_COUNT && reported !is RefreshState.Stalled) {
                // The fast ladder exhausted: report the stall once, then keep retrying on the
                // capped schedule (state stays Stalled until a success flips it to Idle).
                report(RefreshState.Stalled(failure))
            }

            // Interruptible backoff: wake early if an accelerator edge arrives, otherwise sleep out
            // the capped delay. A real cancellation propagates out of `receive()`.
            val wokenEarly =
                withTimeoutOrNull(backoff(failures)) { bufferedWakeUps.receive() } != null
            // The network came back: what follows is a refresh again, not a wait
            if (wokenEarly && reported == RefreshState.Paused) report(RefreshState.Refreshing)
        }
    } finally {
        wakeUpCollector.cancel()
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AccessTokenIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.isSettledSessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * Builds the SDK-internal recovery accelerator: a merged, edge-triggered stream that emits whenever
 * a condition that commonly breaks a `CacheFirstThenNetwork` network refresh has just improved. The
 * refresh loop in
 * [cacheFirstWithParallelRefresh][io.github.nesfeder.supabase.offline.internal.postgrest.cacheFirstWithParallelRefresh]
 * collects this flow and short-circuits its current backoff sleep on each edge, retrying
 * immediately instead of waiting out the (capped) delay.
 *
 * This is a wake-up hint only — **never** required for correctness. With no accelerator the loop
 * still recovers on its own capped schedule (≤60s after conditions actually improve). Because the
 * edges are strict transitions (the value observed at subscription is skipped), a refresh that
 * keeps failing under unchanged conditions can never hot-loop: the worst case is one immediate
 * attempt per real transition plus the capped schedule.
 *
 * Two sources are merged, both derived entirely from types the SDK already owns so the offline
 * package stays free of app-layer imports:
 * - **Auth session settled** — the session status becoming one [trustPolicy] trusts: signed in or
 *   signed out, or only signed in under [ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY]. Covers
 *   the cold-start race where session restore takes longer than the fast ladder, plus recovery from
 *   a failed token refresh. Empty when the Auth plugin is not installed, unless the identity is
 *   told from the access token: then the token naming an identity a read trusts.
 * - **Connectivity regained** — [reconnections]: the network becoming reachable again, as the
 *   connectivity monitor or the app's own signal says, or the app turning network access back on.
 *
 * @param supabase The underlying online client whose auth session status is observed.
 * @param reconnections One [Unit] each time the network becomes reachable again.
 * @param trustPolicy which session statuses a read trusts, and so which ones wake it.
 * @param tokenIdentityStates the identity the access token names, as it changes, for a client that
 *   tells users apart by it; `null` otherwise.
 * @return A merged edge-triggered accelerator flow, or an effectively-empty flow when no source is
 *   available.
 */
internal fun refreshWakeUps(
    supabase: SupabaseClient,
    reconnections: Flow<Unit>,
    trustPolicy: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    tokenIdentityStates: Flow<AccessTokenIdentity.State>? = null,
): Flow<Unit> {
    val sessionReady: (SessionStatus) -> Boolean =
        when (trustPolicy) {
            ResponseTrustPolicy.SETTLED_SESSION -> ::isSettledSessionStatus
            ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY -> { status ->
                status is SessionStatus.Authenticated
            }
        }
    val tokenReady: (AccessTokenIdentity.State) -> Boolean =
        when (trustPolicy) {
            ResponseTrustPolicy.SETTLED_SESSION -> { state ->
                state.settled != null
            }
            ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY -> { state ->
                state.settled is QueueIdentity.User
            }
        }
    val authWakeUps =
        supabase.pluginManager.getPluginOrNull(Auth)?.sessionStatus?.let { statuses ->
            risingEdges(statuses.map(sessionReady))
        }
            ?: tokenIdentityStates?.let { states -> risingEdges(states.map(tokenReady)) }
            ?: emptyFlow()
    return merge(authWakeUps, reconnections)
}

/**
 * Maps a readiness stream to its not-ready→ready edges: emits [Unit] each time [readiness]
 * transitions to `true`, and never for the value observed at subscription time.
 *
 * Designed for [kotlinx.coroutines.flow.StateFlow]-backed sources, whose current value is replayed
 * on collect — `drop(1)` discards that replay so an already-ready source does not fire a spurious
 * trigger, which is what keeps the refresh accelerator strictly edge-driven.
 *
 * @param readiness Source stream of ready/not-ready values; duplicates are conflated.
 * @return One [Unit] per false→true transition observed after subscription.
 */
internal fun risingEdges(readiness: Flow<Boolean>): Flow<Unit> =
    readiness.distinctUntilChanged().drop(1).filter { ready -> ready }.map {}

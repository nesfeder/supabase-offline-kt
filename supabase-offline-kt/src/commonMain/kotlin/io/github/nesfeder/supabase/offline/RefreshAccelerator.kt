// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
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
 * - **Auth session restored** — [SupabaseClient.auth]'s session status transitioning into
 *   [SessionStatus.Authenticated]. Covers the cold-start race where session restore takes longer
 *   than the fast ladder, plus recovery from a failed token refresh. Degrades to an empty flow (via
 *   [runCatching]) when the Auth plugin is not installed, mirroring the defensive probe in
 *   [SupabaseOfflineClientImpl].
 * - **Connectivity regained** — the optional [networkAvailability] hint transitioning to `true`.
 *   This is a plain platform-agnostic `Flow<Boolean>`, not any particular connectivity type; the
 *   caller maps its own connectivity monitor into it, so that mapping stays with the caller.
 *
 * @param supabase The underlying online client whose auth session status is observed.
 * @param networkAvailability Optional connectivity hint; `null` disables the connectivity source.
 * @return A merged edge-triggered accelerator flow, or an effectively-empty flow when no source is
 *   available.
 */
internal fun buildRefreshAccelerator(
    supabase: SupabaseClient,
    networkAvailability: Flow<Boolean>?,
): Flow<Unit> {
    val authEdges = runCatching {
        refreshReadyEdges(
            supabase.auth.sessionStatus.map { status -> status is SessionStatus.Authenticated }
        )
    }
        .getOrDefault(emptyFlow())
    val connectivityEdges =
        networkAvailability?.let { availability -> refreshReadyEdges(availability) } ?: emptyFlow()
    return merge(authEdges, connectivityEdges)
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
internal fun refreshReadyEdges(readiness: Flow<Boolean>): Flow<Unit> =
    readiness.distinctUntilChanged().drop(1).filter { ready -> ready }.map {}

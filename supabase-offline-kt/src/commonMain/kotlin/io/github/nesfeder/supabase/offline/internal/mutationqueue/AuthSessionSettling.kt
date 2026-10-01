// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long [awaitReplaySafeSession] waits for the user JWT to become available before giving up.
 * Cold-start restore normally completes well under a second, and a post-reconnect token refresh
 * within a few; the bound only exists so a misbehaving auth layer cannot suspend a sync trigger
 * forever. A caller that awaits its own session-settle bound before triggering a sync composes with
 * this one serially, so the worst-case wait is the sum of the two.
 */
internal val AUTH_SESSION_SETTLE_TIMEOUT: Duration = 10.seconds

/**
 * Whether a replay fired under [status] goes out with a settled identity. Two statuses qualify:
 *
 * - [SessionStatus.Authenticated]: requests carry the user's JWT, and the queue replays that user's
 *   writes.
 * - [SessionStatus.NotAuthenticated]: requests carry the anon key, and the queue replays the writes
 *   made while signed out — the anonymous identity's own queue. An anonymous write is sent the way
 *   it was made.
 *
 * `Initializing` (cold-start restore in flight) and `RefreshFailure` (expired token, refresh not
 * yet landed — the usual state right after an offline window) are not settled: the stored user is
 * still the identity, but requests would go out on the anon key, turning that user's owner-scoped
 * writes into RLS refusals.
 *
 * Extracted as a pure function so the mapping is unit-testable without a real Supabase auth client.
 */
internal fun isReplaySafeSessionStatus(status: SessionStatus): Boolean =
    status is SessionStatus.Authenticated || status is SessionStatus.NotAuthenticated

/**
 * Suspends until this client's session is settled — [SessionStatus.Authenticated] or
 * [SessionStatus.NotAuthenticated], see [isReplaySafeSessionStatus] — bounded by [timeout].
 *
 * Callers that replay queued mutations gate on this: a replay fired while the session restores or
 * its refresh has not landed would send the signed-in user's writes on the anon key, where RLS
 * refuses them.
 *
 * @param timeout Upper bound on the wait; see [AUTH_SESSION_SETTLE_TIMEOUT].
 * @return `true` when the session is settled; `false` when the bound elapsed first — callers defer
 *   and keep the queue intact for a later trigger. An SDK without the Auth plugin installed has no
 *   session model at all, so the probe failure is treated as settled (fail-open).
 */
internal suspend fun SupabaseClient.awaitReplaySafeSession(
    timeout: Duration = AUTH_SESSION_SETTLE_TIMEOUT
): Boolean =
    try {
        withTimeoutOrNull(timeout) {
            auth.sessionStatus.first { status -> isReplaySafeSessionStatus(status) }
        } != null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        true
    }

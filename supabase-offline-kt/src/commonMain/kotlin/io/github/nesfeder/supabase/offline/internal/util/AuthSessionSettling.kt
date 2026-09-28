package io.github.nesfeder.supabase.offline.internal.util

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long [awaitReplaySafeSession] waits for the user JWT to become available before giving up.
 * Cold-start restore normally completes well under a second, and a post-reconnect token refresh
 * within a few; the bound only exists so a misbehaving auth layer cannot suspend a sync trigger
 * forever. A caller that awaits its own session-settle bound before triggering a sync composes
 * with this one serially, so the worst-case wait is the sum of the two.
 */
internal val AUTH_SESSION_SETTLE_TIMEOUT: Duration = 10.seconds

/**
 * Whether a replay fired under [status] would carry the user JWT. supabase-kt's token resolution
 * reads `currentSessionOrNull()`, which is non-null **only** for [SessionStatus.Authenticated] —
 * `Initializing` (cold-start restore in flight), `NotAuthenticated`, and `RefreshFailure` (expired
 * token, refresh not yet landed — the usual state right after an offline window, which is exactly
 * when the queue is non-empty) all send the anon key, turning every owner-scoped write into an
 * RLS 403 that the permanent-failure classification would then drop.
 *
 * Extracted as a pure function so the mapping is unit-testable without a real Supabase auth
 * client.
 */
internal fun isReplaySafeSessionStatus(status: SessionStatus): Boolean =
    status is SessionStatus.Authenticated

/**
 * Suspends until this client's session reaches [SessionStatus.Authenticated] — the only status
 * under which requests carry the user JWT (see [isReplaySafeSessionStatus]) — bounded by
 * [timeout].
 *
 * Callers that replay queued mutations must gate on this: a replay fired in any other status is
 * sent on the anon key, RLS rejects it with a 403, and the permanent-failure classification drops
 * the mutation — silently losing a write that would have succeeded once the restore or token
 * refresh landed.
 *
 * @param timeout Upper bound on the wait; see [AUTH_SESSION_SETTLE_TIMEOUT].
 * @return `true` when the session is authenticated and a replay would carry the user JWT;
 *   `false` when the bound elapsed first (still restoring, signed out, or an unrecovered refresh
 *   failure) — callers should defer and keep the queue intact for a later trigger. An SDK without
 *   the Auth plugin installed has no session model at all, so the probe failure is treated as
 *   safe (fail-open), preserving pre-gate behavior for auth-less clients.
 */
internal suspend fun SupabaseClient.awaitReplaySafeSession(
    timeout: Duration = AUTH_SESSION_SETTLE_TIMEOUT,
): Boolean = try {
    withTimeoutOrNull(timeout) {
        auth.sessionStatus.first { status -> isReplaySafeSessionStatus(status) }
    } != null
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (_: Throwable) {
    true
}

package io.github.nesfeder.supabase.offline.internal.util

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [isReplaySafeSessionStatus] — the status mapping behind the mutation-replay gate.
 *
 * The invariant: a replay may only proceed when the request would carry the user JWT, which
 * supabase-kt attaches **only** while `Authenticated` (its token resolution reads
 * `currentSessionOrNull()`, null for every other status). Every other status sends the anon
 * key, so an owner-scoped write becomes an RLS 403 and the permanent-failure classification
 * drops the queued mutation. `RefreshFailure` is the case that bites in practice: the queue is
 * non-empty precisely because the device was offline, which is also what leaves the token
 * expired and the refresh unlanded on the reconnect edge.
 */
class AuthSessionSettlingTest {

    @Test
    fun authenticated_is_replay_safe() {
        // Given — a live session; the only status whose requests carry the user JWT
        val status = SessionStatus.Authenticated(
            session = UserSession(
                accessToken = "header.payload.sig",
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
                user = null,
            ),
        )

        // When / Then
        assertTrue(isReplaySafeSessionStatus(status))
    }

    @Test
    fun initializing_is_not_replay_safe() {
        // Given — the cold-start window while the persisted session restores
        val status = SessionStatus.Initializing

        // When / Then — replaying here sends the anon key
        assertFalse(isReplaySafeSessionStatus(status))
    }

    @Test
    fun refresh_failure_is_not_replay_safe() {
        // Given — the post-offline reconnect state: token expired, refresh not yet landed
        @Suppress("DEPRECATION")
        val status = SessionStatus.RefreshFailure(
            RefreshFailureCause.NetworkError(Exception("connect failed: airplane mode")),
        )

        // When / Then — currentSessionOrNull() is null here too; deferring keeps the queue
        assertFalse(isReplaySafeSessionStatus(status))
    }

    @Test
    fun not_authenticated_is_not_replay_safe_either_way() {
        // Given — both terminal signed-out variants supabase-kt emits
        // When / Then — nothing owner-scoped can succeed without a session
        assertFalse(isReplaySafeSessionStatus(SessionStatus.NotAuthenticated(isSignOut = true)))
        assertFalse(isReplaySafeSessionStatus(SessionStatus.NotAuthenticated(isSignOut = false)))
    }
}

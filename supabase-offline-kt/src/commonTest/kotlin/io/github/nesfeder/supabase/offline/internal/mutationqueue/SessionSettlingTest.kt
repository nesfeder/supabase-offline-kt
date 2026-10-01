// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [isReplaySafeSessionStatus] — the status mapping behind the mutation-replay gate.
 *
 * The invariant: a replay may only proceed once the session has settled into an identity the
 * requests will really go out as — signed in (the user JWT, which supabase-kt attaches **only**
 * while `Authenticated`) or signed out (the anon key). The queue is kept per identity, so a settled
 * session replays only its own writes. `Initializing` and `RefreshFailure` send the anon key
 * whoever made the writes, so the gate waits them out. `RefreshFailure` is the case that bites in
 * practice: the queue is non-empty precisely because the device was offline, which is also what
 * leaves the token expired and the refresh unlanded on the reconnect edge.
 */
class SessionSettlingTest {

    @Test
    fun authenticated_is_replay_safe() {
        // Given — a live session; the only status whose requests carry the user JWT
        val status =
            SessionStatus.Authenticated(
                session =
                    UserSession(
                        accessToken = "header.payload.sig",
                        refreshToken = "refresh-token",
                        expiresIn = 3600,
                        tokenType = "bearer",
                        user = null,
                    )
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
        val status =
            SessionStatus.RefreshFailure(
                RefreshFailureCause.NetworkError(Exception("connect failed: airplane mode"))
            )

        // When / Then — currentSessionOrNull() is null here too; deferring keeps the queue
        assertFalse(isReplaySafeSessionStatus(status))
    }

    @Test
    fun not_authenticated_is_replay_safe_either_way() {
        // Given — both terminal signed-out variants supabase-kt emits
        // When / Then — the session is settled: the writes made signed out replay on the anon key,
        // and a signed-in user's writes are not the current identity's, so none of them is sent
        assertTrue(isReplaySafeSessionStatus(SessionStatus.NotAuthenticated(isSignOut = true)))
        assertTrue(isReplaySafeSessionStatus(SessionStatus.NotAuthenticated(isSignOut = false)))
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.mutationqueue.AccessTokenIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * For a client that tells users apart by its own access token, a waiting read is woken when the
 * token comes to name an identity the read trusts: any settled one under the settled-session rule,
 * a user under the authenticated-only rule; never by a token that names no user.
 */
class TokenIdentityWakeUpsTest {

    private val states =
        MutableStateFlow<AccessTokenIdentity.State>(AccessTokenIdentity.State.Unread)

    @Test
    fun under_the_settled_session_rule_a_user_or_nobody_signed_in_wakes_a_read() = runTest {
        // Given
        val woken = collectWakeUps(ResponseTrustPolicy.SETTLED_SESSION)

        // When — no user named, then a user, then a token naming nobody, then nobody signed in
        states.value = AccessTokenIdentity.State.Unnamed
        runCurrent()
        states.value = AccessTokenIdentity.State.Named(QueueIdentity.User("user-a"))
        runCurrent()
        states.value = AccessTokenIdentity.State.Unnamed
        runCurrent()
        states.value = AccessTokenIdentity.State.Named(QueueIdentity.Anonymous)
        runCurrent()

        // Then — woken by the user and by nobody signed in
        assertEquals(2, woken.size)
    }

    @Test
    fun under_the_authenticated_only_rule_only_a_user_wakes_a_read() = runTest {
        // Given
        val woken = collectWakeUps(ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY)

        // When — nobody signed in, then a user
        states.value = AccessTokenIdentity.State.Named(QueueIdentity.Anonymous)
        runCurrent()
        states.value = AccessTokenIdentity.State.Named(QueueIdentity.User("user-a"))
        runCurrent()

        // Then — woken by the user alone
        assertEquals(1, woken.size)
    }

    /** Starts collecting the wake-ups [policy] gives, and returns what they emit, as they do. */
    private fun TestScope.collectWakeUps(policy: ResponseTrustPolicy): List<Unit> {
        val woken = mutableListOf<Unit>()
        val wakeUps =
            refreshWakeUps(
                newTestSupabaseClient(),
                reconnections = emptyFlow(),
                trustPolicy = policy,
                tokenIdentityStates = states,
            )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            wakeUps.collect { woken += it }
        }
        return woken
    }
}

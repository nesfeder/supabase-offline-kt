// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * For a client that tells users apart by its own access token, the replay owner check reads the
 * token each queued write's request carries as the client does: the user its `sub` names, nobody
 * signed in for the project key, and no identity for a token that names no user. A request whose
 * token is not its write's owner's is stopped before it leaves the device.
 */
class ReplayOwnerCheckTokenTest {

    /** The token the app's provider returns now. */
    private var token: String? = testJwt("user-b")

    private val server = RecordingPostgrestServer(answer = { okJson("[]") })

    private val supabase: SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
                accessToken = { token }
                install(Postgrest) { maxRetries = 0 }
            }
            .also { client -> client.installReplayOwnerCheck() }

    @Test
    fun a_request_whose_token_names_another_user_is_stopped_before_it_leaves() = runTest {
        // Given — A's write, while the provider returns B's token
        val owner = ownerOf(QueueIdentity.User("user-a"))

        // When
        assertFails { withContext(owner) { supabase.from("todos").select() } }

        // Then
        assertTrue(owner.stoppedForAnotherSession)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_request_with_its_owners_token_goes() = runTest {
        // Given — A's write, while the provider returns A's token
        token = testJwt("user-a")
        val owner = ownerOf(QueueIdentity.User("user-a"))

        // When
        withContext(owner) { supabase.from("todos").select() }

        // Then
        assertFalse(owner.stoppedForAnotherSession)
        assertEquals("Bearer ${testJwt("user-a")}", server.requests.single().authorization)
    }

    @Test
    fun with_no_token_the_project_key_goes_for_nobody_signed_in() = runTest {
        // Given — a write made signed out, while the provider still returns none
        token = null
        val owner = ownerOf(QueueIdentity.Anonymous)

        // When
        withContext(owner) { supabase.from("todos").select() }

        // Then
        assertFalse(owner.stoppedForAnotherSession)
        assertEquals("Bearer $TEST_SUPABASE_KEY", server.requests.single().authorization)
    }

    @Test
    fun a_token_that_names_no_user_is_stopped_even_for_nobody_signed_in() = runTest {
        // Given — a write made signed out, while the provider returns a token that names nobody
        token = "opaque-token"
        val owner = ownerOf(QueueIdentity.Anonymous)

        // When
        assertFails { withContext(owner) { supabase.from("todos").select() } }

        // Then
        assertTrue(owner.stoppedForAnotherSession)
        assertTrue(server.requests.isEmpty())
    }

    private fun ownerOf(identity: QueueIdentity): ReplayOwner =
        ReplayOwner(
            identity,
            identityOfToken = { requestToken ->
                identityOfAccessToken(requestToken, TEST_SUPABASE_KEY)
            },
        )
}

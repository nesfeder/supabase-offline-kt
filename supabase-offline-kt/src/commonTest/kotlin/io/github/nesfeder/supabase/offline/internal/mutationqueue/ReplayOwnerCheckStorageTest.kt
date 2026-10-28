// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.testdoubles.FakeResumableCache
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer
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
 * The replay owner check reads a queued upload's Storage request as it reads a row write's
 * PostgREST one: a request whose token names another user than the upload's owner is stopped before
 * it leaves the device, so another identity's upload is never sent under the current session.
 */
class ReplayOwnerCheckStorageTest {

    /** The token the app's provider returns now. */
    private var token: String? = testJwt("user-b")

    private val server = UploadAndRowServer()

    private val supabase: SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
                accessToken = { token }
                install(Storage) { resumable { cache = FakeResumableCache() } }
            }
            .also { client -> client.installReplayOwnerCheck() }

    @Test
    fun an_upload_goes_only_with_its_owners_token() = runTest {
        // Given — A's upload
        val owner = ownerOf(QueueIdentity.User("user-a"))

        // When — the provider returns B's token
        assertFails {
            withContext(owner) { supabase.storage.from(BUCKET).upload(PATH, BYTES) }
        }

        // Then — stopped before it left the device
        assertTrue(owner.stoppedForAnotherSession)
        assertTrue(server.requests.isEmpty())

        // When — the provider returns A's token
        token = testJwt("user-a")
        val again = ownerOf(QueueIdentity.User("user-a"))
        withContext(again) { supabase.storage.from(BUCKET).upload(PATH, BYTES) }

        // Then
        assertFalse(again.stoppedForAnotherSession)
        assertEquals("Bearer ${testJwt("user-a")}", server.requests.single().authorization)
    }

    @Test
    fun a_storage_that_sends_its_own_token_is_not_checked_as_one_with_the_sessions_is() = runTest {
        // Given — one Storage with a token of its own, whoever is signed in, and this test's
        // Storage, which sends the session's
        val ownToken =
            newTestSupabaseClient(engine = server.engine) {
                    accessToken = { token }
                    install(Storage) {
                        jwtToken = testJwt("service")
                        resumable { cache = FakeResumableCache() }
                    }
                }
                .also { client -> client.installReplayOwnerCheck() }
        val owner = ownerOf(QueueIdentity.User("user-a"))

        // When — A's upload, while the provider returns B's token
        withContext(owner) { ownToken.storage.from(BUCKET).upload(PATH, BYTES) }
        val withSessionToken = ownerOf(QueueIdentity.User("user-a"))
        assertFails {
            withContext(withSessionToken) { supabase.storage.from(BUCKET).upload(PATH, BYTES) }
        }

        // Then — the first went with its own token, the second was stopped
        assertFalse(owner.stoppedForAnotherSession)
        assertTrue(withSessionToken.stoppedForAnotherSession)
        assertEquals(
            listOf("Bearer ${testJwt("service")}"),
            server.requests.map { it.authorization },
        )
    }

    private fun ownerOf(identity: QueueIdentity): ReplayOwner =
        ReplayOwner(
            identity,
            identityOfToken = { requestToken ->
                identityOfAccessToken(requestToken, TEST_SUPABASE_KEY)
            },
        )

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "user-a/photo.jpg"
        val BYTES = "bytes".encodeToByteArray()
    }
}

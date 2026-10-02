// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The mutation queue and the rejected list are shown per user: each user sees their own queued
 * writes and refusals, and can neither see, discard, dismiss nor retry another's, even knowing its
 * id. Every client here keeps its background work idle, so only the calls the test makes run.
 */
class QueueVisibilityAcrossUsersTest {

    private val server = RecordingPostgrestServer(answer = { connectivityFailure() })

    private suspend fun signedIn(subject: String): Pair<SupabaseClient, OfflineClient> {
        val supabase = newTestSupabaseClientWithAuth(engine = server.engine)
        supabase.signInForTest(subject)
        val client =
            newTestOfflineClient(
                supabase = supabase,
                coordinatorScope = idleCoroutineScope(),
                closeWait = 100.milliseconds,
            )
        return supabase to client
    }

    /** User A's write, queued offline, then refused for good by the server on replay. */
    private suspend fun rejectedWriteOfUserA(client: OfflineClient, body: JsonArray = ROW): Long {
        server.answer = { connectivityFailure() }
        client.from(TABLE).insert(body)
        server.answer = { postgrestError(HttpStatusCode.Forbidden) }
        return client.syncPendingMutations().rejected.single().id
    }

    @Test
    fun a_users_rejection_is_invisible_to_another_user() = runTest {
        // Given — A's write was refused
        val (supabase, client) = signedIn(USER_A)
        val id = rejectedWriteOfUserA(client)
        val shownToA = client.rejectedMutations().first().map { it.id }

        // When — B signs in on the same client
        supabase.signInForTest(USER_B)

        // Then — B sees no rejection, and counts none
        assertEquals(listOf(id), shownToA)
        assertEquals(emptyList(), client.rejectedMutations().first())
        client.close()
    }

    @Test
    fun another_user_cannot_retry_or_dismiss_a_rejection_by_guessing_its_id() = runTest {
        // Given — A's write was refused, and B is signed in
        val (supabase, client) = signedIn(USER_A)
        val id = rejectedWriteOfUserA(client)
        supabase.signInForTest(USER_B)
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size

        // When — B tries A's id
        client.retryRejectedMutation(id)
        client.dismissRejectedMutation(id)
        val sentByB = client.syncPendingMutations()

        // Then — nothing was queued or sent for B, and A still has the rejection
        assertEquals(0, sentByB.syncedCount)
        assertEquals(0, client.pendingMutationCount())
        assertEquals(requestsBefore, server.requests.size)
        supabase.signInForTest(USER_A)
        assertEquals(listOf(id), client.rejectedMutations().first().map { it.id })
        client.close()
    }

    @Test
    fun the_user_who_made_a_refused_write_can_retry_it_and_it_is_sent_again() = runTest {
        // Given
        val (_, client) = signedIn(USER_A)
        val id = rejectedWriteOfUserA(client)

        // When — A fixed the cause and retries
        client.retryRejectedMutation(id)
        server.answer = { okJson("[]") }
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1, report.syncedCount)
        assertEquals(emptyList(), client.rejectedMutations().first())
        client.close()
    }

    @Test
    fun a_users_queued_writes_are_invisible_to_another_user_who_cannot_discard_them() = runTest {
        // Given — A's write waits in the queue, and B signs in
        val (supabase, client) = signedIn(USER_A)
        client.from(TABLE).insert(ROW)
        val id = client.pendingMutations().first().single().id
        supabase.signInForTest(USER_B)

        // When — B looks, and tries to discard A's write by its id
        val shownToB = client.pendingMutations().first()
        val countedForB = client.pendingMutationCount() to client.pendingMutationCountFlow().first()
        client.discardPendingMutation(id)

        // Then — B saw nothing, and A's write is still queued
        assertEquals(emptyList(), shownToB)
        assertEquals(0 to 0, countedForB)
        supabase.signInForTest(USER_A)
        assertEquals(listOf(id), client.pendingMutations().first().map { it.id })
        client.close()
    }

    @Test
    fun a_rejection_whose_body_was_too_large_to_keep_says_so_and_cannot_be_retried() = runTest {
        // Given — a refused write whose body is past what the rejected list keeps
        val (_, client) = signedIn(USER_A)
        val large = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("title", "x".repeat(20 * 1024))
                }
            )
        }
        val id = rejectedWriteOfUserA(client, large)

        // When
        val shown = client.rejectedMutations().first().single()
        val retry = assertFailsWith<IllegalArgumentException> { client.retryRejectedMutation(id) }

        // Then — the type said so before the retry, which changed nothing
        assertFalse(shown.isRetryable)
        assertNull(shown.body)
        assertEquals(true, retry.message?.contains("isRetryable"))
        assertEquals(listOf(id), client.rejectedMutations().first().map { it.id })
        client.close()
    }

    private companion object {
        const val TABLE = "todos"
        const val USER_A = "user-a"
        const val USER_B = "user-b"
        val ROW: JsonArray = buildJsonArray {
            add(
                buildJsonObject {
                    put("id", 1)
                    put("title", "A's note")
                }
            )
        }
    }
}

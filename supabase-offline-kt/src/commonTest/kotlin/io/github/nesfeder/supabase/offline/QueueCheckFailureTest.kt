// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * After a write is answered, or a discard is done, the client reads the queue once to ask whether
 * anything waits behind it. That read only decides whether to ask for a pass: when it fails, the
 * caller is still told what happened to its write, and a pass is asked for anyway.
 */
class QueueCheckFailureTest {

    private val server = RecordingPostgrestServer()
    private val mutationDao = FakeMutationDao()

    /** A client over [mutationDao] that starts no pass of its own, so only the writes read it. */
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            mutationDao = mutationDao,
            config = Offline.Config().apply { autoSync = false },
        )

    @Test
    fun a_write_the_server_applied_is_applied_when_the_queue_read_after_it_fails() = runTest {
        // Given — the server accepts the write, and the read that follows it will fail
        server.answer = { okJson("""[{"id":1,"title":"t1"}]""") }
        mutationDao.failNextWholeQueueCheck = true

        // When
        val outcome = runCatching { client.from(TABLE).insert(row(1)) }

        // Then — the server has it and it left the queue, so the caller is told it was applied
        assertEquals(1, server.requests.count { it.method == "POST" })
        assertEquals(0, client.pendingMutationCount())
        assertTrue(outcome.isSuccess, "the caller got ${outcome.exceptionOrNull()}")
        assertIs<MutationOutcome.Applied>(outcome.getOrNull())
        client.close()
    }

    @Test
    fun a_write_the_server_refused_throws_the_refusal_when_the_queue_read_after_it_fails() =
        runTest {
            // Given — the server refuses the write, and the read that follows it will fail
            server.answer = { postgrestError(HttpStatusCode.BadRequest, code = "23514") }
            mutationDao.failNextWholeQueueCheck = true

            // When
            val refusal = assertFailsWith<Exception> { client.from(TABLE).insert(row(1)) }

            // Then — the server's refusal, not the failed read
            assertIs<PostgrestRestException>(refusal, "the caller got $refusal")
            assertEquals(0, client.pendingMutationCount())
            client.close()
        }

    @Test
    fun a_discard_that_happened_returns_when_the_queue_read_after_it_fails() = runTest {
        // Given — a write queued offline, and the read that follows its discard will fail
        server.answer = { connectivityFailure() }
        val queued = assertIs<MutationOutcome.Queued>(client.from(TABLE).insert(row(1)))
        mutationDao.failNextWholeQueueCheck = true

        // When
        val discard = runCatching { client.discardPendingMutation(queued.pendingMutation.id) }

        // Then
        assertTrue(discard.isSuccess, "the discard threw ${discard.exceptionOrNull()}")
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    private fun row(id: Int): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("id", id)
                    put("title", "t$id")
                }
            )
        )

    private companion object {
        const val TABLE = "tasks"
    }
}

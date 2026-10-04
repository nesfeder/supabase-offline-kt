// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.TOKEN_ENDPOINT
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.refreshRefused
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Offline for longer than a session token lives, supabase-kt refreshes the token before building a
 * request, cannot, and throws before anything is sent. A write that may queue still queues, as the
 * signed-in user's: telling a dry run needs only the request's shape, not the session's token. A
 * dry run in the same state is still refused. Online, a refresh the Auth server refuses throws the
 * same way, so the write is known never to have been sent.
 */
class ExpiredSessionWriteTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestStore(mutationDao = mutationDao)

    @Test
    fun a_write_with_an_expired_session_offline_is_queued_as_its_users() = runTest {
        // Given — user A's session expired, and no network to refresh it
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            newTestOfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val inserted = builder.insert(rows("""[{"id":"k1","title":"Buy milk"}]"""))
        val updated = builder.update(patch()) { filter { eq("id", "k1") } }

        // Then — both queued, as A's, and no request went out with a token it could not refresh
        assertIs<MutationOutcome.Queued>(inserted)
        assertIs<MutationOutcome.Queued>(updated)
        assertEquals(listOf("user:user-a", "user:user-a"), mutationDao.inserted.map { it.owner })
        assertTrue(server.requests.none { it.table == TABLE }, "${server.requests}")
    }

    @Test
    fun a_dry_run_with_an_expired_session_offline_is_still_refused() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            newTestOfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val outcome = runCatching {
            builder.update(patch()) {
                filter { eq("id", "k1") }
                dryRun()
            }
        }

        // Then — the caller gets the failure, and nothing is queued
        assertTrue(outcome.isFailure)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun online_a_write_whose_refresh_is_refused_is_queued_as_never_sent() = runTest {
        // Given — user A's session expired, and the Auth server refuses its refresh token
        val server =
            RecordingPostgrestServer(answer = { request -> answerRefusingRefresh(request) })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            newTestOfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val inserted = builder.insert(rows("""[{"id":"k1","title":"Buy milk"}]"""))

        // Then — queued, unsent, and not recorded as possibly on the server
        assertIs<MutationOutcome.Queued>(inserted)
        assertTrue(server.requests.none { it.table == TABLE }, "${server.requests}")
        val queued = store.mutationQueue.allPendingMutations().single()
        assertFalse(store.mutationQueue.hasUnconfirmedSend(queued.id))

        // And — with A's session good again it replays as the plain INSERT it is
        assertEquals("return=minimal", replayedPreferOnceSignedIn(server, supabase))
    }

    @Test
    fun online_a_replay_whose_refresh_is_refused_is_kept_as_never_sent() = runTest {
        // Given — A's INSERT queued and never sent; A's session expired, its refresh refused
        store.mutationQueue.enqueueOwnedBy("user:user-a") {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val queued = store.mutationQueue.allPendingMutations().single()
        val server =
            RecordingPostgrestServer(answer = { request -> answerRefusingRefresh(request) })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)

        // When
        val report = newTestMutationQueueReplayer(store = store, supabase = supabase).sync()

        // Then — the pass stops before sending, and the write is not recorded as possibly sent
        assertIs<SyncStop.TransientFailure>(report.stop)
        assertTrue(server.requests.none { it.table == TABLE }, "${server.requests}")
        assertFalse(store.mutationQueue.hasUnconfirmedSend(queued.id))

        // And
        assertEquals("return=minimal", replayedPreferOnceSignedIn(server, supabase))
    }

    /** Signs user A in afresh, replays the queue, and returns the `Prefer` of the write sent. */
    private suspend fun replayedPreferOnceSignedIn(
        server: RecordingPostgrestServer,
        supabase: SupabaseClient,
    ): String? {
        server.answer = { okJson("[]") }
        supabase.signInForTest("user-a")
        newTestMutationQueueReplayer(store = store, supabase = supabase).sync()
        return server.requests.single { it.table == TABLE }.prefer
    }

    /** The Auth server refusing the refresh token; PostgREST accepting every write. */
    private fun MockRequestHandleScope.answerRefusingRefresh(
        request: RecordingPostgrestServer.RecordedRequest
    ): HttpResponseData = if (request.table == TOKEN_ENDPOINT) refreshRefused() else okJson("[]")

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"new"}""")

    private companion object {
        const val TABLE = "todos"
    }
}

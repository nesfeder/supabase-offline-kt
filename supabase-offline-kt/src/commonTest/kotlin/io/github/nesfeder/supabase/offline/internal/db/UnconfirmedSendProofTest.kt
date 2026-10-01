// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A keyed INSERT is taken as never sent — and replayed as a plain INSERT, so a duplicate id is
 * refused — only when that is proven: every attempt at it failed before connecting, or PostgREST
 * itself said the write did not run. Anything else — a retried attempt that got through, an HTTP
 * response from anything, a missing or unreadable record — leaves it possibly delivered, and it
 * replays as an upsert that takes rows already on the server as its own.
 */
class UnconfirmedSendProofTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    @Test
    fun a_retried_send_whose_first_attempt_committed_is_not_taken_for_never_sent() = runTest {
        // Given — an app retry plugin; the first attempt commits and loses its response, the retry
        // cannot connect
        val server = FakeRelationalPostgrestServer()
        val answer = server.recorder.answer
        var attempts = 0
        server.recorder.answer = { request ->
            if (request.method == "POST") attempts++
            when {
                request.method == "POST" && attempts == 1 -> {
                    server.loseResponseAfterCommit = { lost -> lost.method == "POST" }
                    answer(request)
                }
                request.method == "POST" && attempts == 2 ->
                    throw ConnectTimeoutException("connect timed out", null)
                else -> answer(request)
            }
        }
        val outcome =
            builderOn(clientRetryingOn(server.recorder))
                .insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))
        server.recorder.answer = answer

        // When
        val report = MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the replay took the committed row as its own rather than refusing it as a
        // duplicate
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(2, attempts)
        assertEquals(emptyList(), report.rejected)
        assertEquals(1, report.syncedCount)
        assertEquals(1, server.rows(TABLE).size)
    }

    @Test
    fun a_response_to_an_earlier_attempt_proves_the_request_left_the_device() = runTest {
        // Given — a gateway answers the first attempt 502; the retry cannot connect
        var attempts = 0
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    if (request.method == "POST") attempts++
                    if (attempts == 1) {
                        respond(
                            content = "bad gateway",
                            status = HttpStatusCode.BadGateway,
                            headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                        )
                    } else {
                        throw ConnectTimeoutException("connect timed out", null)
                    }
                }
            )

        // When
        val outcome =
            builderOn(clientRetryingOn(server))
                .insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))

        // Then — something answered, so the send stays possibly delivered
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(2, attempts)
        assertTrue(store.hasUnconfirmedSend(store.allPendingMutations().single().id))
    }

    @Test
    fun a_connect_failure_carrying_an_earlier_delivered_attempt_is_not_taken_for_never_sent() =
        runTest {
            // Given — the insert commits, then the engine reports a failed reconnect that carries
            // the
            // delivered attempt as a suppressed exception, as OkHttp's own retry does
            val server = FakeRelationalPostgrestServer()
            val answer = server.recorder.answer
            var first = true
            server.recorder.answer = { request ->
                val response = answer(request)
                if (request.method == "POST" && first) {
                    first = false
                    throw ConnectTimeoutException("connect timed out", null).apply {
                        addSuppressed(IOException("unexpected end of stream"))
                    }
                }
                response
            }
            val outcome =
                builderOn(server.supabaseClient())
                    .insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))

            // When
            val report =
                MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

            // Then
            assertIs<MutationOutcome.Queued>(outcome)
            assertEquals(emptyList(), report.rejected)
            assertEquals(1, report.syncedCount)
            assertEquals(1, server.rows(TABLE).size)
        }

    @Test
    fun a_proxy_503_after_the_insert_committed_keeps_the_send_unconfirmed() = runTest {
        // Given — PostgREST commits the insert; a proxy in front of it answers 503
        val server = FakeRelationalPostgrestServer()
        val answer = server.recorder.answer
        var first = true
        server.recorder.answer = { request ->
            val response = answer(request)
            if (request.method == "POST" && first) {
                first = false
                respond(
                    content = "upstream connect error or disconnect/reset before headers",
                    status = HttpStatusCode.ServiceUnavailable,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            } else {
                response
            }
        }
        val outcome =
            builderOn(server.supabaseClient())
                .insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))

        // When
        val report = MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(emptyList(), report.rejected)
        assertEquals(1, report.syncedCount)
        assertEquals(1, server.rows(TABLE).size)
    }

    @Test
    fun postgrest_own_503_at_the_head_proves_the_write_did_not_run() = runTest {
        // Given — PostgREST could not reach the database, and said so with its own code
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST002") }
            )
        builderOn(server.supabaseClient()).insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))
        server.answer = { okJson("[]") }

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the replay is the plain INSERT, so a duplicate id would be refused
        assertEquals("return=minimal", server.requests.last().prefer)
    }

    @Test
    fun a_postgrest_client_error_503_at_the_head_leaves_the_send_unconfirmed() = runTest {
        // Given — PGRST001: the database connection broke during the request, which may have run
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST001") }
            )
        builderOn(server.supabaseClient()).insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))
        server.answer = { okJson("[]") }

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then
        assertTrue(
            "resolution=ignore-duplicates" in server.requests.last().prefer.orEmpty().split(',')
        )
    }

    @Test
    fun a_replay_that_never_connected_clears_its_record() = runTest {
        // Given — a queued keyed INSERT whose first replay cannot connect
        store.enqueueOwnedBy(null) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val server =
            RecordingPostgrestServer(
                answer = { throw ConnectTimeoutException("connect timed out", null) }
            )
        val replayer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())
        replayer.sync()
        server.answer = { okJson("[]") }

        // When
        replayer.sync()

        // Then — it never reached the server, so it replays as the plain INSERT again
        assertEquals(listOf("return=minimal", "return=minimal"), server.requests.map { it.prefer })
    }

    @Test
    fun a_replay_answered_by_postgrest_own_503_clears_its_record() = runTest {
        // Given
        store.enqueueOwnedBy(null) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val server =
            RecordingPostgrestServer(
                answer = { postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST000") }
            )
        val replayer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())
        replayer.sync()
        server.answer = { okJson("[]") }

        // When
        replayer.sync()

        // Then
        assertEquals(listOf("return=minimal", "return=minimal"), server.requests.map { it.prefer })
    }

    @Test
    fun a_send_whose_response_was_lost_is_still_possibly_delivered_after_a_relaunch() = runTest {
        // Given — an insert whose response was lost: its send is recorded in its row
        val server = FakeRelationalPostgrestServer()
        server.loseResponseAfterCommit = { request -> request.method == "POST" }
        builderOn(server.supabaseClient()).insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))

        // When — the next launch replays it
        val report =
            MutationQueueReplayer(store = relaunch(), supabase = server.supabaseClient()).sync()

        // Then — it takes the row the lost send committed as its own
        assertEquals(emptyList(), report.rejected)
        assertEquals(1, report.syncedCount)
        assertEquals(1, server.rows(TABLE).size)
    }

    @Test
    fun an_insert_this_build_queued_and_never_sent_is_still_refused_as_a_duplicate_after_a_relaunch() =
        runTest {
            // Given — another row already has the id of an insert that was queued and never sent
            store.enqueueOwnedBy(null) {
                enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
            }
            val server = FakeRelationalPostgrestServer()
            server.seed(TABLE, row("""{"id":"k1","title":"someone else's"}"""))

            // When — the next launch replays it
            val report =
                MutationQueueReplayer(store = relaunch(), supabase = server.supabaseClient()).sync()

            // Then — nothing on the server can be its own, so the duplicate is refused
            assertEquals("23505", report.rejected.single().serverError?.code)
        }

    @Test
    fun a_retried_rejection_is_refused_again_as_a_duplicate_after_a_relaunch() = runTest {
        // Given — an insert refused as a duplicate, then a new launch
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"k1","title":"someone else's"}"""))
        store.enqueueOwnedBy(null) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val refused =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient())
                .sync()
                .rejected
                .single()
        val next = relaunch()

        // When — the app retries it first thing
        next.retryRejectedMutation(refused.id, identity = null)
        val report = MutationQueueReplayer(store = next, supabase = server.supabaseClient()).sync()

        // Then — the retry was never sent, so it is refused again rather than taken as delivered
        assertEquals("23505", report.rejected.single().serverError?.code)
    }

    private fun relaunch(): OfflineStore =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    private fun builderOn(supabase: SupabaseClient): OfflineTable =
        OfflineTable(delegate = supabase.postgrest.from(TABLE), store = store)

    /** A client on [server] whose app retries a failed request once, on an exception or a 5xx. */
    private fun clientRetryingOn(server: RecordingPostgrestServer): SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            install(Postgrest) { maxRetries = 0 }
            httpConfig {
                install(HttpRequestRetry) {
                    retryOnExceptionOrServerErrors(maxRetries = 1)
                    constantDelay(millis = 1)
                }
            }
        }

    private fun row(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

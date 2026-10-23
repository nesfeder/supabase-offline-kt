// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.mutationqueue.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.gatewayError
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A `QueueIfOffline` write at the head of its identity's queue is sent at once, and what the server
 * says decides where it goes: accepted, it leaves the queue; refused, or answered with a `500` from
 * the database, it leaves the queue and the caller gets the error, as an online write always has;
 * unreachable, or answered with what a replay keeps queued too (a `503`, a `429`, a `401` for a
 * token the server holds expired), it stays queued for the next pass. It is sent only by the call
 * that made it, and only under the identity that made it.
 */
class HeadSendTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestStore(queryCacheDao = queryCacheDao)
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity = QueueIdentity.Anonymous
    private var syncRequests = 0
    private val replayer =
        newTestMutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            currentIdentity = { session },
        )
    private val builder =
        newTestOfflineTable(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { session },
            requestSync = { syncRequests++ },
            headSender = replayer,
        )

    private suspend fun rename(title: String): MutationOutcome =
        builder.update(patch(title)) { filter { eq("id", 1) } }

    @Test
    fun a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue() = runTest {
        // Given
        server.answer = { postgrestError(HttpStatusCode.Forbidden) }

        // When / Then — the caller gets the refusal; nothing is queued or put on the rejected list
        assertFailsWith<PostgrestRestException> { rename("A") }
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
        assertEquals(emptyList(), store.mutationQueue.rejectedList.rejections())
    }

    @Test
    fun a_server_error_at_the_head_is_thrown_and_the_write_leaves_the_queue() = runTest {
        // Given
        server.answer = { postgrestError(HttpStatusCode.InternalServerError, code = "54000") }

        // When / Then
        assertFailsWith<PostgrestRestException> { rename("A") }
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun an_unreachable_server_leaves_the_write_at_the_head_of_the_queue() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        val outcome = rename("A")

        // Then — queued, and nothing asked for a pass while offline
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
        assertEquals(0, syncRequests)
    }

    @Test
    fun a_pass_does_not_send_the_head_while_the_write_that_made_it_is_sending_it() = runTest {
        // Given — the write is on the wire, held open by the server
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val write = async { rename("A") }
        parked.await()

        // When — a pass runs meanwhile
        val report = replayer.sync()
        release.complete(Unit)

        // Then — the pass stopped at it; the write went out once and was applied
        assertEquals(SyncStop.HeadInFlight, report.stop)
        assertEquals(MutationOutcome.Applied::class, write.await()::class)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun writes_queued_behind_a_head_in_flight_get_a_pass_once_it_lands() = runTest {
        // Given — the first write is on the wire
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val first = async { rename("A") }
        parked.await()

        // When — a second write queues behind it, then the first lands
        val second = rename("B")
        release.complete(Unit)
        first.await()

        // Then — one pass asked for by the queued write, one by the head once it landed
        assertIs<MutationOutcome.Queued>(second)
        assertEquals(2, syncRequests)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_write_is_not_sent_at_the_head_once_the_session_is_another_identity() = runTest {
        // Given — the write is made as user A, and the session is already user B when it would go
        // out
        val asUserA =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
                queueIdentity = { USER_A },
                requestSync = { syncRequests++ },
                headSender = replayer,
            )
        session = USER_B

        // When
        val outcome = asUserA.update(patch("A")) { filter { eq("id", 1) } }

        // Then — it waits for A, unsent
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(0, server.requests.size)
        session = USER_A
        assertEquals(1, replayer.sync().syncedCount)
    }

    @Test
    fun an_online_only_write_does_not_wait_behind_the_queue() = runTest {
        // Given — a write is queued
        server.answer = { connectivityFailure() }
        rename("A")
        server.answer = { okJson("[]") }

        // When
        val outcome =
            builder.update(patch("B"), MutationStrategy.OnlineOnly) { filter { eq("id", 1) } }

        // Then — it went straight out, ahead of the queued write
        assertEquals(MutationOutcome.Applied::class, outcome::class)
        assertEquals("""{"title":"B"}""", server.requests.last().body)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_write_the_queue_cannot_record_exactly_is_sent_directly_even_behind_pending_writes() =
        runTest {
            // Given — a queued write
            server.answer = { connectivityFailure() }
            rename("A")
            server.answer = { okJson("[]") }

            // When — an update whose filter the queue cannot record exactly
            val outcome =
                builder.update(patch("B")) {
                    filter {
                        or {
                            eq("id", 1)
                            eq("id", 2)
                        }
                    }
                }

            // Then — it goes out at once, ahead of the queued write, and is never queued
            assertEquals(MutationOutcome.Applied::class, outcome::class)
            assertEquals("""{"title":"B"}""", server.requests.last().body)
            assertEquals(1, store.mutationQueue.allPendingMutations().size)
        }

    @Test
    fun a_write_whose_caller_is_canceled_on_the_wire_stays_queued_and_asks_for_a_pass() = runTest {
        // Given — the write is on the wire
        val parked = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            awaitCancellation()
        }
        val write = async { rename("A") }
        parked.await()

        // When — its caller goes away
        write.cancelAndJoin()

        // Then — it may have reached the server, so it stays queued to replay
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
        assertEquals(1, syncRequests)
    }

    @Test
    fun an_accepted_write_leaves_the_queue_even_if_patching_the_cache_fails() = runTest {
        // Given — a cached row whose rewrite fails
        queryCacheDao.seedPayload(TABLE, "tasks?id=eq.1", """[{"id":1,"title":"original"}]""")
        queryCacheDao.failUpsertOf = { row -> row.table == TABLE }

        // When
        val outcome = runCatching { rename("A") }

        // Then — the server applied it, so the caller is told so, and nothing will send it again
        assertEquals(MutationOutcome.Applied::class, outcome.getOrThrow()::class)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_write_queued_behind_a_head_that_is_refused_gets_a_pass() = runTest {
        // Given — the first write is on the wire, and the second queues behind it
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = { request ->
            if ("\"A\"" in request.body) {
                parked.complete(Unit)
                release.await()
                postgrestError(HttpStatusCode.InternalServerError, code = "54000")
            } else {
                okJson("[]")
            }
        }
        val first = async { runCatching { rename("A") } }
        parked.await()
        rename("B")
        val requestsWhileTheHeadWasOut = syncRequests

        // When — the head is refused
        release.complete(Unit)
        first.await()

        // Then — the refusal asked for another pass, so the write behind it is not stranded
        assertEquals(1, requestsWhileTheHeadWasOut)
        assertEquals(2, syncRequests)
    }

    @Test
    fun a_503_at_the_head_leaves_the_write_queued() = runTest {
        // Given — PostgREST's database connection broke, so the write may not have run
        server.answer = { postgrestError(HttpStatusCode.ServiceUnavailable, code = "PGRST001") }

        // When
        val outcome = rename("A")

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_520_at_the_head_leaves_the_write_queued() = runTest {
        // Given
        server.answer = { postgrestError(HttpStatusCode(520, "Unknown Error"), code = "PGRST000") }

        // When
        val outcome = rename("A")

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_408_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — the server stopped waiting for the request
        server.answer = { gatewayError(HttpStatusCode.RequestTimeout) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_429_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — the gateway turned the request away before it reached PostgREST
        server.answer = { gatewayError(HttpStatusCode.TooManyRequests) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_502_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given
        server.answer = { gatewayError(HttpStatusCode.BadGateway) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_504_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — the gateway gave up on PostgREST, which may have committed the write meanwhile
        server.answer = { gatewayError(HttpStatusCode.GatewayTimeout) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_522_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — Cloudflare could not connect to the origin
        server.answer = { gatewayError(HttpStatusCode(522, "Connection Timed Out")) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_524_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — Cloudflare connected, then stopped waiting for the origin's answer
        server.answer = { gatewayError(HttpStatusCode(524, "A Timeout Occurred")) }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_401_with_pgrst301_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — PostgREST refused the token as invalid, as older versions say of an expired one
        server.answer = {
            postgrestError(HttpStatusCode.Unauthorized, code = "PGRST301", message = "JWT expired")
        }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_401_with_pgrst303_at_the_head_leaves_the_write_queued_for_the_next_pass() = runTest {
        // Given — the device's clock runs behind the server's, so supabase-kt did not refresh
        // a token the server holds expired
        server.answer = {
            postgrestError(HttpStatusCode.Unauthorized, code = "PGRST303", message = "JWT expired")
        }

        // When / Then
        assertQueuedThenReplayed()
    }

    @Test
    fun a_401_for_another_reason_at_the_head_is_thrown_and_the_write_leaves_the_queue() = runTest {
        // Given — a 401 that does not say the token expired
        server.answer = { postgrestError(HttpStatusCode.Unauthorized, code = "PGRST302") }

        // When / Then
        assertFailsWith<PostgrestRestException> { rename("A") }
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
    }

    @Test
    fun a_write_a_pass_is_sending_is_not_sent_again_at_the_head() = runTest {
        // Given — a queued write that a pass is sending, held open by the server
        session = USER_A
        val id =
            (store.mutationQueue.enqueueOwnedBy(USER_A.storedValue) {
                    enqueueUpdate(TABLE, patch("A"), byId("1"))
                } as EnqueueOutcome.Queued)
                .id
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            release.await()
            okJson("[]")
        }
        val pass = async { replayer.sync() }
        parked.await()

        // When — its own caller offers it at the head meanwhile
        val outcome =
            replayer.sendAtHead(id, USER_A, send = { error("sent twice") }, onAccepted = {})
        release.complete(Unit)
        pass.await()

        // Then — it went out once
        assertEquals(MutationQueueReplayer.HeadSendOutcome.NotAtHead, outcome)
        assertEquals(1, server.requests.size)
    }

    /**
     * Makes a write the server answers as [server] is set to: it must come back `Queued`, still in
     * the queue, and the next pass must send it again, as it was made, and empty the queue.
     */
    private suspend fun assertQueuedThenReplayed() {
        val outcome = rename("A")
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, store.mutationQueue.allPendingMutations().size)

        server.answer = { okJson("[]") }
        val report = replayer.sync()

        assertEquals(1, report.syncedCount)
        assertEquals(0, store.mutationQueue.allPendingMutations().size)
        assertEquals(2, server.requests.size)
        val (atTheHead, replayed) = server.requests
        assertEquals(atTheHead.method, replayed.method)
        assertEquals(atTheHead.query, replayed.query)
        assertEquals(atTheHead.body, replayed.body)
    }

    private fun patch(title: String): JsonElement =
        Json.parseToJsonElement("""{"title":"$title"}""")

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id))
        )

    private companion object {
        const val TABLE = "tasks"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}

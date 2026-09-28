package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A `QueueIfOffline` write at the head of its identity's queue is sent at once, and what the server
 * says decides where it goes: accepted, it leaves the queue; refused or answered with a `5xx`, it
 * leaves the queue and the caller gets the error, as an online write always has; unreachable, it
 * stays queued. It is sent only by the call that made it, and only under the identity that made it.
 */
class HeadSendTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity = QueueIdentity.Anonymous
    private var syncRequests = 0
    private val syncer = OfflineMutationSyncer(store = store, supabase = server.supabaseClient(), currentIdentity = { session })
    private val builder = OfflinePostgrestQueryBuilderImpl(
        delegate = server.supabaseClient().postgrest.from(TABLE),
        store = store,
        queueIdentity = { session },
        requestSync = { syncRequests++ },
        headSender = syncer,
    )

    private suspend fun rename(title: String): MutationOutcome =
        builder.updateJson(patch(title)) { filter { eq("id", 1) } }

    @Test
    fun a_refusal_at_the_head_is_thrown_and_the_write_leaves_the_queue() = runTest {
        // Given
        server.answer = { postgrestError(HttpStatusCode.Forbidden) }

        // When / Then — the caller gets the refusal; nothing is queued or put on the rejected list
        assertFailsWith<PostgrestRestException> { rename("A") }
        assertEquals(0, store.allPendingMutations().size)
        assertEquals(emptyList(), store.outboxState.rejections())
    }

    @Test
    fun a_server_error_at_the_head_is_thrown_and_the_write_leaves_the_queue() = runTest {
        // Given
        server.answer = { postgrestError(HttpStatusCode.InternalServerError, code = "54000") }

        // When / Then
        assertFailsWith<PostgrestRestException> { rename("A") }
        assertEquals(0, store.allPendingMutations().size)
    }

    @Test
    fun an_unreachable_server_leaves_the_write_at_the_head_of_the_queue() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        val outcome = rename("A")

        // Then — queued, and nothing asked for a pass while offline
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, store.allPendingMutations().size)
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
        val report = syncer.sync()
        release.complete(Unit)

        // Then — the pass stopped at it; the write went out once and was applied
        assertEquals(SyncStop.HeadInFlight, report.stop)
        assertEquals(MutationOutcome.Applied::class, write.await()::class)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.allPendingMutations().size)
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
        assertEquals(MutationOutcome.Queued, second)
        assertEquals(2, syncRequests)
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun a_write_is_not_sent_at_the_head_once_the_session_is_another_identity() = runTest {
        // Given — the write is made as user A, and the session is already user B when it would go out
        val asUserA = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { USER_A },
            requestSync = { syncRequests++ },
            headSender = syncer,
        )
        session = USER_B

        // When
        val outcome = asUserA.updateJson(patch("A")) { filter { eq("id", 1) } }

        // Then — it waits for A, unsent
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(0, server.requests.size)
        session = USER_A
        assertEquals(1, syncer.sync().syncedCount)
    }

    @Test
    fun an_online_only_write_does_not_wait_behind_the_queue() = runTest {
        // Given — a write is queued
        server.answer = { connectivityFailure() }
        rename("A")
        server.answer = { okJson("[]") }

        // When
        val outcome = builder.updateJson(patch("B"), MutationStrategy.OnlineOnly) { filter { eq("id", 1) } }

        // Then — it went straight out, ahead of the queued write
        assertEquals(MutationOutcome.Applied::class, outcome::class)
        assertEquals("""{"title":"B"}""", server.requests.last().body)
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun a_write_the_queue_cannot_record_exactly_is_sent_directly_even_behind_pending_writes() = runTest {
        // Given — a queued write
        server.answer = { connectivityFailure() }
        rename("A")
        server.answer = { okJson("[]") }

        // When — an update whose filter the queue cannot record exactly
        val outcome = builder.updateJson(patch("B")) {
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
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun a_write_whose_caller_is_cancelled_on_the_wire_stays_queued_and_asks_for_a_pass() = runTest {
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
        assertEquals(1, store.allPendingMutations().size)
        assertEquals(1, syncRequests)
    }

    @Test
    fun an_accepted_write_leaves_the_queue_even_if_patching_the_cache_fails() = runTest {
        // Given — a cached row whose rewrite fails
        objectDao.seedPayload(TABLE, "tasks?id=eq.1", """[{"id":1,"title":"original"}]""")
        objectDao.failUpsertOf = { row -> row.table == TABLE }

        // When
        val outcome = runCatching { rename("A") }

        // Then — the failure surfaces, but the server has the write, so nothing will send it again
        assertTrue(outcome.isFailure)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.allPendingMutations().size)
    }

    private fun patch(title: String): JsonElement = Json.parseToJsonElement("""{"title":"$title"}""")

    private companion object {
        const val TABLE = "tasks"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}

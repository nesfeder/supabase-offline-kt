@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.auth
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The mutation queue across `close()`: everything the client started stops — the replay worker,
 * the session-settle trigger, a pass in flight — later calls fail with a clear error, and a client
 * reopened on the same database resumes the queue with nothing lost and nothing sent twice.
 */
class MutationQueueAcrossCloseTest {

    private val callerJob = SupervisorJob()
    private val callerScope = CoroutineScope(callerJob + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        callerJob.cancel()
    }

    @Test
    fun a_write_or_a_read_after_close_fails_with_a_clear_error_and_sends_nothing() = runTest {
        // Given — a builder obtained while the client was open
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = callerScope)
        val builder = client.from(TABLE)

        // When
        client.close()

        // Then — every verb refuses, whatever its strategy, and nothing reaches the server
        val queued = assertFailsWith<IllegalStateException> { builder.insertJson(ROW) }
        assertEquals(CLIENT_CLOSED_MESSAGE, queued.message)
        assertFailsWith<IllegalStateException> { builder.insertJson(ROW, mutationStrategy = MutationStrategy.OnlineOnly) }
        assertFailsWith<IllegalStateException> { builder.updateJson(buildJsonObject { put("title", "x") }) { filter { eq("id", 1) } } }
        assertFailsWith<IllegalStateException> { builder.delete { filter { eq("id", 1) } } }
        assertFailsWith<IllegalStateException> { builder.select() }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun close_stops_the_session_settle_trigger_and_the_replay_worker() = runTest {
        // Given — user A's edit queued offline, A signed out, and the replay worker started
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        val client = newTestOfflineClient(supabase = supabase, coordinatorScope = callerScope)
        supabase.signInForTest("user-a")
        client.from(TABLE).updateJson(buildJsonObject { put("title", "A's edit") }) { filter { eq("id", 1) } }
        client.from(TABLE).updateJson(buildJsonObject { put("title", "A's second edit") }) { filter { eq("id", 1) } }
        supabase.auth.clearSession()

        // When — the client closes, then the network returns and A signs in again
        client.close()
        val sentAfterClose = AtomicInt(0)
        server.answer = {
            sentAfterClose.addAndFetch(1)
            okJson("[]")
        }
        supabase.signInForTest("user-a")

        // Then — nothing the client started is still running, and the sign-in sent nothing
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (callerJob.children.any()) delay(5) }
            delay(SETTLE_MILLIS)
        }
        assertEquals(0, sentAfterClose.load(), "a pass ran after close()")
    }

    @Test
    fun a_reopened_client_resumes_the_queue_with_nothing_lost_or_duplicated() = runTest {
        // Given — two keyed INSERTs queued offline in client A, over one database
        val objects = FakeObjectDao()
        val storage = InMemoryStorageDao()
        val mutations = FakeMutationDao()
        val server = FakeRelationalPostgrestServer()
        val serverAnswer = server.recorder.answer
        val online = AtomicBoolean(false)
        val held = CompletableDeferred<Unit>()
        server.recorder.answer = { request ->
            if (!online.load()) connectivityFailure()
            val response = serverAnswer(this, request)
            held.await()
            response
        }
        // Passes the engine would start on its own never run, so the one under test is the only one
        val first = newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = newTestOfflineStore(objectDao = objects, storageDao = storage, mutationDao = mutations),
            coordinatorScope = idleCoroutineScope(),
        )
        first.from(TABLE).insertJson(rowWithId(1))
        first.from(TABLE).insertJson(rowWithId(2))

        // When — A's pass commits the first on the server, and A closes before the answer arrives
        online.store(true)
        val pass = callerScope.async { runCatching { first.syncAllPendingMutations() } }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (server.rows(TABLE).isEmpty()) delay(5) } }
        first.close()
        val passOutcome = withContext(Dispatchers.Default) { withTimeout(5.seconds) { pass.await() } }
        held.complete(Unit)
        val reopened = newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = newTestOfflineStore(objectDao = objects, storageDao = storage, mutationDao = mutations),
            coordinatorScope = idleCoroutineScope(),
        )
        reopened.syncAllPendingMutations()

        // Then — A's pass was stopped; the reopened client sent the rest; each row is on the server
        // once, and nothing was refused as a duplicate of itself
        assertIs<IllegalStateException>(passOutcome.exceptionOrNull(), "the pass in flight was not stopped by close()")
        assertEquals(listOf(1, 2), server.rows(TABLE).map { row -> row.getValue("id").jsonPrimitive.int }.sorted())
        assertEquals(0, reopened.pendingMutationCount())
        assertTrue(reopened.rejectedMutationList().isEmpty(), "a mutation was rejected: ${reopened.rejectedMutationList()}")
        reopened.close()
    }

    private fun rowWithId(id: Int): JsonArray = Json.parseToJsonElement("""[{"id":$id,"title":"row $id"}]""") as JsonArray

    private companion object {
        const val TABLE = "todos"
        const val SETTLE_MILLIS = 300L
        val ROW: JsonArray = Json.parseToJsonElement("""[{"id":1}]""") as JsonArray
    }
}

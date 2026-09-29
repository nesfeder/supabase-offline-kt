package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * `close()` releases what the client holds: it stops the client's background work — without
 * touching the scope the caller handed in — runs the factory's release once, and refuses later use.
 * Nothing the client started reaches the server once it has closed: a replay that outlived it
 * would send writes whose bookkeeping can no longer be recorded, and the next client would send
 * them again.
 */
@OptIn(ExperimentalAtomicApi::class)
class SupabaseOfflineClientCloseTest {

    private val callerJob = SupervisorJob()
    private val callerScope = CoroutineScope(callerJob + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        callerJob.cancel()
    }

    @Test
    fun close_stops_the_clients_background_work_and_fails_its_reactive_reads() = runTest {
        // Given — a live reactive read, whose shared refresh runs in the client's own scope
        var releases = 0
        val client = newTestOfflineClient(onClose = { releases++ }, coordinatorScope = callerScope)
        val ended = CompletableDeferred<Throwable?>()
        val observer = callerScope.launch {
            ended.complete(runCatching { client.from("todos").selectAsFlow().collect { } }.exceptionOrNull())
        }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (callerJob.children.none { it !== observer && it.children.any() }) delay(5)
            }
        }

        // When
        client.close()
        client.close()

        // Then — the read fails in band rather than ending as if the table were done, everything
        // the client started is gone, the caller's own scope is untouched, and the factory's
        // release ran exactly once
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                observer.join()
                while (callerJob.children.any()) delay(5)
            }
        }
        assertIs<IllegalStateException>(ended.await(), "the reactive read ended without saying the client closed")
        assertTrue(callerJob.isActive, "close() cancelled the caller's scope")
        assertEquals(1, releases)
    }

    @Test
    fun a_reactive_read_started_on_a_closed_client_fails_rather_than_completing_empty() = runTest {
        // Given — a builder obtained before close()
        val client = newTestOfflineClient(coordinatorScope = callerScope)
        val builder = client.from("todos")

        // When
        client.close()
        val outcome = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { runCatching { builder.selectAsFlow().toList() } }
        }

        // Then
        assertIs<IllegalStateException>(outcome.exceptionOrNull(), "completed with ${outcome.getOrNull()?.size} emissions")
    }

    @Test
    fun nothing_is_sent_after_close() = runTest {
        // Given — two writes queued offline
        val online = AtomicInt(0)
        val sent = AtomicInt(0)
        val held = CompletableDeferred<Unit>()
        val server = RecordingPostgrestServer(
            answer = {
                if (online.load() == 0) connectivityFailure()
                sent.addAndFetch(1)
                held.await()
                okJson("[]")
            },
        )
        val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = callerScope)
        val builder = client.from("todos")
        builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
        builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 2) }) })
        assertEquals(2, client.pendingMutationCount())

        // When — back online, a replay and an online write are in flight, held at the server, when
        // the client closes; then the server answers
        online.store(1)
        val replay = callerScope.launch { runCatching { client.syncAllPendingMutations() } }
        val write = callerScope.launch { runCatching { builder.insertJson(buildJsonArray { add(buildJsonObject { put("id", 3) }) }) } }
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (sent.load() == 0) delay(5) }
            delay(SETTLE_MILLIS)
        }
        client.close()
        val sentAtClose = sent.load()
        held.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                replay.join()
                write.join()
            }
            delay(SETTLE_MILLIS)
        }

        // Then — nothing reached the server after close(), and the client refuses another replay
        assertEquals(sentAtClose, sent.load(), "a request was sent after close()")
        assertFailsWith<IllegalStateException> { client.syncAllPendingMutations() }
    }

    @Test
    fun a_callers_replay_in_flight_sends_nothing_after_close() = runTest {
        // Given — two writes queued offline; the engine's own passes never run, so the caller's
        // replay is the only sender
        val online = AtomicInt(0)
        val sent = AtomicInt(0)
        val held = CompletableDeferred<Unit>()
        val server = RecordingPostgrestServer(
            answer = {
                if (online.load() == 0) connectivityFailure()
                sent.addAndFetch(1)
                held.await()
                okJson("[]")
            },
        )
        val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = idleCoroutineScope())
        client.from("todos").insertJson(buildJsonArray { add(buildJsonObject { put("id", 1) }) })
        client.from("todos").insertJson(buildJsonArray { add(buildJsonObject { put("id", 2) }) })

        // When — its first request is held at the server when the client closes
        online.store(1)
        val replay = callerScope.async { runCatching { client.syncAllPendingMutations() } }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { while (sent.load() == 0) delay(5) } }
        client.close()
        held.complete(Unit)
        val outcome = withContext(Dispatchers.Default) { withTimeout(5.seconds) { replay.await() } }

        // Then — the replay stopped with the closed error and sent nothing more
        assertIs<IllegalStateException>(outcome.exceptionOrNull())
        assertEquals(1, sent.load(), "the replay kept sending after close()")
    }

    @Test
    fun a_closed_client_refuses_every_call() = runTest {
        // Given
        val client = newTestOfflineClient(coordinatorScope = callerScope)

        // When
        client.close()

        // Then
        assertFailsWith<IllegalStateException> { client.from("todos") }
        assertFailsWith<IllegalStateException> { client.storage("avatars") }
        assertFailsWith<IllegalStateException> { client.syncAllPendingMutations() }
        assertFailsWith<IllegalStateException> { client.syncPendingMutations("todos") }
        assertFailsWith<IllegalStateException> { client.pendingMutationCount() }
        assertFailsWith<IllegalStateException> { client.pendingMutationCount("todos") }
        assertFailsWith<IllegalStateException> { client.pendingMutationCountFlow().first() }
        assertFailsWith<IllegalStateException> { client.pendingMutationCountFlow("todos").first() }
        assertFailsWith<IllegalStateException> { client.applyServerRecord("todos", buildJsonObject { put("id", 1) }) }
        assertFailsWith<IllegalStateException> { client.applyServerDelete("todos", buildJsonObject { put("id", 1) }) }
        assertFailsWith<IllegalStateException> { client.clearAllCache() }
    }

    private companion object {
        /** Real time given to anything in flight to reach the server. */
        const val SETTLE_MILLIS = 200L
    }
}

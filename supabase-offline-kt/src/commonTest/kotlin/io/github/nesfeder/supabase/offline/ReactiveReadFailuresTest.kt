package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A reactive read whose upstream fails reaches its collector the way a plain flow would: every value
 * before the failure, then the failure itself, and a cancellation as a cancellation, never as the
 * end of the read. Every read strategy, since each is its own upstream to the client's gate.
 */
@OptIn(ExperimentalAtomicApi::class)
class ReactiveReadFailuresTest {

    private val callerJob = SupervisorJob()
    private val callerScope = CoroutineScope(callerJob + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        callerJob.cancel()
    }

    @Test
    fun a_read_that_fails_after_a_value_delivers_the_value_then_the_failure_on_every_strategy() = runTest {
        for (strategy in FlowReadStrategy.entries) {
            // Given, When — a read that fails as it runs again after its first value
            val (values, failure) = aReadThatFailsAfterItsFirstValue(strategy, ReadFailed())

            // Then
            assertTrue(values.isNotEmpty(), "$strategy: no value reached the collector before the failure")
            assertIs<ReadFailed>(failure, "$strategy ended with $failure")
            assertEquals(READ_FAILED, failure.message)
        }
    }

    @Test
    fun a_read_whose_upstream_cancels_ends_with_that_cancellation_on_every_strategy() = runTest {
        for (strategy in FlowReadStrategy.entries) {
            // Given, When — a read that gives up with a cancellation of its own as it runs again
            val (values, failure) = aReadThatFailsAfterItsFirstValue(strategy, CancellationException(READ_GAVE_UP))

            // Then — the collector sees the cancellation, not the end of the read
            assertTrue(values.isNotEmpty(), "$strategy: no value reached the collector before the cancellation")
            assertIs<CancellationException>(failure, "$strategy ended with $failure")
            assertEquals(READ_GAVE_UP, failure.message)
        }
    }

    @Test
    fun a_network_first_read_whose_request_is_cancelled_ends_with_that_cancellation() = runTest {
        // Given — an interceptor that gives up on every read request with a cancellation
        val server = RecordingPostgrestServer(answer = { request -> if (request.method == "GET") throw CancellationException(INTERCEPTOR_GAVE_UP) else okJson("[]") })
        val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = callerScope)
        val read = { client.from(TABLE).selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache) }
        val caught = mutableListOf<Throwable>()

        // When
        val first = withContext(Dispatchers.Default) { withTimeout(5.seconds) { runCatching { read().first() }.exceptionOrNull() } }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { read().catch { failure -> caught += failure }.collect { } } }
        client.close()

        // Then — first() throws the cancellation, not "no element", and catch sees it
        assertIs<CancellationException>(first, "first() threw $first")
        assertEquals(INTERCEPTOR_GAVE_UP, first.message)
        assertEquals(listOf(INTERCEPTOR_GAVE_UP), caught.map { failure -> failure.message }, "catch saw $caught")
    }

    /**
     * Collects a [strategy] read until it ends. After its first value, the collector queues a write,
     * which runs the read again; from then on the read's overlay throws [failure].
     *
     * @return the values collected, and what the collection ended with.
     */
    private suspend fun aReadThatFailsAfterItsFirstValue(strategy: FlowReadStrategy, failure: Throwable): Pair<List<OfflineReadResult>, Throwable?> {
        val server = RecordingPostgrestServer(answer = { request -> if (request.method == "GET") okJson("""[{"id":1,"title":"a"}]""") else connectivityFailure() })
        val store = newTestOfflineStore()
        val client = newTestOfflineClient(supabase = server.supabaseClient(), store = store, coordinatorScope = callerScope)
        val failing = AtomicBoolean(false)
        store.identityProvider = {
            if (failing.load()) throw failure
            null
        }
        val values = mutableListOf<OfflineReadResult>()
        val ended = withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                runCatching {
                    client.from(TABLE).selectAsFlow(readStrategy = strategy).collect { value ->
                        values += value
                        if (failing.compareAndSet(expectedValue = false, newValue = true)) {
                            client.from(TABLE).insertJson(buildJsonArray { add(buildJsonObject { put("id", 2) }) })
                        }
                    }
                }.exceptionOrNull()
            }
        }
        client.close()
        return values to ended
    }

    private class ReadFailed : RuntimeException(READ_FAILED)

    private companion object {
        const val TABLE = "todos"
        const val READ_FAILED = "the read failed"
        const val READ_GAVE_UP = "the read gave up"
        const val INTERCEPTOR_GAVE_UP = "an interceptor gave up"
    }
}

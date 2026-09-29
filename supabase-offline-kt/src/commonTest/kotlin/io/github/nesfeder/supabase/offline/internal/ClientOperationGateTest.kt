package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A collection through the gate counts until its own work has stopped, so close() never closes the
 * database under a query the collection is still running; and closing ends it however its
 * collector is stuck.
 */
class ClientOperationGateTest {

    @Test
    fun a_collection_whose_collector_is_cancelled_counts_until_its_work_has_ended() = runTest {
        // Given — a live collection whose work, once cancelled, still has a moment to finish, as a
        // query does
        val gate = ClientOperationGate()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val finishing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val work = flow {
            emit(1)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    finishing.complete(Unit)
                    finish.await()
                }
            }
        }
        appScope.launch { gate.callCollecting(work).collect { entered.complete(Unit) } }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }

        // When — the app cancels the collector
        appScope.cancel()
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { finishing.await() }
            // Time for the cancellation to reach every part of the collection
            delay(200)
        }

        // Then — the collection counts until that work has finished, and not after
        assertEquals(1, gate.inFlight, "the collection left the gate while its work was still running")
        finish.complete(Unit)
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { gate.awaitNoCallsInFlight() } }
    }

    @Test
    fun closing_ends_a_collection_whose_collector_is_stuck_in_its_body() = runTest {
        // Given — a collector stuck in its body after the first value
        val gate = ClientOperationGate()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        appScope.launch {
            gate.callCollecting(flow { repeat(Int.MAX_VALUE) { emit(it) } }).collect {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }

        // When
        gate.close()

        // Then — the gate empties although the collector never reads again
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { gate.awaitNoCallsInFlight() } }
        appScope.cancel()
    }

    @Test
    fun closing_ends_a_collection_waiting_for_its_next_value_with_the_closed_error() = runTest {
        // Close and the collection's own end race, so run it enough times to see either order
        repeat(ORDERINGS) { attempt ->
            // Given — a collector waiting for a value that never comes
            val gate = ClientOperationGate()
            val entered = CompletableDeferred<Unit>()
            val collection = async(Dispatchers.Default) {
                runCatching {
                    gate.callCollecting(flow { emit(1); awaitCancellation() }).collect { entered.complete(Unit) }
                }.exceptionOrNull()
            }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }

            // When
            gate.close()

            // Then — it ended with the closed error, never as if the flow were done
            val failure = withContext(Dispatchers.Default) { withTimeout(5.seconds) { collection.await() } }
            assertClientClosedError(failure, "attempt $attempt: the collection live at close()")
            assertEquals(0, gate.inFlight)
        }
    }

    private companion object {
        const val ORDERINGS = 200
    }
}

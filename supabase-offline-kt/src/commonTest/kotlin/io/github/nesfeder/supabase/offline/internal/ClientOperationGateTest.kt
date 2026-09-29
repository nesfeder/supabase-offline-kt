// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A collection through the gate counts until its own work has stopped, so close() never closes the
 * database under a query the collection is still running; and closing ends it however its collector
 * is stuck.
 */
class ClientOperationGateTest {

    @Test
    fun a_collection_whose_collector_is_cancelled_counts_until_its_work_has_ended() = runTest {
        // Given — a live collection whose work, once cancelled, still has a moment to finish, as a
        // query does. The cancellation may land in emit, which returns once the collector is done
        val gate = ClientOperationGate()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>()
        val finishing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val work = flow {
            try {
                emit(1)
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
        assertEquals(
            1,
            gate.inFlight,
            "the collection left the gate while its work was still running",
        )
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
        // Close and the collection's own end race, so run it enough times to see either order.
        // Unconfined
        // ends the upstream inside close() itself, before anything else close() does
        repeat(ORDERINGS) { attempt ->
            // Given — a collector waiting for a value that never comes
            val gate = ClientOperationGate()
            val entered = CompletableDeferred<Unit>()
            val dispatcher = if (attempt % 2 == 0) Dispatchers.Default else Dispatchers.Unconfined
            val collection =
                async(dispatcher) {
                    runCatching {
                        gate
                            .callCollecting(
                                flow {
                                    emit(1)
                                    awaitCancellation()
                                }
                            )
                            .collect { entered.complete(Unit) }
                    }
                        .exceptionOrNull()
                }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }

            // When
            gate.close()

            // Then — it ended with the closed error, never as if the flow were done
            val failure =
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { collection.await() } }
            assertClientClosedError(failure, "attempt $attempt: the collection live at close()")
            assertEquals(0, gate.inFlight)
        }
    }

    @Test
    fun an_upstream_that_offers_a_value_after_close_never_throws_into_the_apps_scope() = runTest {
        // Given — a collection read through produceIn in the app's scope, whose upstream is between
        // two
        // values when close() runs (so close() runs the collection's handler itself). It offers the
        // second before its cancellation reaches it, into a channel close() has already ended, as
        // an
        // upstream caught between two statements does; here it waits, deaf to cancellation, to be
        // sure
        val gate = ClientOperationGate()
        val uncaught = MutableStateFlow<List<Throwable>>(emptyList())
        val appScope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.Default +
                    CoroutineExceptionHandler { _, failure -> uncaught.update { it + failure } }
            )
        val betweenValues = CompletableDeferred<Unit>()
        val closeReturned = CompletableDeferred<Unit>()
        val upstream = FakeUpstreamPastItsCancellation(betweenValues, closeReturned)
        val values = gate.callCollecting(upstream).produceIn(appScope)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                assertEquals(1, values.receive())
                betweenValues.await()
            }
        }

        // When
        gate.close()
        closeReturned.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                appScope.coroutineContext.job.children.forEach { child -> child.join() }
            }
        }

        // Then — the collection ended with the closed error in its own channel, and nothing reached
        // the app's handler
        assertClientClosedError(
            values.receiveCatching().exceptionOrNull(),
            "the collection live at close()",
        )
        assertEquals(emptyList(), uncaught.value, "the closed error escaped into the app's scope")
        assertEquals(0, gate.inFlight)
        appScope.cancel()
    }

    @Test
    fun a_value_and_then_an_upstream_failure_reach_the_collector_in_that_order() = runTest {
        // The failure and the collector's taking of the value race, so run it enough times to see
        // both orders
        repeat(FAILURE_ORDERINGS) { attempt ->
            // Given — an upstream that fails right after a value
            val gate = ClientOperationGate()
            val values = mutableListOf<Int>()

            // When
            val failure =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        runCatching {
                            gate
                                .callCollecting(
                                    flow {
                                        emit(1)
                                        throw Boom("right after a value")
                                    }
                                )
                                .collect { values += it }
                        }
                            .exceptionOrNull()
                    }
                }

            // Then — the value, and then the failure itself
            assertEquals(
                listOf(1),
                values,
                "attempt $attempt: the value sent before the failure was dropped",
            )
            assertIs<Boom>(failure, "attempt $attempt ended with $failure")
            assertEquals("right after a value", failure.message)
            assertEquals(0, gate.inFlight)
        }
    }

    @Test
    fun an_upstream_cancellation_reaches_the_collector_as_a_cancellation() = runTest {
        // Given — upstreams that give up with a cancellation of their own, after a value and at
        // once
        val gate = ClientOperationGate()
        val values = mutableListOf<Int>()
        val caught = mutableListOf<Throwable>()

        // When
        val afterAValue =
            withContext(Dispatchers.Default) {
                runCatching {
                    gate
                        .callCollecting(
                            flow {
                                emit(1)
                                throw CancellationException(GAVE_UP)
                            }
                        )
                        .collect { values += it }
                }
                    .exceptionOrNull()
            }
        withContext(Dispatchers.Default) {
            gate
                .callCollecting(flow<Int> { throw CancellationException(GAVE_UP) })
                .catch { failure -> caught += failure }
                .collect {}
        }
        val first =
            withContext(Dispatchers.Default) {
                runCatching {
                    gate.callCollecting(flow<Int> { throw CancellationException(GAVE_UP) }).first()
                }
                    .exceptionOrNull()
            }

        // Then — the collector sees the cancellation, after the value before it, and never a
        // completion
        assertEquals(listOf(1), values)
        assertIs<CancellationException>(
            afterAValue,
            "a collection whose upstream gave up ended with $afterAValue",
        )
        assertEquals(GAVE_UP, afterAValue.message)
        assertEquals(
            listOf(GAVE_UP),
            caught.map { failure -> failure.message },
            "catch saw $caught",
        )
        assertIs<CancellationException>(first, "first() on an upstream that gave up threw $first")
        assertEquals(0, gate.inFlight)
    }

    @Test
    fun a_slow_collector_is_handed_the_latest_value_not_a_superseded_one() = runTest {
        // Given — a source of snapshots, as every read the gate carries is, that moves on while the
        // collector is busy with its first value
        val gate = ClientOperationGate()
        val source = MutableStateFlow(0)
        val seen = mutableListOf<Int>()

        // When
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                gate.callCollecting(source).first { value ->
                    seen += value
                    if (value == 0) {
                        for (next in 1..LAST_SNAPSHOT) {
                            source.value = next
                            delay(2)
                        }
                        delay(50)
                    }
                    value == LAST_SNAPSHOT
                }
            }
        }

        // Then — the collector went from the first snapshot straight to the latest, as it would
        // bare
        assertEquals(listOf(0, LAST_SNAPSHOT), seen)
    }

    @Test
    fun closing_from_inside_the_collector_empties_the_gate_at_once_and_ends_with_the_closed_error() =
        runTest {
            // Given — a live collection
            val gate = ClientOperationGate()
            val emptied = CompletableDeferred<Boolean>()

            // When — its collector closes the gate and waits for it to empty, as close() does
            val failure =
                withContext(Dispatchers.Default) {
                    withTimeout(5.seconds) {
                        runCatching {
                            gate
                                .callCollecting(flow { repeat(Int.MAX_VALUE) { emit(it) } })
                                .collect {
                                    if (!emptied.isCompleted) {
                                        gate.close()
                                        emptied.complete(
                                            withTimeoutOrNull(PROMPTLY) {
                                                gate.awaitNoCallsInFlight()
                                            } != null
                                        )
                                    }
                                }
                        }
                            .exceptionOrNull()
                    }
                }

            // Then — the gate emptied while the collector was still inside its body, and the
            // collection
            // ended with the closed error
            assertTrue(
                emptied.await(),
                "the gate did not empty while its collector was inside its body",
            )
            assertClientClosedError(failure, "the collection closed from inside its collector")
        }

    private class Boom(message: String) : RuntimeException(message)

    private companion object {
        const val ORDERINGS = 200
        const val FAILURE_ORDERINGS = 500
        const val GAVE_UP = "the upstream gave up"
        const val LAST_SNAPSHOT = 10
        val PROMPTLY: Duration = 2.seconds
    }
}

/**
 * An upstream that emits 1, then — deaf to cancellation, signalling [betweenValues] — waits for
 * [closeReturned] and emits 2. A `flow {}` would refuse that second emission once cancelled.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class FakeUpstreamPastItsCancellation(
    private val betweenValues: CompletableDeferred<Unit>,
    private val closeReturned: CompletableDeferred<Unit>,
) : Flow<Int> {
    override suspend fun collect(collector: FlowCollector<Int>) {
        collector.emit(1)
        withContext(NonCancellable) {
            betweenValues.complete(Unit)
            closeReturned.await()
            collector.emit(2)
        }
    }
}

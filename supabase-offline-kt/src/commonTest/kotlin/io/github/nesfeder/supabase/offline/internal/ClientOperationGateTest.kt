// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.testdoubles.FakeUpstreamPastItsCancellation
import io.github.nesfeder.supabase.offline.testdoubles.RecordingUncaughtExceptions
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A collection through the gate counts until its own work has stopped, so close() never closes the
 * database under a query the collection is still running; and closing ends it however its collector
 * is stuck. A collection live at close() completes, as a flow does at its end; one started after it
 * gets the closed error, as a call does.
 */
class ClientOperationGateTest {

    @Test
    fun a_collection_whose_collector_is_canceled_counts_until_its_work_has_ended() = runTest {
        // Given — a live collection whose work, once canceled, still has a moment to finish, as a
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
    fun closing_completes_a_collection_waiting_for_its_next_value() = runTest {
        // Close and the collection's own end race, so run it enough times to see either order.
        // Unconfined ends the upstream inside close() itself, before anything else close() does
        repeat(ORDERINGS) { attempt ->
            // Given — a collector waiting for a value that never comes
            val gate = ClientOperationGate()
            val entered = CompletableDeferred<Unit>()
            val dispatcher = if (attempt % 2 == 0) Dispatchers.Default else Dispatchers.Unconfined
            val collection =
                async(dispatcher) {
                    val outcome = runCatching {
                        gate
                            .callCollecting(
                                flow {
                                    emit(1)
                                    awaitCancellation()
                                }
                            )
                            .collect { entered.complete(Unit) }
                    }
                    outcome.exceptionOrNull()
                }
            withContext(Dispatchers.Default) { withTimeout(5.seconds) { entered.await() } }

            // When
            gate.close()

            // Then — it completed, with no error and no cancellation
            val failure =
                withContext(Dispatchers.Default) { withTimeout(5.seconds) { collection.await() } }
            assertNull(
                failure,
                "attempt $attempt: the collection live at close() ended with $failure",
            )
            assertEquals(0, gate.inFlight)
        }
    }

    @Test
    fun an_upstream_that_offers_a_value_after_close_never_throws_into_the_apps_scope() = runTest {
        // Given — a collection read through produceIn in the app's scope, whose upstream is between
        // two values when close() runs (so close() runs the collection's handler itself). It offers
        // the second before its cancellation reaches it, into a channel close() has already ended,
        // as an upstream caught between two statements does; here it waits, deaf to cancellation,
        // to be sure. The app's scope runs on this test's scheduler: the deferreds fix the order,
        // and no step waits on the clock, however slow the machine
        val gate = ClientOperationGate()
        val uncaught = RecordingUncaughtExceptions()
        val appScope =
            CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + uncaught)
        val betweenValues = CompletableDeferred<Unit>()
        val closeReturned = CompletableDeferred<Unit>()
        val upstream = FakeUpstreamPastItsCancellation(betweenValues, closeReturned)
        val values = gate.callCollecting(upstream).produceIn(appScope)
        assertEquals(1, values.receive())
        betweenValues.await()

        // When
        gate.close()
        closeReturned.complete(Unit)
        appScope.coroutineContext.job.children.forEach { child -> child.join() }

        // Then — the collection's own channel completed, and nothing reached the app's handler
        val ending = values.receiveCatching()
        assertTrue(ending.isClosed, "the channel held $ending after close()")
        assertNull(ending.exceptionOrNull(), "the collection live at close() ended with an error")
        assertEquals(
            emptyList(),
            uncaught.recorded(),
            "the closed error escaped into the app's scope",
        )
        assertEquals(0, gate.inFlight)
        appScope.cancel()
    }

    @Test
    fun a_collection_the_app_stopped_reading_through_produceIn_ends_quietly_at_close() = runTest {
        // Given — an app's launch that holds a collection through produceIn, reads one value and
        // stops reading, in a scope with a plain Job and another job beside it
        val gate = ClientOperationGate()
        val uncaught = RecordingUncaughtExceptions()
        val appJob = Job()
        val appScope = CoroutineScope(appJob + Dispatchers.Default + uncaught)
        val sibling = appScope.launch { awaitCancellation() }
        val values = CompletableDeferred<ReceiveChannel<Int>>()
        val holder = appScope.launch {
            val channel =
                gate
                    .callCollecting(
                        flow {
                            repeat(LAST_SNAPSHOT) { emit(it) }
                            awaitCancellation()
                        }
                    )
                    .produceIn(this)
            channel.receive()
            values.complete(channel)
        }
        val channel = withContext(Dispatchers.Default) { withTimeout(5.seconds) { values.await() } }

        // When
        gate.close()
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { holder.join() } }

        // Then — the app's launch ended as it would have, not failed; its scope and the job beside
        // it are untouched; nothing reached the app's handler; and reading again finds the channel
        // completed, after whatever it still held
        assertEquals(false, holder.isCancelled, "the app's launch was failed by the closed error")
        assertEquals(true, sibling.isActive, "the job beside it was canceled")
        assertEquals(true, appJob.isActive, "the app's scope was canceled")
        assertEquals(
            emptyList(),
            uncaught.recorded(),
            "the closed error escaped into the app's scope",
        )
        assertNull(channel.drainToItsEnd(), "reading again after close() gave an error")
        assertEquals(0, gate.inFlight)
        appScope.cancel()
    }

    @Test
    fun a_value_and_then_an_upstream_failure_reach_the_collector_in_that_order() = runTest {
        // The failure races the collector's taking of the value, so run it enough times to see
        // both orders of the two
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
        // Given — upstreams that give up with a cancellation of their own, one after a value and
        // one at once
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

        // Then — the collector sees the cancellation, after the value before it, and never sees
        // the read complete
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
    fun a_slow_collector_is_handed_at_most_one_value_a_newer_one_has_replaced() = runTest {
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

        // Then — from the first snapshot to the latest, with at most the one snapshot the gate had
        // already taken from the source when the collector asked again
        assertEquals(0, seen.first(), "saw $seen")
        assertEquals(LAST_SNAPSHOT, seen.last(), "saw $seen")
        assertTrue(seen.size <= 3, "the gate handed on more than one superseded snapshot: $seen")
    }

    @Test
    fun a_collection_started_after_close_through_produceIn_ends_quietly() = runTest {
        // Given — a closed gate, and an app's launch that starts a collection through produceIn
        val gate = ClientOperationGate()
        gate.close()
        val uncaught = RecordingUncaughtExceptions()
        val appJob = Job()
        val appScope = CoroutineScope(appJob + Dispatchers.Default + uncaught)
        val ending = CompletableDeferred<Throwable?>()

        // When
        val holder = appScope.launch {
            ending.complete(
                gate
                    .callCollecting(flow { emit(1) })
                    .produceIn(this)
                    .receiveCatching()
                    .exceptionOrNull()
            )
        }
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { holder.join() } }

        // Then — the app's launch and scope were not failed, and the reader got the closed error
        assertEquals(false, holder.isCancelled, "the app's launch was failed by the closed error")
        assertEquals(true, appJob.isActive, "the app's scope was canceled")
        assertEquals(
            emptyList(),
            uncaught.recorded(),
            "the closed error escaped into the app's scope",
        )
        assertClientClosedError(ending.await(), "a collection started after close()")
        appScope.cancel()
    }

    @Test
    fun closing_from_inside_the_collector_empties_the_gate_at_once_and_completes_the_collection() =
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
                                            withTimeoutOrNull(EMPTIES_AT_ONCE_OR_NEVER) {
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
            // collection completed
            assertTrue(
                emptied.await(),
                "the gate did not empty while its collector was inside its body",
            )
            assertNull(
                failure,
                "the collection closed from inside its collector ended with $failure",
            )
        }

    @Test
    fun an_upstream_the_closing_transport_stops_completes_the_collection_and_its_own_failure_does_not() =
        runTest {
            // Given — a gate whose client's HTTP client is closed, as supabase-kt's close() leaves
            // it before it closes the plugin
            val gate = ClientOperationGate(isTransportClosed = { true })

            // When — one upstream stopped by the closed transport, one failing on its own
            val stopped =
                withContext(Dispatchers.Default) {
                    runCatching {
                        gate
                            .callCollecting(
                                flow<Int> {
                                    throw TransportClosedException(CancellationException(GAVE_UP))
                                }
                            )
                            .collect {}
                    }
                        .exceptionOrNull()
                }
            val failed =
                withContext(Dispatchers.Default) {
                    runCatching {
                        gate.callCollecting(flow<Int> { throw Boom("its own") }).collect {}
                    }
                        .exceptionOrNull()
                }

            // Then — the first completed, and the second kept its own failure
            assertNull(stopped, "a collection the closing transport stopped ended with $stopped")
            assertIs<Boom>(failed, "a collection that failed on its own ended with $failed")
            assertEquals(0, gate.inFlight)
        }

    private class Boom(message: String) : RuntimeException(message)

    /** Reads [this] to its end and returns what it was closed with. */
    private suspend fun <T> ReceiveChannel<T>.drainToItsEnd(): Throwable? {
        while (true) {
            val next = receiveCatching()
            if (next.isClosed) return next.exceptionOrNull()
        }
    }

    private companion object {
        const val ORDERINGS = 200
        const val FAILURE_ORDERINGS = 500
        const val GAVE_UP = "the upstream gave up"
        const val LAST_SNAPSHOT = 10

        /**
         * The gate empties at once or never, so a generous bound costs nothing when it passes and
         * cannot fail on a slow machine.
         */
        val EMPTIES_AT_ONCE_OR_NEVER: Duration = 10.seconds
    }
}

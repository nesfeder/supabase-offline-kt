// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Counts the client's calls in flight, whoever's coroutine runs them. After [close] it refuses new
 * ones, and the database is closed only once no call is using it.
 */
internal class ClientOperationGate {

    /** Whether the gate is closed, and how many calls are in flight, changed together. */
    private data class State(val closed: Boolean, val inFlight: Int)

    private val state = MutableStateFlow(State(closed = false, inFlight = 0))

    /** Completes at [close], which ends every flow [callCollecting] is running. */
    private val closing = CompletableDeferred<Unit>()

    /** Whether [close] has run. */
    val isClosed: Boolean
        get() = state.value.closed

    /** How many calls are in flight. */
    val inFlight: Int
        get() = state.value.inFlight

    /** Throws the client's closed error once [close] has run. */
    fun ensureOpen() {
        check(!isClosed) { CLIENT_CLOSED_MESSAGE }
    }

    /** Runs [block] as a call the database's close waits for; refused once [close] has run. */
    suspend fun <T> call(block: suspend () -> T): T {
        state.update { current ->
            check(!current.closed) { CLIENT_CLOSED_MESSAGE }
            current.copy(inFlight = current.inFlight + 1)
        }
        try {
            return block()
        } finally {
            state.update { current -> current.copy(inFlight = current.inFlight - 1) }
        }
    }

    /**
     * Runs [block] as a call that cancelling [scope] stops: a child of [scope] on the caller's
     * dispatcher. Stopped that way after [close], its caller gets the closed error, not a
     * cancellation.
     */
    suspend fun <T> cancellableCall(scope: CoroutineScope, block: suspend () -> T): T = call {
        val work =
            scope.async(
                currentCoroutineContext().minusKey(Job),
                start = CoroutineStart.UNDISPATCHED,
            ) {
                block()
            }
        try {
            work.await()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // The caller was cancelled: stop the work, and leave the gate only once it has.
                withContext(NonCancellable) { work.cancelAndJoin() }
                throw cancellation
            }
            if (isClosed) throw IllegalStateException(CLIENT_CLOSED_MESSAGE, cancellation)
            throw cancellation
        }
    }

    /**
     * [flow] as a call while collected, via a rendezvous channel. At [close] the channel ends with
     * the closed error, then the upstream is cancelled, and the producer ends quietly. Upstream
     * failures end it after their values; only the collector's cancellation propagates.
     */
    fun <T> callCollecting(flow: Flow<T>): Flow<T> = channelFlow {
        try {
            call {
                // Leaves the gate only once the upstream has ended, even when cancelled
                coroutineScope {
                    val upstream =
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            try {
                                flow.collect { value -> send(value) }
                            } catch (failure: Throwable) {
                                // Only ours (the collector's or close()'s) rethrows; the
                                // upstream's own, a refused send included, ends the channel
                                if (failure is CancellationException && !isActive) {
                                    throw failure
                                }
                                this@channelFlow.close(failure)
                            }
                        }
                    // The closed error first, or the cancelled upstream would end it first
                    val onClose = closing.invokeOnCompletion {
                        this@channelFlow.close(IllegalStateException(CLIENT_CLOSED_MESSAGE))
                        upstream.cancel()
                    }
                    try {
                        upstream.join()
                    } finally {
                        onClose.dispose()
                    }
                }
            }
        } catch (refused: IllegalStateException) {
            // Refused because closed: the channel carries the error, the producer ends quietly
            if (refused is CancellationException) throw refused
            close(refused)
        }
    }
        .buffer(Channel.RENDEZVOUS)

    /**
     * Refuses every call from now on and ends the flows being collected.
     *
     * @return `false` when it had already run.
     */
    fun close(): Boolean {
        var closedNow = false
        state.update { current ->
            closedNow = !current.closed
            current.copy(closed = true)
        }
        closing.complete(Unit)
        return closedNow
    }

    /** Suspends until no call is in flight. */
    suspend fun awaitNoCallsInFlight() {
        state.first { current -> current.inFlight == 0 }
    }
}

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

    private data class State(val closed: Boolean, val inFlight: Int)

    private val state = MutableStateFlow(State(closed = false, inFlight = 0))
    private val closing = CompletableDeferred<Unit>()

    /** Whether [close] has run. */
    val isClosed: Boolean get() = state.value.closed

    /** How many calls are in flight. */
    val inFlight: Int get() = state.value.inFlight

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
     * dispatcher. Stopped that way after [close], its caller gets the closed error, not a cancellation.
     */
    suspend fun <T> cancellableCall(scope: CoroutineScope, block: suspend () -> T): T = call {
        val work = scope.async(currentCoroutineContext().minusKey(Job), start = CoroutineStart.UNDISPATCHED) { block() }
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

    /** [flow] as a call for as long as it is collected, ended with the closed error when [close] runs. */
    fun <T> callCollecting(flow: Flow<T>): Flow<T> = channelFlow {
        call {
            coroutineScope {
                val watcher = launch {
                    closing.await()
                    this@channelFlow.close(IllegalStateException(CLIENT_CLOSED_MESSAGE))
                }
                try {
                    flow.collect { value -> send(value) }
                } finally {
                    watcher.cancel()
                }
            }
        }
    }.buffer(Channel.RENDEZVOUS)

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

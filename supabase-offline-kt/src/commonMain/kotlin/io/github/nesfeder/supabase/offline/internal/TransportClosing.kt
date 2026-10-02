// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.nesfeder.supabase.offline.internal.postgrest.CLIENT_CLOSED_MESSAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.io.IOException

/**
 * A request the client's HTTP client refused because it is closed: `SupabaseClient.close()` closes
 * it before it closes the plugins, so a request made in between, or a retry of one already running,
 * cannot be sent. The engine treats it as a connectivity failure, as it would offline: a read the
 * cache can answer is answered from it, and a write the mutation queue can hold is queued. Anything
 * else that meets it ends with the client's closed error ([closedErrorFor]).
 *
 * @param cancellation the cancellation the closed HTTP client failed the request with.
 */
internal class TransportClosedException(cancellation: CancellationException) :
    IOException("The client's HTTP client is closed: the client is closing", cancellation)

/** Whether this client's HTTP client is closed, as `SupabaseClient.close()` leaves it. */
@OptIn(SupabaseInternal::class)
internal fun SupabaseClient.isTransportClosed(): Boolean = !httpClient.httpClient.isActive

/**
 * Sends [request] through this client. A cancellation it fails with while its caller is still
 * running and the HTTP client is closed is not the caller's: it becomes a
 * [TransportClosedException]. Every other failure, the caller's own cancellation included, is
 * rethrown unchanged.
 */
internal suspend inline fun <T> SupabaseClient.sendUnlessClosing(request: () -> T): T =
    try {
        request()
    } catch (cancellation: CancellationException) {
        if (currentCoroutineContext().isActive && isTransportClosed()) {
            throw TransportClosedException(cancellation)
        }
        throw cancellation
    }

/**
 * Whether [failure] is the client's closing ending a call rather than a failure of the call's own:
 * the closed transport, a cancellation the closing caused while the caller still runs, or the
 * client's closed error itself, once the client or its HTTP client is closed.
 */
internal suspend fun isEndedByClosing(
    failure: Throwable,
    isClosed: Boolean,
    isTransportClosed: Boolean,
): Boolean =
    when {
        failure is TransportClosedException -> true
        !isClosed && !isTransportClosed -> false
        failure is CancellationException -> currentCoroutineContext().isActive
        else -> failure is IllegalStateException && failure.message == CLIENT_CLOSED_MESSAGE
    }

/**
 * The client's closed error, [IllegalStateException] with [CLIENT_CLOSED_MESSAGE], for a call that
 * [failure] stopped because the client is closing; [failure] itself otherwise. A cancellation is
 * the client's when its caller is still running and the client is closed, or its HTTP client is.
 *
 * The error keeps the cancellation as its cause, never the [TransportClosedException], so it does
 * not read as a connectivity failure.
 */
internal suspend fun closedErrorFor(
    failure: Throwable,
    isClosed: Boolean,
    isTransportClosed: Boolean,
): Throwable =
    when {
        failure is TransportClosedException ->
            IllegalStateException(CLIENT_CLOSED_MESSAGE, failure.cause)
        failure is CancellationException &&
            currentCoroutineContext().isActive &&
            (isClosed || isTransportClosed) -> IllegalStateException(CLIENT_CLOSED_MESSAGE, failure)
        else -> failure
    }

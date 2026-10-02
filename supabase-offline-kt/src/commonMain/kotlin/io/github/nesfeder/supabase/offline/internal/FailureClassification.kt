// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.fromHttpToGmtDate
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * The HTTP status carried by this throwable or a cause, as a [PostgrestRestException] or a
 * [ResponseException], or `null` when neither is in the chain.
 */
internal fun Throwable.httpStatusOrNull(): Int? {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is PostgrestRestException -> return current.statusCode
            is ResponseException -> return current.response.status.value
        }
        current = current.cause
    }
    return null
}

/**
 * Returns `true` if this throwable's status marks a **permanent** mutation failure – i.e. one that
 * will not succeed when retried. The replayer asks only after ruling out PostgREST's schema-cache
 * misses (`PGRST204`, `PGRST205`): 4xx answers that pass once the cache reloads.
 */
internal fun Throwable.isPermanentMutationFailure(): Boolean {
    val status = httpStatusOrNull() ?: return false
    return status.isPermanentMutationFailureStatus()
}

/**
 * Status-code based predicate used by [isPermanentMutationFailure]. Split out so we can unit test
 * the classification logic without having to construct concrete HTTP exception types (which are
 * platform-specific in Ktor/Supabase).
 */
internal fun Int.isPermanentMutationFailureStatus(): Boolean {
    val status = this

    // 401 Unauthorized is transient on the offline-replay path, not permanent: it is routine
    // right after reconnect when the access token expired while offline and replay fires before
    // supabase-kt's auto-refresh completes. Dropping the mutation here would silently lose the
    // user's queued write; instead we keep it and let a later sync retry once the refreshed token
    // is in place.
    if (status == 401) return false

    // Transient-ish client codes we don't want to auto-drop:
    // 408: Request Timeout
    // 429: Too Many Requests (rate limit)
    if (status == 408 || status == 429) return false

    // Treat all other 4xx (403 / 404 / 409 / 422, …) as permanent: the request itself is "bad"
    // and will not start succeeding just by retrying. A schema-cache miss is decided before this
    if (status in 400..499) return true

    // 5xx, 1xx, 2xx, 3xx and unknown → assume transient / server side
    return false
}

/**
 * Whether this failure is the network's rather than the server's: a timeout, an [IOException] or a
 * [NetworkDisabledException], here or in a cause. A cancellation, or anything carrying an HTTP
 * status, never is. Read fallback, write queueing and replay all decide on it.
 */
internal fun Throwable.isNetworkOrConnectivityFailure(): Boolean {
    // Cancellation is never treated as a network error.
    if (this is CancellationException) return false

    // A failure with an HTTP status is the server's answer, not a transport failure.
    if (httpStatusOrNull() != null) return false

    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is CancellationException -> return false
            // The app turned network access off: offline, as far as reads and writes go
            is NetworkDisabledException -> return true
            is SyncFailedException -> return current.isConnectivityFailure
            is HttpRequestTimeoutException -> return true
            is ConnectTimeoutException -> return true
            is SocketTimeoutException -> return true
            is IOException -> return true
        }

        current = current.cause
    }

    return false
}

/**
 * How long the server asked the client to wait, from a `Retry-After` header on the response this
 * failure, or a cause, carries: a number of seconds, or an HTTP date.
 *
 * @param now the current time, for an HTTP date.
 * @return the wait, never negative; `null` when no response in the chain carries a readable one.
 */
internal fun Throwable.retryAfterOrNull(
    now: () -> Long = { Clock.System.now().toEpochMilliseconds() }
): Duration? {
    var current: Throwable? = this
    while (current != null) {
        val headers: Headers? =
            when (current) {
                is RestException -> current.response.headers
                is ResponseException -> current.response.headers
                else -> null
            }
        headers?.get(HttpHeaders.RetryAfter)?.let { value ->
            return retryAfterValueOrNull(value.trim(), now)
        }
        current = current.cause
    }
    return null
}

/** A `Retry-After` value read as a wait: delta-seconds, or an HTTP date; `null` when unreadable. */
private fun retryAfterValueOrNull(value: String, now: () -> Long): Duration? {
    value.toLongOrNull()?.let { seconds ->
        return seconds.coerceAtLeast(0L).seconds
    }
    val date =
        try {
            value.fromHttpToGmtDate()
        } catch (_: IllegalStateException) {
            return null
        } catch (_: IllegalArgumentException) {
            return null
        }
    return (date.timestamp - now()).coerceAtLeast(0L).milliseconds
}

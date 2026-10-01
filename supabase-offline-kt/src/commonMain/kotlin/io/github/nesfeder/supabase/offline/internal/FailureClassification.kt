// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
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
 * Returns `true` if this throwable is an HTTP **401 Unauthorized** — the token-refresh race that is
 * transient on the offline-replay path.
 *
 * Right after reconnect the access token can still be expired while supabase-kt's auto-refresh is
 * in flight, so a queued mutation replays into a 401. The syncer keeps the mutation and retries on
 * a later sync; crucially it must **not** count a 401 toward any dead-letter budget, otherwise a
 * bad token window (several rapid reconnect triggers) would silently drop the user's queued write —
 * the exact data loss the transient-401 classification exists to prevent.
 */
internal fun Throwable.isTransientAuthFailure(): Boolean = httpStatusOrNull() == 401

/**
 * Whether this failure is the network's rather than the server's: a timeout or an [IOException],
 * here or in a cause. A cancellation, or anything carrying an HTTP status, never is. Read fallback,
 * write queueing and replay all decide on it.
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
            is HttpRequestTimeoutException -> return true
            is ConnectTimeoutException -> return true
            is SocketTimeoutException -> return true
            is IOException -> return true
        }

        current = current.cause
    }

    return false
}

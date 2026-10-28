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

/** The PostgREST error code carried by this throwable or a cause, or `null`. */
internal fun Throwable.postgrestCodeOrNull(): String? {
    var current: Throwable? = this
    while (current != null) {
        if (current is PostgrestRestException) return current.code
        current = current.cause
    }
    return null
}

/**
 * Statuses that say a request did not finish, so the same request may pass when sent again: the
 * server stopped waiting for it (`408`), asked the client to slow down (`429`), or PostgREST, or a
 * gateway in front of it, got no usable answer from what serves the request (`502`, `503`, `504`,
 * and Cloudflare's `520`, `522`, `524`). A replay never rejects a write on one, and a write sent at
 * once stays queued on one.
 */
internal val MAY_PASS_LATER_STATUSES: Set<Int> = setOf(408, 429, 502, 503, 504, 520, 522, 524)

/**
 * PostgREST's codes for an access token it refused before running anything: one it could not decode
 * or verify (`PGRST301`), or one whose claims it rejected, an expiry already past by the server's
 * clock among them (`PGRST303`). supabase-kt refreshes by the device's clock, so a device whose
 * clock runs behind the server's sends a token the server holds expired; a later send carries a
 * refreshed one.
 */
internal val REFUSED_TOKEN_CODES: Set<String> = setOf("PGRST301", "PGRST303")

/**
 * Whether this failure is an answer after which the same write may pass when sent again: a status
 * in [MAY_PASS_LATER_STATUSES], or a `401` whose PostgREST code is in [REFUSED_TOKEN_CODES]. A
 * replay keeps a write queued on each of them, since [isPermanentMutationFailure] is `false` for
 * each; a write sent at once stays queued on one instead of being thrown.
 */
internal fun Throwable.mayPassWhenSentAgain(): Boolean {
    val status = httpStatusOrNull() ?: return false
    if (status in MAY_PASS_LATER_STATUSES) return true
    return status == HTTP_UNAUTHORIZED && postgrestCodeOrNull() in REFUSED_TOKEN_CODES
}

private const val HTTP_UNAUTHORIZED = 401

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
    if (status == HTTP_UNAUTHORIZED) return false

    // An answer that says the request did not finish (a 408 or a 429 among the 4xx) may pass later:
    // the same list keeps a write sent at once queued, so the two paths cannot disagree on one
    if (status in MAY_PASS_LATER_STATUSES) return false

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

/**
 * The HTTP status of the answer this throwable or a cause carries from Storage: a [RestException]'s
 * (supabase-kt's Storage throws one for every error answer), or a [ResponseException]'s; `null`
 * when neither is in the chain. Hosted Storage answers most refusals with a `400`, its own status
 * in the body, which supabase-kt does not keep for every one.
 */
internal fun Throwable.storageStatusOrNull(): Int? {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is RestException -> return current.statusCode
            is ResponseException -> return current.response.status.value
        }
        current = current.cause
    }
    return null
}

/**
 * Whether Storage refused the access token rather than the upload: a `401`, or Storage's own error
 * for a token it could not verify or found expired (`InvalidJWT`, `jwt expired`), whatever status
 * carries it. A later send carries a refreshed token, so such an upload stays queued.
 */
internal fun Throwable.isStorageTokenRefusal(): Boolean {
    if (storageStatusOrNull() == HTTP_UNAUTHORIZED) return true
    var current: Throwable? = this
    while (current != null) {
        if (current is RestException) {
            val text = current.error + " " + current.description.orEmpty()
            return current.error.equals(STORAGE_INVALID_TOKEN_ERROR, ignoreCase = true) ||
                text.contains(STORAGE_EXPIRED_TOKEN_MESSAGE, ignoreCase = true)
        }
        current = current.cause
    }
    return false
}

/** Storage's error name for a token it could not verify, or whose expiry has passed. */
private const val STORAGE_INVALID_TOKEN_ERROR = "InvalidJWT"

/** What Storage's message says of an expired token. */
private const val STORAGE_EXPIRED_TOKEN_MESSAGE = "jwt expired"

/**
 * Whether a queued upload Storage answered with this failure may pass when sent again: a status in
 * [MAY_PASS_LATER_STATUSES], or a token Storage refused ([isStorageTokenRefusal]). An upload sent
 * at once stays queued on one, as a row write does on its own list.
 */
internal fun Throwable.uploadMayPassWhenSentAgain(): Boolean {
    val status = storageStatusOrNull() ?: return false
    return status in MAY_PASS_LATER_STATUSES || isStorageTokenRefusal()
}

/**
 * Whether this failure is Storage refusing a queued upload for good: a `4xx` that is neither a
 * status that may pass later ([MAY_PASS_LATER_STATUSES]) nor a refused token
 * ([isStorageTokenRefusal]). Any other answer, a `5xx` among them, may pass on a later send.
 */
internal fun Throwable.isStorageRefusal(): Boolean {
    val status = storageStatusOrNull() ?: return false
    if (status !in HTTP_CLIENT_ERRORS || status in MAY_PASS_LATER_STATUSES) return false
    return !isStorageTokenRefusal()
}

/** The statuses of a refusal: the `4xx`. */
private val HTTP_CLIENT_ERRORS: IntRange = 400..499

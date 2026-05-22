package io.github.nesfeder.supabase.internal.util

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException

/**
 * Returns the associated HTTP status code for this [Throwable] if it represents
 * an HTTP failure, or `null` if no status can be determined.
 *
 * We inspect this throwable and its cause-chain for:
 *  - [PostgrestRestException]
 *  - [ResponseException]
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
 * Returns `true` if this throwable clearly represents a **permanent** mutation
 * failure – i.e. one that will not succeed when retried.
 */
internal fun Throwable.isPermanentMutationFailure(): Boolean {
    val status = httpStatusOrNull() ?: return false
    return status.isPermanentMutationFailureStatus()
}

/**
 * Status-code based predicate used by [isPermanentMutationFailure]. Split out
 * so we can unit test the classification logic without having to construct
 * concrete HTTP exception types (which are platform-specific in Ktor/Supabase).
 */
internal fun Int.isPermanentMutationFailureStatus(): Boolean {
    val status = this

    // Transient-ish client codes we don't want to auto-drop:
    // 408: Request Timeout
    // 429: Too Many Requests (rate limit)
    if (status == 408 || status == 429) return false

    // Treat all other 4xx as permanent: the request itself is "bad"
    // and will not start succeeding just by retrying.
    if (status in 400..499) return true

    // 5xx, 1xx, 2xx, 3xx and unknown → assume transient / server side
    return false
}

/**
 * Returns `true` if this error is very likely a **network / connectivity**
 * failure rather than an application or server-side error.
 *
 * This is used for both SELECT cache fallback and mutation retry behavior.
 *
 * Rules:
 *  - True for:
 *      - [HttpRequestTimeoutException]
 *      - [ConnectTimeoutException]
 *      - [SocketTimeoutException]
 *      - [IOException] that indicates transport failure
 *      - Wrapped causes of the above
 *  - False for:
 *      - [CancellationException]
 *      - Any throwable with an associated HTTP status (4xx/5xx)
 *      - PostgREST / server logic errors
 */
internal fun Throwable.isNetworkOrConnectivityFailure(): Boolean {
    // Cancellation is never treated as a network error.
    if (this is CancellationException) return false

    // Any error that already has an HTTP status is assumed to be a server
    // response (4xx/5xx etc.), not a transport/connectivity failure.
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

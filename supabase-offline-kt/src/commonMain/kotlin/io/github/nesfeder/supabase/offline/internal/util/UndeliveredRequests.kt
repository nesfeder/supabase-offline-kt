package io.github.nesfeder.supabase.offline.internal.util

import io.ktor.client.network.sockets.ConnectTimeoutException

/**
 * Whether this failure, as the HTTP engine raised it, proves the request never reached the server:
 * the connection or the host name lookup failed. Any other failure may have been delivered.
 */
internal fun Throwable.provesRequestNeverSent(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ConnectTimeoutException || isConnectFailureOnThisPlatform(current)) return true
        current = current.cause
    }
    return false
}

/** Whether [failure] is this platform's own failure to connect or to resolve the host. */
internal expect fun isConnectFailureOnThisPlatform(failure: Throwable): Boolean

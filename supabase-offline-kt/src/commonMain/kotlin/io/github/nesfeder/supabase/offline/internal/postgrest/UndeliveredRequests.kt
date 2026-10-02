// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.ktor.client.network.sockets.ConnectTimeoutException

/**
 * Whether this failure, as the HTTP engine raised it, proves the request never reached the server.
 * It does when the connection or the host name lookup failed, or the library's network access was
 * off, here or in a cause; an engine that retried inside itself carries its earlier attempts as
 * suppressed exceptions, and each of those must prove it too. Any other failure may have been
 * delivered.
 */
internal fun Throwable.provesRequestNeverSent(): Boolean =
    provesNeverSentWithin(MAX_EXCEPTIONS_WALKED)

/** How many linked exceptions the proof walks; a failure with more is treated as unproven. */
private const val MAX_EXCEPTIONS_WALKED = 32

/** [provesRequestNeverSent], walking at most [budget] linked exceptions. */
private fun Throwable.provesNeverSentWithin(budget: Int): Boolean {
    var connectFailure = false
    var remaining = budget
    var current: Throwable? = this
    while (current != null) {
        if (remaining-- <= 0) return false
        val suppressed = current.suppressedExceptions
        if (
            suppressed.any { attempt ->
                !attempt.provesNeverSentWithin(remaining / suppressed.size)
            }
        )
            return false
        if (
            current is ConnectTimeoutException ||
                current is NetworkDisabledException ||
                isConnectFailureOnThisPlatform(current)
        )
            connectFailure = true
        current = current.cause
    }
    return connectFailure
}

/** Whether [failure] is this platform's own failure to connect or to resolve the host. */
internal expect fun isConnectFailureOnThisPlatform(failure: Throwable): Boolean

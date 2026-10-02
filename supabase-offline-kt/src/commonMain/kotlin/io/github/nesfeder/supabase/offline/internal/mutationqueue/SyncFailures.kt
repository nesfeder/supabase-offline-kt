// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure

/** What every [SyncFailedException] message ends with. */
private const val STILL_QUEUED = "the queued writes stay queued for a later pass"

/**
 * What [SyncStatus.lastSyncError][io.github.nesfeder.supabase.offline.SyncStatus.lastSyncError]
 * reports for a pass that [cause] stopped: its kind, status and PostgREST code, in the library's
 * own words, and nothing of its message, which may carry a row, a filter value or a token.
 */
internal fun syncFailureOf(cause: Throwable): SyncFailedException {
    val connectivity = cause.isNetworkOrConnectivityFailure()
    val status = if (connectivity) null else cause.httpStatusOrNull()
    val code = if (connectivity) null else postgrestCodeIn(cause)
    val message =
        when {
            connectivity -> "The network could not be reached; $STILL_QUEUED"
            status != null ->
                "The server answered $status${code?.let { " ($it)" }.orEmpty()}; $STILL_QUEUED"
            else -> "A failure (${cause::class.simpleName}) stopped the pass; $STILL_QUEUED"
        }
    return SyncFailedException(
        message = message,
        httpStatus = status,
        code = code,
        isConnectivityFailure = connectivity,
    )
}

/** The PostgREST error code [failure] or a cause carries, or `null`. */
private fun postgrestCodeIn(failure: Throwable): String? {
    var current: Throwable? = failure
    while (current != null) {
        if (current is PostgrestRestException) return current.code
        current = current.cause
    }
    return null
}

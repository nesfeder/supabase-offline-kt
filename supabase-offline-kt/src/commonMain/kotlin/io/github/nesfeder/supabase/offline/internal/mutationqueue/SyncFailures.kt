// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.ktor.http.IllegalHeaderNameException
import io.ktor.http.IllegalHeaderValueException

/** What every [SyncFailedException] message ends with. */
private const val STILL_QUEUED = "the queued writes stay queued for a later pass"

/**
 * What a pass stopped by neither the server nor the network says: nothing of the failure, whose
 * class names the library's internals and whose message may carry a row or a token.
 */
private const val LOCAL_FAILURE = "A failure on this device stopped the sync pass"

/**
 * What a pass stopped by a request header the HTTP client refuses says: nothing of the header,
 * which may be the project key or the access token.
 */
private const val ILLEGAL_HEADER =
    "A request header holds a character a header cannot carry, such as a line break; check the " +
        "project key and the access token"

/**
 * What [SyncStatus.lastSyncError][io.github.nesfeder.supabase.offline.SyncStatus.lastSyncError]
 * reports for a pass that [cause] stopped: its kind, status and PostgREST code, in the library's
 * own words, and nothing of its class or its message, which may carry a row, a filter value or a
 * token.
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
            cause.isIllegalHeader() -> "$ILLEGAL_HEADER; $STILL_QUEUED"
            else -> "$LOCAL_FAILURE; $STILL_QUEUED"
        }
    return SyncFailedException(
        message = message,
        httpStatus = status,
        code = code,
        isConnectivityFailure = connectivity,
    )
}

/**
 * What the app is told a pass that ended on [stop] returned for. A pass whose identity could not be
 * told was waiting for the session as much as one whose session did not settle. One stopped by
 * another sender holding the next write never ends a call's report, since the call waits that
 * sender out; nothing failed, so it would read as having run to the end.
 */
internal fun stopReasonOf(stop: SyncStop?): SyncStopReason =
    when (stop) {
        null,
        SyncStop.HeadInFlight -> SyncStopReason.Completed
        SyncStop.SessionNotReady,
        SyncStop.IdentityUnknown -> SyncStopReason.SessionNotReady
        SyncStop.SessionChanged -> SyncStopReason.SessionChanged
        SyncStop.NetworkDisabled -> SyncStopReason.NetworkDisabled
        is SyncStop.HeldBehindOtherTables -> SyncStopReason.HeldBehindOtherTables
        SyncStop.LocalDataCleared -> SyncStopReason.LocalDataCleared
        is SyncStop.TransientFailure -> failureReasonOf(syncFailureOf(stop.cause))
        is SyncStop.HeldBehindEarlierMutations -> failureReasonOf(syncFailureOf(stop.cause))
    }

/**
 * The reason a pass that [failure] stopped is given: the network's, the server's or the device's.
 */
private fun failureReasonOf(failure: SyncFailedException): SyncStopReason =
    when {
        failure.isConnectivityFailure -> SyncStopReason.NetworkUnavailable
        failure.httpStatus != null -> SyncStopReason.ServerFailure(failure)
        else -> SyncStopReason.LocalFailure(failure)
    }

/** Whether this or a cause is Ktor's refusal of a header, whose message quotes the header. */
private fun Throwable.isIllegalHeader(): Boolean =
    generateSequence(this) { it.cause }
        .any { it is IllegalHeaderNameException || it is IllegalHeaderValueException }

/** The PostgREST error code [failure] or a cause carries, or `null`. */
private fun postgrestCodeIn(failure: Throwable): String? {
    var current: Throwable? = failure
    while (current != null) {
        if (current is PostgrestRestException) return current.code
        current = current.cause
    }
    return null
}

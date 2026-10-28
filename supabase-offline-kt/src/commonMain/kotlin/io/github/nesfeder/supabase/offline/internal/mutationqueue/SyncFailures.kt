// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.auth.exception.TokenExpiredException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.SyncStopReason
import io.github.nesfeder.supabase.offline.internal.POSTGREST_DID_NOT_RUN_CODES
import io.github.nesfeder.supabase.offline.internal.SCHEMA_CACHE_MISS_CODES
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.httpStatusOrNull
import io.github.nesfeder.supabase.offline.internal.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.internal.isPermanentMutationFailure
import io.github.nesfeder.supabase.offline.internal.isStorageRefusal
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.SendOutcome
import io.github.nesfeder.supabase.offline.internal.postgrestCodeOrNull
import io.github.nesfeder.supabase.offline.internal.storage.isQueuedUpload
import io.github.nesfeder.supabase.offline.internal.storageStatusOrNull
import io.ktor.client.plugins.ResponseException
import io.ktor.http.IllegalHeaderNameException
import io.ktor.http.IllegalHeaderValueException
import kotlinx.serialization.json.JsonPrimitive

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
    val code = if (connectivity) null else cause.postgrestCodeOrNull()
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

/**
 * Whether this failed send provably did not apply its write. It did not when no attempt reached the
 * server, when the session's token could not be refreshed (thrown before the request is built), or
 * when PostgREST itself answered that it could not reach the database.
 */
internal fun SendOutcome.Failed.provesWriteNotApplied(): Boolean =
    neverReachedServer ||
        thrown is TokenExpiredException ||
        thrown.postgrestCodeOrNull().let { code ->
            code in POSTGREST_DID_NOT_RUN_CODES || code in SCHEMA_CACHE_MISS_CODES
        }

/**
 * Where a replay request's [failure] leaves [mutation], sent as [identity]: kept for a later pass,
 * held behind an earlier write, or refused to the rejected list.
 */
internal suspend fun classifyReplayFailure(
    mutation: MutationRow,
    failure: Exception,
    identity: QueueIdentity,
    mutationQueue: MutationQueue,
    engineLogger: EngineLogger,
): ReplayOutcome =
    if (mutation.isQueuedUpload) classifyUploadFailure(failure, engineLogger)
    else classifyRowWriteFailure(mutation, failure, identity, mutationQueue, engineLogger)

/**
 * Where a queued upload's failed send leaves it. A failure that may pass keeps it queued and stops
 * the pass: no connection or no answer, a token Storage refused or that could not be refreshed, a
 * status that says the request did not finish, and any `5xx`. A refusal (another `4xx`) goes to the
 * rejected list: by then every earlier write of the user has been sent or settled, so none can be
 * the row Storage's policy reads.
 */
private fun classifyUploadFailure(failure: Exception, engineLogger: EngineLogger): ReplayOutcome {
    val status = failure.storageStatusOrNull()
    return when {
        failure.isNetworkOrConnectivityFailure() -> {
            engineLogger.info { "Network unavailable, pausing sync at a queued upload" }
            ReplayOutcome.Stopped(failure)
        }
        status == null || !failure.isStorageRefusal() -> {
            engineLogger.warning {
                "A queued upload failed (${status ?: failure::class.simpleName}); " +
                    "keeping it for a later sync"
            }
            ReplayOutcome.Stopped(failure)
        }
        else -> {
            val serverError = failure.storageServerErrorOrNull()
            engineLogger.warning {
                "Storage refused a queued upload ($status); moved to the rejected list"
            }
            ReplayOutcome.Rejected(
                message = serverError?.message ?: "Storage refused the upload",
                serverError = serverError,
            )
        }
    }
}

/** [classifyReplayFailure] for a row write. */
private suspend fun classifyRowWriteFailure(
    mutation: MutationRow,
    failure: Exception,
    identity: QueueIdentity,
    mutationQueue: MutationQueue,
    engineLogger: EngineLogger,
): ReplayOutcome =
    when {
        failure.isNetworkOrConnectivityFailure() -> {
            engineLogger.info { "Network unavailable, pausing sync for ${mutation.table}" }
            ReplayOutcome.Stopped(failure)
        }

        failure.postgrestCodeOrNull() in SCHEMA_CACHE_MISS_CODES ->
            ReplayOutcome.SchemaCacheMiss(failure)

        failure.isPermanentMutationFailure() -> {
            val status = failure.httpStatusOrNull()
            val mayDependOnEarlierWrite = status == HTTP_FORBIDDEN || status == HTTP_CONFLICT
            if (
                mayDependOnEarlierWrite &&
                    mutationQueue.hasEarlierPendingMutation(mutation.id, identity.storedValue)
            ) {
                engineLogger.warning {
                    "Holding ${mutation.operation} on ${mutation.table} ($status) behind an earlier pending mutation"
                }
                ReplayOutcome.Held(failure)
            } else {
                val serverError = serverErrorOf(failure)
                engineLogger.warning {
                    "Permanent failure for ${mutation.operation} on ${mutation.table} ($status); moved to the rejected list"
                }
                ReplayOutcome.Rejected(
                    message = serverError?.message ?: "The server refused the write",
                    serverError = serverError,
                )
            }
        }

        else -> {
            val status = failure.httpStatusOrNull()
            engineLogger.warning {
                "Replay of ${mutation.operation} on ${mutation.table} failed " +
                    "(${status ?: failure::class.simpleName}); keeping it for a later sync"
            }
            ReplayOutcome.Stopped(failure)
        }
    }

/**
 * What Storage answered with [this] refusal, as the rejected list keeps it: the HTTP status,
 * Storage's error name as the code when supabase-kt kept it apart from the message, and the
 * message. supabase-kt's Storage throws a `RestException` for every error answer.
 */
private fun Throwable.storageServerErrorOrNull(): StoredServerError? {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is RestException ->
                return current.description?.let { description ->
                    StoredServerError(
                        status = current.statusCode,
                        code = current.error,
                        message = description,
                    )
                } ?: StoredServerError(status = current.statusCode, message = current.error)
        }
        current = current.cause
    }
    return null
}

/** The server's refusal as data: PostgREST's fields, or the bare status of another HTTP failure. */
internal fun serverErrorOf(failure: Throwable): StoredServerError? {
    var current: Throwable? = failure
    while (current != null) {
        when (current) {
            is PostgrestRestException ->
                return StoredServerError(
                    status = current.statusCode,
                    code = current.code,
                    message = current.error,
                    details =
                        current.details?.let { details ->
                            (details as? JsonPrimitive)?.takeIf { it.isString }?.content
                                ?: details.toString()
                        },
                    hint = current.hint,
                )

            is ResponseException ->
                return StoredServerError(
                    status = current.response.status.value,
                    message = current.response.status.description,
                )
        }
        current = current.cause
    }
    return null
}

private const val HTTP_FORBIDDEN = 403
private const val HTTP_CONFLICT = 409

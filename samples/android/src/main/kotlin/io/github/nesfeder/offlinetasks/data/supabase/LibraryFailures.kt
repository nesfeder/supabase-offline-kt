// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.offlinetasks.data.ClientUnavailableException
import io.github.nesfeder.offlinetasks.data.InvalidRequestException
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.RowUnreadableException
import io.github.nesfeder.offlinetasks.data.ServerRefusedException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.SessionExpiredException
import io.github.nesfeder.offlinetasks.data.SyncStoppedException
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.UnknownLibraryFailureException
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.OfflineException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.httpStatusOrNull
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive

/** HTTP's "unauthorized": a token that has expired, or none where one is needed. */
private const val HTTP_UNAUTHORIZED = 401

/** HTTP's "not found". */
private const val HTTP_NOT_FOUND = 404

/** The first status of a server failure that may pass, rather than a refusal. */
private const val HTTP_SERVER_ERROR = 500

/** What a library call was doing when it failed, so a failure can name what it was for. */
internal sealed interface LibraryCall {
    /** A read, a count or a database function. */
    data object Read : LibraryCall

    /** A replay of the mutation queue. */
    data object Sync : LibraryCall

    /** A write to [table]; [taskTitle] names the task it was for, when it carried one. */
    data class Write(
        val table: String,
        val operation: TaskMutationOperation,
        val taskTitle: String?,
    ) : LibraryCall

    /** A Storage call about the file at [objectPath]. */
    data class Storage(val objectPath: String) : LibraryCall
}

// --8<-- [start:failure-mapping]
/**
 * Every failure a library call can throw, in the app's terms: one branch for each kind, each with a
 * sentence of its own on screen. The library's own types come first, then supabase-kt's, which the
 * library passes through unchanged.
 */
internal fun Throwable.toAppFailure(call: LibraryCall): Throwable =
    when {
        this is CancellationException -> this // never a failure
        // Simulate offline is on, and the request needed the server.
        this is NetworkDisabledException -> NetworkSwitchedOffException(this)
        // A cache-only read found nothing saved for its query.
        this is CacheMissException -> NotSavedOnDeviceException(this)
        // A write the queue cannot hold found no connection; nothing was queued.
        this is MutationNotQueueableException -> WriteNeedsConnectionException(message.orEmpty(), this)
        // Why a replay stopped, writes still queued: the status and code, never the message.
        this is SyncFailedException ->
            SyncStoppedException(httpStatus, code, isNetworkOrConnectivityFailure(), this)
        // A kind of failure a later release of the library adds.
        this is OfflineException -> UnknownLibraryFailureException(this)
        // The token expired and could not be refreshed.
        httpStatusOrNull() == HTTP_UNAUTHORIZED -> SessionExpiredException(this)
        // PostgREST refused it: a write's refusal names the task, anything else its status.
        this is PostgrestRestException -> serverRefusal(call)
        // Storage refused it, or has no such file.
        this is RestException -> storageRefusal(call)
        // No connection, or no answer in time: the network, not the server.
        isNetworkOrConnectivityFailure() -> NetworkUnavailableException(this)
        // A row that does not decode into the app's type, or a value that does not encode.
        this is SerializationException -> RowUnreadableException(this)
        // Two top-level filters on one column, or a retry of a write that cannot be retried.
        this is IllegalArgumentException -> InvalidRequestException(message.orEmpty(), this)
        // The client is closed, or was built without a plugin the call needs.
        this is IllegalStateException -> ClientUnavailableException(this)
        else -> this // unexpected: reported as it is
    }
// --8<-- [end:failure-mapping]

/** PostgREST's refusal: of a write, the task it was for; of anything else, its status. */
private fun PostgrestRestException.serverRefusal(call: LibraryCall): Exception =
    when {
        call is LibraryCall.Write ->
            TaskWriteRefusedException(
                call.table,
                call.operation,
                call.taskTitle,
                toTaskServerError(),
                this,
            )
        statusCode >= HTTP_SERVER_ERROR -> ServerUnreachableException(this)
        else -> ServerRefusedException(statusCode, code, error, this)
    }

/** Storage's refusal, for the file the call was about. */
private fun RestException.storageRefusal(call: LibraryCall): Exception =
    when {
        // Storage answers a missing object with HTTP 400, which supabase-kt reads as this type.
        call is LibraryCall.Storage &&
            (this is NotFoundRestException || statusCode == HTTP_NOT_FOUND) ->
            PhotoNotFoundException(call.objectPath)
        statusCode >= HTTP_SERVER_ERROR -> ServerUnreachableException(this)
        else -> PhotoStorageException(statusCode, error, this)
    }

// --8<-- [start:reportable-failure]
/**
 * A failure as one line for the app's log: its type, the HTTP status and whether the network
 * caused it. Never the message, which may carry a row, a filter value or a token.
 */
internal fun Throwable.reportableDescription(): String =
    "${this::class.simpleName} status=${httpStatusOrNull()} " +
        "connectivity=${isNetworkOrConnectivityFailure()}"
// --8<-- [end:reportable-failure]

/** PostgREST's refusal as data: its status, the Postgres or PostgREST code and its text. */
internal fun PostgrestRestException.toTaskServerError(): TaskServerError =
    TaskServerError(
        status = statusCode,
        code = code,
        // `error` is PostgREST's own message; the exception's message also carries the request.
        message = error,
        details = (details as? JsonPrimitive)?.content ?: details?.toString(),
        hint = hint,
    )

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.supabase.offline.SyncFailedException
import io.github.nesfeder.supabase.offline.httpStatusOrNull
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import kotlinx.serialization.json.JsonPrimitive

/** HTTP's "not found". */
private const val HTTP_NOT_FOUND = 404

/**
 * A write's failure as `TaskRepository` names it: the server's refusal of a write sent at once, or
 * the network. Anything else is passed on as it is.
 */
internal fun Throwable.asWriteFailure(
    table: String,
    operation: TaskMutationOperation,
    taskTitle: String?,
): Throwable =
    when {
        this is PostgrestRestException ->
            TaskWriteRefusedException(table, operation, taskTitle, toTaskServerError(), this)
        isNetworkOrConnectivityFailure() -> NetworkUnavailableException(this)
        else -> this
    }

/** A read's failure as `TaskRepository` names it: the network, or a server that answers wrong. */
internal fun Throwable.asReadFailure(): Throwable =
    when {
        isNetworkOrConnectivityFailure() -> NetworkUnavailableException(this)
        httpStatusOrNull() != null -> ServerUnreachableException(this)
        else -> this
    }

/**
 * Why the last sync stopped, as `TaskRepository` names it: the network, or a server that answered
 * with a failure that may pass. The writes stay queued either way.
 */
internal fun SyncFailedException.asSyncFailure(): Exception =
    if (isNetworkOrConnectivityFailure()) NetworkUnavailableException(this)
    else ServerUnreachableException(this)

/** A Storage call's failure as `TaskRepository` names it, for the object at [objectPath]. */
internal fun Throwable.asStorageFailure(objectPath: String): Throwable =
    when {
        isNetworkOrConnectivityFailure() -> NetworkUnavailableException(this)
        // Storage answers a missing object with HTTP 400, which supabase-kt reads as this type.
        this is NotFoundRestException || httpStatusOrNull() == HTTP_NOT_FOUND ->
            PhotoNotFoundException(objectPath)
        this is RestException -> PhotoStorageException(statusCode, error, this)
        else -> this
    }

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

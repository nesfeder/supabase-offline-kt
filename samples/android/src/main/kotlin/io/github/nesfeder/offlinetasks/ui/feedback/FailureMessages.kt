// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.feedback

import android.content.res.Resources
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.ClientUnavailableException
import io.github.nesfeder.offlinetasks.data.InvalidRequestException
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import io.github.nesfeder.offlinetasks.data.RowFormatException
import io.github.nesfeder.offlinetasks.data.ServerRefusedException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.SessionExpiredException
import io.github.nesfeder.offlinetasks.data.SyncStoppedException
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.UnknownLibraryFailureException
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException

/** What to tell the user about [failure], in a sentence or two: each kind has its own. */
fun failureMessage(resources: Resources, failure: Throwable): String =
    when (failure) {
        is TaskWriteRefusedException ->
            resources.getString(
                R.string.failure_refused,
                failure.taskTitle?.takeIf { it.isNotBlank() }
                    ?: resources.getString(R.string.task_untitled),
                refusalReason(resources, failure.serverError),
            )
        // Before NetworkUnavailableException, which it is too.
        is NetworkSwitchedOffException -> resources.getString(R.string.failure_network_switched_off)
        is NetworkUnavailableException -> resources.getString(R.string.failure_needs_network)
        is NotSavedOnDeviceException -> resources.getString(R.string.failure_not_saved_on_device)
        is WriteNeedsConnectionException ->
            resources.getString(R.string.failure_write_needs_connection, failure.libraryMessage)
        is SyncStoppedException -> syncStoppedReason(resources, failure)
        is SessionExpiredException -> resources.getString(R.string.failure_session_expired)
        is ServerUnreachableException -> resources.getString(R.string.failure_server_unreachable)
        is ServerRefusedException ->
            resources.getString(R.string.failure_server_refused, failure.status, failure.message)
        is InvalidRequestException ->
            resources.getString(R.string.failure_invalid_request, failure.libraryMessage)
        is RowFormatException -> resources.getString(R.string.failure_row_format)
        is ClientUnavailableException -> resources.getString(R.string.failure_client_unavailable)
        is UnknownLibraryFailureException -> resources.getString(R.string.failure_unknown_library)
        is PhotoStorageException ->
            resources.getString(R.string.failure_storage, failure.status, failure.message)
        is PhotoUnreadableException -> resources.getString(R.string.photo_unreadable)
        is PhotoNotFoundException -> resources.getString(R.string.photo_missing)
        else -> resources.getString(R.string.failure_other)
    }

/** Why the last sync stopped early: the network, or the server's status and code. */
fun syncStoppedReason(resources: Resources, failure: SyncStoppedException): String {
    val status = failure.httpStatus
    return when {
        failure.isConnectivityFailure || status == null ->
            resources.getString(R.string.sync_last_stopped_network)
        else ->
            resources.getString(R.string.sync_last_stopped_server, status, failure.code.orEmpty())
    }
}

/** Why the server refused a write, in words, from the constraint or the status it gave. */
fun refusalReason(resources: Resources, error: TaskServerError): String =
    when {
        "tasks_title_length" in error.message -> resources.getString(R.string.refused_title)
        "tasks_notes_length" in error.message -> resources.getString(R.string.refused_notes)
        error.code == "23503" -> resources.getString(R.string.refused_missing_task)
        else -> resources.getString(R.string.refused_other, error.status, error.message)
    }

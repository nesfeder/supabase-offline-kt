// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.feedback

import android.content.res.Resources
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException

/** What to tell the user about [failure], in a sentence or two. */
fun failureMessage(resources: Resources, failure: Throwable): String =
    when (failure) {
        is TaskWriteRefusedException ->
            resources.getString(
                R.string.failure_refused,
                failure.taskTitle?.takeIf { it.isNotBlank() }
                    ?: resources.getString(R.string.task_untitled),
                refusalReason(resources, failure.serverError),
            )
        is NetworkUnavailableException -> resources.getString(R.string.failure_needs_network)
        is ServerUnreachableException -> resources.getString(R.string.failure_server_unreachable)
        is PhotoStorageException ->
            resources.getString(R.string.failure_storage, failure.status, failure.message)
        is PhotoUnreadableException -> resources.getString(R.string.photo_unreadable)
        is PhotoNotFoundException -> resources.getString(R.string.photo_missing)
        else -> resources.getString(R.string.failure_other)
    }

/** Why the server refused a write, in words, from the constraint or the status it gave. */
fun refusalReason(resources: Resources, error: TaskServerError): String =
    when {
        "tasks_title_length" in error.message -> resources.getString(R.string.refused_title)
        "tasks_notes_length" in error.message -> resources.getString(R.string.refused_notes)
        error.code == "23503" -> resources.getString(R.string.refused_missing_task)
        else -> resources.getString(R.string.refused_other, error.status, error.message)
    }

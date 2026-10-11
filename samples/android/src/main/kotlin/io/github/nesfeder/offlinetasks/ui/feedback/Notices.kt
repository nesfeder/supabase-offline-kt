// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.feedback

import android.content.res.Resources
import io.github.nesfeder.offlinetasks.R
import java.text.DateFormat
import java.util.Date
import kotlin.time.Instant
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** What became of work a screen started, said once on whichever screen is in front by then. */
sealed interface Notice {
    /** The server stored the task [title] at [savedAt], the row's `updated_at`, which it sets. */
    data class TaskSaved(val title: String, val savedAt: Instant) : Notice

    /** The task [title] is saved on this device, and syncs when the server can be reached. */
    data class TaskSavedOnDevice(val title: String) : Notice

    /** A copy of the task [title] was added: on the server, or on this device. */
    data class TaskDuplicated(val title: String, val savedOnDevice: Boolean) : Notice

    /** "Mark all done" changed [count] tasks on the server. */
    data class MarkedDone(val count: Int) : Notice

    /** The photo's file was replaced. */
    data object PhotoReplaced : Notice

    /** The photo moved to the task [taskTitle]. */
    data class PhotoMoved(val taskTitle: String) : Notice

    /** A copy of the photo was added to the task [taskTitle]. */
    data class PhotoCopied(val taskTitle: String) : Notice
}

/** The notices waiting to be shown; one for the whole app, as [FailureReports] is. */
class Notices {
    private val notices = Channel<Notice>(Channel.UNLIMITED)

    /** Each notice, delivered once. */
    val posted: Flow<Notice> = notices.receiveAsFlow()

    /** Posts [notice], to be shown to the user. */
    fun post(notice: Notice) {
        notices.trySend(notice)
    }
}

/** What [notice] says, in a sentence. */
fun noticeMessage(resources: Resources, notice: Notice): String =
    when (notice) {
        is Notice.TaskSaved ->
            resources.getString(
                R.string.notice_task_saved,
                notice.title,
                DateFormat.getTimeInstance(DateFormat.SHORT)
                    .format(Date(notice.savedAt.toEpochMilliseconds())),
            )
        is Notice.TaskSavedOnDevice ->
            resources.getString(R.string.notice_task_saved_on_device, notice.title)
        is Notice.TaskDuplicated ->
            resources.getString(
                if (notice.savedOnDevice) R.string.notice_task_duplicated_on_device
                else R.string.notice_task_duplicated,
                notice.title,
            )
        is Notice.MarkedDone ->
            resources.getQuantityString(R.plurals.notice_marked_done, notice.count, notice.count)
        Notice.PhotoReplaced -> resources.getString(R.string.notice_photo_replaced)
        is Notice.PhotoMoved -> resources.getString(R.string.notice_photo_moved, notice.taskTitle)
        is Notice.PhotoCopied -> resources.getString(R.string.notice_photo_copied, notice.taskTitle)
    }

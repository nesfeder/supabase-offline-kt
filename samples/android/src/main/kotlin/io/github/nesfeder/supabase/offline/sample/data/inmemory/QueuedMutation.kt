// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.data.inmemory

import io.github.nesfeder.supabase.offline.sample.data.MutationOperation
import io.github.nesfeder.supabase.offline.sample.data.Tables
import io.github.nesfeder.supabase.offline.sample.data.Task
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto

/** One write in the in-memory mutation queue, replayed against [InMemoryServer] in order. */
internal sealed interface QueuedMutation {
    /** The queue's own id, which a rejection keeps. */
    val id: Long
    val table: String
    val operation: MutationOperation

    /** The id of the row it writes, when it writes one row. */
    val rowKey: String?

    /** This write with another queue id, for a retry. */
    fun withId(newId: Long): QueuedMutation

    /** Applies the write to the device's rows, the way the overlay shows it before it syncs. */
    fun applyTo(rows: DeviceRows): DeviceRows

    /** `insert into tasks`. */
    data class InsertTask(override val id: Long, val task: Task) : QueuedMutation {
        override val table: String = Tables.TASKS
        override val operation: MutationOperation = MutationOperation.Insert
        override val rowKey: String = task.id

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.copy(tasks = rows.tasks + (task.id to task))
    }

    /** `update tasks set title, notes where id`: both fields are sent, a null notes included. */
    data class EditTask(
        override val id: Long,
        val taskId: String,
        val title: String,
        val notes: String?,
    ) : QueuedMutation {
        override val table: String = Tables.TASKS
        override val operation: MutationOperation = MutationOperation.Update
        override val rowKey: String = taskId

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.updateTask(taskId) { it.copy(title = title, notes = notes) }
    }

    /** `update tasks set done where id`. */
    data class SetTaskDone(override val id: Long, val taskId: String, val isDone: Boolean) :
        QueuedMutation {
        override val table: String = Tables.TASKS
        override val operation: MutationOperation = MutationOperation.Update
        override val rowKey: String = taskId

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.updateTask(taskId) { it.copy(isDone = isDone) }
    }

    /** `delete from tasks where id`. */
    data class DeleteTask(override val id: Long, val taskId: String) : QueuedMutation {
        override val table: String = Tables.TASKS
        override val operation: MutationOperation = MutationOperation.Delete
        override val rowKey: String = taskId

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows = rows.copy(tasks = rows.tasks - taskId)
    }

    /** `insert into task_photos`. */
    data class InsertPhoto(override val id: Long, val photo: TaskPhoto) : QueuedMutation {
        override val table: String = Tables.TASK_PHOTOS
        override val operation: MutationOperation = MutationOperation.Insert
        override val rowKey: String = photo.id

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.copy(photos = rows.photos + (photo.id to photo))
    }

    /** `delete from task_photos where id`. */
    data class DeletePhoto(override val id: Long, val photoId: String) : QueuedMutation {
        override val table: String = Tables.TASK_PHOTOS
        override val operation: MutationOperation = MutationOperation.Delete
        override val rowKey: String = photoId

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.copy(photos = rows.photos - photoId)
    }

    /** `delete from task_photos where task_id`: one write for every photo of a task. */
    data class DeleteTaskPhotos(override val id: Long, val taskId: String) : QueuedMutation {
        override val table: String = Tables.TASK_PHOTOS
        override val operation: MutationOperation = MutationOperation.Delete
        override val rowKey: String? = null

        override fun withId(newId: Long): QueuedMutation = copy(id = newId)

        override fun applyTo(rows: DeviceRows): DeviceRows =
            rows.copy(photos = rows.photos.filterValues { it.taskId != taskId })
    }
}

/** The title a queued write carries, so a rejection can say which task it was. */
internal val QueuedMutation.taskTitle: String?
    get() =
        when (this) {
            is QueuedMutation.InsertTask -> task.title
            is QueuedMutation.EditTask -> title
            else -> null
        }

/** Whether this write changes the photos of the task [taskId]. */
internal fun QueuedMutation.concernsPhotosOf(taskId: String): Boolean =
    when (this) {
        is QueuedMutation.InsertPhoto -> photo.taskId == taskId
        is QueuedMutation.DeleteTaskPhotos -> this.taskId == taskId
        // The row is gone from the device by the time it is queued, so its task is not known.
        is QueuedMutation.DeletePhoto -> true
        else -> false
    }

/** The rows of both tables as the device holds them, by id. */
internal data class DeviceRows(
    val tasks: Map<String, Task> = emptyMap(),
    val photos: Map<String, TaskPhoto> = emptyMap(),
) {
    /** These rows with [change] applied to the task [taskId], if there is one. */
    fun updateTask(taskId: String, change: (Task) -> Task): DeviceRows {
        val task = tasks[taskId] ?: return this
        return copy(tasks = tasks + (taskId to change(task)))
    }
}

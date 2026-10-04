// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.areNotesRefusedByServer
import io.github.nesfeder.offlinetasks.data.isTitleRefusedByServer
import io.github.nesfeder.offlinetasks.data.photoObjectPath
import kotlin.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A stand-in for the Supabase project: every user's rows and photo files, with the rules of the
 * sample's schema. It answers at once; [InMemoryTaskRepository] adds the latency.
 *
 * @param initialTasks rows written before this run, as a returning user has.
 */
internal class InMemoryServer(initialTasks: List<Task> = emptyList()) {
    private val lock = Mutex()
    private var tasks: Map<String, Task> = initialTasks.associateBy { it.id }
    private var photos: Map<String, TaskPhoto> = emptyMap()
    private val files: MutableMap<String, ByteArray> = mutableMapOf()

    /** The rows [userId] may read, as a select of each whole table returns them. */
    suspend fun rowsOf(userId: String): DeviceRows = lock.withLock {
        DeviceRows(
            tasks = tasks.filterValues { it.userId == userId },
            photos = photos.filterValues { it.userId == userId },
        )
    }

    /** Runs [mutation] as [userId] would through PostgREST, with the schema's checks. */
    suspend fun apply(userId: String, mutation: QueuedMutation, now: Instant): ServerAnswer =
        lock.withLock {
            when (mutation) {
                is QueuedMutation.InsertTask -> insertTask(mutation.task, now)
                is QueuedMutation.EditTask ->
                    updateTask(userId, mutation.taskId, mutation.title, now) {
                        it.copy(title = mutation.title, notes = mutation.notes)
                    }
                is QueuedMutation.SetTaskDone ->
                    updateTask(userId, mutation.taskId, title = null, now) {
                        it.copy(isDone = mutation.isDone)
                    }
                is QueuedMutation.DeleteTask -> deleteTask(userId, mutation.taskId)
                is QueuedMutation.InsertPhoto -> insertPhoto(mutation.photo)
                is QueuedMutation.DeletePhoto -> {
                    photos = photos - mutation.photoId
                    ServerAnswer.Accepted(row = null)
                }
                is QueuedMutation.DeleteTaskPhotos -> {
                    photos = photos.filterValues {
                        !(it.taskId == mutation.taskId && it.userId == userId)
                    }
                    ServerAnswer.Accepted(row = null)
                }
            }
        }

    /** Stores a photo file; refused when the path is taken, since the bucket has no upsert. */
    suspend fun upload(objectPath: String, bytes: ByteArray): Boolean = lock.withLock {
        if (objectPath in files) {
            false
        } else {
            files[objectPath] = bytes.copyOf()
            true
        }
    }

    /** The file at [objectPath], or null. */
    suspend fun download(objectPath: String): ByteArray? = lock.withLock {
        files[objectPath]?.copyOf()
    }

    /** Deletes the files at [objectPaths]; a missing one is not an error, as in Storage. */
    suspend fun deleteFiles(objectPaths: Collection<String>) {
        lock.withLock { objectPaths.forEach { files.remove(it) } }
    }

    /**
     * Deletes every row and file of [userId], as a user may delete their own under the policies.
     */
    suspend fun deleteEverythingOf(userId: String) {
        lock.withLock {
            tasks = tasks.filterValues { it.userId != userId }
            photos = photos.filterValues { it.userId != userId }
            files.keys.removeAll { it.startsWith("$userId/") }
        }
    }

    /** Whether a file is stored at [objectPath], for tests. */
    suspend fun hasFile(objectPath: String): Boolean = lock.withLock { objectPath in files }

    private fun insertTask(task: Task, now: Instant): ServerAnswer {
        refusalOf(task.title, task.notes)?.let {
            return it
        }
        if (task.id in tasks) {
            return ServerAnswer.Refused(
                TaskServerError(
                    status = 409,
                    code = "23505",
                    message = "duplicate key value violates unique constraint \"tasks_pkey\"",
                    details = null,
                    hint = null,
                )
            )
        }
        val stored = task.copy(updatedAt = now, hasPendingMutations = false)
        tasks = tasks + (task.id to stored)
        return ServerAnswer.Accepted(row = stored)
    }

    private fun updateTask(
        userId: String,
        taskId: String,
        title: String?,
        now: Instant,
        change: (Task) -> Task,
    ): ServerAnswer {
        val current = tasks[taskId]?.takeIf { it.userId == userId }
        // An update that matches no row is not an error in PostgREST: it changes nothing.
        current ?: return ServerAnswer.Accepted(row = null)
        val updated = change(current).copy(updatedAt = now)
        refusalOf(title ?: updated.title, updated.notes)?.let {
            return it
        }
        tasks = tasks + (taskId to updated)
        return ServerAnswer.Accepted(row = updated)
    }

    private fun deleteTask(userId: String, taskId: String): ServerAnswer {
        if (tasks[taskId]?.userId == userId) {
            tasks = tasks - taskId
            // The foreign key cascades to the photo rows (never to the files).
            photos = photos.filterValues { it.taskId != taskId }
        }
        return ServerAnswer.Accepted(row = null)
    }

    private fun insertPhoto(photo: TaskPhoto): ServerAnswer {
        val parent = tasks[photo.taskId]
        if (parent == null || parent.userId != photo.userId) {
            return ServerAnswer.Refused(
                TaskServerError(
                    status = 409,
                    code = "23503",
                    message =
                        "insert or update on table \"task_photos\" violates foreign key " +
                            "constraint \"task_photos_task_fkey\"",
                    details = null,
                    hint = null,
                )
            )
        }
        if (photo.objectPath != photoObjectPath(photo.userId, photo.taskId, photo.id)) {
            return checkViolation("task_photos", "task_photos_object_path_layout")
        }
        photos = photos + (photo.id to photo.copy(hasPendingMutations = false))
        return ServerAnswer.Accepted(row = null)
    }

    private fun refusalOf(title: String, notes: String?): ServerAnswer.Refused? =
        when {
            isTitleRefusedByServer(title) -> checkViolation("tasks", "tasks_title_length")
            areNotesRefusedByServer(notes) -> checkViolation("tasks", "tasks_notes_length")
            else -> null
        }

    private fun checkViolation(table: String, constraint: String): ServerAnswer.Refused =
        ServerAnswer.Refused(
            TaskServerError(
                status = 400,
                code = "23514",
                message =
                    "new row for relation \"$table\" violates check constraint \"$constraint\"",
                details = null,
                hint = null,
            )
        )
}

/** What [InMemoryServer] said to one write. */
internal sealed interface ServerAnswer {
    /** Accepted; [row] is the task as stored, when the write returns one. */
    data class Accepted(val row: Task?) : ServerAnswer

    /** Refused with [error]: nothing changed. */
    data class Refused(val error: TaskServerError) : ServerAnswer
}

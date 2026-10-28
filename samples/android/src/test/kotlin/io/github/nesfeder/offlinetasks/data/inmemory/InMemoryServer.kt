// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.areNotesRefusedByServer
import io.github.nesfeder.offlinetasks.data.isTitleRefusedByServer
import io.github.nesfeder.offlinetasks.data.photoObjectPath
import kotlin.time.Duration.Companion.microseconds
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

    /** The last `updated_at` stamped, so every version is later than the one before. */
    private var lastStamp: Instant = Instant.DISTANT_PAST

    /** The rows [userId] may read, as a select of each whole table returns them. */
    suspend fun rowsOf(userId: String): DeviceRows = lock.withLock {
        DeviceRows(
            tasks = tasks.filterValues { it.userId == userId },
            photos = photos.filterValues { it.userId == userId },
        )
    }

    /**
     * Runs [mutation] as [userId] would through PostgREST, with the schema's checks. An edit with a
     * base is preconditioned on its version: when the row has moved since, it changes nothing and
     * the answer is [ServerAnswer.Moved].
     */
    suspend fun apply(userId: String, mutation: QueuedMutation, now: Instant): ServerAnswer =
        lock.withLock {
            when (mutation) {
                is QueuedMutation.InsertTask -> insertTask(mutation.task, now)
                is QueuedMutation.EditTask -> {
                    val current = tasks[mutation.taskId]?.takeIf { it.userId == userId }
                    val base = mutation.base
                    if (base != null && current != null && current.updatedAt != base.updatedAt) {
                        return@withLock ServerAnswer.Moved(current)
                    }
                    updateTask(userId, mutation.taskId, mutation.title, now) {
                        it.copy(title = mutation.title, notes = mutation.notes)
                    }
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
                is QueuedMutation.MovePhoto -> {
                    val photo = photos[mutation.photoId]
                    if (photo != null && photo.userId == userId) {
                        val moved =
                            photo.copy(taskId = mutation.taskId, objectPath = mutation.objectPath)
                        photos = photos + (photo.id to moved)
                    }
                    ServerAnswer.Accepted(row = null)
                }
                is QueuedMutation.UploadPhoto ->
                    if (mutation.objectPath in files) {
                        ServerAnswer.Refused(
                            TaskServerError(409, null, "The resource already exists", null, null)
                        )
                    } else {
                        files[mutation.objectPath] = mutation.jpeg.copyOf()
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

    /**
     * Applies [change] to task [taskId] as another device's update would, with a new `updated_at`;
     * false when there is no such task.
     */
    suspend fun changeElsewhere(taskId: String, now: Instant, change: (Task) -> Task): Boolean =
        lock.withLock {
            val task = tasks[taskId] ?: return@withLock false
            tasks = tasks + (taskId to change(task).copy(updatedAt = stamp(now)))
            true
        }

    /**
     * Deletes task [taskId] and its photo rows as another device would; false when there is none.
     */
    suspend fun deleteElsewhere(taskId: String): Boolean = lock.withLock {
        if (taskId !in tasks) return@withLock false
        tasks = tasks - taskId
        photos = photos.filterValues { it.taskId != taskId }
        true
    }

    /** The task with [taskId] as the server holds it, or null. */
    suspend fun task(taskId: String): Task? = lock.withLock { tasks[taskId] }

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

    /** Marks every open task of [userId] done, as `complete_all_tasks` does; returns how many. */
    suspend fun completeAllTasks(userId: String, now: Instant): Int = lock.withLock {
        val open = tasks.values.filter { it.userId == userId && !it.isDone }
        open.forEach { tasks = tasks + (it.id to it.copy(isDone = true, updatedAt = stamp(now))) }
        open.size
    }

    /** Replaces the file at [objectPath] in place; false when there is none. */
    suspend fun replaceFile(objectPath: String, bytes: ByteArray): Boolean = lock.withLock {
        if (objectPath !in files) return@withLock false
        files[objectPath] = bytes.copyOf()
        true
    }

    /** Moves the file at [from] to [to]; false when there is none or [to] is taken. */
    suspend fun moveFile(from: String, to: String): Boolean = lock.withLock {
        val bytes = files[from]
        if (bytes == null || to in files) return@withLock false
        files.remove(from)
        files[to] = bytes
        true
    }

    /** Copies the file at [from] to [to]; false when there is none or [to] is taken. */
    suspend fun copyFile(from: String, to: String): Boolean = lock.withLock {
        val bytes = files[from]
        if (bytes == null || to in files) return@withLock false
        files[to] = bytes.copyOf()
        true
    }

    /** The paths of [userId]'s files, by folder. */
    suspend fun filesOf(userId: String): Map<String, List<Pair<String, Int>>> = lock.withLock {
        files
            .filterKeys { it.startsWith("$userId/") }
            .map { (path, bytes) -> path to bytes.size }
            .groupBy { (path, _) -> path.removePrefix("$userId/").substringBefore('/') }
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
        val stored = task.copy(updatedAt = stamp(now), hasPendingMutations = false)
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
        val updated = change(current).copy(updatedAt = stamp(now))
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

    /**
     * A new `updated_at` at [now], or just after the last one when the clock has not moved past it:
     * two changes in one tick of the test's clock would otherwise share a version.
     */
    private fun stamp(now: Instant): Instant {
        lastStamp = if (now > lastStamp) now else lastStamp + 1.microseconds
        return lastStamp
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

    /** The precondition failed: the row is at another version now, [current]. Nothing changed. */
    data class Moved(val current: Task) : ServerAnswer
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens read and write, for the signed-in user. It names no Android type, so it
 * would compile in `commonMain` unchanged.
 *
 * Reads are live: each returns a flow of [Snapshot]s served from the device first and refreshed
 * from the server when it can be reached. Writes change the device's copy at once and wait in the
 * mutation queue until the server has them, so they work offline; photo uploads are the exception
 * and need the network.
 *
 * **What throws.** Every implementation throws in the same places, so the screens handle one
 * contract:
 * - A write sent at once, because the network is up and nothing waits ahead of it, throws
 *   [TaskWriteRefusedException] when the server refuses it; the device's copy goes back to the
 *   server's. A write that waited in the queue never throws: a refusal found on replay goes to
 *   [rejectedMutations]. A write never throws for a missing network either: it queues.
 * - A call that needs the server now ([refreshTasks]) throws [NetworkUnavailableException] while
 *   the network is off, including while the app switched it off itself, and
 *   [ServerUnreachableException] when the server does not answer.
 * - Photo uploads, downloads and deletes throw Storage's refusals as [PhotoStorageException], and
 *   fail at once with [NetworkUnavailableException] while the network is off.
 *
 * The exceptions named here are the sample's own. A repository built on the library maps the
 * library's onto them: a refused write's `PostgrestRestException` onto [TaskWriteRefusedException],
 * `NetworkDisabledException` and connectivity failures onto [NetworkUnavailableException], and
 * Storage's `RestException` onto [PhotoStorageException]. Anything may also fail in a way no one
 * planned for; the screens report every failure as a message, so none of them crashes the app.
 *
 * **Cancellation.** A call that has started a write finishes it even if its caller is cancelled: a
 * screen that closes mid-delete cannot leave a photo's file deleted and its row in place. The
 * caller only stops waiting for the result.
 */
interface TaskRepository {
    /** The user's tasks, newest first. */
    fun tasks(): Flow<TaskListSnapshot>

    /**
     * The task with [id], as a select filtered on it returns it: a list of at most one row, empty
     * once the device or the server says it does not exist.
     */
    fun task(id: String): Flow<TaskListSnapshot>

    /** The photos of the task with [taskId], oldest first. */
    fun photos(taskId: String): Flow<PhotoListSnapshot>

    /** Every photo the user has, newest first. */
    fun allPhotos(): Flow<PhotoListSnapshot>

    /**
     * Adds a task with a new id and the device's clock as its creation time.
     *
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun addTask(title: String, notes: String?): TaskMutationOutcome

    /**
     * Sets a task's title and notes; null [notes] clears them on the server too.
     *
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun updateTask(id: String, title: String, notes: String?): TaskMutationOutcome

    /**
     * Marks a task done or open again.
     *
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun setTaskDone(id: String, isDone: Boolean): TaskMutationOutcome

    /**
     * Deletes a task: its photo files first (only when online; offline they stay in the bucket),
     * then its photo rows, then the task.
     *
     * @throws PhotoStorageException when Storage refuses to delete the files; nothing is deleted.
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun deleteTask(id: String): TaskMutationOutcome

    /**
     * Uploads [photo] for the task and then writes its row, reporting progress. The flow fails with
     * [NetworkUnavailableException] at once while the network is off, and with
     * [ServerUnreachableException] or [PhotoStorageException] when the upload fails.
     */
    fun attachPhoto(taskId: String, photo: EncodedPhoto): Flow<PhotoUpload>

    /**
     * Deletes a photo's file and then its row. The file needs the network.
     *
     * @throws NetworkUnavailableException while the network is off; nothing is deleted.
     * @throws ServerUnreachableException when the server does not answer; nothing is deleted.
     * @throws PhotoStorageException when Storage refuses; nothing is deleted.
     */
    suspend fun deletePhoto(photoId: String): TaskMutationOutcome

    /** The photo's file if it is on this device, without touching the network. */
    suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile?

    /**
     * The photo's file, downloaded and kept on this device if it is not there yet.
     *
     * @throws NetworkUnavailableException when it is not on the device and the network is off.
     * @throws ServerUnreachableException when the server does not answer.
     * @throws PhotoNotFoundException when the bucket has no such file.
     * @throws PhotoStorageException when Storage refuses.
     */
    suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile

    /** How many writes wait in the mutation queue, for one [table] or all of them. */
    fun pendingMutationCount(table: String? = null): Flow<Int>

    /** The writes the server refused on replay, newest first, until dismissed or retried. */
    fun rejectedMutations(): Flow<List<RejectedTaskMutation>>

    /** Forgets a refused write. */
    suspend fun dismissRejectedMutation(id: Long)

    /**
     * Queues a refused write again, at the back of the queue. It is sent by a replay, so a second
     * refusal comes back through [rejectedMutations], never as an exception.
     */
    suspend fun retryRejectedMutation(id: Long)

    /**
     * Replays the mutation queue now, in order; returns at once when the server is out of reach.
     */
    suspend fun syncPendingMutations(): TaskSyncReport

    /**
     * The network, the queue and the last replay, as one observable value. Its network is current
     * as soon as [setNetworkEnabled] returns; the counts catch up with a write shortly after it, so
     * wait for them rather than reading them straight after.
     */
    val syncStatus: StateFlow<TaskSyncStatus>

    /**
     * Asks the server for the task list again, as pull-to-refresh does.
     *
     * @throws NetworkUnavailableException while the network is off.
     * @throws ServerUnreachableException when the server does not answer.
     */
    suspend fun refreshTasks()

    /** Switches the app's own network access off ("Simulate offline") or back on. */
    suspend fun setNetworkEnabled(enabled: Boolean)

    /** Deletes everything this device holds: cached rows and files, queued and refused writes. */
    suspend fun clearLocalData()

    /**
     * Starts again as a new anonymous user with no tasks. While online it first deletes the current
     * user's own photo files and rows from the server, so nothing is left behind there; offline
     * they stay. Then it clears local data, signs out and signs in again.
     */
    suspend fun startOver()
}

/**
 * Failures a server can be made to have on purpose, so the app can show what each looks like. Only
 * the in-memory repository has them.
 */
interface ServerFaults {
    /** True while every request to the server fails. */
    val isServerOutage: StateFlow<Boolean>

    /** True while Storage refuses every upload, download and delete. */
    val isStorageFailing: StateFlow<Boolean>

    /** Starts or ends the outage. */
    fun setServerOutage(active: Boolean)

    /** Starts or stops Storage's refusals. */
    fun setStorageFailing(active: Boolean)
}

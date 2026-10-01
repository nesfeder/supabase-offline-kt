// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.data

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

    /** Adds a task with a new id and the device's clock as its creation time. */
    suspend fun addTask(title: String, notes: String?): MutationOutcome

    /** Sets a task's title and notes; null [notes] clears them on the server too. */
    suspend fun updateTask(id: String, title: String, notes: String?): MutationOutcome

    /** Marks a task done or open again. */
    suspend fun setTaskDone(id: String, isDone: Boolean): MutationOutcome

    /**
     * Deletes a task: its photo files first (only when online; offline they stay in the bucket),
     * then its photo rows, then the task.
     */
    suspend fun deleteTask(id: String): MutationOutcome

    /**
     * Uploads [photo] for the task and then writes its row. The upload needs the network: offline
     * the flow ends at once with [PhotoUpload.Failed].
     */
    fun attachPhoto(taskId: String, photo: EncodedPhoto): Flow<PhotoUpload>

    /** Deletes a photo's file and then its row. Needs the network for the file. */
    suspend fun deletePhoto(photoId: String): MutationOutcome

    /** The photo's file if it is on this device, without touching the network. */
    suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile?

    /**
     * The photo's file, downloaded and kept on this device if it is not there yet.
     *
     * @throws NetworkUnavailableException when it is not on the device and the network is off.
     */
    suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile

    /** How many writes wait in the mutation queue, for one [table] or all of them. */
    fun pendingMutationCount(table: String? = null): Flow<Int>

    /** The writes the server refused, newest first, until dismissed or retried. */
    fun rejectedMutations(): Flow<List<RejectedMutation>>

    /** Forgets a refused write. */
    suspend fun dismissRejectedMutation(id: Long)

    /** Queues a refused write again, at the back of the queue. */
    suspend fun retryRejectedMutation(id: Long)

    /** Replays the mutation queue now, in order; returns at once when the server is out of reach. */
    suspend fun syncPendingMutations(): SyncReport

    /** The network, the queue and the last replay, as one observable value. */
    val syncStatus: StateFlow<SyncStatus>

    /** Asks the server for the task list again, as pull-to-refresh does. */
    suspend fun refreshTasks()

    /** Switches the app's own network access off ("Simulate offline") or back on. */
    suspend fun setNetworkEnabled(enabled: Boolean)

    /** Deletes everything this device holds: cached rows and files, queued and refused writes. */
    suspend fun clearLocalData()

    /** Clears local data and signs in again as a new anonymous user, with no tasks. */
    suspend fun startOver()
}

/**
 * A server that can be made to fail on purpose, so the app can show what a stalled refresh looks
 * like. Only the in-memory repository has one.
 */
interface ServerOutageSwitch {
    /** True while every request to the server fails. */
    val isServerOutage: StateFlow<Boolean>

    /** Starts or ends the outage. */
    fun setServerOutage(active: Boolean)
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the screens read and write, for the signed-in user. It names no Android type, so it
 * would compile in `commonMain` unchanged.
 *
 * Reads are live: each returns a flow of [RowsSnapshot]s served from the device first and refreshed
 * from the server when it can be reached. Writes change the device's copy at once and wait in the
 * mutation queue until the server has them, so they work offline. The library never queues a
 * Storage write, so a photo added offline waits in the app's own queue instead ([addPhoto]); every
 * other Storage call needs the network.
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
 * - Photo downloads, deletes and the other Storage calls throw Storage's refusals as
 *   [PhotoStorageException], and fail at once with [NetworkUnavailableException] while the network
 *   is off. An upload never throws: a pending photo's failure is kept on its record.
 * - A read whose rows cannot be read (a row the app cannot decode, a server that answers wrong)
 *   fails its flow; it never turns into an empty list.
 *
 * Everything belongs to the signed-in user. Before there is a session, every read shows nothing
 * yet, and a call that needs the user's id throws [IllegalStateException]: the app offers none.
 *
 * The exceptions named here are the sample's own. A repository built on the library maps the
 * library's onto them: a refused write's `PostgrestRestException` onto [TaskWriteRefusedException],
 * `NetworkDisabledException` and connectivity failures onto [NetworkUnavailableException], and
 * Storage's `RestException` onto [PhotoStorageException]. Anything may also fail in a way no one
 * planned for; the screens report every failure as a message, so none of them crashes the app.
 *
 * **Cancellation.** A call that has started a write finishes it even if its caller is canceled: a
 * screen that closes mid-delete cannot leave a photo's file deleted and its row in place. The
 * caller only stops waiting for the result. Pending photos upload in the repository's own scope,
 * whatever screen is open; one whose row the server refuses at once has its file deleted too.
 */
interface TaskRepository {
    /**
     * The user's tasks that [query] selects, newest first. The read's strategy follows
     * [SyncSettings.preferFreshData].
     */
    fun tasks(query: TaskQuery = TaskQuery()): Flow<TaskListSnapshot>

    /**
     * The task with [id], as [tasks] holds it: a list of at most one row, empty once the device or
     * the server says it does not exist.
     */
    fun task(id: String): Flow<TaskListSnapshot>

    /**
     * The photos of the task with [taskId], oldest first, its pending photos among them (each with
     * a [TaskPhoto.pendingUpload]).
     */
    fun photos(taskId: String): Flow<PhotoListSnapshot>

    /** Every photo the user has, newest first, pending photos among them. */
    fun allPhotos(): Flow<PhotoListSnapshot>

    /**
     * The newest [count] photos the server has, a page of the Photos grid, with every pending
     * photo; asking for more reads a new page.
     */
    fun photoPage(count: Int): Flow<PhotoListSnapshot>

    /**
     * How many photos each of [taskIds] has, pending photos counted, for the list's rows; a task
     * with none is absent.
     */
    fun photoCounts(taskIds: List<String>): Flow<Map<String, Int>>

    /**
     * Adds a task with a new id and the device's clock as its creation time. With [needsConnection]
     * it is sent now or not at all, and never queued.
     *
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     * @throws NetworkUnavailableException with [needsConnection], when the server cannot be
     *   reached.
     */
    suspend fun addTask(
        title: String,
        notes: String?,
        needsConnection: Boolean = false,
    ): NewTaskOutcome

    /**
     * Adds a copy of the task with [id], under a new id, as the device holds it.
     *
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun duplicateTask(id: String): NewTaskOutcome

    /** Adds five sample tasks in one write. */
    suspend fun addSampleTasks(): TaskMutationOutcome

    /**
     * Adds the starter list's tasks, each under an id of its own for this user: a second import
     * changes nothing, on the device or on the server.
     */
    suspend fun importStarterList(): TaskMutationOutcome

    /**
     * Adds a task whose title the server refuses: online it is refused at once and thrown; offline
     * it waits, and is refused when it replays.
     */
    suspend fun addTaskWithBlankTitle(): TaskMutationOutcome

    /**
     * Deletes this week's done tasks with one grouped filter. The queue cannot hold such a delete,
     * so it is sent now.
     *
     * @throws WriteNeedsConnectionException when the server cannot be reached; nothing is queued.
     */
    suspend fun deleteThisWeeksDoneTasks(): TaskMutationOutcome

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
     * Deletes a task: its photo files (only when online; offline they stay in the bucket), then its
     * pending photos on this device, its photo rows, and the task.
     *
     * @throws PhotoStorageException when Storage refuses to delete the files; nothing is deleted.
     * @throws TaskWriteRefusedException when it was sent at once and the server refused it.
     */
    suspend fun deleteTask(id: String): TaskMutationOutcome

    /**
     * Files [photo] for the task [taskId] in the app's pending-photo queue on this device, and
     * returns: the photo is in [photos] at once, and uploads when the network allows, its row
     * written only once Storage has its file. Online or offline alike; it throws only when the
     * device cannot save the file.
     */
    suspend fun addPhoto(taskId: String, photo: EncodedPhoto)

    /** Uploads the failed pending photo [photoId] again, as soon as the network allows. */
    suspend fun retryPendingPhoto(photoId: String)

    /**
     * Deletes the pending photo [photoId] from this device; false when there is none, because it
     * finished uploading while this waited for it.
     */
    suspend fun removePendingPhoto(photoId: String): Boolean

    /**
     * Deletes [photo]'s file and then its row. The file needs the network.
     *
     * @throws NetworkUnavailableException while the network is off; nothing is deleted.
     * @throws ServerUnreachableException when the server does not answer; nothing is deleted.
     * @throws PhotoStorageException when Storage refuses; nothing is deleted.
     */
    suspend fun deletePhoto(photo: TaskPhoto): TaskMutationOutcome

    /** The photo's file if it is on this device, without touching the network. */
    suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile?

    /** The photo's file as a `file:` URI if it is on this device, without touching the network. */
    suspend fun photoUriIfCached(photo: TaskPhoto): String?

    /**
     * The photo's file as a `file:` URI, downloaded and kept on this device if it is not there yet.
     *
     * @throws NetworkUnavailableException when it is not on the device and the network is off.
     */
    suspend fun photoUri(photo: TaskPhoto): String

    /**
     * The photo's file on this device, downloaded first if needed, as a path to share it from.
     *
     * @throws NetworkUnavailableException when it is not on the device and the network is off.
     */
    suspend fun photoFileToShare(photo: TaskPhoto): String

    /**
     * What Storage and this device know about the photo's file.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun photoDetails(photo: TaskPhoto): PhotoDetails

    /**
     * A link anyone can open for an hour, to a copy of the photo 512 pixels wide.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun photoShareLink(photo: TaskPhoto): String

    /**
     * Links anyone can open for an hour, one for each of [photos].
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun photoShareLinks(photos: List<TaskPhoto>): List<String>

    /**
     * Replaces the photo's file with [replacement], in place: its row and path stay.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun replacePhoto(photo: TaskPhoto, replacement: EncodedPhoto)

    /**
     * Moves the photo to the task [taskId]: its file first, then its row.
     *
     * @throws NetworkUnavailableException while the network is off; nothing moves.
     */
    suspend fun movePhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome

    /**
     * Adds a copy of the photo to the task [taskId]: a copy of its file, then a row for it.
     *
     * @throws NetworkUnavailableException while the network is off; nothing is copied.
     */
    suspend fun copyPhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome

    /**
     * One page of the user's task folders in the bucket, from the [offset]th, with their files.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun listBucket(offset: Int): BucketListing

    /**
     * Adds a copy of the newest photo to its task, uploaded through a signed upload URL instead of
     * the session; null when there is no photo to copy.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun uploadThroughSignedUrl(): TaskPhoto?

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

    /** How many writes wait in the mutation queue now, read once, as the app starts. */
    suspend fun pendingMutationCountNow(): Int

    /** The writes waiting in the mutation queue, in the order they will be sent. */
    fun waitingChanges(): Flow<List<WaitingChange>>

    /**
     * Removes a waiting write without sending it: its rows show again as the server last sent them.
     */
    suspend fun discardWaitingChange(id: Long)

    /** The writes the server refused on replay, newest first, until dismissed or retried. */
    fun rejectedMutations(): Flow<List<RejectedTaskMutation>>

    /** The refused writes as they stand now, newest first, read once. */
    suspend fun rejectedMutationsNow(): List<RejectedTaskMutation>

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
    val syncStatus: StateFlow<AppSyncStatus>

    /**
     * Asks the server for the task list again, as pull-to-refresh does, and says where the rows
     * came from: the server, or the device's copy when the server could not be reached.
     *
     * @param query the list to read again: each query is cached on its own, so it is the one the
     *   screen shows.
     * @throws NetworkUnavailableException while the network is off and nothing is saved.
     * @throws ServerUnreachableException when the server does not answer and nothing is saved.
     */
    suspend fun refreshTasks(query: TaskQuery = TaskQuery()): ReadProvenance

    /**
     * How many open tasks the device holds, read once as the app starts, from the device's copy
     * when it is a day old at most and from the server otherwise; null when neither has it.
     */
    suspend fun openTaskCountNow(): Int?

    /** The Sync tab's switches. */
    val settings: StateFlow<SyncSettings>

    /** Chooses how the task list reads: the device's copy first, or the server first. */
    fun setPreferFreshData(prefer: Boolean)

    /** Turns applying other devices' changes as they happen on or off. */
    fun setLiveUpdates(enabled: Boolean)

    /** Where applying other devices' changes stands. */
    val liveUpdatesStatus: StateFlow<LiveUpdatesStatus>

    /**
     * Marks every open task done, on the server, through a database function; returns how many it
     * changed. Functions always run on the server.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun markAllDone(): Int

    /**
     * Asks the server how many tasks the user has and how many are done, through a count and a
     * database function. Both always run on the server.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun serverCounts(): ServerCounts

    /**
     * Counts the user's open tasks on the server. A count is never saved on the device.
     *
     * @throws NetworkUnavailableException while the network is off.
     */
    suspend fun countOpenTasksOnServer(): Int

    /**
     * Reads with two filters on one column, which the library refuses before sending anything.
     *
     * @throws InvalidRequestException always.
     */
    suspend fun readWithTwoFiltersOnOneColumn()

    /** Switches the app's own network access off ("Simulate offline") or back on. */
    suspend fun setNetworkEnabled(enabled: Boolean)

    /**
     * Deletes everything this device holds: cached rows and files, queued and refused writes, and
     * the pending photos. An open list then reads its rows again from the server, once the network
     * allows.
     */
    suspend fun clearLocalData()

    /**
     * Starts again as a new anonymous user with no tasks: clears local data, which stops a replay
     * already running, then deletes the current user's own photo files and rows from the server, so
     * nothing is left behind there, then deletes the pending photos, signs out and signs in again.
     * No pending photo uploads meanwhile. If a delete fails, its failure is thrown: the library's
     * data is cleared, the pending photos and what was not deleted on the server are kept, and the
     * same user stays signed in, so Start over can be tried again.
     *
     * @throws NetworkUnavailableException while the network is off; nothing is deleted or cleared.
     */
    suspend fun startOver()
}

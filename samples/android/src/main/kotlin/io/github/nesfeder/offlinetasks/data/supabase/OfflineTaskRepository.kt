// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.BucketListing
import io.github.nesfeder.offlinetasks.data.ConflictChoice
import io.github.nesfeder.offlinetasks.data.ConflictedTaskMutation
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PHOTOS_BUCKET
import io.github.nesfeder.offlinetasks.data.PendingPhotoState
import io.github.nesfeder.offlinetasks.data.PendingUpload
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.PhotoListSnapshot
import io.github.nesfeder.offlinetasks.data.ReadProvenance
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.ServerCounts
import io.github.nesfeder.offlinetasks.data.SyncSettings
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.UserSession
import io.github.nesfeder.offlinetasks.data.WaitingChange
import io.github.nesfeder.offlinetasks.data.photoObjectPath
import io.github.nesfeder.offlinetasks.data.takeCharacters
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingUpload as QueuedUpload
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RejectedUpload
import io.github.nesfeder.supabase.offline.applyServerRecord
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.rpc
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The schema both tables live in, the client's default. */
private const val SCHEMA = "public"

/** HTTP's "conflict": Storage already has a file at the path. */

/** How many photo rows one read of their object paths asks for. */
private const val PATH_PAGE_SIZE = 1_000

/** How old the device's copy may be and still answer pull to refresh when the server cannot. */
private val REFRESH_MAX_AGE = 1.days

/** How old the device's copy may be and still answer the launch badge without the server. */
private val LAUNCH_COUNT_MAX_AGE = 1.days

/** The titles "Add five sample tasks" adds. */
private val SAMPLE_TITLES =
    listOf(
        "Pick up the dry cleaning",
        "Book a haircut",
        "Fix the bike light",
        "Return the library books",
        "Email the landlord",
    )

/** The starter list "Import the starter list" adds, once per user. */
private val STARTER_TITLES =
    listOf("Water the plants", "Call the dentist", "Buy milk", "Back up the laptop")

/**
 * [TaskRepository] over the library: reads are live `selectAsFlow` queries or one-shot `select`s,
 * every write goes through the mutation queue unless it says otherwise, and photos go through the
 * offline Storage bucket, which keeps the files it downloads on the device.
 *
 * Reads wait for a session: the schema refuses every request without a signed-in user, so until
 * [session] has one, each read shows nothing yet and sends nothing.
 *
 * A photo is uploaded through the library's queue: its file, kept on the device while it waits,
 * then its row, a write queued behind it, so the row is never sent before its file. A refused
 * upload does not hold back the row writes queued after it: the photo's row is sent all the same,
 * and the photo shows marked failed until it is retried or removed. The device's copy shows at
 * once, offline too.
 *
 * @param scope where work runs that must finish even when its caller goes away.
 * @param failedPhotoDirectory where a photo whose upload failed when it was sent at once is kept,
 *   with its bytes, for Retry or Remove: the library keeps nothing of an upload it threw.
 */
class OfflineTaskRepository(
    private val supabase: SupabaseClient,
    private val session: UserSession,
    private val scope: CoroutineScope,
    failedPhotoDirectory: File,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString() },
) : TaskRepository {

    private val offline = supabase.offline
    private val files = TaskPhotoFiles(offline)
    private val switches = MutableStateFlow(SyncSettings())

    /** The photos being added whose rows are not written yet, by photo id, with their sends. */
    private val photosBeingAdded = MutableStateFlow<Map<String, PhotoBeingAdded>>(emptyMap())

    /** The photos whose upload failed when it was sent at once, kept here with their bytes. */
    private val failedAdds = FailedPhotoAdds(failedPhotoDirectory, scope)

    override val settings: StateFlow<SyncSettings> = switches.asStateFlow()

    // --8<-- [start:realtime-status]
    /** Where the plugin's channel stands, in the app's five words. */
    override val liveUpdatesStatus: StateFlow<LiveUpdatesStatus> =
        offline.realtimeStatus
            .map { it.toLiveUpdatesStatus() }
            .stateIn(
                scope,
                SharingStarted.Eagerly,
                offline.realtimeStatus.value.toLiveUpdatesStatus(),
            )

    // --8<-- [end:realtime-status]

    override val syncStatus: StateFlow<AppSyncStatus> =
        offline.syncStatus
            .map { it.toAppSyncStatus() }
            .stateIn(scope, SharingStarted.Eagerly, offline.syncStatus.value.toAppSyncStatus())

    // --8<-- [start:reactive-strategies]
    /** The list's live read, with the strategy the "Prefer fresh data" switch picks. */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun tasks(query: TaskQuery): Flow<TaskListSnapshot> =
        switches
            .map { it.preferFreshData }
            .distinctUntilChanged()
            .flatMapLatest { preferFresh ->
                val strategy =
                    if (preferFresh) FlowReadStrategy.NetworkFirstThenCache // ask first
                    else FlowReadStrategy.CacheFirstThenNetwork // the default: the device first
                taskList(query, strategy) { it }
            }

    // --8<-- [end:reactive-strategies]

    // --8<-- [start:row-from-list]
    /**
     * One task, from the list's own query rather than one filtered on its id: each query is cached
     * on its own, and offline a query never asked has nothing to show, while the list is.
     */
    override fun task(id: String): Flow<TaskListSnapshot> =
        taskList(TaskQuery(), FlowReadStrategy.CacheFirstThenNetwork) { tasks ->
            tasks.filter { it.id == id }
        }

    // --8<-- [end:row-from-list]

    override fun photos(taskId: String): Flow<PhotoListSnapshot> =
        storedPhotos(taskId)
            .withPhotosBeingAdded(newestFirst = false) { it.taskId == taskId }
            .withUploadStates()

    // --8<-- [start:cache-only-photos]
    /**
     * A task's photo rows, from the query of [allPhotos]. While Simulate offline is on, the read
     * takes the device's copy alone and sends nothing; the screen says so.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun storedPhotos(taskId: String): Flow<PhotoListSnapshot> =
        offline.syncStatus
            .map { it.network == NetworkStatus.DISABLED }
            .distinctUntilChanged()
            .flatMapLatest { switchedOff ->
                val strategy =
                    if (switchedOff) FlowReadStrategy.CacheOnly
                    else FlowReadStrategy.CacheFirstThenNetwork
                photoList(strategy, page = null) { photos ->
                    photos.filter { it.taskId == taskId }.sortedBy { it.createdAt }
                }
            }

    // --8<-- [end:cache-only-photos]

    override fun allPhotos(): Flow<PhotoListSnapshot> =
        photoList(FlowReadStrategy.CacheFirstThenNetwork, page = null) { it }
            .withPhotosBeingAdded(newestFirst = true) { true }
            .withUploadStates()

    override fun photoPage(count: Int): Flow<PhotoListSnapshot> =
        photoList(FlowReadStrategy.CacheFirstThenNetwork, page = count) { it }.withUploadStates()

    // --8<-- [start:pending-photo-merge]
    /**
     * These photos with where their uploads stand, from the library's lists: waiting in its queue,
     * or refused by Storage. A photo added here shows at once as its queued row, drawn from the
     * library's copy of its file on the device; a waiting upload marks it until Storage has it.
     */
    private fun Flow<PhotoListSnapshot>.withUploadStates(): Flow<PhotoListSnapshot> =
        combine(this, offline.storage.pendingUploads(), offline.storage.rejectedUploads()) {
            snapshot,
            waiting,
            refused ->
            val waitingPaths =
                waiting.filter { it.bucketId == PHOTOS_BUCKET }.mapTo(HashSet()) { it.path }
            val refusals = refused.filter { it.bucketId == PHOTOS_BUCKET }.associateBy { it.path }
            snapshot.copy(
                value =
                    snapshot.value?.map { photo ->
                        val refusal = refusals[photo.objectPath]
                        when {
                            refusal != null ->
                                photo.copy(
                                    pendingUpload =
                                        PendingUpload(
                                            PendingPhotoState.Failed,
                                            refusal.message,
                                            refusal.isRetryable,
                                            refusal.localPath,
                                        )
                                )
                            photo.objectPath in waitingPaths ->
                                photo.copy(pendingUpload = PendingUpload(PendingPhotoState.Waiting))
                            else -> photo
                        }
                    }
            )
        }

    // --8<-- [end:pending-photo-merge]

    /**
     * These photos with the ones [addPhoto] is adding that [belongs] takes and whose rows are not
     * listed yet, marked waiting (online, the upload goes at once and the row after it, and the
     * photo shows meanwhile, drawn from the library's copy of its file), and the ones whose upload
     * failed when it was sent at once, marked failed, drawn from the copy kept here.
     */
    private fun Flow<PhotoListSnapshot>.withPhotosBeingAdded(
        newestFirst: Boolean,
        belongs: (TaskPhoto) -> Boolean,
    ): Flow<PhotoListSnapshot> =
        combine(this, photosBeingAdded, failedAdds.adds, session.userId) {
            snapshot,
            adding,
            failed,
            userId ->
            val listed = snapshot.value ?: return@combine snapshot
            val ids = listed.mapTo(HashSet()) { it.id }
            val extra =
                (adding.values.map { it.photo } +
                        failed.values.filter { it.userId == userId }.map { it.toTaskPhoto() })
                    .distinctBy { it.id }
                    .filter { it.id !in ids && belongs(it) }
            if (extra.isEmpty()) return@combine snapshot
            val photos = listed + extra
            snapshot.copy(
                value =
                    if (newestFirst) photos.sortedByDescending { it.createdAt }
                    else photos.sortedBy { it.createdAt }
            )
        }

    /** How many photos each of [taskIds] has, the rows queued on this device among them. */
    override fun photoCounts(taskIds: List<String>): Flow<Map<String, Int>> {
        if (taskIds.isEmpty()) return flowOf(emptyMap())
        return photoRowsOf(taskIds).map { rows ->
            rows.orEmpty().groupingBy { it.taskId }.eachCount()
        }
    }

    // --8<-- [start:photo-rows-in]
    /**
     * The photo rows of [taskIds]: one live read, by `in` on `task_id`. A set of tasks never read
     * online has no rows offline, so it is null for it.
     */
    private fun photoRowsOf(taskIds: List<String>): Flow<List<PhotoTaskRow>?> {
        val ids = taskIds.sorted() // the same tasks, the same query, the same cached rows
        return whileSignedIn(before = null) {
            offline[Tables.TASK_PHOTOS] // the same builder as from(Tables.TASK_PHOTOS)
                .selectAsFlow(Columns.list(ID, TASK_ID)) { filter { isIn(TASK_ID, ids) } }
                .map { snapshot -> snapshot.result?.decodeList<PhotoTaskRow>() }
        }
    }

    // --8<-- [end:photo-rows-in]

    // --8<-- [start:first-write]
    /** Adds a task; [needsConnection] picks the write's strategy. */
    override suspend fun addTask(
        title: String,
        notes: String?,
        needsConnection: Boolean,
    ): NewTaskOutcome {
        val row = newTaskRow(title, notes)
        val outcome =
            write(Tables.TASKS, TaskMutationOperation.Insert, title) {
                offline.from(Tables.TASKS).insert(row, mutationStrategyFor(needsConnection)) {
                    select() // the stored row back, with the times the server set
                }
            }
        return when (outcome) {
            is MutationOutcome.Applied -> {
                val stored = outcome.result.decodeSingle<TaskRecord>()
                // --8<-- [start:apply-record]
                // An online-only write is never patched into the cache: the stored row goes there,
                // applied as a record Realtime reports is.
                if (needsConnection) offline.applyServerRecord(Tables.TASKS, stored)
                // --8<-- [end:apply-record]
                NewTaskOutcome.Saved(outcome.result.decodeSingle<TaskRow>().toTask(emptySet()))
            }
            is MutationOutcome.Queued -> NewTaskOutcome.SavedOnDevice(row.id) // sent when it can be
        }
    }

    // --8<-- [end:first-write]

    // --8<-- [start:mutation-strategies]
    /** The "Needs a connection" switch: queued when offline (the default), or sent now or never. */
    private fun mutationStrategyFor(needsConnection: Boolean): MutationStrategy =
        if (needsConnection) MutationStrategy.OnlineOnly else MutationStrategy.QueueIfOffline

    // --8<-- [end:mutation-strategies]

    // --8<-- [start:typed-update]
    /** Autosave: the title and notes as one typed value; null notes clear them on the server. */
    override suspend fun updateTask(
        id: String,
        title: String,
        notes: String?,
    ): TaskMutationOutcome =
        write(Tables.TASKS, TaskMutationOperation.Update, title) {
                offline.from(Tables.TASKS).update(TaskTextPatch(title, notes)) {
                    filter { eq(ID, id) }
                }
            }
            .toTaskMutationOutcome()

    // --8<-- [end:typed-update]

    // --8<-- [start:body-update]
    /** The done switch: the change as a JSON body, one column. */
    override suspend fun setTaskDone(id: String, isDone: Boolean): TaskMutationOutcome =
        write(Tables.TASKS, TaskMutationOperation.Update, taskTitle = null) {
                offline.from(Tables.TASKS).update(buildJsonObject { put(DONE, isDone) }) {
                    filter { eq(ID, id) }
                }
            }
            .toTaskMutationOutcome()

    // --8<-- [end:body-update]

    // --8<-- [start:duplicate-task]
    /** A copy of the task under a new id, written with `upsert`: a new key makes a new row. */
    override suspend fun duplicateTask(id: String): NewTaskOutcome {
        val original =
            task(id).first { it.value != null }.value?.firstOrNull()
                ?: throw NotSavedOnDeviceException()
        val title = takeCharacters("${original.title} (copy)", TASK_TITLE_MAX_LENGTH)
        val copy = newTaskRow(title, original.notes, done = original.isDone)
        val outcome =
            write(Tables.TASKS, TaskMutationOperation.Upsert, title) {
                offline.from(Tables.TASKS).upsert(copy) { select() }
            }
        if (outcome.isQueued) return NewTaskOutcome.SavedOnDevice(copy.id) // no answer yet
        val stored = checkNotNull(outcome.resultOrNull()).decodeSingle<TaskRow>()
        return NewTaskOutcome.Saved(stored.toTask(pendingIds = emptySet()))
    }

    // --8<-- [end:duplicate-task]

    // --8<-- [start:insert-list]
    /** Five tasks in one write: one request online, one queued write offline. */
    override suspend fun addSampleTasks(): TaskMutationOutcome {
        val rows = SAMPLE_TITLES.map { title -> newTaskRow(title, notes = null) }
        return write(Tables.TASKS, TaskMutationOperation.Insert, rows.first().title) {
                offline.from(Tables.TASKS).insert(rows)
            }
            .toTaskMutationOutcome()
    }

    // --8<-- [end:insert-list]

    // --8<-- [start:starter-list]
    /**
     * The starter list, each task under an id made from the user's: imported twice, the second
     * changes nothing, since the server keeps the row it has for each id.
     */
    override suspend fun importStarterList(): TaskMutationOutcome {
        val userId = signedInUserId()
        val rows = STARTER_TITLES.mapIndexed { index, title ->
            newTaskRow(title, null, id = starterId(userId, index))
        }
        return write(Tables.TASKS, TaskMutationOperation.Upsert, rows.first().title) {
                offline.from(Tables.TASKS).upsert(rows) {
                    onConflict = ID // the column that decides a duplicate
                    ignoreDuplicates = true // keep the stored row, edited or not
                }
            }
            .toTaskMutationOutcome()
    }

    // --8<-- [end:starter-list]

    // --8<-- [start:json-insert]
    /** A task with a blank title, as a JSON body the server's title check refuses. */
    override suspend fun addTaskWithBlankTitle(): TaskMutationOutcome {
        val body = buildJsonArray {
            addJsonObject {
                put(ID, newId())
                put(USER_ID, signedInUserId())
                put(TITLE, "   ") // the server refuses a title with no text
                put(CREATED_AT, clock.now().toString())
            }
        }
        return write(Tables.TASKS, TaskMutationOperation.Insert, taskTitle = null) {
                offline.from(Tables.TASKS).insert(body)
            }
            .toTaskMutationOutcome()
    }

    // --8<-- [end:json-insert]

    // --8<-- [start:same-column-write]
    /**
     * This week's done tasks, deleted with one grouped filter. The queue cannot record a group, so
     * the write is sent at once, and refused with no connection rather than queued.
     */
    override suspend fun deleteThisWeeksDoneTasks(): TaskMutationOutcome {
        val week = recentWindow(clock.now())
        return write(Tables.TASKS, TaskMutationOperation.Delete, taskTitle = null) {
                offline.from(Tables.TASKS).delete {
                    filter {
                        eq(DONE, true)
                        and {
                            gte(CREATED_AT, week.from.toString())
                            lt(CREATED_AT, week.until.toString())
                        }
                    }
                }
            }
            .toTaskMutationOutcome()
    }

    // --8<-- [end:same-column-write]

    // --8<-- [start:delete-task]
    /** A task's photo files (online only), then its photo rows, then the task itself. */
    override suspend fun deleteTask(id: String): TaskMutationOutcome = detached {
        val photos = cachedPhotosOf(id)
        val folder = photoFolderOf(id)
        // Its uploads still waiting on this device first: a discard waits for one on the wire, so
        // none lands after the files are deleted
        val waiting = discardWaitingUploadsOf(folder)
        // Then the files, and only online: Storage writes are never queued, so offline they stay.
        // A waiting upload that landed meanwhile goes too, though no row of the server names it
        if (canReachNetwork())
            deleteFiles(
                ((photos?.map { it.objectPath } ?: photoPathsOnServer(TASK_ID, id)) + waiting)
                    .distinct()
            )
        // Then its refused uploads, and its photos that failed at once, which nothing will show
        // now; a failure above keeps them
        dismissRefusedUploadsOf(folder)
        failedAdds.forgetAll { it.taskId == id }
        // The server's cascade would delete the photo rows, but the cache never sees a cascade.
        if (photos == null || photos.isNotEmpty()) {
            write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
                offline.from(Tables.TASK_PHOTOS).delete { filter { eq(TASK_ID, id) } }
            }
        }
        write(Tables.TASKS, TaskMutationOperation.Delete, taskTitle = null) {
                offline.from(Tables.TASKS).delete { filter { eq(ID, id) } }
            }
            .toTaskMutationOutcome()
    }

    // --8<-- [end:delete-task]

    // --8<-- [start:pending-photo-add]
    /**
     * Add photo, online or offline: the file through the library's queue, under the path it will
     * have in the bucket, then the photo's row, queued behind it. Offline both wait, the row shown
     * at once and the file from the device; a later sync sends the file first. Online the file is
     * sent at once, and the photo shows marked waiting until its row is written. Run in this
     * repository's scope, so a screen that closes between the two cannot leave the file without its
     * row; a failure is the caller's.
     */
    override suspend fun addPhoto(taskId: String, photo: EncodedPhoto) {
        val userId = signedInUserId()
        val photoId = newId() // the device's, so the path is known before the upload
        val path = photoObjectPath(userId, taskId, photoId)
        val createdAt = clock.now()
        val shown =
            TaskPhoto(
                id = photoId,
                userId = userId,
                taskId = taskId,
                objectPath = path,
                width = photo.width,
                height = photo.height,
                createdAt = createdAt,
                hasPendingMutations = true,
                pendingUpload = PendingUpload(PendingPhotoState.Waiting),
            )
        send(shown, photo.jpeg)
    }

    /**
     * Sends [shown]'s file and then writes its row, the photo shown waiting meanwhile. When the
     * upload sent at once fails, the library has kept nothing of it, so the photo is kept here,
     * failed, with Retry and Remove, and nothing is thrown: the photo says why.
     */
    private suspend fun send(shown: TaskPhoto, jpeg: ByteArray) {
        val adding =
            detachedLater<Unit> {
                val failure =
                    try {
                        storage(shown.objectPath) { files.upload(shown.objectPath, jpeg) }
                        null
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (failure: Exception) {
                        failure
                    }
                if (failure != null) {
                    failedAdds.keep(shown.toFailedAdd(failure), jpeg)
                    return@detachedLater
                }
                failedAdds.forget(shown.id)
                insertPhotoRow(
                    shown.id,
                    shown.userId,
                    shown.taskId,
                    shown.objectPath,
                    shown.width,
                    shown.height,
                    shown.createdAt,
                )
            }
        photosBeingAdded.update { it + (shown.id to PhotoBeingAdded(shown, adding)) }
        adding.invokeOnCompletion { photosBeingAdded.update { it - shown.id } }
        adding.start()
        adding.await().getOrThrow()
    }

    /** This photo as a failed add, failed with [failure]. */
    private fun TaskPhoto.toFailedAdd(failure: Exception) =
        FailedPhotoAdd(
            photoId = id,
            userId = userId,
            taskId = taskId,
            objectPath = objectPath,
            width = width,
            height = height,
            createdAt = createdAt.toString(),
            failure = failure.message,
        )

    /** This failed add as the lists show it: failed, with Retry, drawn from its kept file. */
    private fun FailedPhotoAdd.toTaskPhoto() =
        TaskPhoto(
            id = photoId,
            userId = userId,
            taskId = taskId,
            objectPath = objectPath,
            width = width,
            height = height,
            createdAt = Instant.parse(createdAt),
            hasPendingMutations = true,
            pendingUpload =
                PendingUpload(
                    PendingPhotoState.Failed,
                    failure,
                    retryable = true,
                    keptFile = failedAdds.fileOf(photoId).path,
                ),
        )

    // --8<-- [end:pending-photo-add]

    // --8<-- [start:pending-photo-upload]
    /**
     * Queues the refused upload of [photoId]'s file again, at the end of the user's queue; or, for
     * a photo whose upload failed when it was sent at once, adds it again from its kept bytes.
     */
    override suspend fun retryPendingPhoto(photoId: String) {
        val failed = failedAdds.adds.value[photoId]
        if (failed != null) {
            val jpeg = failedAdds.bytesOf(photoId) ?: return failedAdds.forget(photoId)
            val again = failed.toTaskPhoto()
            return send(again.copy(pendingUpload = PendingUpload(PendingPhotoState.Waiting)), jpeg)
        }
        val refused = refusedUploadOf(photoId) ?: return
        storage(refused.path) { offline.retryRejectedMutation(refused.id) }
    }

    /**
     * Gives up the photo [photoId] before Storage has its file: its waiting or refused upload, its
     * row's queued writes, and then its row and its file wherever a send that ended meanwhile left
     * them, so neither is left behind. A photo still being added is given up once that ends.
     */
    override suspend fun removePendingPhoto(photoId: String): Boolean = detached {
        // Its file is sent or queued and its row written first, or the add would outlive this
        val beingAdded = photosBeingAdded.value[photoId]
        beingAdded?.adding?.join()
        // One whose upload failed at once is only on this device
        if (photoId in failedAdds.adds.value) {
            failedAdds.forget(photoId)
            return@detached true
        }
        val waiting = waitingUploadOf(photoId)
        val refused = if (waiting == null) refusedUploadOf(photoId) else null
        val path =
            waiting?.path ?: refused?.path ?: beingAdded?.photo?.objectPath ?: return@detached false
        storage(path) {
            // Waits for an upload on the wire to end; one that landed is no longer pending
            if (waiting != null) offline.discardPendingMutation(waiting.id)
            if (refused != null) offline.dismissRejectedMutation(refused.id)
        }
        discardQueuedWritesOf(Tables.TASK_PHOTOS, photoId)
        if (canReachNetwork()) deleteFilesQuietly(listOf(path))
        write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
            offline.from(Tables.TASK_PHOTOS).delete { filter { eq(ID, photoId) } }
        }
        true
    }

    // --8<-- [end:pending-photo-upload]

    /** The upload of [photoId]'s file still waiting in the queue, or null. */
    private suspend fun waitingUploadOf(photoId: String): QueuedUpload? =
        offline.storage.pendingUploads().first().firstOrNull { it.isOfPhoto(photoId) }

    /** The refused upload of [photoId]'s file on the rejected list, or null. */
    private suspend fun refusedUploadOf(photoId: String): RejectedUpload? =
        offline.storage.rejectedUploadList().firstOrNull { refused ->
            refused.bucketId == PHOTOS_BUCKET && refused.path.endsWith("/$photoId.jpg")
        }

    /** Whether this waiting upload is the file of the photo [photoId]. */
    private fun QueuedUpload.isOfPhoto(photoId: String): Boolean =
        bucketId == PHOTOS_BUCKET && path.endsWith("/$photoId.jpg")

    /** Discards the queued writes to [table]'s row [rowId], unsent. */
    private suspend fun discardQueuedWritesOf(table: String, rowId: String) {
        offline.pendingMutations(table).first().forEach { mutation ->
            val forRow =
                mutation.rowKeys.any { key -> key[ID]?.jsonPrimitive?.contentOrNull == rowId }
            if (forRow) offline.discardPendingMutation(mutation.id)
        }
    }

    /**
     * Discards the uploads of the files under [folder] still waiting in the queue: a task deleted
     * on this device takes the photos it was adding with it. A discard waits for an upload on the
     * wire to end, so none is on its way once this returns.
     *
     * @return the object paths of those uploads, any of which may have landed meanwhile.
     */
    private suspend fun discardWaitingUploadsOf(folder: String): List<String> {
        val waiting =
            offline.storage.pendingUploads().first().filter { upload ->
                upload.bucketId == PHOTOS_BUCKET && upload.path.startsWith(folder)
            }
        waiting.forEach { upload -> offline.discardPendingMutation(upload.id) }
        return waiting.map { it.path }
    }

    /** Dismisses the refused uploads of the files under [folder], with their files. */
    private suspend fun dismissRefusedUploadsOf(folder: String) {
        offline.storage.rejectedUploadList().forEach { refused ->
            if (refused.bucketId == PHOTOS_BUCKET && refused.path.startsWith(folder)) {
                offline.dismissRejectedMutation(refused.id)
            }
        }
    }

    /** The bucket folder of task [taskId]'s photos: `<user id>/<task id>/`. */
    private fun photoFolderOf(taskId: String): String = "${signedInUserId()}/$taskId/"

    override suspend fun deletePhoto(photo: TaskPhoto): TaskMutationOutcome = detached {
        requireNetwork()
        deleteFiles(listOf(photo.objectPath))
        write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
                offline.from(Tables.TASK_PHOTOS).delete { filter { eq(ID, photo.id) } }
            }
            .toTaskMutationOutcome()
    }

    override suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile? =
        storage(photo.objectPath) { files.cachedFile(photo.objectPath) }

    override suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile =
        storage(photo.objectPath) { files.ensureCachedFile(photo.objectPath) }

    // A failed photo is drawn from the file kept for it: its rejected entry's, which the cache
    // never answers since Storage may hold other bytes at its path, or the app's own
    override suspend fun photoUriIfCached(photo: TaskPhoto): String? =
        photo.keptFile()?.toURI()?.toString()
            ?: storage(photo.objectPath) { files.uriIfCached(photo.objectPath) }

    override suspend fun photoUri(photo: TaskPhoto): String =
        photo.keptFile()?.toURI()?.toString()
            ?: storage(photo.objectPath) { files.uri(photo.objectPath) }

    override suspend fun photoFileToShare(photo: TaskPhoto): String =
        photo.keptFile()?.path ?: storage(photo.objectPath) { files.file(photo.objectPath).path }

    /** The failed upload's file on the device this photo is drawn from, or null. */
    private fun TaskPhoto.keptFile(): File? =
        pendingUpload?.takeIf { it.state == PendingPhotoState.Failed }?.keptFile?.let(::File)

    override suspend fun photoDetails(photo: TaskPhoto): PhotoDetails =
        storage(photo.objectPath) { files.details(photo.objectPath) }

    override suspend fun photoShareLink(photo: TaskPhoto): String =
        storage(photo.objectPath) { files.shareLink(photo.objectPath) }

    override suspend fun photoShareLinks(photos: List<TaskPhoto>): List<String> {
        if (photos.isEmpty()) return emptyList()
        return storage(photos.first().objectPath) { files.shareLinks(photos.map { it.objectPath }) }
    }

    override suspend fun replacePhoto(photo: TaskPhoto, replacement: EncodedPhoto) = detached {
        requireNetwork()
        storage(photo.objectPath) { files.replace(photo.objectPath, replacement.jpeg) }
    }

    // --8<-- [start:move-photo]
    /** The file moves to the other task's folder first, then the row follows it, in one update. */
    override suspend fun movePhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome =
        detached {
            requireNetwork()
            val newPath = photoObjectPath(photo.userId, taskId, photo.id)
            storage(photo.objectPath) { files.move(photo.objectPath, newPath) }
            write(Tables.TASK_PHOTOS, TaskMutationOperation.Update, taskTitle = null) {
                    offline.from(Tables.TASK_PHOTOS).update({
                        set(TASK_ID, taskId)
                        set(OBJECT_PATH, newPath) // the schema ties the path to the task
                    }) {
                        filter { eq(ID, photo.id) }
                    }
                }
                .toTaskMutationOutcome()
        }

    // --8<-- [end:move-photo]

    // --8<-- [start:copy-photo]
    /** A copy of the file in the other task's folder, under a new id, then a row for it. */
    override suspend fun copyPhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome =
        detached {
            requireNetwork()
            val copyId = newId()
            val copyPath = photoObjectPath(photo.userId, taskId, copyId)
            storage(photo.objectPath) { files.copy(photo.objectPath, copyPath) }
            insertPhotoRow(copyId, photo.userId, taskId, copyPath, photo.width, photo.height)
            TaskMutationOutcome.Applied
        }

    // --8<-- [end:copy-photo]

    override suspend fun listBucket(offset: Int): BucketListing {
        requireNetwork()
        val userId = signedInUserId()
        return storage(userId) { files.list(userId, offset) }
    }

    override suspend fun uploadThroughSignedUrl(): TaskPhoto? = detached {
        requireNetwork()
        signedInUserId() // a photo needs its user: before a session there is none to copy
        val newest =
            allPhotos().first { it.value != null }.value?.firstOrNull { it.pendingUpload == null }
        if (newest == null) return@detached null
        val copied = downloadPhotoFile(newest).path
        val jpeg = withContext(Dispatchers.IO) { File(copied).readBytes() }
        val photoId = newId()
        val path = photoObjectPath(newest.userId, newest.taskId, photoId)
        storage(path) { files.uploadThroughSignedUrl(path, jpeg) }
        insertPhotoRow(photoId, newest.userId, newest.taskId, path, newest.width, newest.height)
    }

    // --8<-- [start:pending-count]
    /** The queue's size as it changes, for [table] alone or for every table when it is null. */
    override fun pendingMutationCount(table: String?): Flow<Int> =
        offline.pendingMutationCountFlow(table)

    /** The queue's size, read once; the status carries it too, once it has counted the queue. */
    override suspend fun pendingMutationCountNow(): Int = offline.pendingMutationCount()

    // --8<-- [end:pending-count]

    // --8<-- [start:pending]
    /** Every write in the queue, in the order it will be sent, as rows of the Sync tab. */
    override fun waitingChanges(): Flow<List<WaitingChange>> =
        offline.pendingMutations().map { mutations -> mutations.map { it.toWaitingChange() } }

    // --8<-- [end:pending]

    // --8<-- [start:discard]
    /** Discard: the write is never sent, and its rows show again as the server last sent them. */
    override suspend fun discardWaitingChange(id: Long) {
        offline.discardPendingMutation(id)
    }

    // --8<-- [end:discard]

    // --8<-- [start:rejected-list]
    /** The refused writes, newest first (the library lists the oldest first), until dismissed. */
    override fun rejectedMutations(): Flow<List<RejectedTaskMutation>> =
        offline.rejectedMutations().map { list ->
            list.asReversed().map { it.toRejectedTaskMutation() }
        }

    // --8<-- [end:rejected-list]

    // --8<-- [start:first-rejection]
    /** The refused writes as they stand now: the Tasks tab's alert at launch reads them once. */
    override suspend fun rejectedMutationsNow(): List<RejectedTaskMutation> =
        offline.rejectedMutations().first().asReversed().map { it.toRejectedTaskMutation() }

    // --8<-- [end:first-rejection]

    // --8<-- [start:rejected-actions]
    /** Dismiss: the refused write is forgotten for good. */
    override suspend fun dismissRejectedMutation(id: Long) {
        offline.dismissRejectedMutation(id)
    }

    /** Retry: queued again at the back, and sent by a pass; a second refusal returns here. */
    override suspend fun retryRejectedMutation(id: Long) {
        try {
            offline.retryRejectedMutation(id)
        } catch (notRetryable: IllegalArgumentException) {
            throw notRetryable.toAppFailure(LibraryCall.Sync) // its body was too long to keep
        }
    }

    // --8<-- [end:rejected-actions]

    // --8<-- [start:resolve-conflict]
    /**
     * The writes parked because their task changed on the server meanwhile (the client's resolver
     * holds a title both sides changed), newest first: the library lists the oldest first.
     */
    override fun conflictedMutations(): Flow<List<ConflictedTaskMutation>> =
        offline.conflictedMutations().map { list ->
            list.asReversed().map { it.toConflictedTaskMutation() }
        }

    /**
     * Keep mine queues the write again, preconditioned on the server's row as the conflict saw it,
     * and asks for a pass; Keep theirs drops it, and the server's row stands on the device too.
     */
    override suspend fun resolveConflictedMutation(id: Long, choice: ConflictChoice) {
        val resolution =
            when (choice) {
                ConflictChoice.KeepMine -> ConflictResolution.KeepLocal
                ConflictChoice.KeepTheirs -> ConflictResolution.KeepServer
            }
        try {
            offline.resolveConflictedMutation(id, resolution)
        } catch (cannotKeep: IllegalArgumentException) {
            throw cannotKeep.toAppFailure(LibraryCall.Sync) // the row is gone, or too long to keep
        }
    }

    // --8<-- [end:resolve-conflict]

    // --8<-- [start:sync-now]
    /** Sync now: sends what waits, in order, and reports every count of what happened. */
    override suspend fun syncPendingMutations(): TaskSyncReport =
        offline.syncPendingMutations().toTaskSyncReport() // returns promptly offline

    // --8<-- [end:sync-now]

    // --8<-- [start:first-read]
    /**
     * Pull to refresh: the server's answer when it can be reached, else the device's copy if it is
     * a day old at most; the answer says which it was.
     */
    override suspend fun refreshTasks(query: TaskQuery): ReadProvenance = read {
        offline
            .from(Tables.TASKS)
            .select(readStrategy = ReadStrategy.NetworkFirst(maxAge = REFRESH_MAX_AGE)) {
                tasksMatching(query, recentWindow(clock.now())) // the shown list's own query
            }
            .toReadProvenance()
    }

    // --8<-- [end:first-read]

    // --8<-- [start:badge-read]
    /**
     * The open-task badge at launch: from the device's copy when it is a day old at most, so it
     * shows at once, and from the server otherwise; null when neither can answer.
     */
    override suspend fun openTaskCountNow(): Int? {
        if (session.userId.value == null) return null
        val window = recentWindow(clock.now())
        return try {
            offline
                .from(Tables.TASKS)
                .select(readStrategy = ReadStrategy.CacheFirst(maxAge = LAUNCH_COUNT_MAX_AGE)) {
                    tasksMatching(TaskQuery(), window)
                }
                .decodeList<TaskRow>()
                .count { !it.done }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Offline with no copy a day old: no badge, rather than an error at launch.
            val appFailure = failure.toAppFailure(LibraryCall.Read)
            if (appFailure is NetworkUnavailableException) null else throw appFailure
        }
    }

    // --8<-- [end:badge-read]

    // --8<-- [start:rpc-no-params]
    /**
     * Mark all done: one database function, on the server. Its changes reach the device's lists as
     * any other device's do: through Live updates, or the next refresh.
     */
    override suspend fun markAllDone(): Int = read {
        offline.rpc("complete_all_tasks").decodeAs<Int>() // how many it changed
    }

    // --8<-- [end:rpc-no-params]

    // --8<-- [start:rpc-typed]
    /** The Server section: a count, and a database function called with its parameters twice. */
    override suspend fun serverCounts(): ServerCounts = read {
        val since = recentWindow(clock.now()).from
        ServerCounts(
            // Typed parameters, encoded by the client's serializer; null counts every task.
            done =
                offline
                    .rpc("tasks_done_count", DoneCountParameters(createdSince = null))
                    .decodeAs(),
            total = countTasks(),
            // The same function with parameters as JSON.
            doneThisWeek =
                offline
                    .rpc(
                        "tasks_done_count",
                        buildJsonObject { put("created_since", since.toString()) },
                    )
                    .decodeAs(),
        )
    }

    // --8<-- [end:rpc-typed]

    // --8<-- [start:count]
    /**
     * How many tasks the server holds for the user: a count is never cached, so it needs the
     * server.
     */
    private suspend fun countTasks(): Int =
        offline
            .from(SCHEMA, Tables.TASKS) // the default schema named: from(table) is the same
            .select(Columns.list(ID)) {
                head = true // the count alone, no rows
                count(Count.EXACT)
            }
            .countOrNull()
            ?.toInt() ?: 0

    // --8<-- [end:count]

    // --8<-- [start:operator-filter]
    /** Try it: a count with the generic filter, naming its operator, `eq`, as a value. */
    override suspend fun countOpenTasksOnServer(): Int = read {
        offline[SCHEMA, Tables.TASKS] // from(SCHEMA, Tables.TASKS), as an index
            .select(Columns.list(ID)) {
                head = true
                count(Count.EXACT)
                filter { filter(DONE, FilterOperator.EQ, false) }
            }
            .countOrNull()
            ?.toInt() ?: 0
    }

    // --8<-- [end:operator-filter]

    // --8<-- [start:two-filters-refused]
    /**
     * Try it: two top-level filters on one column. supabase-kt would send only the first, so the
     * library refuses the read before anything is sent, online and offline alike.
     */
    override suspend fun readWithTwoFiltersOnOneColumn() {
        val week = recentWindow(clock.now())
        read {
            offline.from(Tables.TASKS).select {
                filter {
                    gte(CREATED_AT, week.from.toString())
                    lt(CREATED_AT, week.until.toString()) // refused: write them in one `and`
                }
            }
        }
    }

    // --8<-- [end:two-filters-refused]

    // --8<-- [start:network-switch]
    /** Simulate offline: the library's network switch, which nothing else on the device notices. */
    override suspend fun setNetworkEnabled(enabled: Boolean) {
        if (enabled) offline.enableNetwork() else offline.disableNetwork()
        // The library's status is current when the call returns; this one follows it.
        syncStatus.first { (it.network == AppNetworkStatus.Disabled) != enabled }
    }

    // --8<-- [end:network-switch]

    // --8<-- [start:pending-photo-clear]
    // --8<-- [start:clear-local-data]
    /**
     * Clear local cache: everything the library keeps here, queued writes included, never sent, and
     * the photos waiting to upload with them.
     */
    override suspend fun clearLocalData() {
        offline.clearLocalData() // an open list fetches again by itself once the network allows
        failedAdds.forgetAll()
    }

    // --8<-- [end:clear-local-data]

    // --8<-- [start:start-over]
    /** Start over: clear first, so no queued write lands after the deletes; then a new user. */
    override suspend fun startOver() {
        // A new anonymous user needs the server, so nothing is deleted or cleared without it.
        if (!canReachNetwork()) throw NetworkUnavailableException()
        detached {
            val userId = session.userId.value
            // The photos waiting to upload go first: a discard waits for one on the wire, which
            // the clear does not, so none lands after the deletes; one that landed is deleted too
            val waiting = userId?.let { discardWaitingUploadsOf("$it/") }.orEmpty()
            // Stops a replay already running, and drops the rest of the queue, refused uploads too
            offline.clearLocalData()
            failedAdds.forgetAll()
            userId?.let { deleteEverythingOf(it, alsoDelete = waiting) }
            session.signInAsNewUser()
        }
    }

    // --8<-- [end:start-over]
    // --8<-- [end:pending-photo-clear]

    override fun setPreferFreshData(prefer: Boolean) {
        switches.update { it.copy(preferFreshData = prefer) }
    }

    // --8<-- [start:live-updates-switch]
    /**
     * Live updates: the plugin's own switch, which leaves the channel at once and joins it again as
     * soon as the network allows (reading both tables again, since Realtime replays nothing). The
     * switch is kept for the app's run, as the plugin keeps its own.
     */
    override fun setLiveUpdates(enabled: Boolean) {
        switches.update { it.copy(liveUpdates = enabled) }
        scope.launch { if (enabled) offline.enableRealtime() else offline.disableRealtime() }
    }

    // --8<-- [end:live-updates-switch]

    // --8<-- [start:first-observed-read]
    /** The user's tasks that [query] selects, as a live read mapped to the app's snapshot. */
    private fun taskList(
        query: TaskQuery,
        strategy: FlowReadStrategy,
        shown: (List<Task>) -> List<Task>,
    ): Flow<TaskListSnapshot> {
        val recent = recentWindow(clock.now()) // read once: the request must not read the clock
        return whileSignedIn(before = nothingYet()) {
            offline
                .from(Tables.TASKS)
                .selectAsFlow(readStrategy = strategy) { tasksMatching(query, recent) }
                .map { snapshot ->
                    val pending = snapshot.pendingRowIds() // marked from this emission's own rows
                    snapshot.toRowsSnapshot { result ->
                        // A queued task is listed after the server's rows; show it where it sorts.
                        shown(result.decodeList<TaskRow>().map { it.toTask(pending) }.newestFirst())
                    }
                }
        }
    }

    // --8<-- [end:first-observed-read]

    // --8<-- [start:photos-page]
    /** The user's photos, newest first: all of them, or the first [page] when the grid pages. */
    private fun photoList(
        strategy: FlowReadStrategy,
        page: Int?,
        shown: (List<TaskPhoto>) -> List<TaskPhoto>,
    ): Flow<PhotoListSnapshot> =
        whileSignedIn(before = nothingYet()) {
            offline
                .from(Tables.TASK_PHOTOS)
                .selectAsFlow(readStrategy = strategy) {
                    newestFirst()
                    if (page != null) range(0L, page - 1L) // each page size is a query of its own
                }
                .map { snapshot ->
                    val pending = snapshot.pendingRowIds()
                    snapshot.toRowsSnapshot { result ->
                        val photos =
                            result.decodeList<TaskPhotoRow>().map { it.toTaskPhoto(pending) }
                        shown(photos.sortedByDescending { it.createdAt })
                    }
                }
        }

    // --8<-- [end:photos-page]

    /**
     * [read] while there is a session; before, [before] alone, and nothing is sent. A failure ends
     * the read in the app's terms, as every other call's does.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> whileSignedIn(before: T, read: () -> Flow<T>): Flow<T> =
        session.userId
            .flatMapLatest { userId -> if (userId == null) flowOf(before) else read() }
            .catch { failure -> throw failure.toAppFailure(LibraryCall.Read) }
            .flowOn(Dispatchers.Default)

    /** Runs [write] and puts its failure in the app's terms. */
    private suspend fun write(
        table: String,
        operation: TaskMutationOperation,
        taskTitle: String?,
        write: suspend () -> MutationOutcome,
    ): MutationOutcome =
        try {
            write()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.toAppFailure(LibraryCall.Write(table, operation, taskTitle))
        }

    /** Runs a read, a count or a function, and puts its failure in the app's terms. */
    private suspend fun <T> read(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.toAppFailure(LibraryCall.Read)
        }

    /** Runs a Storage call about [objectPath], and puts its failure in the app's terms. */
    private suspend fun <T> storage(objectPath: String, block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.toAppFailure(LibraryCall.Storage(objectPath))
        }

    /** A new task row, made on the device: its id, its owner and its creation time. */
    private fun newTaskRow(
        title: String,
        notes: String?,
        done: Boolean = false,
        id: String = newId(),
    ): NewTaskRow =
        NewTaskRow(
            id = id,
            userId = signedInUserId(),
            title = title,
            notes = notes,
            done = done,
            createdAt = clock.now().toString(),
        )

    /**
     * Writes the row of a photo whose file is in the bucket. A refusal at once (its task deleted on
     * the server meanwhile, say) leaves a file no row will name, so the file goes too.
     */
    private suspend fun insertPhotoRow(
        photoId: String,
        userId: String,
        taskId: String,
        objectPath: String,
        width: Int?,
        height: Int?,
        createdAt: Instant = clock.now(),
    ): TaskPhoto {
        val row =
            NewTaskPhotoRow(
                id = photoId,
                userId = userId,
                taskId = taskId,
                objectPath = objectPath,
                width = width,
                height = height,
                createdAt = createdAt.toString(),
            )
        try {
            write(Tables.TASK_PHOTOS, TaskMutationOperation.Insert, taskTitle = null) {
                offline.from(Tables.TASK_PHOTOS).insert(row)
            }
        } catch (refused: TaskWriteRefusedException) {
            deleteUploadedFile(objectPath, refused)
            throw refused
        }
        return TaskPhoto(
            id = photoId,
            userId = userId,
            taskId = taskId,
            objectPath = objectPath,
            width = width,
            height = height,
            createdAt = Instant.parse(row.createdAt),
        )
    }

    /**
     * Deletes the file just uploaded to [objectPath] when its row was [refused]. Best effort: a
     * failure to delete it is kept on [refused], which is what the caller is told.
     */
    private suspend fun deleteUploadedFile(objectPath: String, refused: Exception) {
        try {
            files.delete(listOf(objectPath))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            refused.addSuppressed(failure)
        }
    }

    // --8<-- [start:cache-only]
    /**
     * The photo rows of task [taskId] the device holds, from the query [allPhotos] reads, or null
     * when it holds none for that query: a cache-only read never sends a request.
     */
    private suspend fun cachedPhotosOf(taskId: String): List<TaskPhotoRow>? =
        try {
            offline
                .from(Tables.TASK_PHOTOS)
                .select(readStrategy = ReadStrategy.CacheOnly()) { newestFirst() }
                .decodeList<TaskPhotoRow>()
                .filter { it.taskId == taskId }
        } catch (missing: CacheMissException) {
            null // nothing saved for the query
        }

    // --8<-- [end:cache-only]

    // --8<-- [start:network-only]
    /**
     * The object paths of the photo rows whose [column] equals [value], asked of the server a page
     * at a time until a page comes back empty: a project answers at most its row limit (1,000
     * unless changed) to one request, so one read could miss some.
     */
    private suspend fun photoPathsOnServer(column: String, value: String): List<String> {
        val paths = mutableListOf<String>()
        while (true) {
            val from = paths.size.toLong()
            val page = read {
                offline
                    .from(Tables.TASK_PHOTOS)
                    .select(Columns.list(OBJECT_PATH), readStrategy = ReadStrategy.NetworkOnly) {
                        filter { eq(column, value) }
                        order(ID, Order.ASCENDING)
                        range(from, from + PATH_PAGE_SIZE - 1)
                    }
                    .decodeList<ObjectPathRow>()
            }
            paths += page.map { it.objectPath }
            if (page.isEmpty()) return paths
        }
    }

    // --8<-- [end:network-only]

    /** [deleteFiles], best effort: a file left behind names a photo no row names any more. */
    private suspend fun deleteFilesQuietly(paths: List<String>) {
        try {
            deleteFiles(paths)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Nothing shows it, and the bucket may still be asked to delete it later
        }
    }

    /** Deletes the files at [paths] from the bucket; a missing file is no error. */
    private suspend fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        storage(paths.first()) { files.delete(paths) }
    }

    // --8<-- [start:delete-my-data]
    /** Deletes [userId]'s files, photo rows and tasks on the server, before that user is left. */
    private suspend fun deleteEverythingOf(userId: String, alsoDelete: List<String> = emptyList()) {
        deleteFiles((photoPathsOnServer(USER_ID, userId) + alsoDelete).distinct())
        // Sent now or not at all: queued, they would outlive the user they are for.
        write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
            offline.from(Tables.TASK_PHOTOS).delete(
                mutationStrategy = MutationStrategy.OnlineOnly
            ) {
                filter { eq(USER_ID, userId) }
            }
        }
        write(Tables.TASKS, TaskMutationOperation.Delete, taskTitle = null) {
            offline.from(Tables.TASKS).delete(mutationStrategy = MutationStrategy.OnlineOnly) {
                filter { eq(USER_ID, userId) }
            }
        }
    }

    // --8<-- [end:delete-my-data]

    /** Whether the library may reach the network: not switched off, and not known to be offline. */
    private fun canReachNetwork(): Boolean =
        offline.syncStatus.value.network.let {
            it != NetworkStatus.OFFLINE && it != NetworkStatus.DISABLED
        }

    /**
     * Fails at once when the library may not reach the network: the Storage writes but a photo's
     * upload never queue.
     */
    private fun requireNetwork() {
        when (offline.syncStatus.value.network) {
            NetworkStatus.DISABLED -> throw NetworkSwitchedOffException()
            NetworkStatus.OFFLINE -> throw NetworkUnavailableException()
            else -> Unit
        }
    }

    /**
     * The signed-in user's id; the app offers no write that needs one before there is a session.
     */
    private fun signedInUserId(): String =
        checkNotNull(session.userId.value) { "No user is signed in yet" }

    /**
     * [block] in this repository's scope, not started: [detached] for a caller that needs the job
     * before it runs. Its failure is its result, so the scope never fails.
     */
    private fun <T> detachedLater(block: suspend () -> T): Deferred<Result<T>> =
        scope.async(start = CoroutineStart.LAZY) {
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }

    /**
     * Runs [block] in this repository's scope, so a caller that goes away cannot cut it short. Its
     * failure is handed to the caller; cancellation stays cancellation.
     */
    private suspend fun <T> detached(block: suspend () -> T): T {
        val outcome = scope.async {
            try {
                Result.success(block())
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(failure)
            }
        }
        return outcome.await().getOrThrow()
    }
}

/**
 * A photo [addPhoto][OfflineTaskRepository.addPhoto] is adding, shown as [photo] until [adding]
 * ends.
 */
private class PhotoBeingAdded(val photo: TaskPhoto, val adding: Job)

/** A photo row read for its file alone. */
@Serializable private data class ObjectPathRow(@SerialName(OBJECT_PATH) val objectPath: String)

/** A photo row read for its task alone. */
@Serializable
private data class PhotoTaskRow(val id: String, @SerialName(TASK_ID) val taskId: String)

/** The arguments of `tasks_done_count`; a null [createdSince] counts every task. */
@Serializable
private data class DoneCountParameters(@SerialName("created_since") val createdSince: String?)

/** The snapshot before there is a session: nothing known, and no refresh until there is a user. */
private fun <T> nothingYet(): RowsSnapshot<T> =
    RowsSnapshot(
        value = null,
        isFromCache = true,
        fetchedAt = null,
        hasPendingMutations = false,
        refresh = TaskRefreshState.Paused,
    )

/** These tasks newest first, as the server orders the list. */
private fun List<Task>.newestFirst(): List<Task> = sortedByDescending { it.createdAt }

/**
 * The id of the starter list's [index]th task for [userId]: the same every time, so a second import
 * finds the first one's rows; a UUID made from a digest of the two.
 */
private fun starterId(userId: String, index: Int): String {
    val digest =
        MessageDigest.getInstance("SHA-256").digest("starter/$userId/$index".encodeToByteArray())
    return Uuid.fromByteArray(digest.copyOf(Uuid.SIZE_BYTES)).toString()
}

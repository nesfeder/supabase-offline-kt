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
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.PhotoListSnapshot
import io.github.nesfeder.offlinetasks.data.PhotoUpload
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
import io.github.nesfeder.supabase.offline.FlowReadStrategy
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.ReadStrategy
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The schema both tables live in, the client's default. */
private const val SCHEMA = "public"

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
 * @param scope where work runs that must finish even when its caller goes away, and Realtime.
 * @param liveUpdates applies other devices' changes; null leaves Live updates off for good.
 */
class OfflineTaskRepository(
    private val supabase: SupabaseClient,
    private val session: UserSession,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString() },
    private val liveUpdates: LiveUpdates? = LiveUpdates(supabase),
) : TaskRepository {

    private val offline = supabase.offline
    private val files = TaskPhotoFiles(offline)
    private val switches = MutableStateFlow(SyncSettings())
    private val liveUpdatesState = MutableStateFlow(LiveUpdatesStatus.Paused)

    override val settings: StateFlow<SyncSettings> = switches.asStateFlow()

    override val liveUpdatesStatus: StateFlow<LiveUpdatesStatus> = liveUpdatesState.asStateFlow()

    override val syncStatus: StateFlow<AppSyncStatus> =
        offline.syncStatus
            .map { it.toAppSyncStatus() }
            .stateIn(scope, SharingStarted.Eagerly, offline.syncStatus.value.toAppSyncStatus())

    init {
        scope.launch { keepLiveUpdatesRunning() }
    }

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

    // --8<-- [start:cache-only-photos]
    /**
     * A task's photos, from the query of [allPhotos]. While Simulate offline is on, the read takes
     * the device's copy alone and sends nothing; the screen says so.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun photos(taskId: String): Flow<PhotoListSnapshot> =
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

    override fun photoPage(count: Int): Flow<PhotoListSnapshot> =
        photoList(FlowReadStrategy.CacheFirstThenNetwork, page = count) { it }

    // --8<-- [start:photo-rows-in]
    /**
     * How many photos each of [taskIds] has: one live read of their rows, by `in` on `task_id`. A
     * set of tasks never read online has no rows offline, so nothing is emitted for it.
     */
    override fun photoCounts(taskIds: List<String>): Flow<Map<String, Int>> {
        if (taskIds.isEmpty()) return flowOf(emptyMap())
        val ids = taskIds.sorted() // the same tasks, the same query, the same cached rows
        return whileSignedIn(before = emptyMap()) {
            offline[Tables.TASK_PHOTOS] // the same builder as from(Tables.TASK_PHOTOS)
                .selectAsFlow(Columns.list(ID, TASK_ID)) { filter { isIn(TASK_ID, ids) } }
                .mapNotNull { snapshot -> snapshot.result?.decodeList<PhotoTaskRow>() }
                .map { rows -> rows.groupingBy { it.taskId }.eachCount() }
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
            is MutationOutcome.Applied ->
                NewTaskOutcome.Saved(outcome.result.decodeSingle<TaskRow>().toTask(emptySet()))
            is MutationOutcome.Queued -> NewTaskOutcome.SavedOnDevice // sent when it can be
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
        if (outcome.isQueued) return NewTaskOutcome.SavedOnDevice // no answer from the server yet
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
        // Files first, and only online: Storage writes are never queued, so offline they stay.
        if (canReachNetwork())
            deleteFiles(photos?.map { it.objectPath } ?: photoPathsOnServer(TASK_ID, id))
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

    override fun attachPhoto(taskId: String, photo: EncodedPhoto): Flow<PhotoUpload> = channelFlow {
        // Storage writes are online-only: offline, fail at once rather than when the request does.
        requireNetwork()
        val userId = signedInUserId()
        val photoId = newId()
        val objectPath = photoObjectPath(userId, taskId, photoId)
        // The file and its row together, in this repository's scope: a screen that closes between
        // the two cannot leave a file with no row. Progress reaches the screen while it listens.
        val attached = detached {
            storage(objectPath) {
                files.upload(objectPath, photo.jpeg) { fraction ->
                    trySend(PhotoUpload.Uploading(fraction))
                }
            }
            insertPhotoRow(photoId, userId, taskId, objectPath, photo.width, photo.height)
        }
        send(PhotoUpload.Attached(attached))
    }

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

    override suspend fun photoUriIfCached(photo: TaskPhoto): String? =
        storage(photo.objectPath) { files.uriIfCached(photo.objectPath) }

    override suspend fun photoUri(photo: TaskPhoto): String =
        storage(photo.objectPath) { files.uri(photo.objectPath) }

    override suspend fun photoFileToShare(photo: TaskPhoto): String =
        storage(photo.objectPath) { files.file(photo.objectPath).path }

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
        val newest = allPhotos().first { it.value != null }.value?.firstOrNull()
        if (newest == null) return@detached null
        val copied = downloadPhotoFile(newest).path
        val jpeg = withContext(Dispatchers.IO) { File(copied).readBytes() }
        val photoId = newId()
        val path = photoObjectPath(newest.userId, newest.taskId, photoId)
        storage(path) { files.uploadThroughSignedUrl(path, jpeg) }
        insertPhotoRow(photoId, newest.userId, newest.taskId, path, newest.width, newest.height)
    }

    override fun pendingMutationCount(table: String?): Flow<Int> =
        offline.pendingMutationCountFlow(table)

    // --8<-- [start:pending-count]
    /** The queue's size, read once: the badge has it before the status's first update. */
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
    override suspend fun refreshTasks(): ReadProvenance = read {
        offline
            .from(Tables.TASKS)
            .select(readStrategy = ReadStrategy.NetworkFirst(maxAge = REFRESH_MAX_AGE)) {
                tasksMatching(TaskQuery(), recentWindow(clock.now())) // the list's own query
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

    // --8<-- [start:clear-local-data]
    /** Clear local cache: everything the library keeps here, queued writes included, never sent. */
    override suspend fun clearLocalData() {
        offline.clearLocalData() // an open list fetches again by itself once the network allows
    }

    // --8<-- [end:clear-local-data]

    // --8<-- [start:start-over]
    /** Start over: clear first, so no queued write lands after the deletes; then a new user. */
    override suspend fun startOver() {
        // A new anonymous user needs the server, so nothing is deleted or cleared without it.
        if (!canReachNetwork()) throw NetworkUnavailableException()
        detached {
            val userId = session.userId.value
            offline.clearLocalData() // stops a replay already running, and drops the queue
            userId?.let { deleteEverythingOf(it) }
            session.signInAsNewUser()
        }
    }

    // --8<-- [end:start-over]

    override fun setPreferFreshData(prefer: Boolean) {
        switches.update { it.copy(preferFreshData = prefer) }
    }

    override fun setLiveUpdates(enabled: Boolean) {
        switches.update { it.copy(liveUpdates = enabled) }
    }

    /**
     * Runs [liveUpdates] while the switch is on, a user is signed in and the app's network is not
     * switched off; Simulate offline pauses it too, so it simulates being offline for Realtime.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun keepLiveUpdatesRunning() {
        val updates = liveUpdates
        if (updates == null) {
            liveUpdatesState.value = LiveUpdatesStatus.Off
            return
        }
        combine(switches, session.userId, offline.syncStatus) { switch, userId, status ->
                when {
                    !switch.liveUpdates -> LiveUpdatesStatus.Off
                    userId == null || status.network == NetworkStatus.DISABLED ->
                        LiveUpdatesStatus.Paused
                    else -> LiveUpdatesStatus.Connecting
                }
            }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                liveUpdatesState.value = wanted
                if (wanted != LiveUpdatesStatus.Connecting) return@collectLatest
                try {
                    updates.applyChangesUntilCanceled { connected ->
                        val wasConnected = liveUpdatesState.value == LiveUpdatesStatus.Connected
                        liveUpdatesState.value =
                            if (connected) LiveUpdatesStatus.Connected
                            else LiveUpdatesStatus.Connecting
                        if (connected && !wasConnected) scope.launch { readListsAgain() }
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    liveUpdatesState.value = LiveUpdatesStatus.Failed
                }
            }
    }

    // --8<-- [start:read-again]
    /**
     * Reads both lists from the server once more: Realtime does not replay what happened while it
     * was not connected, so each new connection catches up this way.
     */
    private suspend fun readListsAgain() {
        refreshTasks()
        offline.from(Tables.TASK_PHOTOS).select(readStrategy = ReadStrategy.NetworkOnly) {
            newestFirst() // the query of allPhotos()
        }
    }

    // --8<-- [end:read-again]

    // --8<-- [start:first-observed-read]
    /** The user's tasks that [query] selects, as a live read mapped to the app's snapshot. */
    private fun taskList(
        query: TaskQuery,
        strategy: FlowReadStrategy,
        shown: (List<Task>) -> List<Task>,
    ): Flow<TaskListSnapshot> {
        val recent = recentWindow(clock.now()) // read once: the request must not read the clock
        return whileSignedIn(before = nothingYet()) {
            combine(
                offline.from(Tables.TASKS).selectAsFlow(readStrategy = strategy) {
                    tasksMatching(query, recent)
                },
                pendingRowIds(Tables.TASKS),
            ) { snapshot, pending ->
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
            combine(
                offline.from(Tables.TASK_PHOTOS).selectAsFlow(readStrategy = strategy) {
                    newestFirst()
                    if (page != null) range(0L, page - 1L) // each page size is a query of its own
                },
                pendingRowIds(Tables.TASK_PHOTOS),
            ) { snapshot, pending ->
                snapshot.toRowsSnapshot { result ->
                    val photos = result.decodeList<TaskPhotoRow>().map { it.toTaskPhoto(pending) }
                    shown(photos.sortedByDescending { it.createdAt })
                }
            }
        }

    // --8<-- [end:photos-page]

    /** [read] while there is a session; before, [before] alone, and nothing is sent. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> whileSignedIn(before: T, read: () -> Flow<T>): Flow<T> =
        session.userId
            .flatMapLatest { userId -> if (userId == null) flowOf(before) else read() }
            .flowOn(Dispatchers.Default)

    // --8<-- [start:pending-marks]
    /** The ids of [table]'s rows a queued write touches, from each write's row keys. */
    private fun pendingRowIds(table: String): Flow<Set<String>> =
        offline.pendingMutations(table).map { mutations -> mutations.rowIds() }

    // --8<-- [end:pending-marks]

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
    ): TaskPhoto {
        val row =
            NewTaskPhotoRow(
                id = photoId,
                userId = userId,
                taskId = taskId,
                objectPath = objectPath,
                width = width,
                height = height,
                createdAt = clock.now().toString(),
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

    /** Deletes the files at [paths] from the bucket; a missing file is no error. */
    private suspend fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        storage(paths.first()) { files.delete(paths) }
    }

    // --8<-- [start:delete-my-data]
    /** Deletes [userId]'s files, photo rows and tasks on the server, before that user is left. */
    private suspend fun deleteEverythingOf(userId: String) {
        deleteFiles(photoPathsOnServer(USER_ID, userId))
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

    /** Fails at once when the library may not reach the network: Storage writes never queue. */
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

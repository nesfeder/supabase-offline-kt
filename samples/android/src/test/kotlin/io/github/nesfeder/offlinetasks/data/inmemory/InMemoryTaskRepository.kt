// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.BucketFile
import io.github.nesfeder.offlinetasks.data.BucketFolder
import io.github.nesfeder.offlinetasks.data.BucketListing
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.InvalidRequestException
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.NotSavedOnDeviceException
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.PhotoListSnapshot
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.ReadProvenance
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.ServerCounts
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.SyncSettings
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.WaitingChange
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException
import io.github.nesfeder.offlinetasks.data.photoObjectPath
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How long the in-memory server takes to answer a read; a replayed write takes half as long. */
val DEFAULT_SERVER_LATENCY: Duration = 700.milliseconds

/** How many refused writes are kept, newest first, as the library keeps them. */
private const val REJECTED_MUTATIONS_KEPT = 100

/** How many progress steps a simulated upload reports. */
private const val UPLOAD_STEPS = 10

/** How old the device's copy may be and still answer pull to refresh, as the real one allows. */
private val REFRESH_MAX_AGE = 1.days

/** How far back the Recent filter reaches. */
private val RECENT = 7.days

/** How many task folders one page of the bucket's listing shows. */
private const val FOLDERS_PER_PAGE = 10

/** The titles of the sample tasks and of the starter list, as the real repository's. */
private val SAMPLE_TITLES = List(5) { "Sample task ${it + 1}" }
private val STARTER_TITLES = listOf("Water the plants", "Call the dentist", "Buy milk")

/**
 * A [TaskRepository] that keeps everything in memory, with a simulated server behind a simulated
 * network, so every screen and offline state can be tried without a Supabase project.
 *
 * It behaves as the library is specified to, failures included (see [TaskRepository]): a write
 * changes the device's rows at once and joins the mutation queue; the queue replays in order when
 * the server can be reached, by itself after each write and on every reconnection. A write sent at
 * once that the server refuses is thrown back to its caller; one refused on replay is kept as a
 * rejection. Either way the device's copy goes back to the server's. Nothing survives the process.
 *
 * @param scope where the network watcher, replays and refreshes run; it outlives every screen.
 * @param photoFiles where photo files are kept on the device.
 * @param networkAvailability whether the device has a network, as a connectivity monitor reports.
 * @param seedSampleTasks whether the first user starts with [sampleTasks] on the server.
 */
class InMemoryTaskRepository(
    private val scope: CoroutineScope,
    private val photoFiles: PhotoFileStore,
    networkAvailability: Flow<Boolean>,
    private val clock: Clock = Clock.System,
    private val latency: Duration = DEFAULT_SERVER_LATENCY,
    private val newId: () -> String = { Uuid.random().toString() },
    seedSampleTasks: Boolean = true,
) : TaskRepository, ServerFaults {

    private val state: MutableStateFlow<DeviceState>
    private val server: InMemoryServer

    /** Held while a server write and the device state change it causes happen together. */
    private val writeLock = Mutex()

    /** Held while one refresh from the server runs. */
    private val refreshLock = Mutex()

    /** Guards [runningReplay], so concurrent callers join one replay instead of starting two. */
    private val replayStartLock = Mutex()
    private var runningReplay: Deferred<TaskSyncReport>? = null

    init {
        val firstUserId = newId()
        state = MutableStateFlow(DeviceState(userId = firstUserId))
        server =
            InMemoryServer(
                initialTasks =
                    if (seedSampleTasks) sampleTasks(firstUserId, clock.now(), newId)
                    else emptyList()
            )
    }

    private val publishedSyncStatus = MutableStateFlow(state.value.toSyncStatus())
    private val publishedServerOutage = MutableStateFlow(state.value.serverOutage)
    private val publishedStorageFailing = MutableStateFlow(state.value.storageFailing)

    // Published as each change is made, so `value` is current the moment a write returns.
    override val syncStatus: StateFlow<AppSyncStatus> = publishedSyncStatus.asStateFlow()

    override val isServerOutage: StateFlow<Boolean> = publishedServerOutage.asStateFlow()

    override val isStorageFailing: StateFlow<Boolean> = publishedStorageFailing.asStateFlow()

    private val switches = MutableStateFlow(SyncSettings())

    override val settings: StateFlow<SyncSettings> = switches.asStateFlow()

    // Nothing pushes changes in memory: connected means only that the switch is on and the app's
    // network is not switched off.
    override val liveUpdatesStatus: StateFlow<LiveUpdatesStatus> =
        combine(switches, publishedSyncStatus) { switch, status ->
                when {
                    !switch.liveUpdates -> LiveUpdatesStatus.Off
                    status.network == AppNetworkStatus.Disabled -> LiveUpdatesStatus.Paused
                    else -> LiveUpdatesStatus.Connected
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, LiveUpdatesStatus.Connected)

    init {
        // Last: the watcher may run at once, on another thread, and needs every field above.
        scope.launch {
            networkAvailability.distinctUntilChanged().collect { online ->
                changeNetwork { it.copy(deviceOnline = online) }
            }
        }
    }

    override fun tasks(query: TaskQuery): Flow<TaskListSnapshot> {
        val since = clock.now() - RECENT
        val search = query.search.trim()
        return observe {
            it.taskSnapshot(
                rows = { task ->
                    task.matches(query.filter, since) &&
                        task.title.contains(search, ignoreCase = true)
                },
                writes = { true },
            )
        }
    }

    override fun task(id: String): Flow<TaskListSnapshot> = observe {
        it.taskSnapshot(rows = { task -> task.id == id }, writes = { it.rowKey == id })
    }

    override fun photos(taskId: String): Flow<PhotoListSnapshot> = observe { current ->
        current.photoSnapshot(
            newestFirst = false,
            rows = { it.taskId == taskId },
            writes = { it.concernsPhotosOf(taskId) },
        )
    }

    override fun allPhotos(): Flow<PhotoListSnapshot> = observe {
        it.photoSnapshot(newestFirst = true, rows = { true }, writes = { true })
    }

    override fun photoPage(count: Int): Flow<PhotoListSnapshot> =
        allPhotos().map { snapshot -> snapshot.copy(value = snapshot.value?.take(count)) }

    override fun photoCounts(taskIds: List<String>): Flow<Map<String, Int>> =
        state
            .map { current ->
                current
                    .visibleRows()
                    ?.photos
                    ?.values
                    ?.filter { it.taskId in taskIds }
                    ?.groupingBy { it.taskId }
                    ?.eachCount()
                    .orEmpty()
            }
            .distinctUntilChanged()

    override suspend fun addTask(
        title: String,
        notes: String?,
        needsConnection: Boolean,
    ): NewTaskOutcome {
        // Online only: sent now or not at all, as an OnlineOnly write is.
        if (needsConnection) unreachableServerError()?.let { throw it }
        val task = newTask(title, notes)
        return when (write { QueuedMutation.InsertTask(it, task) }) {
            TaskMutationOutcome.Applied -> NewTaskOutcome.Saved(task)
            is TaskMutationOutcome.Queued -> NewTaskOutcome.SavedOnDevice
        }
    }

    override suspend fun duplicateTask(id: String): NewTaskOutcome {
        val original =
            state.value.visibleRows()?.tasks?.get(id) ?: throw NotSavedOnDeviceException()
        val copy =
            newTask("${original.title} (copy)", original.notes).copy(isDone = original.isDone)
        return when (write { QueuedMutation.InsertTask(it, copy) }) {
            TaskMutationOutcome.Applied -> NewTaskOutcome.Saved(copy)
            is TaskMutationOutcome.Queued -> NewTaskOutcome.SavedOnDevice
        }
    }

    override suspend fun addSampleTasks(): TaskMutationOutcome =
        SAMPLE_TITLES.map { title ->
                val task = newTask(title, notes = null)
                write { QueuedMutation.InsertTask(it, task) }
            }
            .lastOrNull { it is TaskMutationOutcome.Queued } ?: TaskMutationOutcome.Applied

    override suspend fun importStarterList(): TaskMutationOutcome {
        val userId = state.value.userId
        val existing = state.value.visibleRows()?.tasks.orEmpty()
        return STARTER_TITLES.mapIndexedNotNull { index, title ->
                val id = "$userId-starter-$index"
                if (id in existing) return@mapIndexedNotNull null // a second import changes nothing
                val task = newTask(title, notes = null).copy(id = id)
                write { QueuedMutation.InsertTask(it, task) }
            }
            .lastOrNull { it is TaskMutationOutcome.Queued } ?: TaskMutationOutcome.Applied
    }

    override suspend fun addTaskWithBlankTitle(): TaskMutationOutcome {
        val task = newTask("   ", notes = null)
        return write { QueuedMutation.InsertTask(it, task) }
    }

    override suspend fun deleteThisWeeksDoneTasks(): TaskMutationOutcome {
        unreachableServerError()?.let {
            throw WriteNeedsConnectionException(
                "A delete with a logical group cannot be queued",
                it,
            )
        }
        val since = clock.now() - RECENT
        val done =
            state.value.visibleRows()?.tasks?.values.orEmpty().filter {
                it.isDone && it.createdAt >= since
            }
        done.forEach { task -> write { QueuedMutation.DeleteTask(it, task.id) } }
        return TaskMutationOutcome.Applied
    }

    /** A new task for the signed-in user, made now. */
    private fun newTask(title: String, notes: String?): Task {
        val now = clock.now()
        return Task(
            id = newId(),
            userId = state.value.userId,
            title = title,
            notes = notes,
            isDone = false,
            createdAt = now,
            updatedAt = now,
        )
    }

    override suspend fun updateTask(
        id: String,
        title: String,
        notes: String?,
    ): TaskMutationOutcome = write {
        QueuedMutation.EditTask(it, id, title, notes)
    }

    override suspend fun setTaskDone(id: String, isDone: Boolean): TaskMutationOutcome = write {
        QueuedMutation.SetTaskDone(it, id, isDone)
    }

    override suspend fun deleteTask(id: String): TaskMutationOutcome = detached {
        val photosOfTask =
            state.value.visibleRows()?.photos?.values?.filter { it.taskId == id }.orEmpty()
        if (photosOfTask.isNotEmpty()) {
            // Files first, and only online: nothing queues a Storage delete, so offline they stay.
            if (state.value.canReachServer) {
                delay(latency)
                storageRefusal()?.let { throw it }
                server.deleteFiles(photosOfTask.map { it.objectPath })
            }
            photosOfTask.forEach { photoFiles.delete(it.fileName) }
            enqueue { QueuedMutation.DeleteTaskPhotos(it, id) }
        }
        write { QueuedMutation.DeleteTask(it, id) }
    }

    override fun attachPhoto(taskId: String, photo: EncodedPhoto): Flow<PhotoUpload> = flow {
        val userId = state.value.userId
        val photoId = newId()
        val objectPath = photoObjectPath(userId, taskId, photoId)
        for (step in 0 until UPLOAD_STEPS) {
            unreachableServerError()?.let { throw it }
            emit(PhotoUpload.Uploading(step.toFloat() / UPLOAD_STEPS))
            delay(latency * 2 / UPLOAD_STEPS)
        }
        storageRefusal()?.let { throw it }
        if (!server.upload(objectPath, photo.jpeg)) {
            throw PhotoStorageException(status = 409, message = "The resource already exists")
        }
        emit(PhotoUpload.Uploading(1f))
        val row =
            TaskPhoto(
                id = photoId,
                userId = userId,
                taskId = taskId,
                objectPath = objectPath,
                width = photo.width,
                height = photo.height,
                createdAt = clock.now(),
            )
        try {
            write { QueuedMutation.InsertPhoto(it, row) }
        } catch (refused: TaskWriteRefusedException) {
            // As the library-backed repository does: no row will ever name the file.
            server.deleteFiles(listOf(objectPath))
            throw refused
        }
        emit(PhotoUpload.Attached(row))
    }

    override suspend fun deletePhoto(photo: TaskPhoto): TaskMutationOutcome = detached {
        val photoId = photo.id
        if (state.value.visibleRows()?.photos?.get(photoId) == null) {
            return@detached TaskMutationOutcome.Applied
        }
        unreachableServerError()?.let { throw it }
        delay(latency)
        storageRefusal()?.let { throw it }
        server.deleteFiles(listOf(photo.objectPath))
        photoFiles.delete(photo.fileName)
        write { QueuedMutation.DeletePhoto(it, photoId) }
    }

    override suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile? =
        photoFiles.find(photo.fileName)

    override suspend fun photoUriIfCached(photo: TaskPhoto): String? =
        photoFiles.find(photo.fileName)?.let { "file://${it.path}" }

    override suspend fun photoUri(photo: TaskPhoto): String =
        "file://${downloadPhotoFile(photo).path}"

    override suspend fun photoFileToShare(photo: TaskPhoto): String = downloadPhotoFile(photo).path

    override suspend fun photoDetails(photo: TaskPhoto): PhotoDetails {
        unreachableServerError()?.let { throw it }
        val bytes = server.download(photo.objectPath)
        return PhotoDetails(
            existsInBucket = bytes != null,
            sizeBytes = bytes?.size?.toLong() ?: 0,
            contentType = "image/jpeg",
            createdAt = photo.createdAt,
            updatedAt = photo.createdAt,
            etag = null,
            publicUrl = "$FAKE_STORAGE_URL/public/${photo.objectPath}",
            authenticatedUrl = "$FAKE_STORAGE_URL/authenticated/${photo.objectPath}",
            cachedFile = photoFiles.find(photo.fileName),
        )
    }

    override suspend fun photoShareLink(photo: TaskPhoto): String {
        unreachableServerError()?.let { throw it }
        return "$FAKE_STORAGE_URL/sign/${photo.objectPath}?width=512"
    }

    override suspend fun photoShareLinks(photos: List<TaskPhoto>): List<String> {
        unreachableServerError()?.let { throw it }
        return photos.map { "$FAKE_STORAGE_URL/sign/${it.objectPath}" }
    }

    override suspend fun replacePhoto(photo: TaskPhoto, replacement: EncodedPhoto) = detached {
        unreachableServerError()?.let { throw it }
        storageRefusal()?.let { throw it }
        if (!server.replaceFile(photo.objectPath, replacement.jpeg)) {
            throw PhotoNotFoundException(photo.objectPath)
        }
        photoFiles.delete(photo.fileName)
    }

    override suspend fun movePhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome =
        detached {
            unreachableServerError()?.let { throw it }
            storageRefusal()?.let { throw it }
            val newPath = photoObjectPath(photo.userId, taskId, photo.id)
            if (!server.moveFile(photo.objectPath, newPath))
                throw PhotoNotFoundException(photo.objectPath)
            write { QueuedMutation.MovePhoto(it, photo.id, taskId, newPath) }
        }

    override suspend fun copyPhoto(photo: TaskPhoto, taskId: String): TaskMutationOutcome =
        detached {
            unreachableServerError()?.let { throw it }
            storageRefusal()?.let { throw it }
            val copyId = newId()
            val copyPath = photoObjectPath(photo.userId, taskId, copyId)
            if (!server.copyFile(photo.objectPath, copyPath))
                throw PhotoNotFoundException(photo.objectPath)
            val copy =
                photo.copy(
                    id = copyId,
                    taskId = taskId,
                    objectPath = copyPath,
                    createdAt = clock.now(),
                )
            write { QueuedMutation.InsertPhoto(it, copy) }
        }

    override suspend fun listBucket(offset: Int): BucketListing {
        unreachableServerError()?.let { throw it }
        val folders = server.filesOf(state.value.userId).toSortedMap().entries.toList()
        val page = folders.drop(offset).take(FOLDERS_PER_PAGE)
        return BucketListing(
            folders =
                page.map { (taskId, files) ->
                    BucketFolder(
                        taskId,
                        files.map { (path, size) ->
                            BucketFile(
                                path.substringAfterLast('/'),
                                size.toLong(),
                                null,
                                null,
                                null,
                            )
                        },
                    )
                },
            hasMore = folders.size > offset + page.size,
            nextOffset = offset + page.size,
        )
    }

    override suspend fun uploadThroughSignedUrl(): TaskPhoto? = detached {
        unreachableServerError()?.let { throw it }
        val newest =
            state.value.visibleRows()?.photos?.values?.maxByOrNull { it.createdAt }
                ?: return@detached null
        val bytes =
            server.download(newest.objectPath) ?: throw PhotoNotFoundException(newest.objectPath)
        val photoId = newId()
        val path = photoObjectPath(newest.userId, newest.taskId, photoId)
        server.upload(path, bytes)
        val copy = newest.copy(id = photoId, objectPath = path, createdAt = clock.now())
        write { QueuedMutation.InsertPhoto(it, copy) }
        copy
    }

    override suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile {
        photoFiles.find(photo.fileName)?.let {
            return it
        }
        unreachableServerError()?.let { throw it }
        delay(latency)
        storageRefusal()?.let { throw it }
        val bytes =
            server.download(photo.objectPath) ?: throw PhotoNotFoundException(photo.objectPath)
        return photoFiles.write(photo.fileName, bytes)
    }

    override fun pendingMutationCount(table: String?): Flow<Int> =
        state
            .map { current -> current.queue.count { table == null || it.table == table } }
            .distinctUntilChanged()

    override suspend fun pendingMutationCountNow(): Int = state.value.queue.size

    override fun waitingChanges(): Flow<List<WaitingChange>> =
        state
            .map { current ->
                current.queue.map { mutation ->
                    WaitingChange(
                        id = mutation.id,
                        table = mutation.table,
                        operation = mutation.operation,
                        createdAt = current.queuedAt[mutation.id] ?: clock.now(),
                        rowKey = mutation.rowKey,
                        taskTitle = mutation.taskTitle,
                    )
                }
            }
            .distinctUntilChanged()

    override suspend fun discardWaitingChange(id: Long) {
        updateState { it.copy(queue = it.queue.filterNot { mutation -> mutation.id == id }) }
    }

    override fun rejectedMutations(): Flow<List<RejectedTaskMutation>> =
        state.map { it.rejected }.distinctUntilChanged()

    override suspend fun rejectedMutationsNow(): List<RejectedTaskMutation> = state.value.rejected

    override suspend fun dismissRejectedMutation(id: Long) {
        updateState { it.withoutRejection(id) }
    }

    override suspend fun retryRejectedMutation(id: Long) {
        val original = state.value.rejectedWrites[id] ?: return
        dismissRejectedMutation(id)
        enqueue { original.withId(it) }
        // Sent by a replay, so a second refusal is found on replay and kept, never thrown.
        scope.launch { syncPendingMutations() }
    }

    override suspend fun syncPendingMutations(): TaskSyncReport {
        var synced = 0
        val rejected = mutableListOf<RejectedTaskMutation>()
        while (true) {
            val pass = joinOrStartReplay().await()
            synced += pass.syncedCount
            rejected += pass.rejected
            val current = state.value
            // A write queued while the pass was finishing waits for one more pass.
            if (current.queue.isEmpty() || !current.canReachServer) {
                return TaskSyncReport(synced, rejected, current.queue.size)
            }
        }
    }

    override suspend fun refreshTasks(): ReadProvenance {
        // As a NetworkFirst select with a day's maxAge: off the network it answers from the
        // device's copy when it is a day old at most, and against a failing server it fails.
        val before = state.value
        if (before.network != AppNetworkStatus.Online) {
            val fetchedAt = before.fetchedAt
            if (
                before.cache == null ||
                    fetchedAt == null ||
                    clock.now() - fetchedAt > REFRESH_MAX_AGE
            ) {
                throw NetworkUnavailableException()
            }
            return ReadProvenance(true, fetchedAt, before.queue.isNotEmpty())
        }
        detached { refreshFromServer(force = true) }
        unreachableServerError()?.let { throw it }
        val after = state.value
        return ReadProvenance(false, after.fetchedAt, after.queue.isNotEmpty())
    }

    override suspend fun openTaskCountNow(): Int? =
        state.value.visibleRows()?.tasks?.values?.count { !it.isDone }

    override fun setPreferFreshData(prefer: Boolean) {
        switches.update { it.copy(preferFreshData = prefer) }
    }

    override fun setLiveUpdates(enabled: Boolean) {
        switches.update { it.copy(liveUpdates = enabled) }
    }

    override suspend fun markAllDone(): Int {
        unreachableServerError()?.let { throw it }
        delay(latency)
        return server.completeAllTasks(state.value.userId, clock.now())
    }

    override suspend fun serverCounts(): ServerCounts {
        unreachableServerError()?.let { throw it }
        val tasks = server.rowsOf(state.value.userId).tasks.values
        val since = clock.now() - RECENT
        return ServerCounts(
            done = tasks.count { it.isDone },
            total = tasks.size,
            doneThisWeek = tasks.count { it.isDone && it.createdAt >= since },
        )
    }

    override suspend fun countOpenTasksOnServer(): Int {
        unreachableServerError()?.let { throw it }
        return server.rowsOf(state.value.userId).tasks.values.count { !it.isDone }
    }

    override suspend fun readWithTwoFiltersOnOneColumn() {
        throw InvalidRequestException("Two top-level filters on column created_at")
    }

    override suspend fun setNetworkEnabled(enabled: Boolean) {
        changeNetwork { it.copy(networkEnabled = enabled) }
    }

    override fun setServerOutage(active: Boolean) {
        changeNetwork { it.copy(serverOutage = active) }
    }

    override fun setStorageFailing(active: Boolean) {
        updateState { it.copy(storageFailing = active) }
    }

    override suspend fun clearLocalData() {
        detached {
            writeLock.withLock {
                updateState { it.cleared(userId = it.userId) }
                photoFiles.deleteAll()
            }
            refreshFromServer(force = true)
        }
    }

    override suspend fun startOver() {
        // As the real repository: a new user needs the server, so nothing goes without it.
        val network = state.value.network
        if (network == AppNetworkStatus.Offline || network == AppNetworkStatus.Disabled) {
            throw NetworkUnavailableException()
        }
        detached {
            val userId = state.value.userId
            // Clear first, as the real repository does, then delete what this user owns.
            writeLock.withLock {
                updateState { it.cleared(userId = userId) }
                photoFiles.deleteAll()
            }
            delay(latency)
            storageRefusal()?.let { throw it }
            server.deleteEverythingOf(userId)
            writeLock.withLock { updateState { it.copy(userId = newId()) } }
            refreshFromServer(force = true)
        }
    }

    /** Applies [change] to the device state, then publishes the values derived from it. */
    private fun updateState(change: (DeviceState) -> DeviceState) {
        state.update(change)
        // Until the state stops moving under it, so the last publisher always leaves it current.
        while (true) {
            val current = state.value
            publishedSyncStatus.value = current.toSyncStatus()
            publishedServerOutage.value = current.serverOutage
            publishedStorageFailing.value = current.storageFailing
            if (state.value === current) return
        }
    }

    /**
     * Runs [block] in this repository's scope, so a screen that goes away cannot cut it short. Its
     * failure is handed to the caller, never to the scope, which goes on serving every screen;
     * cancellation stays cancellation.
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

    /** A live query: [read] of every state, refreshed from the server when it is not fresh. */
    private fun <T> observe(read: (DeviceState) -> RowsSnapshot<T>): Flow<RowsSnapshot<T>> =
        state.map(read).distinctUntilChanged().onStart {
            scope.launch { refreshFromServer(force = false) }
        }

    /**
     * Queues a write, then sends it at once when it heads the queue and the server can be reached.
     * Sent at once and refused, it is abandoned and the refusal is thrown.
     */
    private suspend fun write(build: (mutationId: Long) -> QueuedMutation): TaskMutationOutcome {
        val mutation = enqueue(build)
        val current = state.value
        if (!current.canReachServer) return TaskMutationOutcome.Queued(mutation.id)
        if (current.queue.firstOrNull()?.id != mutation.id) {
            // Behind other writes it waits its turn: a replay sends them all, in order.
            scope.launch { syncPendingMutations() }
            return TaskMutationOutcome.Queued(mutation.id)
        }
        return detached { sendAtOnce(mutation) }
    }

    private suspend fun sendAtOnce(mutation: QueuedMutation): TaskMutationOutcome {
        delay(latency / 2)
        val step = sendHead(mutation, keepRefusal = false)
        // Anything queued behind it while it was on its way goes next.
        if (state.value.queue.isNotEmpty()) scope.launch { syncPendingMutations() }
        return when (step) {
            ReplayStep.Accepted -> TaskMutationOutcome.Applied
            is ReplayStep.Refused ->
                throw TaskWriteRefusedException(
                    table = mutation.table,
                    operation = mutation.operation,
                    taskTitle = mutation.taskTitle,
                    serverError = step.rejection.serverError ?: error("Refused without an answer"),
                )
            // The server went out of reach first: it stays queued for the next replay.
            is ReplayStep.Stopped -> TaskMutationOutcome.Queued(mutation.id)
            // A replay sent it meanwhile; a refusal there is kept, as any found on replay is.
            ReplayStep.Skipped ->
                if (state.value.rejected.any { it.id == mutation.id }) {
                    TaskMutationOutcome.Queued(mutation.id)
                } else {
                    TaskMutationOutcome.Applied
                }
        }
    }

    private fun enqueue(build: (mutationId: Long) -> QueuedMutation): QueuedMutation {
        lateinit var queued: QueuedMutation
        val now = clock.now()
        updateState { current ->
            queued = build(current.nextMutationId)
            current.copy(
                queue = current.queue + queued,
                queuedAt = current.queuedAt + (queued.id to now),
                nextMutationId = current.nextMutationId + 1,
            )
        }
        return queued
    }

    private suspend fun joinOrStartReplay(): Deferred<TaskSyncReport> = replayStartLock.withLock {
        runningReplay?.takeIf { it.isActive }
            ?: scope.async { replayQueue() }.also { runningReplay = it }
    }

    /** One pass over the queue, head first, until it is empty or the server is out of reach. */
    private suspend fun replayQueue(): TaskSyncReport {
        if (state.value.queue.isEmpty() || !state.value.canReachServer) {
            return TaskSyncReport(0, emptyList(), state.value.queue.size)
        }
        updateState { it.copy(isSyncing = true) }
        var synced = 0
        val rejected = mutableListOf<RejectedTaskMutation>()
        var stoppedBy: Throwable? = null
        try {
            while (true) {
                val head = state.value.queue.firstOrNull() ?: break
                delay(latency / 2)
                when (val step = sendHead(head, keepRefusal = true)) {
                    ReplayStep.Skipped -> continue
                    is ReplayStep.Stopped -> {
                        stoppedBy = step.cause
                        break
                    }
                    ReplayStep.Accepted -> synced++
                    is ReplayStep.Refused -> rejected += step.rejection
                }
            }
        } finally {
            updateState { current ->
                val finishedClean = stoppedBy == null && current.queue.isEmpty()
                current.copy(
                    isSyncing = false,
                    lastSyncError = stoppedBy,
                    lastSyncedAt = if (finishedClean) clock.now() else current.lastSyncedAt,
                )
            }
        }
        return TaskSyncReport(synced, rejected, state.value.queue.size)
    }

    /**
     * Sends [head] if it still heads the queue. A refusal is kept as a rejection when
     * [keepRefusal], or dropped when its caller is there to be told.
     */
    private suspend fun sendHead(head: QueuedMutation, keepRefusal: Boolean): ReplayStep =
        writeLock.withLock {
            val current = state.value
            // Cleared or replaced while this pass waited: look at the queue again.
            if (current.queue.firstOrNull()?.id != head.id) return@withLock ReplayStep.Skipped
            unreachableServerError()?.let {
                return@withLock ReplayStep.Stopped(it)
            }
            when (val answer = server.apply(current.userId, head, clock.now())) {
                is ServerAnswer.Accepted -> {
                    updateState { it.accepted(head, answer.row) }
                    ReplayStep.Accepted
                }
                is ServerAnswer.Refused -> {
                    val rejection =
                        RejectedTaskMutation(
                            id = head.id,
                            table = head.table,
                            operation = head.operation,
                            rowKeys = listOfNotNull(head.rowKey),
                            taskTitle = head.taskTitle,
                            serverError = answer.error,
                            message = answer.error.message,
                            rejectedAt = clock.now(),
                            createdAt = current.queuedAt[head.id] ?: clock.now(),
                        )
                    updateState {
                        if (keepRefusal) it.refused(head, rejection) else it.abandoned(head)
                    }
                    ReplayStep.Refused(rejection)
                }
            }
        }

    /** Reads every row of the user from the server, unless [force] is false and they are fresh. */
    private suspend fun refreshFromServer(force: Boolean) {
        if (force) refreshLock.lock() else if (!refreshLock.tryLock()) return
        try {
            val start = state.value
            if (!force && start.confirmedByServer && start.cache != null) return
            if (start.network != AppNetworkStatus.Online) return
            updateState { it.copy(refresh = TaskRefreshState.Refreshing) }
            delay(latency)
            writeLock.withLock {
                val current = state.value
                when {
                    current.userId != start.userId -> Unit
                    current.network != AppNetworkStatus.Online ->
                        updateState { it.copy(refresh = TaskRefreshState.Paused) }
                    current.serverOutage ->
                        updateState {
                            it.copy(
                                refresh = TaskRefreshState.Stalled(ServerUnreachableException()),
                                confirmedByServer = false,
                            )
                        }
                    else -> {
                        val rows = server.rowsOf(current.userId)
                        updateState {
                            it.copy(
                                cache = rows,
                                fetchedAt = clock.now(),
                                confirmedByServer = true,
                                refresh = TaskRefreshState.Idle,
                            )
                        }
                    }
                }
            }
        } finally {
            refreshLock.unlock()
        }
    }

    /** Applies a change to the network facts, then catches up if the server just came in reach. */
    private fun changeNetwork(change: (DeviceState) -> DeviceState) {
        var reachedServer = false
        updateState { current ->
            val next = change(current).withRefreshForNetwork()
            reachedServer = !current.canReachServer && next.canReachServer
            next
        }
        if (reachedServer) {
            scope.launch {
                syncPendingMutations()
                refreshFromServer(force = true)
            }
        }
    }

    /** Why Storage refuses right now, or null when it does not. */
    private fun storageRefusal(): PhotoStorageException? =
        if (state.value.storageFailing) {
            PhotoStorageException(status = 503, message = "Storage is failing on purpose")
        } else {
            null
        }

    /** Why the server cannot be reached right now, or null when it can. */
    private fun unreachableServerError(): Exception? {
        val current = state.value
        return when {
            current.network == AppNetworkStatus.Disabled -> NetworkSwitchedOffException()
            current.network != AppNetworkStatus.Online -> NetworkUnavailableException()
            current.serverOutage -> ServerUnreachableException()
            else -> null
        }
    }
}

/** What happened to the head of the queue in one replay step. */
private sealed interface ReplayStep {
    /** The queue changed under the step; nothing was sent. */
    data object Skipped : ReplayStep

    /** The server could not be reached; the write stays queued. */
    data class Stopped(val cause: Throwable) : ReplayStep

    /** Sent and accepted. */
    data object Accepted : ReplayStep

    /** Sent and refused. */
    data class Refused(val rejection: RejectedTaskMutation) : ReplayStep
}

/** Where the in-memory bucket's links point: nowhere real. */
private const val FAKE_STORAGE_URL = "https://in-memory.test/storage/v1/object/task-photos"

/** Whether this task is one [filter] selects; Recent means made since [since]. */
private fun Task.matches(filter: TaskFilter, since: Instant): Boolean =
    when (filter) {
        TaskFilter.All -> true
        TaskFilter.Open -> !isDone
        TaskFilter.Done -> isDone
        TaskFilter.Recent -> createdAt >= since
    }

/** The name of a photo's file in the device's [PhotoFileStore]. */
private val TaskPhoto.fileName: String
    get() = "$id.jpg"

/** Everything the device holds, as one immutable value. */
private data class DeviceState(
    val userId: String,
    /** The rows the server last returned, or null when it never has. */
    val cache: DeviceRows? = null,
    val fetchedAt: Instant? = null,
    val confirmedByServer: Boolean = false,
    val refresh: TaskRefreshState = TaskRefreshState.Idle,
    val queue: List<QueuedMutation> = emptyList(),
    /** When each queued write was made, by its id. */
    val queuedAt: Map<Long, Instant> = emptyMap(),
    val rejected: List<RejectedTaskMutation> = emptyList(),
    /** The refused writes themselves, by rejection id, for a retry. */
    val rejectedWrites: Map<Long, QueuedMutation> = emptyMap(),
    val nextMutationId: Long = 1,
    val networkEnabled: Boolean = true,
    val deviceOnline: Boolean? = null,
    val serverOutage: Boolean = false,
    val storageFailing: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val lastSyncError: Throwable? = null,
) {
    val network: AppNetworkStatus
        get() =
            when {
                !networkEnabled -> AppNetworkStatus.Disabled
                deviceOnline == null -> AppNetworkStatus.Unknown
                deviceOnline -> AppNetworkStatus.Online
                else -> AppNetworkStatus.Offline
            }

    val canReachServer: Boolean
        get() = network == AppNetworkStatus.Online && !serverOutage

    /** The cached rows with every queued write applied on top, as the screens see them. */
    fun visibleRows(): DeviceRows? {
        val base = cache ?: if (queue.isEmpty()) return null else DeviceRows()
        return queue.fold(base) { rows, mutation -> mutation.applyTo(rows) }
    }

    /** The tasks [rows] selects, newest first; [writes] picks the queued writes that touch them. */
    fun taskSnapshot(
        rows: (Task) -> Boolean,
        writes: (QueuedMutation) -> Boolean,
    ): TaskListSnapshot {
        val pending = queue.filter { it.table == Tables.TASKS && writes(it) }
        val pendingIds = pending.mapNotNullTo(HashSet()) { it.rowKey }
        val tasks =
            visibleRows()
                ?.tasks
                ?.values
                ?.filter(rows)
                ?.map { it.copy(hasPendingMutations = it.id in pendingIds) }
                ?.sortedByDescending { it.createdAt }
        return snapshotOf(tasks, hasPendingMutations = pending.isNotEmpty())
    }

    /** The photos [rows] selects; [writes] picks the queued writes that touch them. */
    fun photoSnapshot(
        newestFirst: Boolean,
        rows: (TaskPhoto) -> Boolean,
        writes: (QueuedMutation) -> Boolean,
    ): PhotoListSnapshot {
        val pending = queue.filter { it.table == Tables.TASK_PHOTOS && writes(it) }
        val pendingIds = pending.mapNotNullTo(HashSet()) { it.rowKey }
        val photos =
            visibleRows()
                ?.photos
                ?.values
                ?.filter(rows)
                ?.map { it.copy(hasPendingMutations = it.id in pendingIds) }
                ?.let { list ->
                    if (newestFirst) list.sortedByDescending { it.createdAt }
                    else list.sortedBy { it.createdAt }
                }
        return snapshotOf(photos, hasPendingMutations = pending.isNotEmpty())
    }

    private fun <T> snapshotOf(value: T?, hasPendingMutations: Boolean): RowsSnapshot<T> =
        RowsSnapshot(
            value = value,
            isFromCache = !confirmedByServer,
            fetchedAt = fetchedAt,
            hasPendingMutations = hasPendingMutations,
            refresh = refresh,
        )

    fun toSyncStatus(): AppSyncStatus =
        AppSyncStatus(
            network = network,
            pendingMutationCount = queue.size,
            rejectedMutationCount = rejected.size,
            isSyncing = isSyncing,
            lastSyncedAt = lastSyncedAt,
            lastSyncError = lastSyncError,
        )

    /** Off the network a refresh is paused and the rows shown are only the device's. */
    fun withRefreshForNetwork(): DeviceState =
        if (network == AppNetworkStatus.Online) this
        else copy(refresh = TaskRefreshState.Paused, confirmedByServer = false)

    /** The head write accepted: it leaves the queue and the cache takes the server's copy. */
    fun accepted(head: QueuedMutation, storedRow: Task?): DeviceState {
        var rows = head.applyTo(cache ?: DeviceRows())
        if (storedRow != null) rows = rows.copy(tasks = rows.tasks + (storedRow.id to storedRow))
        return copy(queue = queue.drop(1), cache = rows)
    }

    /** The head write refused: it leaves the queue, so the device shows the server's copy again. */
    fun refused(head: QueuedMutation, rejection: RejectedTaskMutation): DeviceState {
        val kept = (listOf(rejection) + rejected).take(REJECTED_MUTATIONS_KEPT)
        val keptIds = kept.mapTo(HashSet()) { it.id }
        return copy(
            queue = queue.drop(1),
            rejected = kept,
            rejectedWrites = (rejectedWrites + (rejection.id to head)).filterKeys { it in keptIds },
        )
    }

    /** The head write refused while its caller waited: it leaves the queue and is not kept. */
    fun abandoned(head: QueuedMutation): DeviceState =
        copy(queue = queue.filterNot { it.id == head.id })

    fun withoutRejection(id: Long): DeviceState =
        copy(rejected = rejected.filterNot { it.id == id }, rejectedWrites = rejectedWrites - id)

    /** Nothing cached, queued or refused, for [userId]; the network facts are kept. */
    fun cleared(userId: String): DeviceState =
        DeviceState(
                userId = userId,
                nextMutationId = nextMutationId,
                networkEnabled = networkEnabled,
                deviceOnline = deviceOnline,
                serverOutage = serverOutage,
                storageFailing = storageFailing,
            )
            .withRefreshForNetwork()
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.data.inmemory

import io.github.nesfeder.supabase.offline.sample.data.EncodedPhoto
import io.github.nesfeder.supabase.offline.sample.data.MutationOutcome
import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.data.NetworkUnavailableException
import io.github.nesfeder.supabase.offline.sample.data.PhotoFile
import io.github.nesfeder.supabase.offline.sample.data.PhotoFileStore
import io.github.nesfeder.supabase.offline.sample.data.PhotoListSnapshot
import io.github.nesfeder.supabase.offline.sample.data.PhotoNotFoundException
import io.github.nesfeder.supabase.offline.sample.data.PhotoUpload
import io.github.nesfeder.supabase.offline.sample.data.RefreshState
import io.github.nesfeder.supabase.offline.sample.data.RejectedMutation
import io.github.nesfeder.supabase.offline.sample.data.ServerOutageSwitch
import io.github.nesfeder.supabase.offline.sample.data.ServerUnreachableException
import io.github.nesfeder.supabase.offline.sample.data.Snapshot
import io.github.nesfeder.supabase.offline.sample.data.SyncReport
import io.github.nesfeder.supabase.offline.sample.data.SyncStatus
import io.github.nesfeder.supabase.offline.sample.data.Tables
import io.github.nesfeder.supabase.offline.sample.data.Task
import io.github.nesfeder.supabase.offline.sample.data.TaskListSnapshot
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.data.photoObjectPath
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
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

/**
 * A [TaskRepository] that keeps everything in memory, with a simulated server behind a simulated
 * network, so every screen and offline state can be tried without a Supabase project.
 *
 * It behaves as the library is specified to: a write changes the device's rows at once and joins
 * the mutation queue; the queue replays in order when the server can be reached, by itself after
 * each write and on every reconnection; a write the server refuses is kept as a rejection and the
 * device's copy goes back to the server's. Nothing survives the process.
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
) : TaskRepository, ServerOutageSwitch {

    private val state: MutableStateFlow<DeviceState>
    private val server: InMemoryServer

    /** Held while a server write and the device state change it causes happen together. */
    private val writeLock = Mutex()

    /** Held while one refresh from the server runs. */
    private val refreshLock = Mutex()

    /** Guards [runningReplay], so concurrent callers join one replay instead of starting two. */
    private val replayStartLock = Mutex()
    private var runningReplay: Deferred<SyncReport>? = null

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

    // Published as each change is made, so `value` is current the moment a write returns.
    override val syncStatus: StateFlow<SyncStatus> = publishedSyncStatus.asStateFlow()

    override val isServerOutage: StateFlow<Boolean> = publishedServerOutage.asStateFlow()

    init {
        // Last: the watcher may run at once, on another thread, and needs every field above.
        scope.launch {
            networkAvailability.distinctUntilChanged().collect { online ->
                changeNetwork { it.copy(deviceOnline = online) }
            }
        }
    }

    override fun tasks(): Flow<TaskListSnapshot> = observe {
        it.taskSnapshot(rows = { true }, writes = { true })
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

    override suspend fun addTask(title: String, notes: String?): MutationOutcome {
        val now = clock.now()
        val task =
            Task(
                id = newId(),
                userId = state.value.userId,
                title = title,
                notes = notes,
                isDone = false,
                createdAt = now,
                updatedAt = now,
            )
        return write { QueuedMutation.InsertTask(it, task) }
    }

    override suspend fun updateTask(id: String, title: String, notes: String?): MutationOutcome =
        write {
            QueuedMutation.EditTask(it, id, title, notes)
        }

    override suspend fun setTaskDone(id: String, isDone: Boolean): MutationOutcome = write {
        QueuedMutation.SetTaskDone(it, id, isDone)
    }

    override suspend fun deleteTask(id: String): MutationOutcome = detached {
        val photosOfTask =
            state.value.visibleRows()?.photos?.values?.filter { it.taskId == id }.orEmpty()
        if (photosOfTask.isNotEmpty()) {
            // Files first, and only online: nothing queues a Storage delete, so offline they stay.
            if (state.value.canReachServer) {
                delay(latency)
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
            unreachableServerError()?.let {
                emit(PhotoUpload.Failed(it))
                return@flow
            }
            emit(PhotoUpload.Uploading(step.toFloat() / UPLOAD_STEPS))
            delay(latency * 2 / UPLOAD_STEPS)
        }
        if (!server.upload(objectPath, photo.jpeg)) {
            emit(PhotoUpload.Failed(IllegalStateException("A file is already at that path")))
            return@flow
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
        write { QueuedMutation.InsertPhoto(it, row) }
        emit(PhotoUpload.Attached(row))
    }

    override suspend fun deletePhoto(photoId: String): MutationOutcome = detached {
        val photo =
            state.value.visibleRows()?.photos?.get(photoId)
                ?: return@detached MutationOutcome.Applied
        unreachableServerError()?.let { throw it }
        delay(latency)
        server.deleteFiles(listOf(photo.objectPath))
        photoFiles.delete(photo.fileName)
        write { QueuedMutation.DeletePhoto(it, photoId) }
    }

    override suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile? =
        photoFiles.find(photo.fileName)

    override suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile {
        photoFiles.find(photo.fileName)?.let {
            return it
        }
        unreachableServerError()?.let { throw it }
        delay(latency)
        val bytes = server.download(photo.objectPath) ?: throw PhotoNotFoundException(photo.id)
        return photoFiles.write(photo.fileName, bytes)
    }

    override fun pendingMutationCount(table: String?): Flow<Int> =
        state
            .map { current -> current.queue.count { table == null || it.table == table } }
            .distinctUntilChanged()

    override fun rejectedMutations(): Flow<List<RejectedMutation>> =
        state.map { it.rejected }.distinctUntilChanged()

    override suspend fun dismissRejectedMutation(id: Long) {
        updateState { it.withoutRejection(id) }
    }

    override suspend fun retryRejectedMutation(id: Long) {
        val original = state.value.rejectedWrites[id] ?: return
        dismissRejectedMutation(id)
        write { original.withId(it) }
    }

    override suspend fun syncPendingMutations(): SyncReport {
        var synced = 0
        val rejected = mutableListOf<RejectedMutation>()
        while (true) {
            val pass = joinOrStartReplay().await()
            synced += pass.syncedCount
            rejected += pass.rejected
            val current = state.value
            // A write queued while the pass was finishing waits for one more pass.
            if (current.queue.isEmpty() || !current.canReachServer) {
                return SyncReport(synced, rejected, current.queue.size)
            }
        }
    }

    override suspend fun refreshTasks() {
        detached { refreshFromServer(force = true) }
    }

    override suspend fun setNetworkEnabled(enabled: Boolean) {
        changeNetwork { it.copy(networkEnabled = enabled) }
    }

    override fun setServerOutage(active: Boolean) {
        changeNetwork { it.copy(serverOutage = active) }
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
        detached {
            writeLock.withLock {
                updateState { it.cleared(userId = newId()) }
                photoFiles.deleteAll()
            }
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
            if (state.value === current) return
        }
    }

    /** Runs [block] in this repository's scope, so a screen that goes away cannot cut it short. */
    private suspend fun <T> detached(block: suspend () -> T): T = scope.async { block() }.await()

    /** A live query: [read] of every state, refreshed from the server when it is not fresh. */
    private fun <T> observe(read: (DeviceState) -> Snapshot<T>): Flow<Snapshot<T>> =
        state.map(read).distinctUntilChanged().onStart {
            scope.launch { refreshFromServer(force = false) }
        }

    /** Queues a write and sends it now when it heads the queue and the server can be reached. */
    private suspend fun write(build: (mutationId: Long) -> QueuedMutation): MutationOutcome {
        val mutation = enqueue(build)
        val current = state.value
        if (!current.canReachServer) return MutationOutcome.Queued(mutation.id)
        val isHead = current.queue.firstOrNull()?.id == mutation.id
        val replay = scope.async { syncPendingMutations() }
        // Behind other writes it waits its turn; the replay just started sends them all in order.
        if (!isHead) return MutationOutcome.Queued(mutation.id)
        val report = replay.await()
        val accepted =
            report.rejected.none { it.id == mutation.id } &&
                state.value.queue.none { it.id == mutation.id }
        return if (accepted) MutationOutcome.Applied else MutationOutcome.Queued(mutation.id)
    }

    private fun enqueue(build: (mutationId: Long) -> QueuedMutation): QueuedMutation {
        lateinit var queued: QueuedMutation
        updateState { current ->
            queued = build(current.nextMutationId)
            current.copy(
                queue = current.queue + queued,
                nextMutationId = current.nextMutationId + 1,
            )
        }
        return queued
    }

    private suspend fun joinOrStartReplay(): Deferred<SyncReport> = replayStartLock.withLock {
        runningReplay?.takeIf { it.isActive }
            ?: scope.async { replayQueue() }.also { runningReplay = it }
    }

    /** One pass over the queue, head first, until it is empty or the server is out of reach. */
    private suspend fun replayQueue(): SyncReport {
        if (state.value.queue.isEmpty() || !state.value.canReachServer) {
            return SyncReport(0, emptyList(), state.value.queue.size)
        }
        updateState { it.copy(isSyncing = true) }
        var synced = 0
        val rejected = mutableListOf<RejectedMutation>()
        var stoppedBy: Throwable? = null
        try {
            while (true) {
                val head = state.value.queue.firstOrNull() ?: break
                delay(latency / 2)
                when (val step = sendHead(head)) {
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
        return SyncReport(synced, rejected, state.value.queue.size)
    }

    private suspend fun sendHead(head: QueuedMutation): ReplayStep = writeLock.withLock {
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
                    RejectedMutation(
                        id = head.id,
                        table = head.table,
                        operation = head.operation,
                        rowKey = head.rowKey,
                        taskTitle = head.taskTitle,
                        serverError = answer.error,
                        message = answer.error.message,
                        rejectedAt = clock.now(),
                    )
                updateState { it.refused(head, rejection) }
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
            if (start.network != NetworkStatus.Online) return
            updateState { it.copy(refresh = RefreshState.Refreshing) }
            delay(latency)
            writeLock.withLock {
                val current = state.value
                when {
                    current.userId != start.userId -> Unit
                    current.network != NetworkStatus.Online ->
                        updateState { it.copy(refresh = RefreshState.Paused) }
                    current.serverOutage ->
                        updateState {
                            it.copy(
                                refresh = RefreshState.Stalled(ServerUnreachableException()),
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
                                refresh = RefreshState.Idle,
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

    /** Why the server cannot be reached right now, or null when it can. */
    private fun unreachableServerError(): Throwable? {
        val current = state.value
        return when {
            current.network != NetworkStatus.Online -> NetworkUnavailableException()
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
    data class Refused(val rejection: RejectedMutation) : ReplayStep
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
    val refresh: RefreshState = RefreshState.Idle,
    val queue: List<QueuedMutation> = emptyList(),
    val rejected: List<RejectedMutation> = emptyList(),
    /** The refused writes themselves, by rejection id, for a retry. */
    val rejectedWrites: Map<Long, QueuedMutation> = emptyMap(),
    val nextMutationId: Long = 1,
    val networkEnabled: Boolean = true,
    val deviceOnline: Boolean? = null,
    val serverOutage: Boolean = false,
    val isSyncing: Boolean = false,
    val lastSyncedAt: Instant? = null,
    val lastSyncError: Throwable? = null,
) {
    val network: NetworkStatus
        get() =
            when {
                !networkEnabled -> NetworkStatus.Disabled
                deviceOnline == null -> NetworkStatus.Unknown
                deviceOnline -> NetworkStatus.Online
                else -> NetworkStatus.Offline
            }

    val canReachServer: Boolean
        get() = network == NetworkStatus.Online && !serverOutage

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

    private fun <T> snapshotOf(value: T?, hasPendingMutations: Boolean): Snapshot<T> =
        Snapshot(
            value = value,
            isFromCache = !confirmedByServer,
            fetchedAt = fetchedAt,
            hasPendingMutations = hasPendingMutations,
            refresh = refresh,
        )

    fun toSyncStatus(): SyncStatus =
        SyncStatus(
            network = network,
            pendingMutationCount = queue.size,
            rejectedMutationCount = rejected.size,
            isSyncing = isSyncing,
            lastSyncedAt = lastSyncedAt,
            lastSyncError = lastSyncError,
        )

    /** Off the network a refresh is paused and the rows shown are only the device's. */
    fun withRefreshForNetwork(): DeviceState =
        if (network == NetworkStatus.Online) this
        else copy(refresh = RefreshState.Paused, confirmedByServer = false)

    /** The head write accepted: it leaves the queue and the cache takes the server's copy. */
    fun accepted(head: QueuedMutation, storedRow: Task?): DeviceState {
        var rows = head.applyTo(cache ?: DeviceRows())
        if (storedRow != null) rows = rows.copy(tasks = rows.tasks + (storedRow.id to storedRow))
        return copy(queue = queue.drop(1), cache = rows)
    }

    /** The head write refused: it leaves the queue, so the device shows the server's copy again. */
    fun refused(head: QueuedMutation, rejection: RejectedMutation): DeviceState =
        copy(
            queue = queue.drop(1),
            rejected = (listOf(rejection) + rejected).take(REJECTED_MUTATIONS_KEPT),
            rejectedWrites = rejectedWrites + (rejection.id to head),
        )

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
            )
            .withRefreshForNetwork()
}

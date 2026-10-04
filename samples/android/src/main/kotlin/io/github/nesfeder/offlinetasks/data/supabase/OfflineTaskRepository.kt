// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.uploadAsFlow
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PHOTOS_BUCKET
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.offlinetasks.data.PhotoListSnapshot
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.data.UserSession
import io.github.nesfeder.offlinetasks.data.photoObjectPath
import io.github.nesfeder.supabase.offline.CacheMissException
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.QuerySnapshot
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RefreshState
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.offline
import io.ktor.http.ContentType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The column every task and photo is ordered by, which the app stamps on insert. */
private const val CREATED_AT = "created_at"

/** The key column of both tables. */
private const val ID = "id"

/** The owner column of both tables. */
private const val USER_ID = "user_id"

/** The column that ties a photo to its task. */
private const val TASK_ID = "task_id"

/** The photo row's column naming its file in the bucket. */
private const val OBJECT_PATH = "object_path"

/** How many objects one Storage delete request names at most. */
private const val STORAGE_DELETE_BATCH = 100

/**
 * [TaskRepository] over the library: every read is a live `selectAsFlow` query, every write goes
 * through the mutation queue, and photos go through the offline Storage bucket, which keeps the
 * files it downloads on the device.
 *
 * Reads wait for a session: the schema refuses every request without a signed-in user, so until
 * [session] has one, each read shows nothing yet and sends nothing.
 *
 * @param scope where work runs that must finish even when its caller goes away.
 */
class OfflineTaskRepository(
    supabase: SupabaseClient,
    private val session: UserSession,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val newId: () -> String = { Uuid.random().toString() },
) : TaskRepository {

    private val offline = supabase.offline
    private val photoBucket = offline.storage.from(PHOTOS_BUCKET)

    override val syncStatus: StateFlow<AppSyncStatus> =
        offline.syncStatus
            .map { it.toAppSyncStatus() }
            .stateIn(scope, SharingStarted.Eagerly, offline.syncStatus.value.toAppSyncStatus())

    override fun tasks(): Flow<TaskListSnapshot> = taskList { it }

    // The list's query, not one filtered on the id: each query is cached on its own, and offline a
    // query never fetched has nothing to show, while the list is fetched as the app starts.
    override fun task(id: String): Flow<TaskListSnapshot> = taskList { tasks ->
        tasks.filter { it.id == id }
    }

    // The query of allPhotos(), for the same reason as task(): one cached list serves every task.
    override fun photos(taskId: String): Flow<PhotoListSnapshot> = photoList { photos ->
        photos.filter { it.taskId == taskId }.sortedBy { it.createdAt }
    }

    override fun allPhotos(): Flow<PhotoListSnapshot> = photoList { it }

    override suspend fun addTask(title: String, notes: String?): TaskMutationOutcome {
        val row =
            NewTaskRow(
                id = newId(),
                userId = signedInUserId(),
                title = title,
                notes = notes,
                done = false,
                createdAt = clock.now().toString(),
            )
        return write(Tables.TASKS, TaskMutationOperation.Insert, title) {
            offline.from(Tables.TASKS).insert(row)
        }
    }

    override suspend fun updateTask(
        id: String,
        title: String,
        notes: String?,
    ): TaskMutationOutcome =
        write(Tables.TASKS, TaskMutationOperation.Update, title) {
            offline.from(Tables.TASKS).update({
                set("title", title)
                // Sent as an explicit null when cleared, so the server clears it too.
                set("notes", notes)
            }) {
                filter { eq(ID, id) }
            }
        }

    override suspend fun setTaskDone(id: String, isDone: Boolean): TaskMutationOutcome =
        write(Tables.TASKS, TaskMutationOperation.Update, taskTitle = null) {
            offline.from(Tables.TASKS).update({ set("done", isDone) }) { filter { eq(ID, id) } }
        }

    override suspend fun deleteTask(id: String): TaskMutationOutcome = detached {
        val photos = cachedPhotosOf(id)
        // Files first, and only online: Storage writes are never queued, so offline they stay.
        if (canReachNetwork()) {
            val paths = photos?.map { it.objectPath } ?: photoPathsOnServer(TASK_ID, id)
            deleteFiles(paths)
        }
        // The server's cascade would delete the photo rows, but the cache never sees a cascade.
        if (photos == null || photos.isNotEmpty()) {
            write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
                offline.from(Tables.TASK_PHOTOS).delete { filter { eq(TASK_ID, id) } }
            }
        }
        write(Tables.TASKS, TaskMutationOperation.Delete, taskTitle = null) {
            offline.from(Tables.TASKS).delete { filter { eq(ID, id) } }
        }
    }

    override fun attachPhoto(taskId: String, photo: EncodedPhoto): Flow<PhotoUpload> = flow {
        // Storage writes are online-only: offline, fail at once rather than when the request does.
        if (!canReachNetwork()) throw NetworkUnavailableException()
        val userId = signedInUserId()
        val photoId = newId()
        val objectPath = photoObjectPath(userId, taskId, photoId)
        try {
            photoBucket
                .uploadAsFlow(objectPath, photo.jpeg) {
                    // No upsert: the bucket has no update policy, and a path is never reused.
                    upsert = false
                    contentType = ContentType.Image.JPEG
                }
                .collect { status ->
                    if (status is UploadStatus.Progress && status.contentLength > 0) {
                        emit(
                            PhotoUpload.Uploading(
                                status.totalBytesSend.toFloat() / status.contentLength
                            )
                        )
                    }
                }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asStorageFailure(objectPath)
        }
        val row =
            NewTaskPhotoRow(
                id = photoId,
                userId = userId,
                taskId = taskId,
                objectPath = objectPath,
                width = photo.width,
                height = photo.height,
                createdAt = clock.now().toString(),
            )
        write(Tables.TASK_PHOTOS, TaskMutationOperation.Insert, taskTitle = null) {
            offline.from(Tables.TASK_PHOTOS).insert(row)
        }
        emit(
            PhotoUpload.Attached(
                TaskPhoto(
                    id = photoId,
                    userId = userId,
                    taskId = taskId,
                    objectPath = objectPath,
                    width = photo.width,
                    height = photo.height,
                    createdAt = Instant.parse(row.createdAt),
                )
            )
        )
    }

    override suspend fun deletePhoto(photo: TaskPhoto): TaskMutationOutcome = detached {
        if (!canReachNetwork()) throw NetworkUnavailableException()
        deleteFiles(listOf(photo.objectPath))
        write(Tables.TASK_PHOTOS, TaskMutationOperation.Delete, taskTitle = null) {
            offline.from(Tables.TASK_PHOTOS).delete { filter { eq(ID, photo.id) } }
        }
    }

    override suspend fun cachedPhotoFile(photo: TaskPhoto): PhotoFile? =
        photoBucket.cachedFile(photo.objectPath)?.toPhotoFile()

    override suspend fun downloadPhotoFile(photo: TaskPhoto): PhotoFile =
        try {
            photoBucket.ensureCachedFile(photo.objectPath).toPhotoFile()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asStorageFailure(photo.objectPath)
        }

    override fun pendingMutationCount(table: String?): Flow<Int> =
        offline.pendingMutationCountFlow(table)

    override fun rejectedMutations(): Flow<List<RejectedTaskMutation>> =
        // The library lists them oldest first; the app shows the newest first.
        offline.rejectedMutations().map { list ->
            list.asReversed().map { it.toRejectedTaskMutation() }
        }

    override suspend fun dismissRejectedMutation(id: Long) {
        offline.dismissRejectedMutation(id)
    }

    override suspend fun retryRejectedMutation(id: Long) {
        offline.retryRejectedMutation(id)
    }

    override suspend fun syncPendingMutations(): TaskSyncReport =
        offline.syncPendingMutations().toTaskSyncReport()

    override suspend fun refreshTasks() {
        try {
            // The same query as tasks(): its answer replaces that query's cached rows, and every
            // reader of it sees them.
            offline.from(Tables.TASKS).select(readStrategy = ReadStrategy.NetworkOnly) {
                newestFirst()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asReadFailure()
        }
    }

    override suspend fun setNetworkEnabled(enabled: Boolean) {
        if (enabled) offline.enableNetwork() else offline.disableNetwork()
        // The library's status is current when the call returns; this one follows it.
        syncStatus.first { (it.network == AppNetworkStatus.Disabled) != enabled }
    }

    override suspend fun clearLocalData() {
        offline.clearLocalData()
    }

    override suspend fun startOver() {
        // A new anonymous user needs the server, so nothing is deleted or cleared without it.
        if (!canReachNetwork()) throw NetworkUnavailableException()
        detached {
            session.userId.value?.let { deleteEverythingOf(it) }
            offline.clearLocalData()
            session.signInAsNewUser()
        }
    }

    /** The user's tasks, newest first, as [shown] passes them on. */
    private fun taskList(shown: (List<Task>) -> List<Task>): Flow<TaskListSnapshot> =
        whileSignedIn {
            combine(
                offline.from(Tables.TASKS).selectAsFlow { newestFirst() },
                pendingRowIds(Tables.TASKS),
            ) { snapshot, pending ->
                snapshot.toRowsSnapshot { result ->
                    // A task still queued is listed after the server's rows; show it where it sorts.
                    shown(result.decodeList<TaskRow>().map { it.toTask(pending) }.newestFirst())
                }
            }
        }

    /** The user's photos, newest first, as [shown] passes them on. */
    private fun photoList(shown: (List<TaskPhoto>) -> List<TaskPhoto>): Flow<PhotoListSnapshot> =
        whileSignedIn {
            combine(
                offline.from(Tables.TASK_PHOTOS).selectAsFlow { newestFirst() },
                pendingRowIds(Tables.TASK_PHOTOS),
            ) { snapshot, pending ->
                snapshot.toRowsSnapshot { result ->
                    val photos = result.decodeList<TaskPhotoRow>().map { it.toTaskPhoto(pending) }
                    shown(photos.sortedByDescending { it.createdAt })
                }
            }
        }

    /**
     * [read] while there is a session; before, one snapshot that knows nothing and sends nothing.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun <T> whileSignedIn(read: () -> Flow<RowsSnapshot<T>>): Flow<RowsSnapshot<T>> =
        session.userId
            .flatMapLatest { userId -> if (userId == null) flowOf(nothingYet()) else read() }
            .flowOn(Dispatchers.Default)

    /** The ids of [table]'s rows a queued write touches, from each write's row keys. */
    private fun pendingRowIds(table: String): Flow<Set<String>> =
        offline.pendingMutations(table).map { mutations -> mutations.rowIds() }

    /** Runs [write] and turns its outcome and its failure into the repository's terms. */
    private suspend fun write(
        table: String,
        operation: TaskMutationOperation,
        taskTitle: String?,
        write: suspend () -> MutationOutcome,
    ): TaskMutationOutcome =
        try {
            when (val outcome = write()) {
                is MutationOutcome.Applied -> TaskMutationOutcome.Applied
                is MutationOutcome.Queued -> TaskMutationOutcome.Queued(outcome.pendingMutation.id)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asWriteFailure(table, operation, taskTitle)
        }

    /**
     * The photo rows of task [taskId] the device holds, from the same query [photos] reads, or null
     * when it holds none for that query.
     */
    private suspend fun cachedPhotosOf(taskId: String): List<TaskPhotoRow>? =
        try {
            offline
                .from(Tables.TASK_PHOTOS)
                .select(readStrategy = ReadStrategy.CacheOnly()) { newestFirst() }
                .decodeList<TaskPhotoRow>()
                .filter { it.taskId == taskId }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (missing: CacheMissException) {
            null
        }

    /** The object paths of the photo rows whose [column] equals [value], asked of the server. */
    private suspend fun photoPathsOnServer(column: String, value: String): List<String> =
        try {
            offline
                .from(Tables.TASK_PHOTOS)
                .select(Columns.list(OBJECT_PATH), readStrategy = ReadStrategy.NetworkOnly) {
                    filter { eq(column, value) }
                }
                .decodeList<ObjectPathRow>()
                .map { it.objectPath }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw failure.asReadFailure()
        }

    /** Deletes the files at [paths] from the bucket, in batches; a missing file is no error. */
    private suspend fun deleteFiles(paths: List<String>) {
        for (batch in paths.chunked(STORAGE_DELETE_BATCH)) {
            try {
                photoBucket.delete(batch)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                throw failure.asStorageFailure(batch.first())
            }
        }
    }

    /** Deletes [userId]'s files, photo rows and tasks on the server, before that user is left. */
    private suspend fun deleteEverythingOf(userId: String) {
        deleteFiles(photoPathsOnServer(USER_ID, userId))
        // Sent now or not at all: the queue is about to be cleared.
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

    /** Whether the library may reach the network: not switched off, and not known to be offline. */
    private fun canReachNetwork(): Boolean =
        offline.syncStatus.value.network.let {
            it != NetworkStatus.OFFLINE && it != NetworkStatus.DISABLED
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

/**
 * The one order every list is read in, newest first. Each query is cached apart, so every read of a
 * table asks the same query and shares one cached list.
 */
private fun PostgrestRequestBuilder.newestFirst() {
    order(CREATED_AT, Order.DESCENDING)
}

/** The snapshot before there is a session: nothing known, nothing being fetched. */
private fun <T> nothingYet(): RowsSnapshot<T> =
    RowsSnapshot(
        value = null,
        isFromCache = true,
        fetchedAt = null,
        hasPendingMutations = false,
        refresh = TaskRefreshState.Idle,
    )

/** This snapshot in the app's terms, its rows decoded by [rows] when there are any. */
private fun <T> QuerySnapshot.toRowsSnapshot(rows: (PostgrestResult) -> T): RowsSnapshot<T> =
    RowsSnapshot(
        value = result?.let(rows),
        isFromCache = isFromCache,
        fetchedAt = fetchedAt,
        hasPendingMutations = hasPendingMutations,
        refresh = refresh.toTaskRefreshState(),
    )

private fun RefreshState.toTaskRefreshState(): TaskRefreshState =
    when (this) {
        RefreshState.Idle -> TaskRefreshState.Idle
        RefreshState.Refreshing -> TaskRefreshState.Refreshing
        RefreshState.Paused -> TaskRefreshState.Paused
        is RefreshState.Stalled -> TaskRefreshState.Stalled(cause)
    }

/** These tasks newest first, as the server orders the list. */
private fun List<Task>.newestFirst(): List<Task> = sortedByDescending { it.createdAt }

/** The `id` values in the row keys of these queued writes. */
private fun List<PendingMutation>.rowIds(): Set<String> =
    flatMapTo(HashSet()) { mutation ->
        mutation.rowKeys.mapNotNull { key -> key[ID]?.jsonPrimitive?.contentOrNull }
    }

private fun CachedFile.toPhotoFile(): PhotoFile = PhotoFile(path, fetchedAt, sizeBytes)

private fun SyncStatus.toAppSyncStatus(): AppSyncStatus =
    AppSyncStatus(
        network =
            when (network) {
                NetworkStatus.UNKNOWN -> AppNetworkStatus.Unknown
                NetworkStatus.ONLINE -> AppNetworkStatus.Online
                NetworkStatus.OFFLINE -> AppNetworkStatus.Offline
                NetworkStatus.DISABLED -> AppNetworkStatus.Disabled
            },
        pendingMutationCount = pendingMutationCount,
        rejectedMutationCount = rejectedMutationCount,
        isSyncing = isSyncing,
        lastSyncedAt = lastSyncedAt,
        lastSyncError = lastSyncError,
    )

private fun SyncReport.toTaskSyncReport(): TaskSyncReport =
    TaskSyncReport(
        syncedCount = syncedCount,
        rejected = rejected.map { it.toRejectedTaskMutation() },
        remainingCount = remainingCount,
    )

private fun RejectedMutation.toRejectedTaskMutation(): RejectedTaskMutation =
    RejectedTaskMutation(
        id = id,
        table = table,
        operation = operation.toTaskMutationOperation(),
        rowKey = rowKeys.firstOrNull()?.get(ID)?.jsonPrimitive?.contentOrNull,
        taskTitle = body?.titleOrNull(),
        serverError = serverError?.toTaskServerError(),
        message = message,
        rejectedAt = rejectedAt,
    )

private fun ServerError.toTaskServerError(): TaskServerError =
    TaskServerError(status = status, code = code, message = message, details = details, hint = hint)

private fun MutationOperation.toTaskMutationOperation(): TaskMutationOperation =
    when (this) {
        MutationOperation.INSERT -> TaskMutationOperation.Insert
        MutationOperation.UPSERT -> TaskMutationOperation.Upsert
        MutationOperation.UPDATE -> TaskMutationOperation.Update
        MutationOperation.DELETE -> TaskMutationOperation.Delete
    }

/** The `title` a write's body sets: its first row's for an insert, its value for an update. */
private fun JsonElement.titleOrNull(): String? {
    val row = (this as? JsonArray)?.firstOrNull() ?: this
    return (row as? JsonObject)?.get("title")?.jsonPrimitive?.contentOrNull
}

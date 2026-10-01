// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskNetworkStatus
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncStatus
import io.github.nesfeder.offlinetasks.data.takeCharacters
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import io.github.nesfeder.offlinetasks.ui.navigation.ShownTask
import io.github.nesfeder.offlinetasks.ui.photos.PhotoFileState
import io.github.nesfeder.offlinetasks.ui.photos.loadPhotoFile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long typing must pause before an edit is saved. */
val AUTOSAVE_DELAY: Duration = 600.milliseconds

/** Where one task's latest change stands, for the line under its title. */
enum class TaskDetailSyncState {
    /** On the server. */
    Synced,

    /** Changed here while offline: kept on the device until the network is back. */
    SavedOnDevice,

    /** Changed here; it will be sent in a moment. */
    WaitingToSync,

    /** Being sent now. */
    Syncing,
}

/** One photo on its way up, with how much has arrived. */
data class UploadProgress(val id: Long, val fraction: Float)

/** What the task detail screen shows. */
sealed interface TaskDetailUiState {
    /**
     * Nothing read from the device yet. [shown] is the task as the list showed it, when the screen
     * was opened from the list, so the screen is drawn in full from its first frame.
     */
    data class Loading(val shown: ShownTask?) : TaskDetailUiState

    /** The task does not exist: deleted here, on the server, or refused when it replayed. */
    data object Gone : TaskDetailUiState

    /**
     * The task, as edited. [title] and [notes] are what the user typed, saved after a pause;
     * [isTitleBlank] means the title will not be saved until it has some text.
     */
    data class Editing(
        val taskId: String,
        val title: String,
        val notes: String,
        val isTitleBlank: Boolean,
        val isDone: Boolean,
        val syncState: TaskDetailSyncState,
        val photos: List<TaskPhoto>,
        val uploads: List<UploadProgress>,
        /** Photos are uploaded and deleted only when the server can be reached. */
        val canChangePhotos: Boolean,
    ) : TaskDetailUiState
}

/** Something the detail screen says once. */
sealed interface TaskDetailMessage {
    /** The task was deleted from this screen; it closes. */
    data object Deleted : TaskDetailMessage
}

/**
 * One task: its live row and photos, the title and notes saved as you type, the done switch, photo
 * uploads with progress, and deletion.
 *
 * @param writeScope where writes run; it outlives this screen, so an edit typed just before leaving
 *   is still saved.
 * @param failures where a failed write or upload is reported, to be shown as a message.
 * @param shown the task as the list showed it, drawn until the device's copy is read.
 */
@OptIn(FlowPreview::class)
class TaskDetailViewModel(
    private val taskId: String,
    private val repository: TaskRepository,
    private val photoEncoder: PhotoEncoder,
    private val writeScope: CoroutineScope,
    private val failures: FailureReports,
    shown: ShownTask? = null,
    autosaveDelay: Duration = AUTOSAVE_DELAY,
) : ViewModel() {

    private val opening = TaskDetailUiState.Loading(shown)

    /** The title and notes as typed; null until the task has loaded. */
    private val draft = MutableStateFlow<Draft?>(null)

    /** The title and notes the device holds, which the draft is compared with. */
    private var stored: Draft? = null
    private val uploads = MutableStateFlow<List<UploadProgress>>(emptyList())
    private var nextUploadId = 1L
    private val messageChannel = Channel<TaskDetailMessage>(Channel.BUFFERED)
    private val task = MutableStateFlow<TaskLookup>(TaskLookup.Unknown)

    /** One-off messages, each delivered once. */
    val messages: Flow<TaskDetailMessage> = messageChannel.receiveAsFlow()

    /** The screen's state. */
    val uiState: StateFlow<TaskDetailUiState> =
        combine(
                task,
                draft,
                repository.photos(taskId).map { it.value.orEmpty() },
                uploads,
                repository.syncStatus,
            ) { lookup, draft, photos, uploads, status ->
                stateOf(lookup, draft, photos, uploads, status)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), opening)

    init {
        viewModelScope.launchReporting(failures) {
            repository.task(taskId).collect { snapshot ->
                val rows = snapshot.value
                task.value =
                    when {
                        rows == null -> TaskLookup.Unknown
                        rows.isEmpty() -> TaskLookup.Missing
                        else -> TaskLookup.Found(rows.first())
                    }
                rows?.firstOrNull()?.let(::adoptStoredText)
            }
        }
        viewModelScope.launchReporting(failures) {
            draft.filterNotNull().debounce(autosaveDelay).collect { save(it) }
        }
    }

    /** Takes the title as typed; it is saved after a pause unless blank. */
    fun onTitleChange(title: String) {
        draft.update { it?.copy(title = takeCharacters(title, TASK_TITLE_MAX_LENGTH)) }
    }

    /** Takes the notes as typed; they are saved after a pause, and clearing them clears them. */
    fun onNotesChange(notes: String) {
        draft.update { it?.copy(notes = takeCharacters(notes, TASK_NOTES_MAX_LENGTH)) }
    }

    /** Marks the task done or open again. */
    fun setDone(isDone: Boolean) {
        writeScope.launchReporting(failures) { repository.setTaskDone(taskId, isDone) }
    }

    /** Re-encodes the image at [contentUri] and uploads it, reporting progress as it goes. */
    fun attachPhoto(contentUri: String) {
        val uploadId = nextUploadId++
        uploads.update { it + UploadProgress(uploadId, 0f) }
        viewModelScope.launchReporting(failures) {
            try {
                val photo = photoEncoder.encode(contentUri)
                repository.attachPhoto(taskId, photo).collect { progress ->
                    if (progress is PhotoUpload.Uploading) {
                        setUploadProgress(uploadId, progress.fraction)
                    }
                }
            } finally {
                uploads.update { list -> list.filterNot { it.id == uploadId } }
            }
        }
    }

    /** The device's copy of [photo], fetched first if needed. */
    suspend fun photoFile(photo: TaskPhoto): PhotoFileState = repository.loadPhotoFile(photo)

    /**
     * Deletes the task (its files first when online), and closes the screen at once. A refusal is
     * reported, and the task is back in the list.
     */
    fun delete() {
        writeScope.launchReporting(failures) { repository.deleteTask(taskId) }
        messageChannel.trySend(TaskDetailMessage.Deleted)
    }

    override fun onCleared() {
        // An edit typed less than the autosave delay ago is saved on the way out.
        draft.value?.let(::save)
    }

    private fun adoptStoredText(task: Task) {
        val incoming = Draft(task.title, task.notes.orEmpty())
        val current = draft.value
        // Text from elsewhere replaces the draft only where the user has not typed over it.
        if (current == null || current == stored) draft.value = incoming
        stored = incoming
    }

    private fun save(edited: Draft) {
        if (edited == stored || edited.title.isBlank()) return
        stored = edited
        writeScope.launchReporting(failures) {
            repository.updateTask(
                id = taskId,
                title = edited.title.trim(),
                notes = edited.notes.trim().ifEmpty { null },
            )
        }
    }

    private fun setUploadProgress(uploadId: Long, fraction: Float) {
        uploads.update { list ->
            list.map { if (it.id == uploadId) it.copy(fraction = fraction) else it }
        }
    }

    private fun stateOf(
        lookup: TaskLookup,
        draft: Draft?,
        photos: List<TaskPhoto>,
        uploads: List<UploadProgress>,
        status: TaskSyncStatus,
    ): TaskDetailUiState =
        when (lookup) {
            TaskLookup.Unknown -> opening
            TaskLookup.Missing -> TaskDetailUiState.Gone
            is TaskLookup.Found -> {
                val text = draft ?: Draft(lookup.task.title, lookup.task.notes.orEmpty())
                TaskDetailUiState.Editing(
                    taskId = taskId,
                    title = text.title,
                    notes = text.notes,
                    isTitleBlank = text.title.isBlank(),
                    isDone = lookup.task.isDone,
                    syncState = syncStateOf(lookup.task, status),
                    photos = photos,
                    uploads = uploads,
                    canChangePhotos = status.network == TaskNetworkStatus.Online,
                )
            }
        }

    private fun syncStateOf(task: Task, status: TaskSyncStatus): TaskDetailSyncState =
        when {
            !task.hasPendingMutations -> TaskDetailSyncState.Synced
            status.network != TaskNetworkStatus.Online -> TaskDetailSyncState.SavedOnDevice
            status.isSyncing -> TaskDetailSyncState.Syncing
            else -> TaskDetailSyncState.WaitingToSync
        }

    /** A task's editable text. */
    private data class Draft(val title: String, val notes: String)

    /** What the device knows about the task. */
    private sealed interface TaskLookup {
        data object Unknown : TaskLookup

        data object Missing : TaskLookup

        data class Found(val task: Task) : TaskLookup
    }

    companion object {
        /**
         * Builds the ViewModel for the task [taskId] from the app's single instances, drawing
         * [shown] until the task is read.
         */
        fun factory(taskId: String, shown: ShownTask?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val application = offlineTasksApplication
                    TaskDetailViewModel(
                        taskId = taskId,
                        repository = application.repository,
                        photoEncoder = application.photoEncoder,
                        writeScope = application.applicationScope,
                        failures = application.failures,
                        shown = shown,
                    )
                }
            }
    }
}

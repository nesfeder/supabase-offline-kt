// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.takeCharacters
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.Notice
import io.github.nesfeder.offlinetasks.ui.feedback.Notices
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the New-task screen shows. */
sealed interface NewTaskUiState {
    /**
     * The form, as typed so far. [canAdd] is true once the title is a title the server takes;
     * [needsConnection] sends the task now or not at all, never queued; [photos] are the content
     * URIs of the photos picked for it, in the order picked.
     */
    data class Editing(
        val title: String,
        val notes: String,
        val canAdd: Boolean,
        val needsConnection: Boolean = false,
        val photos: List<String> = emptyList(),
    ) : NewTaskUiState

    /** The task is added (or queued) with this [title] and [notes]; the screen closes. */
    data class Added(val title: String, val notes: String) : NewTaskUiState
}

/**
 * The New-task form. The title must not be blank; the field lengths stop at the schema's limits, so
 * the form never queues a write the server would refuse. Photos picked here are filed with the task
 * in the app's pending-photo queue, online or offline.
 *
 * @param photoEncoder re-encodes each picked photo as the bucket takes it, once the task is saved.
 * @param writeScope where the write runs; it outlives this screen, which closes at once.
 * @param failures where a refused write is reported, to be shown on the screen in front by then.
 * @param notices where the outcome is said: saved on the server, or on this device.
 */
class NewTaskViewModel(
    private val repository: TaskRepository,
    private val photoEncoder: PhotoEncoder,
    private val writeScope: CoroutineScope,
    private val failures: FailureReports,
    private val notices: Notices = Notices(),
) : ViewModel() {

    private val state = MutableStateFlow<NewTaskUiState>(NewTaskUiState.Editing("", "", false))

    /** The screen's state. */
    val uiState: StateFlow<NewTaskUiState> = state.asStateFlow()

    /** Takes the title as typed, cut at the schema's limit. */
    fun onTitleChange(title: String) {
        edit { it.copy(title = takeCharacters(title, TASK_TITLE_MAX_LENGTH)) }
    }

    /** Takes the notes as typed, cut at the schema's limit. */
    fun onNotesChange(notes: String) {
        edit { it.copy(notes = takeCharacters(notes, TASK_NOTES_MAX_LENGTH)) }
    }

    /** Chooses whether the task is sent now or not at all, rather than queued when offline. */
    fun onNeedsConnectionChange(needsConnection: Boolean) {
        state.update { current ->
            if (current is NewTaskUiState.Editing) current.copy(needsConnection = needsConnection)
            else current
        }
    }

    /** Adds the photos picked, at the content URIs [contentUris], after those picked before. */
    fun onPhotosPicked(contentUris: List<String>) {
        state.update { current ->
            if (current !is NewTaskUiState.Editing) return@update current
            current.copy(photos = (current.photos + contentUris).distinct())
        }
    }

    /** Takes the photo at [contentUri] off the task before it is added. */
    fun onRemovePhoto(contentUri: String) {
        state.update { current ->
            if (current !is NewTaskUiState.Editing) current
            else current.copy(photos = current.photos - contentUri)
        }
    }

    /**
     * Adds the task, then files its photos in the pending-photo queue. The screen closes straight
     * away, and the outcome is said on the screen in front: saved, with the time the server stored
     * it (the row's `updated_at`, which the server sets; `created_at` is the device's), or saved on
     * this device.
     */
    fun add() {
        val form = state.value as? NewTaskUiState.Editing ?: return
        if (!form.canAdd) return
        state.value = NewTaskUiState.Added(form.title, form.notes)
        val title = form.title.trim()
        writeScope.launchReporting(failures) {
            val outcome =
                repository.addTask(
                    title = title,
                    notes = form.notes.trim().ifEmpty { null },
                    needsConnection = form.needsConnection,
                )
            notices.post(
                when (outcome) {
                    is NewTaskOutcome.Saved -> Notice.TaskSaved(title, outcome.task.updatedAt)
                    is NewTaskOutcome.SavedOnDevice -> Notice.TaskSavedOnDevice(title)
                }
            )
            form.photos.forEach { contentUri -> filePhoto(outcome.taskId, contentUri) }
        }
    }

    /** Files the photo at [contentUri] for the task [taskId]; one that fails is reported alone. */
    private suspend fun filePhoto(taskId: String, contentUri: String) {
        try {
            repository.addPhoto(taskId, photoEncoder.encode(contentUri))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failures.report(failure) // an image that cannot be read; the others still go
        }
    }

    private fun edit(change: (NewTaskUiState.Editing) -> NewTaskUiState.Editing) {
        state.update { current ->
            if (current !is NewTaskUiState.Editing) return@update current
            val edited = change(current)
            edited.copy(canAdd = edited.title.isNotBlank())
        }
    }

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                NewTaskViewModel(
                    repository = application.repository,
                    photoEncoder = application.photoEncoder,
                    writeScope = application.applicationScope,
                    failures = application.failures,
                    notices = application.notices,
                )
            }
        }
    }
}

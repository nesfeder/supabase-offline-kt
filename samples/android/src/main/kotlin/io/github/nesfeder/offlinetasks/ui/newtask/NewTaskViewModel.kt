// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.newtask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.takeCharacters
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the New-task screen shows. */
sealed interface NewTaskUiState {
    /** The form, as typed so far. [canAdd] is true once the title is a title the server takes. */
    data class Editing(val title: String, val notes: String, val canAdd: Boolean) : NewTaskUiState

    /** The task is added (or queued) with this [title] and [notes]; the screen closes. */
    data class Added(val title: String, val notes: String) : NewTaskUiState
}

/**
 * The New-task form. The title must not be blank; the field lengths stop at the schema's limits, so
 * the form never queues a write the server would refuse.
 *
 * @param writeScope where the write runs; it outlives this screen, which closes at once.
 * @param failures where a refused write is reported, to be shown on the screen in front by then.
 */
class NewTaskViewModel(
    private val repository: TaskRepository,
    private val writeScope: CoroutineScope,
    private val failures: FailureReports,
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

    /** Adds the task. It is on the device at once, so the screen can close straight away. */
    fun add() {
        val form = state.value as? NewTaskUiState.Editing ?: return
        if (!form.canAdd) return
        state.value = NewTaskUiState.Added(form.title, form.notes)
        writeScope.launchReporting(failures) {
            repository.addTask(
                title = form.title.trim(),
                notes = form.notes.trim().ifEmpty { null },
            )
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
                    writeScope = application.applicationScope,
                    failures = application.failures,
                )
            }
        }
    }
}

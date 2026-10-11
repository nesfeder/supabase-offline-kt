// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.newtask

import io.github.nesfeder.offlinetasks.data.PendingPhotoState
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskServerError
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.testing.FailingTaskRepository
import io.github.nesfeder.offlinetasks.testing.FakePhotoEncoder
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.awaitPendingCount
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class NewTaskViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val failures = FailureReports()

    @Test
    fun a_blank_title_cannot_be_added() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = NewTaskViewModel(repository, FakePhotoEncoder(), backgroundScope, failures)

        // When
        viewModel.onTitleChange("  \t ")
        viewModel.add()

        // Then — still editing, and nothing written
        val state = assertIs<NewTaskUiState.Editing>(viewModel.uiState.value)
        assertFalse(state.canAdd)
        repository.awaitPendingCount(0)
    }

    @Test
    fun a_task_the_server_refuses_after_the_screen_closed_is_a_message() = runTest {
        // Given — a server that refuses the task for a reason the form cannot know
        val refusal =
            TaskWriteRefusedException(
                table = Tables.TASKS,
                operation = TaskMutationOperation.Insert,
                taskTitle = "Buy stamps",
                serverError =
                    TaskServerError(
                        status = 403,
                        code = "42501",
                        message = "new row violates row-level security policy",
                        details = null,
                        hint = null,
                    ),
            )
        val repository = FailingTaskRepository(repositoryFixture().repository, refusal)
        val viewModel = NewTaskViewModel(repository, FakePhotoEncoder(), backgroundScope, failures)
        viewModel.onTitleChange("Buy stamps")

        // When — added; the screen closes at once
        viewModel.add()

        // Then — the refusal reaches the app's messages instead of vanishing with the screen
        assertIs<NewTaskUiState.Added>(viewModel.uiState.value)
        assertEquals(refusal, failures.reported.first())
    }

    @Test
    fun add_writes_the_trimmed_task_and_closes() = runTest {
        // Given
        val repository = repositoryFixture(online = false).repository
        val viewModel = NewTaskViewModel(repository, FakePhotoEncoder(), backgroundScope, failures)
        viewModel.onTitleChange("  Buy stamps ")
        viewModel.onNotesChange("   ")
        assertTrue((viewModel.uiState.value as NewTaskUiState.Editing).canAdd)

        // When
        viewModel.add()

        // Then — the screen closes and the task is on the device, notes left out
        assertIs<NewTaskUiState.Added>(viewModel.uiState.value)
        val task =
            repository.tasks().first { it.value?.isNotEmpty() == true }.value.orEmpty().single()
        assertEquals("Buy stamps", task.title)
        assertNull(task.notes)
    }

    @Test
    fun photos_picked_for_a_new_task_are_listed_for_it_waiting_to_upload() = runTest {
        // Given — offline, a title, and two photos picked, one of them taken off again
        val repository = repositoryFixture(online = false).repository
        val encoder = FakePhotoEncoder()
        val viewModel = NewTaskViewModel(repository, encoder, backgroundScope, failures)
        viewModel.onTitleChange("Fix the fence")
        viewModel.onPhotosPicked(listOf("content://picked/1", "content://picked/2"))
        viewModel.onPhotosPicked(listOf("content://picked/3"))
        viewModel.onRemovePhoto("content://picked/2")

        // When
        viewModel.add()

        // Then — the task, and its two photos on the device, waiting to upload, in order
        val task =
            repository.tasks().first { it.value?.isNotEmpty() == true }.value.orEmpty().single()
        val photos = repository.photos(task.id).first { it.value?.size == 2 }.value.orEmpty()
        assertTrue(photos.all { it.pendingUpload?.state == PendingPhotoState.Waiting })
        assertEquals(listOf("content://picked/1", "content://picked/3"), encoder.encodedUris)
    }

    @Test
    fun the_title_stops_at_the_schema_limit() = runTest {
        // Given
        val viewModel =
            NewTaskViewModel(
                repositoryFixture().repository,
                FakePhotoEncoder(),
                backgroundScope,
                failures,
            )

        // When
        viewModel.onTitleChange("x".repeat(TASK_TITLE_MAX_LENGTH + 20))

        // Then
        val state = assertIs<NewTaskUiState.Editing>(viewModel.uiState.value)
        assertEquals(TASK_TITLE_MAX_LENGTH, state.title.length)
    }
}

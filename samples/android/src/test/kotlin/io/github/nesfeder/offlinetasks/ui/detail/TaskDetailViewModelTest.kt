// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.testing.FailingReadsTaskRepository
import io.github.nesfeder.offlinetasks.testing.FakePhotoEncoder
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.awaitPendingCount
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.navigation.ShownTask
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Rule

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val failures = FailureReports()

    @Test
    fun an_edit_is_saved_after_typing_pauses() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        viewModel.onTitleChange("Water the basil")

        // Then — not before the pause, and then on the device and the server
        advanceTimeBy(AUTOSAVE_DELAY / 2)
        assertEquals(task.title, repository.titleOf(task.id))
        val saved =
            repository.tasks().first { snapshot ->
                snapshot.value?.any { it.id == task.id && it.title == "Water the basil" } == true &&
                    !snapshot.hasPendingMutations
            }
        assertTrue(saved.value.orEmpty().any { it.title == "Water the basil" })
    }

    @Test
    fun a_blank_title_is_flagged_and_never_saved() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        viewModel.onTitleChange("   ")
        advanceTimeBy(AUTOSAVE_DELAY * 3)

        // Then
        assertTrue(viewModel.editing { it.title == "   " }.isTitleBlank)
        assertEquals(task.title, repository.titleOf(task.id))
        repository.awaitPendingCount(0)
    }

    @Test
    fun clearing_the_notes_saves_no_notes() = runTest {
        // Given — a task with notes
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first { it.notes != null }
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        viewModel.onNotesChange("")

        // Then
        val saved =
            repository.tasks().first { snapshot ->
                snapshot.value?.single { it.id == task.id }?.notes == null &&
                    !snapshot.hasPendingMutations
            }
        assertNull(saved.value.orEmpty().single { it.id == task.id }.notes)
    }

    @Test
    fun the_screen_says_gone_when_the_task_is_deleted() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When — deleted elsewhere
        repository.deleteTask(task.id)

        // Then
        assertEquals(
            TaskDetailUiState.Gone,
            viewModel.uiState.first { it == TaskDetailUiState.Gone },
        )
    }

    @Test
    fun an_attached_photo_shows_its_progress_and_then_the_photo() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        viewModel.attachPhoto("content://picked/1")

        // Then — an upload in progress, then the photo and no upload
        assertTrue(viewModel.editing { it.uploads.isNotEmpty() }.uploads.single().fraction < 1f)
        val done = viewModel.editing { it.photos.isNotEmpty() && it.uploads.isEmpty() }
        assertEquals(task.id, done.photos.single().taskId)
    }

    @Test
    fun a_failing_read_is_a_message_and_the_task_stays_editable() = runTest {
        // Given — the task and its photos read, then their reads failing
        val fixture = repositoryFixture(seedSampleTasks = true).repository
        val task = fixture.awaitServerTasks().first()
        val failure = IllegalStateException("A row the app cannot read")
        val viewModel =
            detailViewModel(FailingReadsTaskRepository(fixture, failure), task.id, backgroundScope)

        // When
        viewModel.editing()

        // Then — reported, not thrown, and the task is still there to edit
        assertEquals(failure, failures.reported.first())
        assertEquals(task.title, assertIs<TaskDetailUiState.Editing>(viewModel.uiState.value).title)
    }

    @Test
    fun an_unreadable_photo_is_reported_and_nothing_uploads() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel =
            detailViewModel(
                repository,
                task.id,
                backgroundScope,
                photoEncoder = FakePhotoEncoder(unreadableUri = "content://broken"),
            )
        viewModel.editing()

        // When
        viewModel.attachPhoto("content://broken")

        // Then
        assertIs<PhotoUnreadableException>(failures.reported.first())
        assertTrue(viewModel.editing { it.uploads.isEmpty() }.photos.isEmpty())
    }

    @Test
    fun an_upload_storage_refuses_is_a_message_and_its_progress_goes() = runTest {
        // Given — Storage refusing
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()
        repository.setStorageFailing(true)

        // When
        viewModel.attachPhoto("content://picked/1")

        // Then — reported, not thrown; no upload left on screen and no photo attached
        assertIs<PhotoStorageException>(failures.reported.first())
        assertTrue(viewModel.editing { it.uploads.isEmpty() }.photos.isEmpty())
    }

    @Test
    fun the_screen_is_drawn_from_the_list_row_until_the_task_is_read() = runTest {
        // Given — what the list showed of a task
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val shown = ShownTask(task.title, task.notes, task.isDone)

        // When — the screen opens
        val viewModel = detailViewModel(repository, task.id, backgroundScope, shown = shown)

        // Then — its first state already holds the row, and the task follows
        assertEquals(TaskDetailUiState.Loading(shown), viewModel.uiState.value)
        assertEquals(task.title, viewModel.editing().title)
    }

    @Test
    fun photos_cannot_be_changed_offline() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        repository.setNetworkEnabled(false)

        // Then
        assertEquals(false, viewModel.editing { !it.canChangePhotos }.canChangePhotos)
    }

    private fun detailViewModel(
        repository: TaskRepository,
        taskId: String,
        writeScope: CoroutineScope,
        photoEncoder: PhotoEncoder = FakePhotoEncoder(),
        shown: ShownTask? = null,
    ) =
        TaskDetailViewModel(
            taskId = taskId,
            repository = repository,
            photoEncoder = photoEncoder,
            writeScope = writeScope,
            failures = failures,
            shown = shown,
        )

    private suspend fun TaskDetailViewModel.editing(
        matches: (TaskDetailUiState.Editing) -> Boolean = { true }
    ): TaskDetailUiState.Editing =
        assertIs<TaskDetailUiState.Editing>(
            uiState.first { it is TaskDetailUiState.Editing && matches(it) }
        )

    private suspend fun TaskRepository.titleOf(taskId: String): String? =
        tasks().first().value?.firstOrNull { it.id == taskId }?.title
}

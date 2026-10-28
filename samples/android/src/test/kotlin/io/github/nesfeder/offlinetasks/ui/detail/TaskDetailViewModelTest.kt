// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

import io.github.nesfeder.offlinetasks.data.PendingPhotoState
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.PhotoUnreadableException
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    /**
     * The device's list can still carry the old title after an autosave: it must not replace what
     * was typed, which would move the field's cursor back (#178).
     */
    @Test
    fun the_old_title_arriving_after_an_autosave_does_not_replace_what_was_typed() = runTest {
        // Given — a title typed and saved, before the device's list has it
        val fixture = repositoryFixture(seedSampleTasks = true).repository
        val task = fixture.awaitServerTasks().first()
        val repository = LaggingRowRepository(fixture, task)
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()
        val typed = "${task.title} abc"
        viewModel.onTitleChange(typed)
        repository.savedTitles.first { typed in it }

        // When — a list update with the write's pending mark but still the old title
        repository.row.value = task.copy(hasPendingMutations = true)

        // Then — what was typed stays
        viewModel.editing { it.syncState != TaskDetailSyncState.Synced }
        runCurrent()
        assertEquals(typed, viewModel.editing().title)
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
    fun an_added_photo_shows_once_uploaded() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()

        // When
        viewModel.addPhoto("content://picked/1")

        // Then — sent at once, the file first, and shown as the server's photo
        val done = viewModel.editing { state ->
            state.photos.singleOrNull()?.let { photo -> photo.pendingUpload == null } == true
        }
        assertEquals(task.id, done.photos.single().taskId)
    }

    @Test
    fun a_photo_added_offline_waits_and_uploads_when_the_network_is_back() = runTest {
        // Given — the screen open, then the network switched off
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()
        repository.setNetworkEnabled(false)

        // When
        viewModel.addPhoto("content://picked/1")

        // Then — waiting on the device
        val waiting = viewModel.editing { it.photos.isNotEmpty() }.photos.single()
        assertEquals(PendingPhotoState.Waiting, waiting.pendingUpload?.state)

        // When — the network back on
        repository.setNetworkEnabled(true)

        // Then
        viewModel.editing { it.photos.singleOrNull()?.pendingUpload == null }
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
        viewModel.addPhoto("content://broken")

        // Then
        assertIs<PhotoUnreadableException>(failures.reported.first())
        assertTrue(viewModel.editing().photos.isEmpty())
    }

    @Test
    fun an_upload_storage_refuses_stays_marked_until_retry_uploads_it() = runTest {
        // Given — a photo added offline, and Storage refusing it once the network is back
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()
        repository.setNetworkEnabled(false)
        viewModel.addPhoto("content://picked/1")
        viewModel.editing { it.photos.isNotEmpty() }
        repository.setStorageFailing(true)

        // When
        repository.setNetworkEnabled(true)

        // Then — the photo stays, marked failed with why
        val failed =
            viewModel
                .editing {
                    it.photos.singleOrNull()?.pendingUpload?.state == PendingPhotoState.Failed
                }
                .photos
                .single()
        assertEquals("Storage refused the upload", failed.pendingUpload?.failure)

        // When — Storage back, and Retry
        repository.setStorageFailing(false)
        viewModel.retryPhoto(failed)

        // Then
        viewModel.editing { it.photos.singleOrNull()?.pendingUpload == null }
    }

    @Test
    fun remove_deletes_a_failed_photo_from_the_device() = runTest {
        // Given — a photo whose upload Storage refused
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val task = repository.awaitServerTasks().first()
        val viewModel = detailViewModel(repository, task.id, backgroundScope)
        viewModel.editing()
        repository.setNetworkEnabled(false)
        viewModel.addPhoto("content://picked/1")
        viewModel.editing { it.photos.isNotEmpty() }
        repository.setStorageFailing(true)
        repository.setNetworkEnabled(true)
        val failed =
            viewModel
                .editing {
                    it.photos.singleOrNull()?.pendingUpload?.state == PendingPhotoState.Failed
                }
                .photos
                .single()

        // When
        viewModel.removePhoto(failed)

        // Then
        assertTrue(viewModel.editing { it.photos.isEmpty() }.photos.isEmpty())
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

/**
 * [delegate], except that the task's row is whatever [row] holds and an edit only records its
 * title: a device whose list has not caught up with a write yet.
 */
private class LaggingRowRepository(private val delegate: TaskRepository, task: Task) :
    TaskRepository by delegate {

    /** The task as the device's list shows it. */
    val row = MutableStateFlow(task)

    /** The titles saved, oldest first. */
    val savedTitles = MutableStateFlow(emptyList<String>())

    override fun task(id: String): Flow<TaskListSnapshot> = row.map { shown ->
        RowsSnapshot(
            value = listOf(shown).filter { it.id == id },
            isFromCache = false,
            fetchedAt = null,
            hasPendingMutations = shown.hasPendingMutations,
            refresh = TaskRefreshState.Idle,
        )
    }

    override suspend fun updateTask(
        id: String,
        title: String,
        notes: String?,
    ): TaskMutationOutcome {
        savedTitles.update { it + title }
        return TaskMutationOutcome.Queued(pendingMutationId = savedTitles.value.size.toLong())
    }
}

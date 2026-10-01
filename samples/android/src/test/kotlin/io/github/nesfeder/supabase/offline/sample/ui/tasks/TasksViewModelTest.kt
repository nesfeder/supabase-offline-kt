// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.tasks

import io.github.nesfeder.supabase.offline.sample.testing.MainDispatcherRule
import io.github.nesfeder.supabase.offline.sample.testing.awaitServerTasks
import io.github.nesfeder.supabase.offline.sample.testing.repositoryFixture
import io.github.nesfeder.supabase.offline.sample.ui.status.SyncHeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class TasksViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun the_tasks_load_split_into_open_and_done() = runTest {
        // Given — the sample tasks on the server
        val viewModel =
            TasksViewModel(repositoryFixture(seedSampleTasks = true).repository, backgroundScope)

        // When
        val state = viewModel.loaded()

        // Then
        assertEquals(3, state.openTasks.size)
        assertEquals(2, state.doneTasks.size)
        assertTrue(state.openTasks.none { it.isDone })
        assertEquals(3, state.openCount)
        assertEquals(SyncHeadline.UpToDate, state.headline)
    }

    @Test
    fun the_open_filter_hides_done_tasks_but_keeps_the_open_count() = runTest {
        // Given
        val viewModel =
            TasksViewModel(repositoryFixture(seedSampleTasks = true).repository, backgroundScope)
        viewModel.loaded()

        // When
        viewModel.setFilter(TaskFilter.Open)

        // Then
        val state = viewModel.loaded { it.filter == TaskFilter.Open }
        assertTrue(state.doneTasks.isEmpty())
        assertEquals(3, state.openTasks.size)
        assertEquals(3, state.openCount)
    }

    @Test
    fun the_first_launch_offline_has_no_data_yet_and_is_not_loading() = runTest {
        // Given — nothing cached and no network
        val viewModel =
            TasksViewModel(repositoryFixture(online = false).repository, backgroundScope)

        // When
        val state = viewModel.uiState.first { it.headline is SyncHeadline.Offline }

        // Then
        val noData = assertIs<TasksUiState.NoDataYet>(state)
        assertFalse(noData.isLoading)
    }

    @Test
    fun a_swiped_task_is_hidden_then_deleted_when_its_snackbar_goes() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = TasksViewModel(repository, backgroundScope)
        val task = viewModel.loaded().openTasks.first()

        // When — swiped away
        viewModel.delete(task)

        // Then — hidden at once, announced, still on the server
        assertTrue(viewModel.loaded { it.openTasks.size == 2 }.openTasks.none { it.id == task.id })
        assertEquals(TasksMessage.Deleted(task.id, task.title), viewModel.messages.first())
        assertEquals(5, repository.awaitServerTasks().size)

        // When — the snackbar goes without Undo
        viewModel.commitDelete(task.id)

        // Then — deleted
        val remaining = repository.tasks().first { snapshot -> snapshot.value?.size == 4 }
        assertTrue(remaining.value.orEmpty().none { it.id == task.id })
    }

    @Test
    fun undo_brings_a_swiped_task_back_and_deletes_nothing() = runTest {
        // Given — a task swiped away
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = TasksViewModel(repository, backgroundScope)
        val task = viewModel.loaded().openTasks.first()
        viewModel.delete(task)
        viewModel.loaded { it.openTasks.size == 2 }

        // When
        viewModel.undoDelete(task.id)
        viewModel.commitDelete(task.id)

        // Then — back, and a late commit does nothing
        assertTrue(viewModel.loaded { it.openTasks.size == 3 }.openTasks.any { it.id == task.id })
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
        assertEquals(5, repository.awaitServerTasks().size)
    }

    @Test
    fun the_headline_counts_changes_saved_offline() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = TasksViewModel(repository, backgroundScope)
        val task = viewModel.loaded().openTasks.first()
        repository.setNetworkEnabled(false)

        // When
        viewModel.setDone(task, isDone = true)

        // Then
        val state = viewModel.loaded { it.headline == SyncHeadline.Offline(pendingCount = 1) }
        assertTrue(state.doneTasks.single { it.id == task.id }.hasPendingMutations)
    }

    @Test
    fun pull_to_refresh_offline_says_the_network_is_needed() = runTest {
        // Given
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = TasksViewModel(repository, backgroundScope)
        viewModel.loaded()
        repository.setNetworkEnabled(false)

        // When
        viewModel.refresh()

        // Then
        assertEquals(TasksMessage.RefreshNeedsNetwork, viewModel.messages.first())
    }

    @Test
    fun a_write_the_server_refuses_is_announced() = runTest {
        // Given — the screen up, with its rejections already read
        val repository = repositoryFixture().repository
        val viewModel = TasksViewModel(repository, backgroundScope)
        viewModel.loaded()

        // When — a blank title replays and is refused
        repository.addTask(" ", notes = null)

        // Then
        val message = assertIs<TasksMessage.Rejected>(viewModel.messages.first())
        assertEquals("23514", message.rejection.serverError?.code)
    }

    /** The first loaded state that [matches]. */
    private suspend fun TasksViewModel.loaded(
        matches: (TasksUiState.Loaded) -> Boolean = { true }
    ): TasksUiState.Loaded =
        uiState.first { it is TasksUiState.Loaded && matches(it) } as TasksUiState.Loaded
}

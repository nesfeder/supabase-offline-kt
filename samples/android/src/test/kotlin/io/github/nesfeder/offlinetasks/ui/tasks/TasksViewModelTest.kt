// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.tasks

import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.testing.FailingReadsTaskRepository
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.addUploadedPhoto
import io.github.nesfeder.offlinetasks.testing.awaitRejectedCount
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.status.SyncHeadline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule

class TasksViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val failures = FailureReports()
    private val signedIn = MutableStateFlow<SessionState>(SessionState.SignedIn)

    @Test
    fun the_tasks_load_split_into_open_and_done() = runTest {
        // Given — the sample tasks on the server
        val viewModel = tasksViewModel(repositoryFixture(seedSampleTasks = true).repository)

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
    fun before_a_session_there_is_nothing_to_show_or_add() = runTest {
        // Given — the first launch, not signed in yet
        signedIn.value = SessionState.SigningIn
        val viewModel = tasksViewModel(repositoryFixture(seedSampleTasks = true).repository)

        // When
        val state = viewModel.uiState.first { it is TasksUiState.NoSession }

        // Then — the no-session state, while the network is there to sign in with
        assertTrue(assertIs<TasksUiState.NoSession>(state).isSigningIn)
    }

    @Test
    fun the_open_filter_hides_done_tasks_but_keeps_the_open_count() = runTest {
        // Given
        val viewModel = tasksViewModel(repositoryFixture(seedSampleTasks = true).repository)
        viewModel.loaded()

        // When
        viewModel.setFilter(TaskFilter.Open)

        // Then
        val state = viewModel.loaded { it.query.filter == TaskFilter.Open }
        assertTrue(state.doneTasks.isEmpty())
        assertEquals(3, state.openTasks.size)
        assertEquals(3, state.openCount)
    }

    @Test
    fun the_first_launch_offline_has_no_data_yet_and_is_not_loading() = runTest {
        // Given — nothing cached and no network
        val viewModel = tasksViewModel(repositoryFixture(online = false).repository)

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
        val viewModel = tasksViewModel(repository)
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
        val viewModel = tasksViewModel(repository)
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
        val viewModel = tasksViewModel(repository)
        val task = viewModel.loaded().openTasks.first()
        repository.setNetworkEnabled(false)

        // When
        viewModel.setDone(task, isDone = true)

        // Then
        val state = viewModel.loaded { it.headline == SyncHeadline.Offline(pendingCount = 1) }
        assertTrue(state.doneTasks.single { it.id == task.id }.hasPendingMutations)
    }

    @Test
    fun pull_to_refresh_offline_answers_from_the_device_and_says_so() = runTest {
        // Given — the list loaded online, then the network switched off
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = tasksViewModel(repository)
        viewModel.loaded()
        repository.setNetworkEnabled(false)

        // When
        viewModel.refresh()

        // Then — the caption says the rows are this device's copy
        val caption = viewModel.loaded { it.caption != null }.caption
        assertTrue(caption?.provenance?.isFromCache == true)
    }

    @Test
    fun pull_to_refresh_reads_again_the_list_the_chips_show() = runTest {
        // Given — the Open chip chosen
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = tasksViewModel(repository)
        viewModel.loaded()
        viewModel.setFilter(TaskFilter.Open)
        viewModel.loaded { it.query.filter == TaskFilter.Open }

        // When
        viewModel.refresh()

        // Then — the open tasks' query, each query being cached on its own
        val asked = repository.refreshedQueries.first { it.isNotEmpty() }
        assertEquals(TaskQuery(TaskFilter.Open), asked.single())
    }

    @Test
    fun mark_all_done_behind_a_chip_reads_again_that_list_and_the_whole_one() = runTest {
        // Given — the Open chip chosen
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = tasksViewModel(repository)
        viewModel.loaded()
        viewModel.setFilter(TaskFilter.Open)
        viewModel.loaded { it.query.filter == TaskFilter.Open }

        // When
        viewModel.markAllDone()

        // Then — the list shown, then the whole list the open count is read from
        val asked = repository.refreshedQueries.first { it.size == 2 }
        assertEquals(listOf(TaskQuery(TaskFilter.Open), TaskQuery()), asked)
    }

    @Test
    fun pull_to_refresh_with_nothing_saved_says_the_network_is_needed() = runTest {
        // Given — a first launch offline: nothing saved on the device
        val viewModel = tasksViewModel(repositoryFixture(online = false).repository)

        // When
        viewModel.refresh()

        // Then
        assertEquals(TasksMessage.RefreshNeedsNetwork, viewModel.messages.first())
    }

    @Test
    fun the_open_chip_asks_for_open_tasks_and_the_search_narrows_them() = runTest {
        // Given
        val viewModel = tasksViewModel(repositoryFixture(seedSampleTasks = true).repository)
        val all = viewModel.loaded()
        val title = all.openTasks.first().title

        // When
        viewModel.setFilter(TaskFilter.Open)
        viewModel.setSearch(title)

        // Then — only open tasks whose title holds the text
        val state = viewModel.loaded { it.query.search == title && it.openTasks.size == 1 }
        assertTrue(state.doneTasks.isEmpty())
        assertEquals(title, state.openTasks.single().title)
    }

    @Test
    fun a_write_the_server_refuses_on_replay_is_announced() = runTest {
        // Given — the screen up offline, with its rejections already read
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        val viewModel = tasksViewModel(repository)
        viewModel.uiState.first { it.headline is SyncHeadline.Offline }

        // When — a blank title queued offline replays and is refused
        repository.addTask(" ", notes = null)
        fixture.goOnline()

        // Then
        val message = assertIs<TasksMessage.Rejected>(viewModel.messages.first())
        assertEquals("23514", message.rejection.serverError?.code)
    }

    @Test
    fun a_refusal_dismissed_before_the_screen_shows_it_is_not_announced() = runTest {
        // Given — a refusal found on replay while the user is on another screen
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        val viewModel = tasksViewModel(repository)
        viewModel.uiState.first { it.headline is SyncHeadline.Offline }
        repository.addTask(" ", notes = null)
        fixture.goOnline()
        val rejection = repository.rejectedMutations().first { it.isNotEmpty() }.single()

        // When — dismissed on the Sync screen before the Tasks screen shows its messages
        repository.dismissRejectedMutation(rejection.id)
        repository.awaitRejectedCount(0)
        testScheduler.runCurrent()

        // Then — the announcement waiting for the screen is dropped
        assertNull(withTimeoutOrNull(10.seconds) { viewModel.messages.first() })
    }

    @Test
    fun a_failed_list_read_starts_again_when_the_network_changes() = runTest {
        // Given — the screen up, its list read failing once it has loaded
        val inMemory = repositoryFixture(seedSampleTasks = true).repository
        val failure = IllegalStateException("A row the app cannot read")
        val repository = FailingReadsTaskRepository(inMemory, failure)
        val viewModel = tasksViewModel(repository)
        backgroundScope.launch { viewModel.uiState.collect {} } // the screen stays up throughout
        viewModel.loaded()
        assertEquals(failure, failures.reported.first())

        // When — reads work again, the network is switched off, and a task is added on the device
        repository.failing.value = false
        inMemory.setNetworkEnabled(false)
        inMemory.addTask("Made after the failure", notes = null)

        // Then — the list was read again, and keeps following the device's copy
        withTimeout(30.seconds) {
            viewModel.loaded { state ->
                state.openTasks.any { it.title == "Made after the failure" }
            }
        }
    }

    @Test
    fun a_failing_read_is_a_message_and_the_list_stays() = runTest {
        // Given — the list loads, then its read fails
        val failure = IllegalStateException("A row the app cannot read")
        val repository =
            FailingReadsTaskRepository(
                repositoryFixture(seedSampleTasks = true).repository,
                failure,
            )
        val viewModel = tasksViewModel(repository)

        // When
        viewModel.loaded()

        // Then — reported for a message, not thrown, and the list still shows
        assertEquals(failure, failures.reported.first())
        assertEquals(3, assertIs<TasksUiState.Loaded>(viewModel.uiState.value).openTasks.size)
    }

    @Test
    fun a_failed_refresh_is_a_message_and_the_indicator_stops() = runTest {
        // Given — loaded, then the server failing while the network stays up
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = tasksViewModel(repository)
        viewModel.loaded()
        repository.setServerOutage(true)

        // When
        viewModel.refresh()

        // Then — reported, not thrown, and the refresh indicator goes away
        assertIs<ServerUnreachableException>(failures.reported.first())
        assertFalse(viewModel.loaded { !it.isRefreshing }.isRefreshing)
    }

    @Test
    fun a_delete_storage_refuses_is_a_message_and_the_task_comes_back() = runTest {
        // Given — a task with a photo, swiped away while Storage refuses
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val viewModel = tasksViewModel(repository)
        val task = viewModel.loaded().openTasks.first()
        repository.addUploadedPhoto(task.id)
        repository.setStorageFailing(true)
        viewModel.delete(task)
        viewModel.loaded { it.openTasks.none { open -> open.id == task.id } }

        // When — its snackbar goes
        viewModel.commitDelete(task.id)

        // Then — reported, and the task is back in the list
        assertIs<PhotoStorageException>(failures.reported.first())
        viewModel.loaded { it.openTasks.any { open -> open.id == task.id } }
    }

    private fun TestScope.tasksViewModel(repository: TaskRepository) =
        TasksViewModel(
            repository,
            sessionState = signedIn,
            commitScope = backgroundScope,
            failures = failures,
        )

    /** The first loaded state that [matches]. */
    private suspend fun TasksViewModel.loaded(
        matches: (TasksUiState.Loaded) -> Boolean = { true }
    ): TasksUiState.Loaded =
        uiState.first { it is TasksUiState.Loaded && matches(it) } as TasksUiState.Loaded
}

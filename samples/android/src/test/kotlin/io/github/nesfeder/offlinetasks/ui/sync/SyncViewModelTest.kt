// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.sync

import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.testing.FailingReadsTaskRepository
import io.github.nesfeder.offlinetasks.testing.FailingTaskRepository
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.awaitPendingCount
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule

class SyncViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn)
    private val failures = FailureReports()

    @Test
    fun simulate_offline_switches_the_network_off_and_on() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = syncViewModel(repository)
        viewModel.ready()

        // When
        viewModel.setSimulateOffline(true)

        // Then
        viewModel.ready { it.status.network == AppNetworkStatus.Disabled }

        // When
        viewModel.setSimulateOffline(false)

        // Then
        viewModel.ready { it.status.network == AppNetworkStatus.Online }
    }

    @Test
    fun the_queue_is_counted_per_table_and_sync_now_reports_what_it_sent() = runTest {
        // Given — two tasks written offline
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        val viewModel = syncViewModel(repository)
        repository.addTask("One", notes = null)
        repository.addTask("Two", notes = null)
        assertEquals(0, viewModel.ready { it.pendingTasks == 2 }.pendingPhotos)

        // When — back online, the user presses Sync now, joining the automatic replay
        fixture.goOnline()
        viewModel.syncNow()

        // Then — everything sent, and the screen says so
        // (each count is its own flow, so the screen settles on them one after another)
        val state = viewModel.ready {
            it.lastReport != null && it.status.pendingMutationCount == 0 && it.pendingTasks == 0
        }
        assertEquals(0, state.lastReport?.remainingCount)
    }

    @Test
    fun an_invalid_title_online_is_refused_at_once_as_a_message() = runTest {
        // Given — online, nothing queued
        val repository = repositoryFixture().repository
        val viewModel = syncViewModel(repository)
        repository.awaitServerTasks()

        // When — the tour's "Queue an invalid title"
        viewModel.queueInvalidTitle()

        // Then — the refusal is reported for a message, and not kept as a rejection
        val refused = assertIs<TaskWriteRefusedException>(failures.reported.first())
        assertEquals("23514", refused.serverError.code)
        assertTrue(viewModel.ready().rejected.isEmpty())
    }

    @Test
    fun an_invalid_title_offline_is_refused_on_replay_and_can_be_dismissed() = runTest {
        // Given — offline
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        val viewModel = syncViewModel(repository)

        // When — queued, then replayed once the device is back
        viewModel.queueInvalidTitle()
        repository.awaitPendingCount(1)
        fixture.goOnline()

        // Then
        val rejection = viewModel.ready { it.rejected.isNotEmpty() }.rejected.single()
        assertEquals("23514", rejection.serverError?.code)

        // When
        viewModel.dismiss(rejection)

        // Then
        assertTrue(viewModel.ready { it.rejected.isEmpty() }.rejected.isEmpty())
    }

    @Test
    fun a_failed_sync_now_is_a_message_and_the_button_comes_back() = runTest {
        // Given — a replay that fails
        val failure = ServerUnreachableException()
        val repository = FailingTaskRepository(repositoryFixture().repository, failure)
        val viewModel = syncViewModel(repository)
        viewModel.ready()

        // When
        viewModel.syncNow()

        // Then — reported, not thrown, and "Sync now" can be pressed again
        assertEquals(failure, failures.reported.first())
        assertFalse(viewModel.ready { !it.isSyncNowRunning }.isSyncNowRunning)
    }

    @Test
    fun a_failing_read_is_a_message_and_the_screen_stays() = runTest {
        // Given — the screen's reads failing after they load
        val failure = IllegalStateException("A row the app cannot read")
        val repository =
            FailingReadsTaskRepository(
                repositoryFixture(seedSampleTasks = true).repository,
                failure,
            )
        val viewModel = syncViewModel(repository)

        // When
        viewModel.ready()

        // Then — reported, not thrown, and the screen still shows
        assertEquals(failure, failures.reported.first())
        assertIs<SyncUiState.Ready>(viewModel.uiState.value)
    }

    @Test
    fun before_a_session_an_invalid_title_is_not_queued() = runTest {
        // Given — the first launch, offline, before any sign-in
        session.value = SessionState.SigningIn
        val repository = repositoryFixture(online = false).repository
        val viewModel = syncViewModel(repository)
        viewModel.ready { it.session == SessionState.SigningIn }

        // When — the row is disabled, and the call is a no-op if it is reached anyway
        viewModel.queueInvalidTitle()

        // Then — nothing written, nothing reported
        testScheduler.runCurrent()
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
        assertNull(withTimeoutOrNull(10.seconds) { failures.reported.first() })
    }

    @Test
    fun clearing_local_data_drops_what_waits() = runTest {
        // Given
        val repository = repositoryFixture(online = false).repository
        val viewModel = syncViewModel(repository)
        repository.addTask("Never sent", notes = null)
        viewModel.ready { it.status.pendingMutationCount == 1 }

        // When
        viewModel.clearLocalData()

        // Then
        viewModel.ready { it.status.pendingMutationCount == 0 }
    }

    private fun TestScope.syncViewModel(repository: TaskRepository) =
        SyncViewModel(repository, session, writeScope = backgroundScope, failures = failures)

    private suspend fun SyncViewModel.ready(
        matches: (SyncUiState.Ready) -> Boolean = { true }
    ): SyncUiState.Ready =
        assertIs<SyncUiState.Ready>(uiState.first { it is SyncUiState.Ready && matches(it) })
}

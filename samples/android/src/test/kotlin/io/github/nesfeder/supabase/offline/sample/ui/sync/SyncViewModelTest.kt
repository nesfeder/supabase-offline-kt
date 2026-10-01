// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.sync

import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.platform.SessionState
import io.github.nesfeder.supabase.offline.sample.testing.MainDispatcherRule
import io.github.nesfeder.supabase.offline.sample.testing.awaitServerTasks
import io.github.nesfeder.supabase.offline.sample.testing.repositoryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class SyncViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val session = MutableStateFlow<SessionState>(SessionState.SignedIn)

    @Test
    fun simulate_offline_switches_the_network_off_and_on() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = SyncViewModel(repository, session, backgroundScope)
        viewModel.ready()

        // When
        viewModel.setSimulateOffline(true)

        // Then
        viewModel.ready { it.status.network == NetworkStatus.Disabled }

        // When
        viewModel.setSimulateOffline(false)

        // Then
        viewModel.ready { it.status.network == NetworkStatus.Online }
    }

    @Test
    fun the_queue_is_counted_per_table_and_sync_now_reports_what_it_sent() = runTest {
        // Given — two tasks written offline
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        val viewModel = SyncViewModel(repository, session, backgroundScope)
        repository.addTask("One", notes = null)
        repository.addTask("Two", notes = null)
        assertEquals(0, viewModel.ready { it.pendingTasks == 2 }.pendingPhotos)

        // When — back online, the user presses Sync now, joining the automatic replay
        fixture.goOnline()
        viewModel.syncNow()

        // Then — everything sent, and the screen says so
        // (each count is its own flow, so the screen settles on them one after another)
        val state =
            viewModel.ready {
                it.lastReport != null && it.status.pendingMutationCount == 0 && it.pendingTasks == 0
            }
        assertEquals(0, state.lastReport?.remainingCount)
    }

    @Test
    fun an_invalid_title_is_refused_and_can_be_dismissed() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = SyncViewModel(repository, session, backgroundScope)
        repository.awaitServerTasks()

        // When
        viewModel.queueInvalidTitle()

        // Then
        val rejection = viewModel.ready { it.rejected.isNotEmpty() }.rejected.single()
        assertEquals("23514", rejection.serverError?.code)

        // When
        viewModel.dismiss(rejection)

        // Then
        assertTrue(viewModel.ready { it.rejected.isEmpty() }.rejected.isEmpty())
    }

    @Test
    fun the_in_memory_server_can_be_made_to_fail() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = SyncViewModel(repository, session, backgroundScope)

        // When
        viewModel.setServerOutage(true)

        // Then
        assertEquals(true, viewModel.ready { it.serverOutage == true }.serverOutage)
    }

    @Test
    fun clearing_local_data_drops_what_waits() = runTest {
        // Given
        val repository = repositoryFixture(online = false).repository
        val viewModel = SyncViewModel(repository, session, backgroundScope)
        repository.addTask("Never sent", notes = null)
        viewModel.ready { it.status.pendingMutationCount == 1 }

        // When
        viewModel.clearLocalData()

        // Then
        viewModel.ready { it.status.pendingMutationCount == 0 }
    }

    private suspend fun SyncViewModel.ready(
        matches: (SyncUiState.Ready) -> Boolean = { true }
    ): SyncUiState.Ready =
        assertIs<SyncUiState.Ready>(uiState.first { it is SyncUiState.Ready && matches(it) })
}

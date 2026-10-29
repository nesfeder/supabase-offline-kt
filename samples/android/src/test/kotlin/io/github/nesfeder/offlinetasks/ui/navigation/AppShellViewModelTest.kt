// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.navigation

import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class AppShellViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun the_sync_badge_waits_for_the_queue_to_be_counted() = runTest {
        // Given — a repository whose status has not counted the queue yet, as the library's is for
        // a moment after it opens, over a device with two writes waiting
        val status = MutableStateFlow(STARTING)
        val repository =
            object : TaskRepository by repositoryFixture().repository {
                override val syncStatus: StateFlow<AppSyncStatus> = status
            }
        val viewModel = AppShellViewModel(repository, FailureReports())
        val shown = mutableListOf<AppShellUiState>()
        backgroundScope.launch { viewModel.uiState.collect { shown += it } }

        // When — the badges are drawn before the count, then the count lands
        val beforeTheCount = viewModel.uiState.first { it.openTaskCount != null }
        status.value = STARTING.copy(pendingMutationCount = 2, isQueueCounted = true)
        val counted = viewModel.uiState.first { it.pendingCount != null }

        // Then — the Sync badge showed nothing, never a zero, until the queue was counted
        assertNull(beforeTheCount.pendingCount)
        assertEquals(2, counted.pendingCount)
        assertTrue(shown.none { it.pendingCount == 0 }, "$shown")
    }

    private companion object {
        /** The library's status before its first count: zeros for want of a count. */
        val STARTING =
            AppSyncStatus(
                network = AppNetworkStatus.Online,
                pendingMutationCount = 0,
                rejectedMutationCount = 0,
                isQueueCounted = false,
                isSyncing = false,
                lastSyncedAt = null,
                lastSyncError = null,
            )
    }
}

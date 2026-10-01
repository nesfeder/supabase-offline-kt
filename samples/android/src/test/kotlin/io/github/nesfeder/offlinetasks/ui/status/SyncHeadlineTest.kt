// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.status

import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class SyncHeadlineTest {

    @Test
    fun offline_wins_over_everything_else() {
        // Given
        val status = status(AppNetworkStatus.Disabled, pending = 2, isSyncing = true)

        // When
        val headline = syncHeadlineOf(status, TaskRefreshState.Stalled(IllegalStateException()))

        // Then
        assertEquals(SyncHeadline.Offline(pendingCount = 2), headline)
    }

    @Test
    fun syncing_then_a_stalled_refresh_then_waiting_then_up_to_date() {
        assertEquals(
            SyncHeadline.Syncing(3),
            syncHeadlineOf(
                status(AppNetworkStatus.Online, 3, isSyncing = true),
                TaskRefreshState.Idle,
            ),
        )
        assertEquals(
            SyncHeadline.ShowingSavedData,
            syncHeadlineOf(
                status(AppNetworkStatus.Online, 3),
                TaskRefreshState.Stalled(Exception()),
            ),
        )
        assertEquals(
            SyncHeadline.Waiting(3),
            syncHeadlineOf(status(AppNetworkStatus.Online, 3), TaskRefreshState.Refreshing),
        )
        assertEquals(
            SyncHeadline.UpToDate,
            syncHeadlineOf(status(AppNetworkStatus.Online, 0), TaskRefreshState.Idle),
        )
    }

    @Test
    fun a_finished_sync_shows_synced_for_a_moment() = runTest {
        // Given — a sync that empties the queue
        val headlines = flow {
            emit(SyncHeadline.Syncing(1))
            emit(SyncHeadline.UpToDate)
        }

        // When
        val shown = headlines.withSyncedMoment().toList()

        // Then
        assertEquals(
            listOf(SyncHeadline.Syncing(1), SyncHeadline.JustSynced, SyncHeadline.UpToDate),
            shown,
        )
    }

    @Test
    fun up_to_date_from_the_start_shows_no_synced_moment() = runTest {
        // When
        val shown = flow { emit(SyncHeadline.UpToDate) }.withSyncedMoment().toList()

        // Then
        assertEquals(listOf<SyncHeadline>(SyncHeadline.UpToDate), shown)
    }

    private fun status(network: AppNetworkStatus, pending: Int, isSyncing: Boolean = false) =
        AppSyncStatus(
            network = network,
            pendingMutationCount = pending,
            rejectedMutationCount = 0,
            isSyncing = isSyncing,
            lastSyncedAt = null,
            lastSyncError = null,
        )
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.supabase.offline.sample.data.RefreshState
import io.github.nesfeder.supabase.offline.sample.data.RejectedMutation
import io.github.nesfeder.supabase.offline.sample.data.ServerOutageSwitch
import io.github.nesfeder.supabase.offline.sample.data.SyncReport
import io.github.nesfeder.supabase.offline.sample.data.SyncStatus
import io.github.nesfeder.supabase.offline.sample.data.Tables
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.offlineTasksApplication
import io.github.nesfeder.supabase.offline.sample.platform.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The blank title the developer action queues, which the server's title rule refuses. */
const val INVALID_TITLE: String = "   "

/** What the Sync screen shows. */
sealed interface SyncUiState {
    /** Waiting for the first reading of the queue and the network. */
    data object Loading : SyncUiState

    /** Everything the app knows about syncing, and the switches that change it. */
    data class Ready(
        val status: SyncStatus,
        val pendingTasks: Int,
        val pendingPhotos: Int,
        /** The task list's refresh from the server. */
        val refresh: RefreshState,
        /** Newest first. */
        val rejected: List<RejectedMutation>,
        val session: SessionState,
        /** Whether the server is failing on purpose; null when this build cannot fail it. */
        val serverOutage: Boolean?,
        /** True while a "Sync now" the user pressed is running. */
        val isSyncNowRunning: Boolean,
        /** What the last "Sync now" did, until the next one. */
        val lastReport: SyncReport?,
    ) : SyncUiState
}

/**
 * The Sync screen: the network and the simulate-offline switch, the mutation queue per table, the
 * refresh state, refused writes with dismiss and retry, and the two ways to start again.
 *
 * @param writeScope where clearing and starting over run, so leaving the screen cannot cut them
 *   short.
 */
class SyncViewModel(
    private val repository: TaskRepository,
    sessionState: StateFlow<SessionState>,
    private val writeScope: CoroutineScope,
) : ViewModel() {

    private val outageSwitch = repository as? ServerOutageSwitch
    private val syncNow = MutableStateFlow(SyncNowRun(isRunning = false, report = null))

    private val queue: Flow<QueueCounts> =
        combine(
            repository.pendingMutationCount(Tables.TASKS),
            repository.pendingMutationCount(Tables.TASK_PHOTOS),
            ::QueueCounts,
        )

    /** The screen's state. */
    val uiState: StateFlow<SyncUiState> =
        combine(
                combine(repository.syncStatus, queue, repository.tasks().map { it.refresh }) {
                    status,
                    counts,
                    refresh ->
                    Triple(status, counts, refresh)
                },
                repository.rejectedMutations(),
                sessionState,
                outageSwitch?.isServerOutage ?: flowOf(null),
                syncNow,
            ) { (status, counts, refresh), rejected, session, outage, run ->
                SyncUiState.Ready(
                    status = status,
                    pendingTasks = counts.tasks,
                    pendingPhotos = counts.photos,
                    refresh = refresh,
                    rejected = rejected,
                    session = session,
                    serverOutage = outage,
                    isSyncNowRunning = run.isRunning,
                    lastReport = run.report,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState.Loading)

    /** Switches the app's own network access off or on again: "Simulate offline". */
    fun setSimulateOffline(simulate: Boolean) {
        viewModelScope.launch { repository.setNetworkEnabled(!simulate) }
    }

    /** Replays the mutation queue now and keeps what it did for the screen. */
    fun syncNow() {
        if (syncNow.value.isRunning) return
        syncNow.value = SyncNowRun(isRunning = true, report = null)
        viewModelScope.launch {
            val report = repository.syncPendingMutations()
            syncNow.value = SyncNowRun(isRunning = false, report = report)
        }
    }

    /** Forgets a refused write. */
    fun dismiss(rejection: RejectedMutation) {
        viewModelScope.launch { repository.dismissRejectedMutation(rejection.id) }
    }

    /** Queues a refused write again. */
    fun retry(rejection: RejectedMutation) {
        viewModelScope.launch { repository.retryRejectedMutation(rejection.id) }
    }

    /** Queues a task whose title the server refuses, to show what a refused write looks like. */
    fun queueInvalidTitle() {
        writeScope.launch { repository.addTask(title = INVALID_TITLE, notes = null) }
    }

    /** Makes every request to the server fail, or stops doing so. */
    fun setServerOutage(active: Boolean) {
        outageSwitch?.setServerOutage(active)
    }

    /** Deletes everything this device holds, unsynced changes included. */
    fun clearLocalData() {
        writeScope.launch { repository.clearLocalData() }
    }

    /** Clears local data and continues as a new anonymous user. */
    fun startOver() {
        writeScope.launch { repository.startOver() }
    }

    private data class QueueCounts(val tasks: Int, val photos: Int)

    private data class SyncNowRun(val isRunning: Boolean, val report: SyncReport?)

    companion object {
        /** Builds the ViewModel from the app's single instances. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                SyncViewModel(
                    repository = application.repository,
                    sessionState = application.session.state,
                    writeScope = application.applicationScope,
                )
            }
        }
    }
}

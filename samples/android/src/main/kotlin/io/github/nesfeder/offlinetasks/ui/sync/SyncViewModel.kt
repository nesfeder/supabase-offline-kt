// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The blank title the developer action queues, which the server's title rule refuses. */
const val INVALID_TITLE: String = "   "

/** What the Sync screen shows. */
sealed interface SyncUiState {
    /** Waiting for the first reading of the queue and the network. */
    data object Loading : SyncUiState

    /** Everything the app knows about syncing, and the switches that change it. */
    data class Ready(
        val status: AppSyncStatus,
        val pendingTasks: Int,
        val pendingPhotos: Int,
        /** The task list's refresh from the server. */
        val refresh: TaskRefreshState,
        /** Newest first. */
        val rejected: List<RejectedTaskMutation>,
        val session: SessionState,
        /** True while a "Sync now" the user pressed is running. */
        val isSyncNowRunning: Boolean,
        /** What the last "Sync now" did, until the next one. */
        val lastReport: TaskSyncReport?,
    ) : SyncUiState
}

/**
 * The Sync screen: the network and the simulate-offline switch, the mutation queue per table, the
 * refresh state, refused writes with dismiss and retry, and the two ways to start again.
 *
 * @param writeScope where clearing and starting over run, so leaving the screen cannot cut them
 *   short.
 * @param failures where every failure is reported, to be shown as a message.
 */
class SyncViewModel(
    private val repository: TaskRepository,
    sessionState: StateFlow<SessionState>,
    private val writeScope: CoroutineScope,
    private val failures: FailureReports,
) : ViewModel() {
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
                syncNow,
            ) { (status, counts, refresh), rejected, session, run ->
                SyncUiState.Ready(
                    status = status,
                    pendingTasks = counts.tasks,
                    pendingPhotos = counts.photos,
                    refresh = refresh,
                    rejected = rejected,
                    session = session,
                    isSyncNowRunning = run.isRunning,
                    lastReport = run.report,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState.Loading)

    /** Switches the app's own network access off or on again: "Simulate offline". */
    fun setSimulateOffline(simulate: Boolean) {
        viewModelScope.launchReporting(failures) { repository.setNetworkEnabled(!simulate) }
    }

    /** Replays the mutation queue now and keeps what it did for the screen. */
    fun syncNow() {
        if (syncNow.value.isRunning) return
        syncNow.value = SyncNowRun(isRunning = true, report = null)
        viewModelScope.launchReporting(failures) {
            var report: TaskSyncReport? = null
            try {
                report = repository.syncPendingMutations()
            } finally {
                // Failed or not, the button comes back.
                syncNow.value = SyncNowRun(isRunning = false, report = report)
            }
        }
    }

    /** Forgets a refused write. */
    fun dismiss(rejection: RejectedTaskMutation) {
        viewModelScope.launchReporting(failures) {
            repository.dismissRejectedMutation(rejection.id)
        }
    }

    /** Queues a refused write again. */
    fun retry(rejection: RejectedTaskMutation) {
        viewModelScope.launchReporting(failures) { repository.retryRejectedMutation(rejection.id) }
    }

    /**
     * Adds a task whose title the server refuses, to show what a refused write looks like: online
     * it is refused at once and reported as a message; offline it is refused when it replays and
     * listed under the refused changes.
     */
    fun queueInvalidTitle() {
        writeScope.launchReporting(failures) {
            repository.addTask(title = INVALID_TITLE, notes = null)
        }
    }

    /** Deletes everything this device holds, unsynced changes included. */
    fun clearLocalData() {
        writeScope.launchReporting(failures) { repository.clearLocalData() }
    }

    /** Deletes this user's tasks and photos when online, then continues as a new anonymous user. */
    fun startOver() {
        writeScope.launchReporting(failures) { repository.startOver() }
    }

    private data class QueueCounts(val tasks: Int, val photos: Int)

    private data class SyncNowRun(val isRunning: Boolean, val report: TaskSyncReport?)

    companion object {
        /** Builds the ViewModel from the app's single instances. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                SyncViewModel(
                    repository = application.repository,
                    sessionState = application.session.state,
                    writeScope = application.applicationScope,
                    failures = application.failures,
                )
            }
        }
    }
}

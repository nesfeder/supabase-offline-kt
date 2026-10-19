// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.BucketListing
import io.github.nesfeder.offlinetasks.data.ConflictChoice
import io.github.nesfeder.offlinetasks.data.ConflictedTaskMutation
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.ServerCounts
import io.github.nesfeder.offlinetasks.data.SyncSettings
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.data.TaskSyncReport
import io.github.nesfeder.offlinetasks.data.WaitingChange
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.awaitNetworkChange
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import io.github.nesfeder.offlinetasks.ui.feedback.readingAgainAfterFailures
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The developer actions of the Try it section, each a call the library makes or refuses. */
enum class TryIt {
    /** A task the server's title check refuses. */
    InvalidTitle,

    /** Five tasks in one write. */
    SampleTasks,

    /** The starter list, imported so a second time changes nothing. */
    StarterList,

    /** A grouped delete, which the queue cannot hold, made while the network is off. */
    GroupedDelete,

    /** Two top-level filters on one column, which the library refuses. */
    TwoFilters,

    /** A count of open tasks on the server, with the generic filter. */
    CountOpen,

    /** A copy of the newest photo, uploaded through a signed URL. */
    SignedUpload,
}

/** What one Try it action did, said in one line under it. */
sealed interface TryItResult {
    /** It is running. */
    data object Running : TryItResult

    /** A write that was sent at once or queued. */
    data class Written(val outcome: TaskMutationOutcome) : TryItResult

    /** A count the server gave. */
    data class Counted(val count: Int) : TryItResult

    /** It worked, with nothing more to say. */
    data object Done : TryItResult

    /** It worked, but there was nothing to act on (no photo to copy, say). */
    data object NothingToDo : TryItResult

    /** It failed, as the library or the server said. */
    data class Failed(val failure: Throwable) : TryItResult
}

/** A call to the server the Sync screen makes on demand: not asked yet, asking, or answered. */
sealed interface ServerAnswer<out T> {
    /** Not asked yet. */
    data object NotAsked : ServerAnswer<Nothing>

    /** Asking now. */
    data object Asking : ServerAnswer<Nothing>

    /** The answer. */
    data class Answered<T>(val value: T) : ServerAnswer<T>

    /** The call failed. */
    data class Failed(val failure: Throwable) : ServerAnswer<Nothing>
}

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
        /** The waiting writes, in the order they will be sent. */
        val waiting: List<WaitingChange> = emptyList(),
        val settings: SyncSettings = SyncSettings(),
        val liveUpdates: LiveUpdatesStatus = LiveUpdatesStatus.Off,
        /** The Server section's counts. */
        val serverCounts: ServerAnswer<ServerCounts> = ServerAnswer.NotAsked,
        /** The Storage section's listing of the bucket. */
        val bucket: ServerAnswer<BucketListing> = ServerAnswer.NotAsked,
        /** What each Try it action did last. */
        val tryIt: Map<TryIt, TryItResult> = emptyMap(),
        /** The writes parked on a conflict, newest first. */
        val conflicted: List<ConflictedTaskMutation> = emptyList(),
    ) : SyncUiState
}

/**
 * The Sync screen: the network and the simulate-offline switch, the two settings, the mutation
 * queue as rows, the refresh state, refused writes with dismiss and retry, conflicting writes with
 * Keep mine and Keep theirs, the server's counts, the bucket, the Try it actions, and the two ways
 * to start again.
 *
 * @param writeScope where clearing and starting over run, so leaving the screen cannot cut them
 *   short.
 * @param failures where every failure is reported, to be shown as a message.
 */
class SyncViewModel(
    private val repository: TaskRepository,
    private val sessionState: StateFlow<SessionState>,
    private val writeScope: CoroutineScope,
    private val failures: FailureReports,
) : ViewModel() {
    private val syncNow = MutableStateFlow(SyncNowRun(isRunning = false, report = null))
    private val serverCounts = MutableStateFlow<ServerAnswer<ServerCounts>>(ServerAnswer.NotAsked)
    private val bucket = MutableStateFlow<ServerAnswer<BucketListing>>(ServerAnswer.NotAsked)
    private val tryIt = MutableStateFlow<Map<TryIt, TryItResult>>(emptyMap())

    private val queue: Flow<QueueCounts> =
        combine(
            repository.pendingMutationCount(Tables.TASKS).reportingFailures(failures),
            repository.pendingMutationCount(Tables.TASK_PHOTOS).reportingFailures(failures),
            repository.waitingChanges().reportingFailures(failures),
            ::QueueCounts,
        )

    private val calls: Flow<OnDemand> = combine(serverCounts, bucket, tryIt, syncNow, ::OnDemand)

    private val attention: Flow<Attention> =
        combine(
            repository.rejectedMutations().reportingFailures(failures),
            repository.conflictedMutations().reportingFailures(failures),
            ::Attention,
        )

    private val switches: Flow<Switches> =
        combine(repository.settings, repository.liveUpdatesStatus, ::Switches)

    /** The screen's state. */
    val uiState: StateFlow<SyncUiState> =
        combine(
                combine(
                    repository.syncStatus,
                    queue,
                    repository
                        .tasks()
                        .map { it.refresh }
                        .readingAgainAfterFailures(failures) {
                            repository.syncStatus.awaitNetworkChange()
                        },
                    ::Triple,
                ),
                attention,
                sessionState,
                calls,
                switches,
            ) { (status, counts, refresh), needsUser, session, onDemand, switch ->
                SyncUiState.Ready(
                    status = status,
                    pendingTasks = counts.tasks,
                    pendingPhotos = counts.photos,
                    refresh = refresh,
                    rejected = needsUser.rejected,
                    session = session,
                    isSyncNowRunning = onDemand.syncNow.isRunning,
                    lastReport = onDemand.syncNow.report,
                    waiting = counts.waiting,
                    settings = switch.settings,
                    liveUpdates = switch.liveUpdates,
                    serverCounts = onDemand.serverCounts,
                    bucket = onDemand.bucket,
                    tryIt = onDemand.tryIt,
                    conflicted = needsUser.conflicted,
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncUiState.Loading)

    /** Switches the app's own network access off or on again: "Simulate offline". */
    fun setSimulateOffline(simulate: Boolean) {
        viewModelScope.launchReporting(failures) { repository.setNetworkEnabled(!simulate) }
    }

    /** "Prefer fresh data": the list asks the server first, rather than show this device's copy. */
    fun setPreferFreshData(prefer: Boolean) {
        repository.setPreferFreshData(prefer)
    }

    /** "Live updates": other devices' changes appear as they happen, through Realtime. */
    fun setLiveUpdates(enabled: Boolean) {
        repository.setLiveUpdates(enabled)
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

    /** Throws a waiting write away without sending it. */
    fun discard(change: WaitingChange) {
        viewModelScope.launchReporting(failures) { repository.discardWaitingChange(change.id) }
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

    /** Settles a conflicting write: this device's change sent again, or the server's row kept. */
    fun resolve(conflict: ConflictedTaskMutation, choice: ConflictChoice) {
        viewModelScope.launchReporting(failures) {
            repository.resolveConflictedMutation(conflict.id, choice)
        }
    }

    /** Asks the server for the user's counts: a count and a database function. */
    fun askServer() {
        serverCounts.value = ServerAnswer.Asking
        viewModelScope.launch { serverCounts.value = answerOf { repository.serverCounts() } }
    }

    /** Lists the user's task folders in the bucket, from the [offset]th. */
    fun listBucket(offset: Int = 0) {
        bucket.value = ServerAnswer.Asking
        viewModelScope.launch { bucket.value = answerOf { repository.listBucket(offset) } }
    }

    /**
     * Runs a Try it action and keeps what it did for the line under it. Before there is a session
     * there is no user to write for, and the screen does not offer them.
     */
    fun tryIt(action: TryIt) {
        if (sessionState.value != SessionState.SignedIn) return
        tryIt.update { it + (action to TryItResult.Running) }
        writeScope.launch {
            val result =
                try {
                    run(action)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    TryItResult.Failed(failure)
                }
            tryIt.update { it + (action to result) }
        }
    }

    /** Adds a task whose title the server refuses: the Try it action that came first. */
    fun queueInvalidTitle() {
        tryIt(TryIt.InvalidTitle)
    }

    /** Deletes everything this device holds, unsynced changes included. */
    fun clearLocalData() {
        writeScope.launchReporting(failures) { repository.clearLocalData() }
    }

    /** Deletes this user's tasks and photos when online, then continues as a new anonymous user. */
    fun startOver() {
        writeScope.launchReporting(failures) { repository.startOver() }
    }

    private suspend fun run(action: TryIt): TryItResult =
        when (action) {
            TryIt.InvalidTitle -> TryItResult.Written(repository.addTaskWithBlankTitle())
            TryIt.SampleTasks -> TryItResult.Written(repository.addSampleTasks())
            TryIt.StarterList -> TryItResult.Written(repository.importStarterList())
            TryIt.GroupedDelete -> TryItResult.Written(repository.deleteThisWeeksDoneTasks())
            TryIt.TwoFilters -> {
                repository.readWithTwoFiltersOnOneColumn()
                TryItResult.Done
            }
            TryIt.CountOpen -> TryItResult.Counted(repository.countOpenTasksOnServer())
            TryIt.SignedUpload ->
                if (repository.uploadThroughSignedUrl() == null) TryItResult.NothingToDo
                else TryItResult.Done
        }

    /** [call]'s answer, or its failure, kept for the screen rather than thrown. */
    private suspend fun <T> answerOf(call: suspend () -> T): ServerAnswer<T> =
        try {
            ServerAnswer.Answered(call())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            ServerAnswer.Failed(failure)
        }

    private data class QueueCounts(
        val tasks: Int,
        val photos: Int,
        val waiting: List<WaitingChange>,
    )

    private data class SyncNowRun(val isRunning: Boolean, val report: TaskSyncReport?)

    /** The calls the screen makes when asked, and what they answered. */
    private data class OnDemand(
        val serverCounts: ServerAnswer<ServerCounts>,
        val bucket: ServerAnswer<BucketListing>,
        val tryIt: Map<TryIt, TryItResult>,
        val syncNow: SyncNowRun,
    )

    /** The writes that need the user: refused, and parked on a conflict. */
    private data class Attention(
        val rejected: List<RejectedTaskMutation>,
        val conflicted: List<ConflictedTaskMutation>,
    )

    /** The two settings, and where Live updates stands. */
    private data class Switches(val settings: SyncSettings, val liveUpdates: LiveUpdatesStatus)

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

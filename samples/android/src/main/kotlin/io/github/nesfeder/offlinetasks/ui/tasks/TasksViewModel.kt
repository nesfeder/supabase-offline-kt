// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import io.github.nesfeder.offlinetasks.ui.status.SyncHeadline
import io.github.nesfeder.offlinetasks.ui.status.syncHeadlineOf
import io.github.nesfeder.offlinetasks.ui.status.withSyncedMoment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Which tasks the list shows. */
enum class TaskFilter {
    All,
    Open,
    Done,
}

/** What the Tasks screen shows. */
sealed interface TasksUiState {
    /** The one sentence about syncing: the subtitle and the banner. */
    val headline: SyncHeadline
    val filter: TaskFilter

    /**
     * Nothing is saved on this device yet. [isLoading] is true while the server is being asked,
     * false when there is no network to ask it with.
     */
    data class NoDataYet(
        override val headline: SyncHeadline,
        override val filter: TaskFilter,
        val isLoading: Boolean,
    ) : TasksUiState

    /** The tasks, split into open and done and filtered; both lists may be empty. */
    data class Loaded(
        override val headline: SyncHeadline,
        override val filter: TaskFilter,
        val openTasks: List<Task>,
        val doneTasks: List<Task>,
        /** Open tasks whatever the filter, for the subtitle. */
        val openCount: Int,
        val isRefreshing: Boolean,
    ) : TasksUiState
}

/** Something the Tasks screen says once, in a snackbar. */
sealed interface TasksMessage {
    /** A task was swiped away and can be brought back. */
    data class Deleted(val taskId: String, val title: String) : TasksMessage

    /** The server refused a queued write. */
    data class Rejected(val rejection: RejectedTaskMutation) : TasksMessage

    /** Pull-to-refresh needs the network; the saved list stays. */
    data object RefreshNeedsNetwork : TasksMessage
}

/**
 * The Tasks screen's state: the live task list, the sync headline, the filter, and deletions that
 * can still be undone. A swiped-away task is only hidden until its snackbar goes; then it is
 * deleted, unless the user chose Undo.
 *
 * @param commitScope where deletions run; it outlives this ViewModel, so a task swiped away just
 *   before the screen goes is still deleted.
 * @param failures where a failed write or refresh is reported, to be shown as a message.
 */
class TasksViewModel(
    private val repository: TaskRepository,
    private val commitScope: CoroutineScope,
    private val failures: FailureReports,
) : ViewModel() {

    private val filter = MutableStateFlow(TaskFilter.All)
    private val hiddenTaskIds = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshing = MutableStateFlow(false)

    /** Tasks swiped away whose deletion can still be undone. */
    private val undoableDeletions = mutableSetOf<String>()
    private val messageChannel = Channel<TasksMessage>(Channel.BUFFERED)

    private val snapshots: Flow<TaskListSnapshot> =
        repository.tasks().shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val headlines: Flow<SyncHeadline> =
        combine(repository.syncStatus, snapshots.map { it.refresh }, ::syncHeadlineOf)
            .withSyncedMoment()

    /** One-off messages, each delivered once. */
    val messages: Flow<TasksMessage> = messageChannel.receiveAsFlow()

    /** The screen's state. */
    val uiState: StateFlow<TasksUiState> =
        combine(
                combine(snapshots, repository.syncStatus, ::Pair),
                headlines,
                filter,
                hiddenTaskIds,
                isRefreshing,
            ) { (snapshot, status), headline, filter, hidden, refreshing ->
                stateOf(snapshot, status.network, headline, filter, hidden, refreshing)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                TasksUiState.NoDataYet(SyncHeadline.UpToDate, TaskFilter.All, isLoading = true),
            )

    init {
        announceNewRejections()
    }

    /** Shows only [newFilter]'s tasks. */
    fun setFilter(newFilter: TaskFilter) {
        filter.value = newFilter
    }

    /** Marks [task] done or open again. */
    fun setDone(task: Task, isDone: Boolean) {
        viewModelScope.launchReporting(failures) { repository.setTaskDone(task.id, isDone) }
    }

    /** Hides [task] at once; it is deleted by [commitDelete], or brought back by [undoDelete]. */
    fun delete(task: Task) {
        if (!undoableDeletions.add(task.id)) return
        hiddenTaskIds.update { it + task.id }
        messageChannel.trySend(TasksMessage.Deleted(task.id, task.title))
    }

    /** Brings back a task swiped away whose deletion has not been committed. */
    fun undoDelete(taskId: String) {
        if (undoableDeletions.remove(taskId)) hiddenTaskIds.update { it - taskId }
    }

    /** Deletes a swiped-away task for good: its undo snackbar has gone. */
    fun commitDelete(taskId: String) {
        if (!undoableDeletions.remove(taskId)) return
        commitScope.launchReporting(failures) {
            // Refused or not, the row stops being hidden: gone, or back where it was.
            try {
                repository.deleteTask(taskId)
            } finally {
                hiddenTaskIds.update { it - taskId }
            }
        }
    }

    override fun onCleared() {
        // Nothing can undo them any more.
        undoableDeletions.toList().forEach(::commitDelete)
    }

    /** Pull-to-refresh: asks the server for the list again, or says why it cannot. */
    fun refresh() {
        val network = repository.syncStatus.value.network
        if (network == AppNetworkStatus.Offline || network == AppNetworkStatus.Disabled) {
            messageChannel.trySend(TasksMessage.RefreshNeedsNetwork)
            return
        }
        viewModelScope.launchReporting(failures) {
            isRefreshing.value = true
            try {
                repository.refreshTasks()
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /** Announces each write refused while this screen exists; older ones are on the Sync screen. */
    private fun announceNewRejections() {
        viewModelScope.launchReporting(failures) {
            var seen: Set<Long>? = null
            repository.rejectedMutations().collect { rejections ->
                val known = seen
                if (known != null) {
                    rejections
                        .filter { it.id !in known }
                        .reversed()
                        .forEach { messageChannel.trySend(TasksMessage.Rejected(it)) }
                }
                seen = rejections.mapTo(HashSet()) { it.id } + known.orEmpty()
            }
        }
    }

    private fun stateOf(
        snapshot: TaskListSnapshot,
        network: AppNetworkStatus,
        headline: SyncHeadline,
        filter: TaskFilter,
        hidden: Set<String>,
        refreshing: Boolean,
    ): TasksUiState {
        val tasks = snapshot.value
        if (tasks == null) {
            val canAskServer =
                network == AppNetworkStatus.Online || network == AppNetworkStatus.Unknown
            return TasksUiState.NoDataYet(
                headline = headline,
                filter = filter,
                isLoading = canAskServer && snapshot.refresh !is TaskRefreshState.Stalled,
            )
        }
        val visible = tasks.filter { it.id !in hidden }
        val (done, open) = visible.partition { it.isDone }
        return TasksUiState.Loaded(
            headline = headline,
            filter = filter,
            openTasks = if (filter == TaskFilter.Done) emptyList() else open,
            doneTasks = if (filter == TaskFilter.Open) emptyList() else done,
            openCount = open.size,
            isRefreshing = refreshing,
        )
    }

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                TasksViewModel(
                    repository = application.repository,
                    commitScope = application.applicationScope,
                    failures = application.failures,
                )
            }
        }
    }
}

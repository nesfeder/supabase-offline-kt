// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.data.RefreshState
import io.github.nesfeder.supabase.offline.sample.data.RejectedMutation
import io.github.nesfeder.supabase.offline.sample.data.Task
import io.github.nesfeder.supabase.offline.sample.data.TaskListSnapshot
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.offlineTasksApplication
import io.github.nesfeder.supabase.offline.sample.ui.status.SyncHeadline
import io.github.nesfeder.supabase.offline.sample.ui.status.syncHeadlineOf
import io.github.nesfeder.supabase.offline.sample.ui.status.withSyncedMoment
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import kotlinx.coroutines.launch

/** How long a swiped-away task can be brought back before it is deleted. */
val DELETE_UNDO_WINDOW: Duration = 5.seconds

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
    data class Rejected(val rejection: RejectedMutation) : TasksMessage

    /** Pull-to-refresh needs the network; the saved list stays. */
    data object RefreshNeedsNetwork : TasksMessage
}

/**
 * The Tasks screen's state: the live task list, the sync headline, the filter, and deletions that
 * can still be undone.
 *
 * @param commitScope where a deletion waits out its undo window; it outlives this ViewModel, so a
 *   task swiped away just before the app closes is still deleted.
 */
class TasksViewModel(
    private val repository: TaskRepository,
    private val commitScope: CoroutineScope,
    private val undoWindow: Duration = DELETE_UNDO_WINDOW,
) : ViewModel() {

    private val filter = MutableStateFlow(TaskFilter.All)
    private val hiddenTaskIds = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshing = MutableStateFlow(false)
    private val pendingDeletions = mutableMapOf<String, Job>()
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
        combine(snapshots, headlines, filter, hiddenTaskIds, isRefreshing) {
                snapshot,
                headline,
                filter,
                hidden,
                refreshing ->
                stateOf(snapshot, headline, filter, hidden, refreshing)
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
        viewModelScope.launch { repository.setTaskDone(task.id, isDone) }
    }

    /** Hides [task] at once and deletes it when the undo window closes. */
    fun delete(task: Task) {
        hiddenTaskIds.update { it + task.id }
        pendingDeletions.remove(task.id)?.cancel()
        pendingDeletions[task.id] =
            commitScope.launch {
                delay(undoWindow)
                repository.deleteTask(task.id)
                hiddenTaskIds.update { it - task.id }
            }
        messageChannel.trySend(TasksMessage.Deleted(task.id, task.title))
    }

    /** Brings back a task swiped away less than the undo window ago. */
    fun undoDelete(taskId: String) {
        pendingDeletions.remove(taskId)?.cancel()
        hiddenTaskIds.update { it - taskId }
    }

    /** Pull-to-refresh: asks the server for the list again, or says why it cannot. */
    fun refresh() {
        val network = repository.syncStatus.value.network
        if (network == NetworkStatus.Offline || network == NetworkStatus.Disabled) {
            messageChannel.trySend(TasksMessage.RefreshNeedsNetwork)
            return
        }
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        headline: SyncHeadline,
        filter: TaskFilter,
        hidden: Set<String>,
        refreshing: Boolean,
    ): TasksUiState {
        val tasks = snapshot.value
        if (tasks == null) {
            val network = repository.syncStatus.value.network
            val canAskServer = network == NetworkStatus.Online || network == NetworkStatus.Unknown
            return TasksUiState.NoDataYet(
                headline = headline,
                filter = filter,
                isLoading = canAskServer && snapshot.refresh !is RefreshState.Stalled,
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
                TasksViewModel(application.repository, application.applicationScope)
            }
        }
    }
}

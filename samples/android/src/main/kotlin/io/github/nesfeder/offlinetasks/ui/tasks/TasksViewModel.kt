// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.ReadProvenance
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.Notice
import io.github.nesfeder.offlinetasks.ui.feedback.Notices
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import io.github.nesfeder.offlinetasks.ui.feedback.readingAgainAfterFailures
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import io.github.nesfeder.offlinetasks.ui.status.SyncHeadline
import io.github.nesfeder.offlinetasks.ui.status.syncHeadlineOf
import io.github.nesfeder.offlinetasks.ui.status.withSyncedMoment
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How long typing must pause before the search is sent. */
private val SEARCH_DELAY = 300.milliseconds

/**
 * The caption under the list's title after a pull to refresh: where its answer came from, and how
 * many changes still wait.
 */
data class RefreshCaption(val provenance: ReadProvenance, val pendingCount: Int)

/** What the Tasks screen shows. */
sealed interface TasksUiState {
    /** The one sentence about syncing: the subtitle and the banner. */
    val headline: SyncHeadline
    val query: TaskQuery

    /**
     * No one is signed in yet, so there is nothing to show and nothing can be added: the first
     * launch, before the app has reached the network. [isSigningIn] is true while it is trying.
     */
    data class NoSession(
        override val headline: SyncHeadline,
        override val query: TaskQuery,
        val isSigningIn: Boolean,
    ) : TasksUiState

    /**
     * Nothing is saved on this device for the query yet. [isLoading] is true while the server is
     * being asked, false when there is no network to ask it with.
     */
    data class NoDataYet(
        override val headline: SyncHeadline,
        override val query: TaskQuery,
        val isLoading: Boolean,
    ) : TasksUiState

    /** The tasks the query selects, split into open and done; both lists may be empty. */
    data class Loaded(
        override val headline: SyncHeadline,
        override val query: TaskQuery,
        val openTasks: List<Task>,
        val doneTasks: List<Task>,
        /** Open tasks whatever the query, for the subtitle. */
        val openCount: Int,
        val isRefreshing: Boolean,
        /** How many photos each task shown has; a task with none is absent. */
        val photoCounts: Map<String, Int> = emptyMap(),
        /** Where the last pull to refresh's answer came from; null before the first. */
        val caption: RefreshCaption? = null,
    ) : TasksUiState
}

/** Something the Tasks screen says once, in a snackbar. */
sealed interface TasksMessage {
    /** A task was swiped away and can be brought back. */
    data class Deleted(val taskId: String, val title: String) : TasksMessage

    /** The server refused a queued write. */
    data class Rejected(val rejection: RejectedTaskMutation) : TasksMessage

    /** Pull to refresh found neither the server nor a copy on this device a day old. */
    data object RefreshNeedsNetwork : TasksMessage
}

/**
 * The Tasks screen's state: the live task list for the chips and the search, the sync headline, the
 * photo counts, deletions that can still be undone, and Mark all done. A swiped-away task is only
 * hidden until its snackbar goes; then it is deleted, unless the user chose Undo.
 *
 * @param sessionState whether anyone is signed in: nothing can be shown or added before.
 * @param commitScope where deletions run; it outlives this ViewModel, so a task swiped away just
 *   before the screen goes is still deleted.
 * @param failures where a failed read, write or refresh is reported, to be shown as a message.
 * @param notices where Mark all done says how many it changed.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class TasksViewModel(
    private val repository: TaskRepository,
    private val sessionState: StateFlow<SessionState>,
    private val commitScope: CoroutineScope,
    private val failures: FailureReports,
    private val notices: Notices = Notices(),
) : ViewModel() {

    private val filter = MutableStateFlow(TaskFilter.All)
    private val search = MutableStateFlow("")
    private val hiddenTaskIds = MutableStateFlow<Set<String>>(emptySet())
    private val isRefreshing = MutableStateFlow(false)
    private val caption = MutableStateFlow<ReadProvenance?>(null)

    /** A pull to refresh or Mark all done: a list read that failed starts again then. */
    private val readAgainRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Tasks swiped away whose deletion can still be undone. */
    private val undoableDeletions = mutableSetOf<String>()
    private val messageChannel = Channel<TasksMessage>(Channel.BUFFERED)

    /** The refused writes still on the list, so an announcement waiting unseen can be dropped. */
    private val currentRejectionIds = MutableStateFlow<Set<Long>>(emptySet())

    /** The query sent to the server: the chip at once, the search once typing pauses. */
    private val query: Flow<TaskQuery> =
        combine(filter, search.debounce { if (it.isEmpty()) 0.milliseconds else SEARCH_DELAY }) {
                chip,
                text ->
                TaskQuery(chip, text)
            }
            .distinctUntilChanged()

    private val snapshots: Flow<Pair<TaskQuery, TaskListSnapshot>> =
        query
            .flatMapLatest { asked ->
                repository
                    .tasks(asked)
                    .readingAgainAfterFailures(failures, ::untilWorthReadingAgain)
                    .map { asked to it }
            }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    /**
     * Every task, for the open count and the headline, whatever the query. A failure is the shown
     * list's to report: both read the same rows.
     */
    private val allTasks: Flow<TaskListSnapshot> =
        repository
            .tasks()
            .readingAgainAfterFailures(failures = null, readAgain = ::untilWorthReadingAgain)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val photoCounts: Flow<Map<String, Int>> =
        snapshots
            .map { (_, snapshot) -> snapshot.value.orEmpty().map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids -> repository.photoCounts(ids).reportingFailures(failures) }
            .onStart { emit(emptyMap()) }

    private val headlines: Flow<SyncHeadline> =
        combine(repository.syncStatus, allTasks.map { it.refresh }, ::syncHeadlineOf)
            .withSyncedMoment()

    /**
     * One-off messages, each delivered once. A refusal announced while the screen was away is
     * dropped if it has left the list since (dismissed, retried or cleared on the Sync screen).
     */
    val messages: Flow<TasksMessage> =
        messageChannel.receiveAsFlow().filter { message ->
            message !is TasksMessage.Rejected || message.rejection.id in currentRejectionIds.value
        }

    /** The text in the search field, as typed. */
    val searchText: StateFlow<String> = search

    /** The screen's state. */
    val uiState: StateFlow<TasksUiState> =
        combine(
                combine(snapshots, allTasks, repository.syncStatus, sessionState, ::ListInputs),
                headlines,
                hiddenTaskIds,
                combine(isRefreshing, caption, photoCounts, ::Extras),
                search,
            ) { inputs, headline, hidden, extras, typed ->
                val (asked, snapshot) = inputs.snapshot
                // The field shows the text as typed, ahead of the query it sends after a pause.
                val shownQuery = asked.copy(search = typed)
                if (inputs.session != SessionState.SignedIn) {
                    TasksUiState.NoSession(
                        headline,
                        shownQuery,
                        isSigningIn = inputs.status.network.canAsk(),
                    )
                } else {
                    stateOf(snapshot, inputs, headline, shownQuery, hidden, extras)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                TasksUiState.NoDataYet(SyncHeadline.UpToDate, TaskQuery(), isLoading = true),
            )

    init {
        announceRejections()
    }

    /** Asks the server for [newFilter]'s tasks. */
    fun setFilter(newFilter: TaskFilter) {
        filter.value = newFilter
    }

    /** Asks the server for tasks whose title contains [text], once typing pauses. */
    fun setSearch(text: String) {
        search.value = text
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

    /**
     * Returns when a list read that failed is worth starting again: the network's state changes, or
     * the user pulls to refresh. A server's own error does not pass by itself.
     */
    private suspend fun untilWorthReadingAgain() {
        val network = repository.syncStatus.value.network
        merge(repository.syncStatus.filter { it.network != network }.map {}, readAgainRequests)
            .first()
    }

    override fun onCleared() {
        // Nothing can undo them any more.
        undoableDeletions.toList().forEach(::commitDelete)
    }

    /**
     * Pull to refresh: asks the server for the list again, and keeps where the answer came from for
     * the caption. Offline it answers from this device, when the copy is a day old at most.
     */
    fun refresh() {
        readAgainRequests.tryEmit(Unit)
        viewModelScope.launchReporting(failures) {
            isRefreshing.value = true
            try {
                caption.value = repository.refreshTasks(uiState.value.query)
            } catch (offline: NetworkUnavailableException) {
                messageChannel.trySend(TasksMessage.RefreshNeedsNetwork)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    /**
     * Marks every open task done on the server, says how many, and reads again the list shown and,
     * behind a chip or a search, the whole list the counts come from: a function's changes reach
     * the device by a read, and each query is cached on its own.
     */
    fun markAllDone() {
        commitScope.launchReporting(failures) {
            notices.post(Notice.MarkedDone(repository.markAllDone()))
            readAgainRequests.tryEmit(Unit)
            val shown = uiState.value.query
            caption.value = repository.refreshTasks(shown)
            if (shown != TaskQuery()) repository.refreshTasks()
        }
    }

    /**
     * Announces the newest refusal waiting at launch, read once, then each write refused while this
     * screen exists; the rest are on the Sync screen.
     */
    private fun announceRejections() {
        viewModelScope.launchReporting(failures) {
            val waiting = repository.rejectedMutationsNow()
            currentRejectionIds.value = waiting.mapTo(HashSet()) { it.id }
            waiting.firstOrNull()?.let { messageChannel.trySend(TasksMessage.Rejected(it)) }
            var seen: Set<Long> = waiting.mapTo(HashSet()) { it.id }
            repository.rejectedMutations().collect { rejections ->
                currentRejectionIds.value = rejections.mapTo(HashSet()) { it.id }
                rejections
                    .filter { it.id !in seen }
                    .reversed()
                    .forEach { messageChannel.trySend(TasksMessage.Rejected(it)) }
                seen = seen + rejections.map { it.id }
            }
        }
    }

    private fun stateOf(
        snapshot: TaskListSnapshot,
        inputs: ListInputs,
        headline: SyncHeadline,
        query: TaskQuery,
        hidden: Set<String>,
        extras: Extras,
    ): TasksUiState {
        val tasks = snapshot.value
        if (tasks == null) {
            return TasksUiState.NoDataYet(
                headline = headline,
                query = query,
                isLoading =
                    inputs.status.network.canAsk() && snapshot.refresh !is TaskRefreshState.Stalled,
            )
        }
        val visible = tasks.filter { it.id !in hidden }
        val (done, open) = visible.partition { it.isDone }
        val allOpen = inputs.all.value.orEmpty().count { !it.isDone && it.id !in hidden }
        return TasksUiState.Loaded(
            headline = headline,
            query = query,
            openTasks = open,
            doneTasks = done,
            openCount = allOpen,
            isRefreshing = extras.isRefreshing,
            photoCounts = extras.photoCounts,
            caption =
                extras.caption?.let { RefreshCaption(it, inputs.status.pendingMutationCount) },
        )
    }

    /** The reads the state is built from. */
    private data class ListInputs(
        val snapshot: Pair<TaskQuery, TaskListSnapshot>,
        val all: TaskListSnapshot,
        val status: AppSyncStatus,
        val session: SessionState,
    )

    /** What the list shows beside its rows. */
    private data class Extras(
        val isRefreshing: Boolean,
        val caption: ReadProvenance?,
        val photoCounts: Map<String, Int>,
    )

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                TasksViewModel(
                    repository = application.repository,
                    sessionState = application.session.state,
                    commitScope = application.applicationScope,
                    failures = application.failures,
                    notices = application.notices,
                )
            }
        }
    }
}

/** Whether the server may be reachable: known online, or not known yet. */
private fun AppNetworkStatus.canAsk(): Boolean =
    this == AppNetworkStatus.Online || this == AppNetworkStatus.Unknown

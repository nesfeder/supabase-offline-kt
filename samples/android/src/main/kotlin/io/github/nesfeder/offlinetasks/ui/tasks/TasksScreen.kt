// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.ui.components.EmptyState
import io.github.nesfeder.offlinetasks.ui.components.segmentedScreenAppBarColors
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.feedback.LocalAppSnackbars
import io.github.nesfeder.offlinetasks.ui.navigation.SharedBounds
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys
import io.github.nesfeder.offlinetasks.ui.navigation.ShownTask
import io.github.nesfeder.offlinetasks.ui.status.RollingText
import io.github.nesfeder.offlinetasks.ui.status.SyncHeadline
import io.github.nesfeder.offlinetasks.ui.status.SyncStatusBanner
import io.github.nesfeder.offlinetasks.ui.status.relativeTime
import kotlinx.coroutines.launch

/** The test tag of the task list, so tests can scroll it. */
const val TASK_LIST_TAG: String = "task-list"

/**
 * The Tasks destination: the screen wired to its ViewModel, with snackbars for undo, refused writes
 * and an offline pull-to-refresh, posted to the app's one snackbar queue.
 *
 * @param selectedTaskId the task open beside the list, marked as selected; null on a phone.
 * @param onOpenTask opens a task, given what the row shows of it, so its screen starts drawn.
 */
@Composable
fun TasksRoute(
    selectedTaskId: String?,
    onOpenTask: (taskId: String, shown: ShownTask) -> Unit,
    onNewTask: () -> Unit,
    onShowSync: () -> Unit,
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = LocalAppSnackbars.current.hostState
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            // Each in its own coroutine: the host shows them one after another.
            launch {
                when (message) {
                    is TasksMessage.Deleted -> {
                        // A newer deletion ends the older one's chance to be undone.
                        snackbarHostState.currentSnackbarData?.dismiss()
                        var answered = false
                        try {
                            val result =
                                snackbarHostState.showSnackbar(
                                    message =
                                        resources.getString(
                                            R.string.tasks_deleted,
                                            message.title.ifBlank {
                                                resources.getString(R.string.task_untitled)
                                            },
                                        ),
                                    actionLabel = resources.getString(R.string.action_undo),
                                    duration = SnackbarDuration.Short,
                                )
                            answered = true
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.undoDelete(message.taskId)
                            } else {
                                viewModel.commitDelete(message.taskId)
                            }
                        } finally {
                            // The screen went before the snackbar did: nothing can undo it now.
                            if (!answered) viewModel.commitDelete(message.taskId)
                        }
                    }
                    is TasksMessage.Rejected -> {
                        val title =
                            message.rejection.taskTitle?.takeIf { it.isNotBlank() }
                                ?: resources.getString(R.string.task_untitled)
                        val result =
                            snackbarHostState.showSnackbar(
                                message = resources.getString(R.string.tasks_rejected, title),
                                actionLabel = resources.getString(R.string.action_view),
                                duration = SnackbarDuration.Long,
                            )
                        if (result == SnackbarResult.ActionPerformed) onShowSync()
                    }
                    TasksMessage.RefreshNeedsNetwork ->
                        snackbarHostState.showSnackbar(
                            resources.getString(R.string.tasks_refresh_offline)
                        )
                }
            }
        }
    }
    TasksScreen(
        state = state,
        selectedTaskId = selectedTaskId,
        onFilterChange = viewModel::setFilter,
        onSearchChange = viewModel::setSearch,
        onMarkAllDone = viewModel::markAllDone,
        onDoneChange = viewModel::setDone,
        onDelete = viewModel::delete,
        onRefresh = viewModel::refresh,
        onOpenTask = { task ->
            onOpenTask(task.id, ShownTask(task.title, task.notes, task.isDone))
        },
        onNewTask = onNewTask,
    )
}

/**
 * The task list: a large flexible app bar with the sync subtitle and Mark all done, the caption of
 * the last pull to refresh, the status banner, the search and the filters, open and done sections,
 * pull-to-refresh and the New-task button.
 *
 * @param selectedTaskId the task open beside the list, if any, which its row shows as selected.
 */
@Composable
fun TasksScreen(
    state: TasksUiState,
    onFilterChange: (TaskFilter) -> Unit,
    onDoneChange: (Task, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onRefresh: () -> Unit,
    onOpenTask: (Task) -> Unit,
    onNewTask: () -> Unit,
    modifier: Modifier = Modifier,
    selectedTaskId: String? = null,
    onSearchChange: (String) -> Unit = {},
    onMarkAllDone: () -> Unit = {},
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Segmented rows are drawn in the surface color, so they sit on a surface container.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                colors = segmentedScreenAppBarColors(),
                title = { Text(stringResource(R.string.tasks_title)) },
                subtitle = { TasksSubtitle(state) },
                actions = {
                    if (state !is TasksUiState.NoSession) TasksMenu(onMarkAllDone)
                },
                scrollBehavior = scrollBehavior,
            )
        },
        // A task needs its user's id, so New task waits for a session.
        floatingActionButton = {
            if (state !is TasksUiState.NoSession) {
                NewTaskButton(listState.isScrollingUp(), onNewTask)
            }
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        val isRefreshing = (state as? TasksUiState.Loaded)?.isRefreshing == true
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                RefreshIndicator(pullState, isRefreshing, Modifier.align(Alignment.TopCenter))
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(TASK_LIST_TAG),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                // Before the first sign-in nothing can be changed, so no banner promises to save
                // it.
                (state as? TasksUiState.Loaded)?.caption?.let { caption ->
                    item(key = "caption") { RefreshCaptionText(caption) }
                }
                if (state !is TasksUiState.NoSession) {
                    item(key = "banner") {
                        SyncStatusBanner(state.headline, Modifier.padding(bottom = 8.dp))
                    }
                    item(key = "search") { SearchField(state.query.search, onSearchChange) }
                }
                item(key = "filter") { TaskFilterButtons(state.query.filter, onFilterChange) }
                when (state) {
                    is TasksUiState.NoSession -> item(key = "no-session") { NoSession(state) }
                    is TasksUiState.NoDataYet -> item(key = "no-data-yet") { NoDataYet(state) }
                    is TasksUiState.Loaded ->
                        if (state.openTasks.isEmpty() && state.doneTasks.isEmpty()) {
                            item(key = "empty") { NoTasks(state.query) }
                        } else {
                            taskSection(
                                key = "open",
                                tasks = state.openTasks,
                                photoCounts = state.photoCounts,
                                selectedTaskId = selectedTaskId,
                                onDoneChange = onDoneChange,
                                onDelete = onDelete,
                                onOpenTask = onOpenTask,
                            )
                            taskSection(
                                key = "done",
                                tasks = state.doneTasks,
                                photoCounts = state.photoCounts,
                                selectedTaskId = selectedTaskId,
                                onDoneChange = onDoneChange,
                                onDelete = onDelete,
                                onOpenTask = onOpenTask,
                            )
                        }
                }
            }
        }
    }
}

/**
 * The extended New-task button, which grows into the New-task screen. Extended, its label names it;
 * collapsed to the icon, the icon does, so a screen reader says it once either way.
 */
@Composable
private fun NewTaskButton(expanded: Boolean, onClick: () -> Unit) {
    SharedBounds(SharedKeys.NEW_TASK) {
        val label = stringResource(R.string.new_task)
        MediumExtendedFloatingActionButton(
            // One line, whatever width the container transform gives it on the way.
            text = { Text(label, maxLines = 1, softWrap = false) },
            icon = {
                Icon(
                    painterResource(R.drawable.ic_add),
                    contentDescription = if (expanded) null else label,
                )
            },
            onClick = onClick,
            expanded = expanded,
        )
    }
}

/** Pull-to-refresh's expressive indicator: a loading shape that morphs while it waits. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RefreshIndicator(
    state: PullToRefreshState,
    isRefreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    PullToRefreshDefaults.LoadingIndicator(
        state = state,
        isRefreshing = isRefreshing,
        modifier = modifier,
    )
}

/** "3 open · 2 waiting to sync": the count rolls when it changes. */
@Composable
private fun TasksSubtitle(state: TasksUiState) {
    val status = subtitleStatus(state.headline)
    val text =
        if (state is TasksUiState.Loaded) {
            stringResource(
                R.string.tasks_subtitle,
                pluralStringResource(R.plurals.tasks_open_count, state.openCount, state.openCount),
                status,
            )
        } else {
            status
        }
    val pending =
        when (val headline = state.headline) {
            is SyncHeadline.Offline -> headline.pendingCount
            is SyncHeadline.Syncing -> headline.pendingCount
            is SyncHeadline.Waiting -> headline.pendingCount
            else -> 0
        }
    RollingText(text = text, count = pending)
}

@Composable
private fun subtitleStatus(headline: SyncHeadline): String =
    when (headline) {
        SyncHeadline.UpToDate,
        SyncHeadline.JustSynced -> stringResource(R.string.subtitle_synced)
        SyncHeadline.ShowingSavedData -> stringResource(R.string.subtitle_saved_copy)
        is SyncHeadline.Offline -> stringResource(R.string.subtitle_offline)
        is SyncHeadline.Syncing -> stringResource(R.string.subtitle_syncing)
        is SyncHeadline.Waiting ->
            pluralStringResource(
                R.plurals.subtitle_waiting,
                headline.pendingCount,
                headline.pendingCount,
            )
    }

/** The app bar's menu: Mark all done, which a database function does on the server. */
@Composable
private fun TasksMenu(onMarkAllDone: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.tasks_mark_all_done)) },
            onClick = {
                expanded = false
                onMarkAllDone()
            },
        )
    }
}

/**
 * Under the title after a pull to refresh: "Updated just now" when the server answered, "From this
 * device, 3 min ago" when it could not, and how many changes still wait.
 */
@Composable
private fun RefreshCaptionText(caption: RefreshCaption) {
    val provenance = caption.provenance
    val fetched = provenance.fetchedAt?.let { relativeTime(it) }
    val source =
        when {
            !provenance.isFromCache && fetched != null ->
                stringResource(R.string.tasks_caption_updated, fetched)
            fetched != null -> stringResource(R.string.tasks_caption_from_device, fetched)
            else -> stringResource(R.string.tasks_caption_from_device_never)
        }
    val text =
        if (provenance.hasPendingMutations && caption.pendingCount > 0) {
            stringResource(
                R.string.tasks_subtitle,
                source,
                pluralStringResource(
                    R.plurals.status_waiting,
                    caption.pendingCount,
                    caption.pendingCount,
                ),
            )
        } else {
            source
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** The search field: the title must contain the text, which the server checks. */
@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.tasks_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
    )
}

/** All / Open / Done / Recent, as a connected button group with one choice. */
@Composable
private fun TaskFilterButtons(selected: TaskFilter, onSelect: (TaskFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        val filters = TaskFilter.entries
        filters.forEachIndexed { index, filter ->
            ToggleButton(
                checked = selected == filter,
                onCheckedChange = { onSelect(filter) },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        filters.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                // The default unchecked color is the screen's own surface container.
                colors =
                    ToggleButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(filter.label))
            }
        }
    }
}

private val TaskFilter.label: Int
    get() =
        when (this) {
            TaskFilter.All -> R.string.filter_all
            TaskFilter.Open -> R.string.filter_open
            TaskFilter.Done -> R.string.filter_done
            TaskFilter.Recent -> R.string.filter_recent
        }

/** One section of the list, with its header; nothing when [tasks] is empty. */
private fun LazyListScope.taskSection(
    key: String,
    tasks: List<Task>,
    photoCounts: Map<String, Int>,
    selectedTaskId: String?,
    onDoneChange: (Task, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (Task) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "header-$key") {
        val motion = MaterialTheme.motionScheme
        Text(
            text =
                if (key == "open") {
                    pluralStringResource(R.plurals.section_open, tasks.size, tasks.size)
                } else {
                    pluralStringResource(R.plurals.section_done, tasks.size, tasks.size)
                },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.animateItem(
                        fadeInSpec = motion.defaultEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                        fadeOutSpec = motion.defaultEffectsSpec(),
                    )
                    .padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
        )
    }
    itemsIndexed(tasks, key = { _, task -> task.id }) { index, task ->
        val motion = MaterialTheme.motionScheme
        TaskRow(
            task = task,
            photoCount = photoCounts[task.id] ?: 0,
            selected = task.id == selectedTaskId,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = tasks.size),
            onDoneChange = onDoneChange,
            onDelete = onDelete,
            onOpen = onOpenTask,
            modifier =
                Modifier.animateItem(
                    fadeInSpec = motion.defaultEffectsSpec(),
                    placementSpec = motion.defaultSpatialSpec(),
                    fadeOutSpec = motion.defaultEffectsSpec(),
                ),
        )
    }
}

@Composable
private fun NoSession(state: TasksUiState.NoSession) {
    EmptyState(
        icon = if (state.isSigningIn) R.drawable.ic_account_circle else R.drawable.ic_cloud_off,
        title = stringResource(R.string.tasks_no_session_title),
        body =
            stringResource(
                if (state.isSigningIn) R.string.tasks_no_session_signing_in_body
                else R.string.tasks_no_session_offline_body
            ),
        isLoading = state.isSigningIn,
    )
}

@Composable
private fun NoDataYet(state: TasksUiState.NoDataYet) {
    if (state.isLoading) {
        EmptyState(
            icon = R.drawable.ic_cloud_sync,
            title = stringResource(R.string.tasks_loading_title),
            body = stringResource(R.string.tasks_loading_body),
            isLoading = true,
        )
    } else {
        EmptyState(
            icon = R.drawable.ic_cloud_off,
            title = stringResource(R.string.tasks_no_data_title),
            body = stringResource(R.string.tasks_no_data_body),
        )
    }
}

@Composable
private fun NoTasks(query: TaskQuery) {
    if (query.search.isNotBlank()) {
        EmptyState(
            icon = R.drawable.ic_search,
            title = stringResource(R.string.tasks_empty_search_title, query.search.trim()),
            body = stringResource(R.string.tasks_empty_search_body),
        )
        return
    }
    val (title, body) =
        when (query.filter) {
            TaskFilter.All -> R.string.tasks_empty_title to R.string.tasks_empty_body
            TaskFilter.Open -> R.string.tasks_empty_open_title to R.string.tasks_empty_open_body
            TaskFilter.Done -> R.string.tasks_empty_done_title to R.string.tasks_empty_done_body
            TaskFilter.Recent ->
                R.string.tasks_empty_recent_title to R.string.tasks_empty_recent_body
        }
    EmptyState(
        icon = R.drawable.ic_checklist,
        title = stringResource(title),
        body = stringResource(body),
    )
}

/** True while the list is scrolled toward its top, or at rest there: the FAB is extended. */
@Composable
private fun LazyListState.isScrollingUp(): Boolean {
    var previousIndex by remember(this) { mutableIntStateOf(firstVisibleItemIndex) }
    var previousOffset by remember(this) { mutableIntStateOf(firstVisibleItemScrollOffset) }
    return remember(this) {
            derivedStateOf {
                val scrollingUp =
                    if (previousIndex != firstVisibleItemIndex) {
                        previousIndex > firstVisibleItemIndex
                    } else {
                        previousOffset >= firstVisibleItemScrollOffset
                    }
                previousIndex = firstVisibleItemIndex
                previousOffset = firstVisibleItemScrollOffset
                scrollingUp
            }
        }
        .value
}

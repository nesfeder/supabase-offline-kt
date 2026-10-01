// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.tasks

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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.Task
import io.github.nesfeder.supabase.offline.sample.ui.components.EmptyState
import io.github.nesfeder.supabase.offline.sample.ui.components.segmentedScreenAppBarColors
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedBounds
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedKeys
import io.github.nesfeder.supabase.offline.sample.ui.status.RollingText
import io.github.nesfeder.supabase.offline.sample.ui.status.SyncHeadline
import io.github.nesfeder.supabase.offline.sample.ui.status.SyncStatusBanner
import kotlinx.coroutines.launch

/** The test tag of the task list, so tests can scroll it. */
const val TASK_LIST_TAG: String = "task-list"

/**
 * The Tasks destination: the screen wired to its ViewModel, with snackbars for undo, refused
 * writes and an offline pull-to-refresh.
 */
@Composable
fun TasksRoute(
    onOpenTask: (taskId: String) -> Unit,
    onNewTask: () -> Unit,
    onShowSync: () -> Unit,
    viewModel: TasksViewModel = viewModel(factory = TasksViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
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
        snackbarHostState = snackbarHostState,
        onFilterChange = viewModel::setFilter,
        onDoneChange = viewModel::setDone,
        onDelete = viewModel::delete,
        onRefresh = viewModel::refresh,
        onOpenTask = onOpenTask,
        onNewTask = onNewTask,
    )
}

/**
 * The task list: a large flexible app bar with the sync subtitle, the status banner, the filter,
 * open and done sections, pull-to-refresh and the New-task button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TasksScreen(
    state: TasksUiState,
    snackbarHostState: SnackbarHostState,
    onFilterChange: (TaskFilter) -> Unit,
    onDoneChange: (Task, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onRefresh: () -> Unit,
    onOpenTask: (taskId: String) -> Unit,
    onNewTask: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Segmented rows are drawn in the surface colour, so they sit on a surface container.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                colors = segmentedScreenAppBarColors(),
                title = { Text(stringResource(R.string.tasks_title)) },
                subtitle = { TasksSubtitle(state) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            SharedBounds(SharedKeys.NEW_TASK) {
                val label = stringResource(R.string.new_task)
                MediumExtendedFloatingActionButton(
                    text = { Text(label) },
                    // The label leaves the accessibility tree with the text, so the icon names it.
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = label) },
                    onClick = onNewTask,
                    expanded = listState.isScrollingUp(),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val pullState = rememberPullToRefreshState()
        val isRefreshing = (state as? TasksUiState.Loaded)?.isRefreshing == true
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            state = pullState,
            modifier = Modifier.fillMaxSize().padding(padding),
            indicator = {
                PullToRefreshDefaults.LoadingIndicator(
                    state = pullState,
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag(TASK_LIST_TAG),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 128.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                item(key = "banner") {
                    SyncStatusBanner(state.headline, Modifier.padding(bottom = 8.dp))
                }
                item(key = "filter") { TaskFilterButtons(state.filter, onFilterChange) }
                when (state) {
                    is TasksUiState.NoDataYet -> item(key = "no-data-yet") { NoDataYet(state) }
                    is TasksUiState.Loaded ->
                        if (state.openTasks.isEmpty() && state.doneTasks.isEmpty()) {
                            item(key = "empty") { NoTasks(state.filter) }
                        } else {
                            taskSection(
                                key = "open",
                                tasks = state.openTasks,
                                onDoneChange = onDoneChange,
                                onDelete = onDelete,
                                onOpenTask = onOpenTask,
                            )
                            taskSection(
                                key = "done",
                                tasks = state.doneTasks,
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

/** All / Open / Done, as a connected button group with one choice. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                // The default unchecked colour is the screen's own surface container.
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
        }

/** One section of the list, with its header; nothing when [tasks] is empty. */
private fun LazyListScope.taskSection(
    key: String,
    tasks: List<Task>,
    onDoneChange: (Task, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onOpenTask: (taskId: String) -> Unit,
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
private fun NoTasks(filter: TaskFilter) {
    val (title, body) =
        when (filter) {
            TaskFilter.All -> R.string.tasks_empty_title to R.string.tasks_empty_body
            TaskFilter.Open -> R.string.tasks_empty_open_title to R.string.tasks_empty_open_body
            TaskFilter.Done -> R.string.tasks_empty_done_title to R.string.tasks_empty_done_body
        }
    EmptyState(
        icon = R.drawable.ic_checklist,
        title = stringResource(title),
        body = stringResource(body),
    )
}

/** True while the list is scrolled towards its top, or at rest there: the FAB is extended. */
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

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.sync

import android.content.res.Resources
import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.BucketListing
import io.github.nesfeder.offlinetasks.data.ColumnConflict
import io.github.nesfeder.offlinetasks.data.ConflictChoice
import io.github.nesfeder.offlinetasks.data.ConflictedTaskMutation
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.ServerCounts
import io.github.nesfeder.offlinetasks.data.SyncStoppedException
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.WaitingChange
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.ui.components.segmentedScreenAppBarColors
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.feedback.failureMessage
import io.github.nesfeder.offlinetasks.ui.feedback.refusalReason
import io.github.nesfeder.offlinetasks.ui.feedback.syncStoppedReason
import io.github.nesfeder.offlinetasks.ui.status.RollingText
import io.github.nesfeder.offlinetasks.ui.status.relativeTime

/** The Sync destination: the screen wired to its ViewModel. */
@Composable
fun SyncRoute(viewModel: SyncViewModel = viewModel(factory = SyncViewModel.Factory)) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SyncScreen(
        state = state,
        actions =
            SyncActions(
                onSimulateOffline = viewModel::setSimulateOffline,
                onSyncNow = viewModel::syncNow,
                onDismiss = viewModel::dismiss,
                onRetry = viewModel::retry,
                onClearLocalData = viewModel::clearLocalData,
                onStartOver = viewModel::startOver,
                onPreferFreshData = viewModel::setPreferFreshData,
                onLiveUpdates = viewModel::setLiveUpdates,
                onDiscard = viewModel::discard,
                onAskServer = viewModel::askServer,
                onListBucket = viewModel::listBucket,
                onTryIt = viewModel::tryIt,
                onResolve = viewModel::resolve,
            ),
    )
}

/** Everything the Sync screen can ask for. */
class SyncActions(
    val onSimulateOffline: (Boolean) -> Unit,
    val onSyncNow: () -> Unit,
    val onDismiss: (RejectedTaskMutation) -> Unit,
    val onRetry: (RejectedTaskMutation) -> Unit,
    val onClearLocalData: () -> Unit,
    val onStartOver: () -> Unit,
    val onPreferFreshData: (Boolean) -> Unit = {},
    val onLiveUpdates: (Boolean) -> Unit = {},
    val onDiscard: (WaitingChange) -> Unit = {},
    val onAskServer: () -> Unit = {},
    val onListBucket: (offset: Int) -> Unit = {},
    val onTryIt: (TryIt) -> Unit = {},
    val onResolve: (ConflictedTaskMutation, ConflictChoice) -> Unit = { _, _ -> },
)

/** A step that throws away data, waiting for the user to confirm it. */
private enum class Confirmation {
    ClearLocalData,
    StartOver,
}

/**
 * The library's offline state made visible: the network with a switch to simulate losing it, the
 * two settings, the waiting writes with "Sync now", the list's refresh state, refused writes,
 * conflicting writes, what the server says, the bucket, the Try it actions, and the two ways to
 * start again.
 */
@Composable
fun SyncScreen(state: SyncUiState, actions: SyncActions, modifier: Modifier = Modifier) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirming by rememberSaveable { mutableStateOf<Confirmation?>(null) }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        // Segmented rows are drawn in the surface color, so they sit on a surface container.
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            MediumFlexibleTopAppBar(
                colors = segmentedScreenAppBarColors(),
                title = { Text(stringResource(R.string.sync_title)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { AppSnackbarHost() },
    ) { padding ->
        val ready = state as? SyncUiState.Ready ?: return@Scaffold
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            connectionGroup(ready, actions)
            settingsGroup(ready, actions)
            queueGroup(ready, actions)
            refreshGroup(ready)
            rejectedGroup(ready, actions)
            conflictedGroup(ready, actions)
            serverGroup(ready, actions)
            storageGroup(ready, actions)
            developerGroup(ready, actions)
            localDataGroup(onConfirm = { confirming = it })
            aboutGroup(ready)
        }
    }
    confirming?.let { step ->
        ConfirmationDialog(
            step = step,
            onConfirm = {
                confirming = null
                when (step) {
                    Confirmation.ClearLocalData -> actions.onClearLocalData()
                    Confirmation.StartOver -> actions.onStartOver()
                }
            },
            onDismiss = { confirming = null },
        )
    }
}

private fun LazyListScope.connectionGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("connection", R.string.sync_connection)
    item(key = "network") {
        val network = state.status.network
        InfoRow(
            index = 0,
            count = 2,
            icon =
                when (network) {
                    AppNetworkStatus.Online -> R.drawable.ic_cloud_done
                    AppNetworkStatus.Disabled -> R.drawable.ic_wifi_off
                    else -> R.drawable.ic_cloud_off
                },
            headline = stringResource(network.label),
            supporting = stringResource(network.description),
        )
    }
    item(key = "simulate-offline") {
        val simulating = state.status.network == AppNetworkStatus.Disabled
        SwitchRow(
            index = 1,
            count = 2,
            icon = R.drawable.ic_wifi_off,
            checked = simulating,
            onCheckedChange = actions.onSimulateOffline,
            headline = stringResource(R.string.sync_simulate_offline),
            supporting = stringResource(R.string.sync_simulate_offline_body),
        )
    }
}

private fun LazyListScope.settingsGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("settings", R.string.sync_settings)
    item(key = "prefer-fresh-data") {
        SwitchRow(
            index = 0,
            count = 2,
            icon = R.drawable.ic_refresh,
            checked = state.settings.preferFreshData,
            onCheckedChange = actions.onPreferFreshData,
            headline = stringResource(R.string.sync_prefer_fresh_data),
            supporting = stringResource(R.string.sync_prefer_fresh_data_body),
        )
    }
    item(key = "live-updates") {
        SwitchRow(
            index = 1,
            count = 2,
            icon = R.drawable.ic_cloud_sync,
            checked = state.settings.liveUpdates,
            onCheckedChange = actions.onLiveUpdates,
            headline = stringResource(R.string.sync_live_updates),
            supporting = stringResource(state.liveUpdates.description),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.queueGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("queue", R.string.sync_queue)
    val rows = state.waiting.size + 1
    item(key = "pending") {
        val pending = state.status.pendingMutationCount
        val canSync = state.status.network == AppNetworkStatus.Online
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = rows),
            leadingContent = { Icon(painterResource(R.drawable.ic_cloud_upload), null) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(
                            R.string.sync_queue_by_table,
                            state.pendingTasks,
                            state.pendingPhotos,
                        )
                    )
                    Text(lastSyncText(state))
                    state.lastReport?.let { report ->
                        Text(
                            stringResource(
                                R.string.sync_last_report,
                                report.syncedCount,
                                report.rejected.size,
                                report.remainingCount,
                                report.alreadyOnServerCount,
                                report.heldBehindOtherTablesCount,
                                report.conflictsResolvedCount,
                                report.conflictsKeptServerCount,
                                report.conflicted.size,
                            )
                        )
                    }
                    Button(
                        onClick = actions.onSyncNow,
                        enabled = canSync && !state.isSyncNowRunning,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        if (state.isSyncNowRunning || state.status.isSyncing) {
                            LoadingIndicator(Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.sync_now_running),
                                Modifier.padding(start = 8.dp),
                            )
                        } else {
                            Text(stringResource(R.string.sync_now))
                        }
                    }
                    if (!canSync) Text(stringResource(R.string.sync_now_needs_network))
                }
            },
        ) {
            RollingText(
                text =
                    if (pending == 0) stringResource(R.string.sync_queue_empty)
                    else pluralStringResource(R.plurals.status_waiting, pending, pending),
                count = pending,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    state.waiting.forEachIndexed { index, change ->
        item(key = "waiting-${change.id}") {
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index = index + 1, count = rows),
                trailingContent = {
                    TextButton(onClick = { actions.onDiscard(change) }) {
                        Text(stringResource(R.string.action_discard))
                    }
                },
                supportingContent = { Text(relativeTime(change.createdAt)) },
            ) {
                Text(waitingChangeText(LocalResources.current, change))
            }
        }
    }
}

private fun LazyListScope.refreshGroup(state: SyncUiState.Ready) {
    groupTitle("refresh", R.string.sync_refresh)
    item(key = "refresh-state") {
        val (icon, text) =
            when {
                // Before the first sign-in nothing is read, so there is nothing to be up to date.
                state.session != SessionState.SignedIn ->
                    R.drawable.ic_cloud_off to R.string.sync_refresh_no_session
                else -> refreshRowOf(state.refresh)
            }
        InfoRow(
            index = 0,
            count = 1,
            icon = icon,
            headline = stringResource(R.string.sync_refresh_task_list),
            supporting = stringResource(text),
        )
    }
}

private fun LazyListScope.rejectedGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("rejected", R.string.sync_rejected)
    if (state.rejected.isEmpty()) {
        item(key = "rejected-none") {
            InfoRow(
                index = 0,
                count = 1,
                icon = R.drawable.ic_cloud_done,
                headline = stringResource(R.string.sync_rejected_none),
                supporting = stringResource(R.string.sync_rejected_none_body),
            )
        }
        return
    }
    state.rejected.forEachIndexed { index, rejection ->
        item(key = "rejected-${rejection.id}") {
            RejectedRow(rejection, index, state.rejected.size, actions)
        }
    }
}

/** A refused write: why, when it was made and refused, its rows, Retry and Dismiss. */
@Composable
private fun RejectedRow(
    rejection: RejectedTaskMutation,
    index: Int,
    count: Int,
    actions: SyncActions,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_report),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        supportingContent = {
            Column {
                Text(
                    rejection.serverError?.let { refusalReason(LocalResources.current, it) }
                        ?: rejection.message
                )
                // The server's own answer, as the library keeps it for each refusal.
                rejection.serverError?.let { error ->
                    SmallText(
                        stringResource(
                            R.string.sync_rejected_server_answer,
                            error.status,
                            error.code.orEmpty(),
                        )
                    )
                }
                SmallText(
                    stringResource(
                        R.string.sync_rejected_times,
                        relativeTime(rejection.createdAt),
                        relativeTime(rejection.rejectedAt),
                    )
                )
                if (rejection.rowKeys.isNotEmpty()) {
                    SmallText(
                        stringResource(
                            R.string.sync_rejected_rows,
                            rejection.rowKeys.joinToString(),
                        )
                    )
                }
                if (!rejection.isRetryable) {
                    SmallText(stringResource(R.string.sync_rejected_not_retryable))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { actions.onRetry(rejection) },
                        enabled = rejection.isRetryable,
                    ) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = { actions.onDismiss(rejection) }) {
                        Text(stringResource(R.string.action_dismiss))
                    }
                }
            }
        },
    ) {
        Text(
            stringResource(
                R.string.sync_rejected_item,
                rejection.taskTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.task_untitled),
            )
        )
    }
}

private fun LazyListScope.conflictedGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("conflicted", R.string.sync_conflicted)
    if (state.conflicted.isEmpty()) {
        item(key = "conflicted-none") {
            InfoRow(
                index = 0,
                count = 1,
                icon = R.drawable.ic_cloud_done,
                headline = stringResource(R.string.sync_conflicted_none),
                supporting = stringResource(R.string.sync_conflicted_none_body),
            )
        }
        return
    }
    state.conflicted.forEachIndexed { index, conflict ->
        item(key = "conflicted-${conflict.id}") {
            ConflictedRow(conflict, index, state.conflicted.size, actions)
        }
    }
}

/**
 * A write parked on a conflict: each column both sides changed, with the server's value and this
 * device's, when it was made and held, Keep mine and Keep theirs.
 */
@Composable
private fun ConflictedRow(
    conflict: ConflictedTaskMutation,
    index: Int,
    count: Int,
    actions: SyncActions,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index, count),
        leadingContent = {
            Icon(
                painterResource(R.drawable.ic_sync_problem),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        supportingContent = {
            Column {
                conflict.columns.forEach { column -> Text(columnConflictText(column)) }
                if (conflict.operation == TaskMutationOperation.Delete) {
                    Text(stringResource(R.string.sync_conflicted_delete))
                }
                SmallText(
                    stringResource(
                        R.string.sync_conflicted_times,
                        relativeTime(conflict.createdAt),
                        relativeTime(conflict.conflictedAt),
                    )
                )
                if (!conflict.canKeepMine) {
                    SmallText(stringResource(R.string.sync_conflicted_cannot_keep_mine))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = { actions.onResolve(conflict, ConflictChoice.KeepMine) },
                        enabled = conflict.canKeepMine,
                    ) {
                        Text(stringResource(R.string.action_keep_mine))
                    }
                    TextButton(
                        onClick = { actions.onResolve(conflict, ConflictChoice.KeepTheirs) }
                    ) {
                        Text(stringResource(R.string.action_keep_theirs))
                    }
                }
            }
        },
    ) {
        Text(
            stringResource(
                R.string.sync_conflicted_item,
                conflict.taskTitle?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.task_untitled),
            )
        )
    }
}

/** One column both sides changed: "Title: “Buy oat milk” on the server, “Buy milk” here". */
@Composable
private fun columnConflictText(column: ColumnConflict): String {
    val none = stringResource(R.string.sync_conflicted_empty)
    return stringResource(
        R.string.sync_conflicted_column,
        column.column,
        column.theirs ?: none,
        column.mine ?: none,
    )
}

private fun LazyListScope.serverGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("server", R.string.sync_server)
    item(key = "server-counts") {
        val answer = state.serverCounts
        ActionRow(
            index = 0,
            count = 1,
            icon = R.drawable.ic_cloud_sync,
            headline = serverCountsText(answer),
            supporting = stringResource(R.string.sync_server_body),
            action = stringResource(R.string.sync_server_ask),
            enabled = state.session == SessionState.SignedIn && answer != ServerAnswer.Asking,
            onAction = actions.onAskServer,
        )
    }
}

@Composable
private fun serverCountsText(answer: ServerAnswer<ServerCounts>): String =
    when (answer) {
        ServerAnswer.NotAsked -> stringResource(R.string.sync_server_not_asked)
        ServerAnswer.Asking -> stringResource(R.string.sync_server_asking)
        is ServerAnswer.Answered ->
            stringResource(
                R.string.sync_server_counts,
                answer.value.done,
                answer.value.total,
                answer.value.doneThisWeek,
            )
        is ServerAnswer.Failed -> failureMessage(LocalResources.current, answer.failure)
    }

private fun LazyListScope.storageGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("storage", R.string.sync_storage)
    val listing = (state.bucket as? ServerAnswer.Answered)?.value
    item(key = "bucket") {
        ActionRow(
            index = 0,
            count = 2,
            icon = R.drawable.ic_photo_library,
            headline = bucketText(state.bucket),
            supporting = stringResource(R.string.sync_storage_body),
            action =
                stringResource(
                    if (listing?.hasMore == true) R.string.sync_storage_more
                    else R.string.sync_storage_list
                ),
            enabled = state.session == SessionState.SignedIn && state.bucket != ServerAnswer.Asking,
            onAction = { actions.onListBucket(nextOffsetOf(state.bucket)) },
        )
    }
    item(key = "signed-upload") {
        TryItRow(
            index = 1,
            count = 2,
            action = TryIt.SignedUpload,
            state = state,
            actions = actions,
        )
    }
}

/** Where the next page of folders starts: after this one when it has more, else from the top. */
private fun nextOffsetOf(answer: ServerAnswer<BucketListing>): Int {
    val listing = (answer as? ServerAnswer.Answered)?.value ?: return 0
    return if (listing.hasMore) listing.nextOffset else 0
}

@Composable
private fun bucketText(answer: ServerAnswer<BucketListing>): String {
    val context = LocalContext.current
    return when (answer) {
        ServerAnswer.NotAsked -> stringResource(R.string.sync_storage_not_asked)
        ServerAnswer.Asking -> stringResource(R.string.sync_server_asking)
        is ServerAnswer.Answered -> {
            val files = answer.value.folders.flatMap { it.files }
            val bytes = files.sumOf { it.sizeBytes ?: 0L }
            val newest = files.mapNotNull { it.createdAt }.maxOrNull()
            stringResource(
                R.string.sync_storage_listing,
                pluralStringResource(R.plurals.sync_storage_files, files.size, files.size),
                pluralStringResource(
                    R.plurals.sync_storage_folders,
                    answer.value.folders.size,
                    answer.value.folders.size,
                ),
                Formatter.formatShortFileSize(context, bytes),
                newest?.let { relativeTime(it) } ?: stringResource(R.string.sync_storage_none),
            )
        }
        is ServerAnswer.Failed -> failureMessage(LocalResources.current, answer.failure)
    }
}

private fun LazyListScope.developerGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("developer", R.string.sync_developer)
    val items =
        listOf(
            TryIt.InvalidTitle,
            TryIt.SampleTasks,
            TryIt.StarterList,
            TryIt.GroupedDelete,
            TryIt.TwoFilters,
            TryIt.CountOpen,
        )
    val rows = items.size + 1
    items.forEachIndexed { index, action ->
        item(key = "try-${action.name}") {
            TryItRow(index = index, count = rows, action = action, state = state, actions = actions)
        }
    }
    // No stand-in server ships in the app, so a conflict needs a second writer: the recipe.
    item(key = "try-make-conflict") {
        InfoRow(
            index = items.size,
            count = rows,
            icon = R.drawable.ic_science,
            headline = stringResource(R.string.try_make_conflict),
            supporting = stringResource(R.string.try_make_conflict_body),
        )
    }
}

/** One Try it action: what it calls, and what it did last time, in a line. */
@Composable
private fun TryItRow(
    index: Int,
    count: Int,
    action: TryIt,
    state: SyncUiState.Ready,
    actions: SyncActions,
) {
    // Every action needs its user, so before the first sign-in there is nothing to try.
    val signedIn = state.session == SessionState.SignedIn
    val offline =
        state.status.network.let {
            it == AppNetworkStatus.Offline || it == AppNetworkStatus.Disabled
        }
    // The grouped delete is about being offline: online it would delete this week's done tasks.
    val enabled =
        signedIn &&
            state.tryIt[action] != TryItResult.Running &&
            (action != TryIt.GroupedDelete || offline)
    val result = state.tryIt[action]
    SegmentedListItem(
        onClick = { actions.onTryIt(action) },
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        enabled = enabled,
        leadingContent = { Icon(painterResource(R.drawable.ic_science), null) },
        supportingContent = {
            Column {
                Text(
                    when {
                        !signedIn -> stringResource(R.string.sync_queue_invalid_no_session)
                        action == TryIt.GroupedDelete && !offline ->
                            stringResource(R.string.try_grouped_delete_online)
                        else -> stringResource(action.body)
                    }
                )
                result?.let { SmallText(tryItResultText(action, it)) }
            }
        },
    ) {
        Text(stringResource(action.title))
    }
}

/** What a Try it action did, in one line. */
@Composable
private fun tryItResultText(action: TryIt, result: TryItResult): String =
    when (result) {
        TryItResult.Running -> stringResource(R.string.try_running)
        is TryItResult.Written ->
            when (result.outcome) {
                TaskMutationOutcome.Applied -> stringResource(R.string.try_applied)
                is TaskMutationOutcome.Queued -> stringResource(R.string.try_queued)
            }
        is TryItResult.Counted ->
            pluralStringResource(R.plurals.try_counted_open, result.count, result.count)
        TryItResult.Done ->
            stringResource(
                if (action == TryIt.SignedUpload) R.string.try_signed_upload_done
                else R.string.try_done
            )
        TryItResult.NothingToDo -> stringResource(R.string.try_signed_upload_nothing)
        is TryItResult.Failed -> failureMessage(LocalResources.current, result.failure)
    }

private val TryIt.title: Int
    get() =
        when (this) {
            TryIt.InvalidTitle -> R.string.sync_queue_invalid
            TryIt.SampleTasks -> R.string.try_sample_tasks
            TryIt.StarterList -> R.string.try_starter_list
            TryIt.GroupedDelete -> R.string.try_grouped_delete
            TryIt.TwoFilters -> R.string.try_two_filters
            TryIt.CountOpen -> R.string.try_count_open
            TryIt.SignedUpload -> R.string.try_signed_upload
        }

private val TryIt.body: Int
    get() =
        when (this) {
            TryIt.InvalidTitle -> R.string.sync_queue_invalid_body
            TryIt.SampleTasks -> R.string.try_sample_tasks_body
            TryIt.StarterList -> R.string.try_starter_list_body
            TryIt.GroupedDelete -> R.string.try_grouped_delete_body
            TryIt.TwoFilters -> R.string.try_two_filters_body
            TryIt.CountOpen -> R.string.try_count_open_body
            TryIt.SignedUpload -> R.string.try_signed_upload_body
        }

private fun LazyListScope.localDataGroup(onConfirm: (Confirmation) -> Unit) {
    groupTitle("local-data", R.string.sync_local_data)
    item(key = "clear") {
        SegmentedListItem(
            onClick = { onConfirm(Confirmation.ClearLocalData) },
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
            leadingContent = { Icon(painterResource(R.drawable.ic_delete_sweep), null) },
            supportingContent = { Text(stringResource(R.string.sync_clear_body)) },
        ) {
            Text(stringResource(R.string.sync_clear))
        }
    }
    item(key = "start-over") {
        SegmentedListItem(
            onClick = { onConfirm(Confirmation.StartOver) },
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
            leadingContent = { Icon(painterResource(R.drawable.ic_restart_alt), null) },
            supportingContent = { Text(stringResource(R.string.sync_start_over_body)) },
        ) {
            Text(stringResource(R.string.sync_start_over))
        }
    }
}

private fun LazyListScope.aboutGroup(state: SyncUiState.Ready) {
    groupTitle("about", R.string.sync_about)
    item(key = "session") {
        InfoRow(
            index = 0,
            count = 1,
            icon = R.drawable.ic_account_circle,
            headline = stringResource(R.string.sync_session),
            supporting =
                stringResource(
                    when (state.session) {
                        SessionState.SigningIn -> R.string.sync_session_signing_in
                        SessionState.SignedIn -> R.string.sync_session_signed_in
                        is SessionState.Failed -> R.string.sync_session_failed
                    }
                ),
        )
    }
}

private fun LazyListScope.groupTitle(key: String, @StringRes title: Int) {
    item(key = "title-$key") {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 20.dp, bottom = 6.dp),
        )
    }
}

/** A row that only informs: an icon, a headline and a sentence. */
@Composable
private fun InfoRow(
    index: Int,
    count: Int,
    @DrawableRes icon: Int,
    headline: String,
    supporting: String,
) {
    val shapes: ListItemShapes = ListItemDefaults.segmentedShapes(index = index, count = count)
    SegmentedListItem(
        shapes = shapes,
        leadingContent = { Icon(painterResource(icon), null) },
        supportingContent = { Text(supporting) },
    ) {
        Text(headline)
    }
}

/** A row with a switch: the whole row toggles it. */
@Composable
private fun SwitchRow(
    index: Int,
    count: Int,
    @DrawableRes icon: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    headline: String,
    supporting: String,
) {
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onCheckedChange,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { Icon(painterResource(icon), null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        supportingContent = { Text(supporting) },
    ) {
        Text(headline)
    }
}

/** A row with one button: the headline says what the last press found. */
@Composable
private fun ActionRow(
    index: Int,
    count: Int,
    @DrawableRes icon: Int,
    headline: String,
    supporting: String,
    action: String,
    enabled: Boolean,
    onAction: () -> Unit,
) {
    SegmentedListItem(
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        leadingContent = { Icon(painterResource(icon), null) },
        supportingContent = {
            Column {
                Text(supporting)
                TextButton(onClick = onAction, enabled = enabled) { Text(action) }
            }
        },
    ) {
        Text(headline)
    }
}

/** A line of detail, smaller and quieter than the text above it. */
@Composable
private fun SmallText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun lastSyncText(state: SyncUiState.Ready): String {
    val error = state.status.lastSyncError
    val syncedAt = state.status.lastSyncedAt
    return when {
        error is SyncStoppedException -> syncStoppedReason(LocalResources.current, error)
        error != null -> stringResource(R.string.sync_last_stopped)
        syncedAt != null -> stringResource(R.string.sync_last_synced, relativeTime(syncedAt))
        else -> stringResource(R.string.sync_never_synced)
    }
}

/** A waiting write in words: "Added “Buy milk”", "Deleted a photo". */
private fun waitingChangeText(resources: Resources, change: WaitingChange): String {
    val title =
        change.taskTitle?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.task_untitled)
    return when {
        change.table == Tables.TASK_PHOTOS ->
            resources.getString(
                when (change.operation) {
                    TaskMutationOperation.Insert,
                    TaskMutationOperation.Upsert -> R.string.waiting_photo_added
                    TaskMutationOperation.Update -> R.string.waiting_photo_moved
                    TaskMutationOperation.Delete -> R.string.waiting_photo_deleted
                }
            )
        change.operation == TaskMutationOperation.Delete ->
            resources.getString(R.string.waiting_task_deleted)
        // An update that sets no title, such as the done switch's, says which change it was not.
        change.operation == TaskMutationOperation.Update && change.taskTitle == null ->
            resources.getString(R.string.waiting_task_changed)
        change.operation == TaskMutationOperation.Update ->
            resources.getString(R.string.waiting_task_edited, title)
        else -> resources.getString(R.string.waiting_task_added, title)
    }
}

@Composable
private fun ConfirmationDialog(step: Confirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val (title, body, confirm) =
        when (step) {
            Confirmation.ClearLocalData ->
                Triple(
                    R.string.sync_clear_title,
                    R.string.sync_clear_confirm_body,
                    R.string.sync_clear,
                )
            Confirmation.StartOver ->
                Triple(
                    R.string.sync_start_over_title,
                    R.string.sync_start_over_confirm_body,
                    R.string.sync_start_over,
                )
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val AppNetworkStatus.label: Int
    get() =
        when (this) {
            AppNetworkStatus.Unknown -> R.string.network_unknown
            AppNetworkStatus.Online -> R.string.network_online
            AppNetworkStatus.Offline -> R.string.network_offline
            AppNetworkStatus.Disabled -> R.string.network_disabled
        }

private val AppNetworkStatus.description: Int
    get() =
        when (this) {
            AppNetworkStatus.Unknown -> R.string.network_unknown_body
            AppNetworkStatus.Online -> R.string.network_online_body
            AppNetworkStatus.Offline -> R.string.network_offline_body
            AppNetworkStatus.Disabled -> R.string.network_disabled_body
        }

private val LiveUpdatesStatus.description: Int
    get() =
        when (this) {
            LiveUpdatesStatus.Off -> R.string.live_updates_off
            LiveUpdatesStatus.Paused -> R.string.live_updates_paused
            LiveUpdatesStatus.Connecting -> R.string.live_updates_connecting
            LiveUpdatesStatus.Connected -> R.string.live_updates_connected
            LiveUpdatesStatus.Failed -> R.string.live_updates_failed
        }

/** The icon and the text of the Task list row for [refresh]. */
private fun refreshRowOf(refresh: TaskRefreshState): Pair<Int, Int> =
    when (refresh) {
        TaskRefreshState.Idle -> R.drawable.ic_cloud_done to R.string.sync_refresh_idle
        TaskRefreshState.Refreshing -> R.drawable.ic_refresh to R.string.sync_refresh_running
        TaskRefreshState.Paused -> R.drawable.ic_cloud_off to R.string.sync_refresh_paused
        is TaskRefreshState.Stalled -> R.drawable.ic_sync_problem to R.string.sync_refresh_stalled
    }

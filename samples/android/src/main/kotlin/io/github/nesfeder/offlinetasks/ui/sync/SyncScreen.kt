// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.sync

import android.text.format.DateUtils
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.RejectedTaskMutation
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.platform.SessionState
import io.github.nesfeder.offlinetasks.ui.components.segmentedScreenAppBarColors
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.feedback.refusalReason
import io.github.nesfeder.offlinetasks.ui.status.RollingText

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
                onQueueInvalidTitle = viewModel::queueInvalidTitle,
                onClearLocalData = viewModel::clearLocalData,
                onStartOver = viewModel::startOver,
            ),
    )
}

/** Everything the Sync screen can ask for. */
class SyncActions(
    val onSimulateOffline: (Boolean) -> Unit,
    val onSyncNow: () -> Unit,
    val onDismiss: (RejectedTaskMutation) -> Unit,
    val onRetry: (RejectedTaskMutation) -> Unit,
    val onQueueInvalidTitle: () -> Unit,
    val onClearLocalData: () -> Unit,
    val onStartOver: () -> Unit,
)

/** A step that throws away data, waiting for the user to confirm it. */
private enum class Confirmation {
    ClearLocalData,
    StartOver,
}

/**
 * The library's offline state made visible: the network with a switch to simulate losing it, the
 * writes waiting per table with "Sync now", the list's refresh state, refused writes, and the two
 * ways to start again, each behind a confirmation.
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
            queueGroup(ready, actions)
            refreshGroup(ready)
            rejectedGroup(ready, actions)
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
        SegmentedListItem(
            checked = simulating,
            onCheckedChange = actions.onSimulateOffline,
            shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
            leadingContent = { Icon(painterResource(R.drawable.ic_wifi_off), null) },
            trailingContent = { Switch(checked = simulating, onCheckedChange = null) },
            supportingContent = { Text(stringResource(R.string.sync_simulate_offline_body)) },
        ) {
            Text(stringResource(R.string.sync_simulate_offline))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.queueGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("queue", R.string.sync_queue)
    item(key = "pending") {
        val pending = state.status.pendingMutationCount
        val canSync = state.status.network == AppNetworkStatus.Online
        SegmentedListItem(
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
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
            SegmentedListItem(
                shapes = ListItemDefaults.segmentedShapes(index, state.rejected.size),
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
                            Text(
                                text =
                                    stringResource(
                                        R.string.sync_rejected_server_answer,
                                        error.status,
                                        error.code.orEmpty(),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { actions.onRetry(rejection) }) {
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
    }
}

private fun LazyListScope.developerGroup(state: SyncUiState.Ready, actions: SyncActions) {
    groupTitle("developer", R.string.sync_developer)
    item(key = "queue-invalid") {
        // A task needs its user, so before the first sign-in there is nothing to queue.
        val signedIn = state.session == SessionState.SignedIn
        SegmentedListItem(
            onClick = actions.onQueueInvalidTitle,
            shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1),
            enabled = signedIn,
            leadingContent = { Icon(painterResource(R.drawable.ic_science), null) },
            supportingContent = {
                Text(
                    stringResource(
                        if (signedIn) R.string.sync_queue_invalid_body
                        else R.string.sync_queue_invalid_no_session
                    )
                )
            },
        ) {
            Text(stringResource(R.string.sync_queue_invalid))
        }
    }
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

@Composable
private fun lastSyncText(state: SyncUiState.Ready): String {
    val error = state.status.lastSyncError
    val syncedAt = state.status.lastSyncedAt
    return when {
        error != null -> stringResource(R.string.sync_last_stopped)
        syncedAt != null ->
            stringResource(
                R.string.sync_last_synced,
                DateUtils.getRelativeTimeSpanString(
                    syncedAt.toEpochMilliseconds(),
                    System.currentTimeMillis(),
                    DateUtils.SECOND_IN_MILLIS,
                ),
            )
        else -> stringResource(R.string.sync_never_synced)
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

/** The icon and the text of the Task list row for [refresh]. */
private fun refreshRowOf(refresh: TaskRefreshState): Pair<Int, Int> =
    when (refresh) {
        TaskRefreshState.Idle -> R.drawable.ic_cloud_done to R.string.sync_refresh_idle
        TaskRefreshState.Refreshing -> R.drawable.ic_refresh to R.string.sync_refresh_running
        TaskRefreshState.Paused -> R.drawable.ic_cloud_off to R.string.sync_refresh_paused
        is TaskRefreshState.Stalled -> R.drawable.ic_sync_problem to R.string.sync_refresh_stalled
    }

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.status

import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.AppSyncStatus
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest

/** How long "All changes synced" stays up after a sync before the banner goes. */
val SYNCED_MOMENT: Duration = 2_500.milliseconds

/** The one thing the app says about syncing: the Tasks subtitle and the banner under it. */
sealed interface SyncHeadline {
    /** Online with nothing waiting: the banner is hidden. */
    data object UpToDate : SyncHeadline

    /** A sync just emptied the queue; shown for [SYNCED_MOMENT], then [UpToDate]. */
    data object JustSynced : SyncHeadline

    /** No network: changes are kept on the device. */
    data class Offline(val pendingCount: Int) : SyncHeadline

    /** The queue is replaying now. */
    data class Syncing(val pendingCount: Int) : SyncHeadline

    /** Changes wait while the server is in reach, between two replays. */
    data class Waiting(val pendingCount: Int) : SyncHeadline

    /** The network is up but the server does not answer; the list shown is the saved one. */
    data object ShowingSavedData : SyncHeadline
}

// --8<-- [start:headline]
/** The headline for [status] and the task list's [refresh], in order of what matters most. */
fun syncHeadlineOf(status: AppSyncStatus, refresh: TaskRefreshState): SyncHeadline {
    val pending = status.pendingMutationCount
    return when {
        status.network == AppNetworkStatus.Offline || status.network == AppNetworkStatus.Disabled ->
            SyncHeadline.Offline(pending)
        status.isSyncing && pending > 0 -> SyncHeadline.Syncing(pending)
        refresh is TaskRefreshState.Stalled -> SyncHeadline.ShowingSavedData
        pending > 0 -> SyncHeadline.Waiting(pending)
        else -> SyncHeadline.UpToDate
    }
}

// --8<-- [end:headline]

/** These headlines with [SyncHeadline.JustSynced] shown for [holdFor] after a sync finishes. */
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<SyncHeadline>.withSyncedMoment(holdFor: Duration = SYNCED_MOMENT): Flow<SyncHeadline> =
    flow {
        var previous: SyncHeadline? = null
        emitAll(
            distinctUntilChanged().transformLatest { headline ->
                val syncJustFinished =
                    headline == SyncHeadline.UpToDate && previous is SyncHeadline.Syncing
                previous = headline
                if (syncJustFinished) {
                    emit(SyncHeadline.JustSynced)
                    delay(holdFor)
                }
                emit(headline)
            }
        )
    }

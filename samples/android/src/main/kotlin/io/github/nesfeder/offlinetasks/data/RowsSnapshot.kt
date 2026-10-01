// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

/**
 * One emission of a live query: the rows the device knows and how fresh they are. A new one arrives
 * whenever the rows or any of these facts change.
 *
 * @property value the rows, or null when nothing is known yet (first launch, nothing cached).
 * @property isFromCache true when the rows came from the device and the server has not confirmed
 *   them since the network came back.
 * @property fetchedAt when the server last answered this query, or null if it never has.
 * @property hasPendingMutations true when a queued write changes these rows.
 */
data class RowsSnapshot<out T>(
    val value: T?,
    val isFromCache: Boolean,
    val fetchedAt: Instant?,
    val hasPendingMutations: Boolean,
    val refresh: TaskRefreshState,
)

/** The task list as a live query sees it. */
typealias TaskListSnapshot = RowsSnapshot<List<Task>>

/** A list of photos as a live query sees it. */
typealias PhotoListSnapshot = RowsSnapshot<List<TaskPhoto>>

/** What a live query's refresh from the server is doing. */
sealed interface TaskRefreshState {
    /** The last refresh succeeded, or none is due. */
    data object Idle : TaskRefreshState

    /** A refresh is on its way to the server. */
    data object Refreshing : TaskRefreshState

    /** The network is known to be unavailable; the refresh resumes when it comes back. */
    data object Paused : TaskRefreshState

    /** Requests are failing while the network is believed up; the rows shown are the saved ones. */
    data class Stalled(val cause: Throwable) : TaskRefreshState
}

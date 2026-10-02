// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.network

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import platform.Network.nw_path_get_status
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.darwin.dispatch_queue_create

/**
 * Whether the device has a usable network, from Network's path monitor: `true` while the path is
 * satisfied. A cold flow: collecting it starts a monitor, and ending the collection cancels it.
 *
 * @return the flow, starting with the path at collection.
 */
internal fun pathMonitorNetworkAvailability(): Flow<Boolean> = callbackFlow {
    val monitor = nw_path_monitor_create()
    nw_path_monitor_set_update_handler(monitor) { path ->
        trySend(nw_path_get_status(path) == nw_path_status_satisfied)
    }
    nw_path_monitor_set_queue(monitor, dispatch_queue_create(MONITOR_QUEUE_LABEL, null))
    nw_path_monitor_start(monitor)
    awaitClose { nw_path_monitor_cancel(monitor) }
}
    .distinctUntilChanged()

/** The label of the dispatch queue the path monitor reports on. */
private const val MONITOR_QUEUE_LABEL = "io.github.nesfeder.supabase.offline.network"

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.network

import io.github.nesfeder.supabase.offline.NetworkDisabledException
import io.github.nesfeder.supabase.offline.NetworkStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Whether the client may reach the network, and whether it believes it can: the app's switch
 * ([disable], [enable]) and the connectivity [source] it watches. One per client; the switch is
 * never persisted, so a new client starts enabled.
 *
 * @param source `true` while the device is online, from the platform's monitor or the app's own
 *   signal; `null` for none, which leaves the status [NetworkStatus.UNKNOWN].
 */
internal class NetworkAccess(private val source: Flow<Boolean>? = null) {

    /**
     * What the status is made of, changed together so a reader never sees half a change.
     *
     * @property reconnections how many times the network has become reachable again.
     */
    private data class Inputs(
        val monitored: NetworkStatus,
        val disabled: Boolean,
        val reconnections: Long = 0L,
    ) {
        /** The status these inputs make: the switch wins over the monitor. */
        fun status(): NetworkStatus = if (disabled) NetworkStatus.DISABLED else monitored

        /** These inputs changed by [change], counting a reconnection when it makes one. */
        fun changedBy(change: (Inputs) -> Inputs): Inputs {
            val next = change(this)
            val reconnected = !status().isReachable() && next.status().isReachable()
            return if (reconnected) next.copy(reconnections = reconnections + 1) else next
        }
    }

    private val inputs = MutableStateFlow(Inputs(NetworkStatus.UNKNOWN, disabled = false))

    /** The status as it stands now. */
    val status: NetworkStatus
        get() = inputs.value.status()

    /**
     * The status each time anything it is made of changes, starting with the current one. Not
     * distinct, so a reader that reads [status] on each emission always ends on the current one.
     */
    val statusChanges: Flow<NetworkStatus> = inputs.map { it.status() }

    /** Whether the app has turned network access off. */
    val isDisabled: Boolean
        get() = inputs.value.disabled

    /** Whether the network is known to be unreachable: offline by the monitor, or disabled. */
    val isKnownUnavailable: Boolean
        get() = !status.isReachable()

    /**
     * One [Unit] when the network has become reachable again (offline or disabled before, online or
     * unknown now) since the last; never for what happened before collection. Several in a row a
     * slow collector has not taken may arrive as one.
     */
    val reconnections: Flow<Unit> = flow {
        var seen = inputs.value.reconnections
        inputs.collect { current ->
            if (current.reconnections > seen) {
                seen = current.reconnections
                emit(Unit)
            }
        }
    }

    /**
     * Watches [source] in [scope] until [scope] ends, when a platform monitor unregisters;
     * [onReconnected] runs each time it makes the network reachable again. When the source fails
     * (rethrown for [scope] to log) or ends, the status falls back to [NetworkStatus.UNKNOWN], so a
     * monitor that stopped while saying offline holds nothing back.
     */
    fun watchIn(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        val watched = source ?: return
        scope.launch {
            try {
                watched.collect { online ->
                    val monitored = if (online) NetworkStatus.ONLINE else NetworkStatus.OFFLINE
                    if (change { current -> current.copy(monitored = monitored) }) onReconnected()
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                stopMonitoring(onReconnected)
                throw failure
            }
            stopMonitoring(onReconnected)
        }
    }

    /** A source that says no more: [NetworkStatus.UNKNOWN], a reconnection when it said offline. */
    private suspend fun stopMonitoring(onReconnected: suspend () -> Unit) {
        if (change { current -> current.copy(monitored = NetworkStatus.UNKNOWN) }) onReconnected()
    }

    /** Turns network access off: nothing more is sent until [enable]. */
    fun disable() {
        change { current -> current.copy(disabled = true) }
    }

    /**
     * Turns network access back on.
     *
     * @return whether this made the network reachable again.
     */
    fun enable(): Boolean = change { current -> current.copy(disabled = false) }

    /** Applies [change] to the inputs; says whether it made the network reachable again. */
    private fun change(change: (Inputs) -> Inputs): Boolean {
        var reconnected = false
        inputs.update { current ->
            current.changedBy(change).also { next ->
                reconnected = next.reconnections > current.reconnections
            }
        }
        return reconnected
    }

    /** Throws [NetworkDisabledException] while network access is off: call before any request. */
    fun ensureEnabled() {
        if (isDisabled) throw NetworkDisabledException(NETWORK_DISABLED_MESSAGE)
    }

    /** Suspends until network access is on. */
    suspend fun awaitEnabled() {
        inputs.first { current -> !current.disabled }
    }

    internal companion object {
        /** What a request refused while network access is off fails with. */
        const val NETWORK_DISABLED_MESSAGE: String =
            "The library's network access is off (disableNetwork()); nothing was sent"
    }
}

/** Whether a request has a chance: online, or not known to be offline. */
private fun NetworkStatus.isReachable(): Boolean =
    this == NetworkStatus.ONLINE || this == NetworkStatus.UNKNOWN

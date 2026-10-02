// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Whether the library believes it can reach the network, as [SyncStatus.network] reports it.
 *
 * It is a hint, never a gate: a request the app makes is tried whatever this says, because a
 * connectivity monitor can be wrong either way. It decides only when the library tries by itself,
 * and whether a waiting refresh reads as [RefreshState.Paused].
 *
 * The set is closed: these four cases are every status, so a `when` over them stays exhaustive.
 */
public enum class NetworkStatus {

    /** Not known yet: the monitor, or the app's own signal, has not said. Treated as online. */
    UNKNOWN,

    /** The device has a network the monitor, or the app's own signal, takes as usable. */
    ONLINE,

    /** The device has no usable network, as the monitor or the app's own signal says. */
    OFFLINE,

    /**
     * The app turned the library's network access off with [Offline.disableNetwork]: nothing is
     * sent until [Offline.enableNetwork].
     */
    DISABLED,
}

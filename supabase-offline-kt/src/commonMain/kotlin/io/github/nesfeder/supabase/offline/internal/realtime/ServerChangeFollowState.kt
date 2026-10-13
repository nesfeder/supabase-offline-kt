// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

/** Where the client stands with the channel that follows its watched tables. */
internal sealed interface ServerChangeFollowState {

    /** No table is watched, the app switched the subscription off, or the client is closed. */
    data object Off : ServerChangeFollowState

    /**
     * Tables are watched and the subscription is on, but there is no user to subscribe for, or the
     * network is known to be down or switched off.
     */
    data object Paused : ServerChangeFollowState

    /** A join is in flight. */
    data object Subscribing : ServerChangeFollowState

    /** Changes arrive and are applied; entered again after every rejoin. */
    data object Subscribed : ServerChangeFollowState

    /**
     * The join failed or the channel ended; it is tried again after a bounded wait, at once on an
     * edge.
     *
     * @property cause what ended the channel.
     */
    data class Stalled(val cause: Throwable) : ServerChangeFollowState
}

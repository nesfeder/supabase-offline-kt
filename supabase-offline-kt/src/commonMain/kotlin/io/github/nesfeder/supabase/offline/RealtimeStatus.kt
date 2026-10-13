// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * Where the plugin stands with the channel that keeps the tables `realtime { }` named current
 * ([Offline.realtimeStatus]).
 *
 * The set is closed: these five cases are every state the channel can be in, so a `when` over them
 * stays exhaustive.
 */
public sealed interface RealtimeStatus {

    /**
     * No table is named in `realtime { }`, the app called [Offline.disableRealtime], or the client
     * is closed.
     */
    public data object Off : RealtimeStatus

    /**
     * Tables are named and the subscription is on, but there is no user to subscribe for (the
     * session restores, its refresh failed, or nobody is signed in), or the network is known to be
     * down or switched off. The channel is joined on the next edge.
     */
    public data object Paused : RealtimeStatus

    /** A join is in flight. */
    public data object Subscribing : RealtimeStatus

    /**
     * Changes arrive and are applied. Entered again after every rejoin, and each entry reads the
     * named tables again, since Realtime replays nothing.
     */
    public data object Subscribed : RealtimeStatus

    /**
     * The join was refused or the channel ended. It is tried again after a wait of at most a
     * minute, and at once when the network comes back or the session settles.
     *
     * @property cause what ended the channel.
     */
    @ConsistentCopyVisibility
    public data class Stalled internal constructor(public val cause: Throwable) : RealtimeStatus
}

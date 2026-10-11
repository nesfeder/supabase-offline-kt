// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * How the refresh behind a reactive read stands: what an app shows next to rows it may already
 * have.
 *
 * The set is closed: these four cases are every state a refresh can be in, so a `when` over them
 * stays exhaustive.
 */
public sealed interface RefreshState {

    /** No refresh is running or waiting: the last one succeeded, or the read does not refresh. */
    public data object Idle : RefreshState

    /**
     * A refresh is running, or is about to be tried again soon after a failure, or waits for a
     * session that is still settling, which is not a failure. Under
     * [ResponseTrust.AuthenticatedSessionOnly], a live read of an empty table made signed out
     * stays here, retried at most a minute apart, until the table has rows or a user signs in;
     * without Auth, only rows end it.
     */
    public data object Refreshing : RefreshState

    /**
     * The refresh is waiting for the network, which the library knows to be unavailable, and is
     * tried again when it comes back. Unlike [Stalled], nothing is failing.
     */
    public data object Paused : RefreshState

    /**
     * The refresh keeps failing. The read keeps showing the rows it has, and the refresh is still
     * tried, at most a minute apart.
     *
     * @property cause the failure that stalled the refresh; a later, different one does not replace
     *   it.
     */
    @ConsistentCopyVisibility
    public data class Stalled internal constructor(public val cause: Throwable) : RefreshState
}

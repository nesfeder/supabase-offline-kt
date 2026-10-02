// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth

/**
 * Which responses from the server may replace what the cache holds, set by
 * [Offline.Config.responseTrust].
 *
 * The question matters for a response fetched while the session is not the user's: while a stored
 * session restores, or after its refresh failed, supabase-kt sends the anon key, and row-level
 * security can then answer with fewer rows, or none. Written to the cache, such an answer would
 * hide the user's rows.
 *
 * Without the [Auth] plugin, or with the app's own token provider, there is one identity for the
 * life of the install: an app whose own token provider serves more than one user must clear local
 * data itself when its user changes.
 */
public sealed interface ResponseTrust {

    /**
     * A response replaces the cache when its request went out under a settled session that had not
     * changed by the time it answered: signed in, signed out, or a client without the [Auth]
     * plugin. A session that restores, or whose refresh failed, never writes to the cache. The
     * default.
     */
    public data object SettledSession : ResponseTrust

    /**
     * Only a signed-in session's response may empty the cache: an empty response is written only
     * when the session was signed in before and after the request. A response with rows is written
     * too, signed out or without the [Auth] plugin, but never while a stored session restores or
     * its refresh has failed, and never when another user signed in during the request. An app that
     * reads public rows signed out, and must never see a signed-out empty answer replace them,
     * chooses this.
     */
    public data object AuthenticatedSessionOnly : ResponseTrust
}

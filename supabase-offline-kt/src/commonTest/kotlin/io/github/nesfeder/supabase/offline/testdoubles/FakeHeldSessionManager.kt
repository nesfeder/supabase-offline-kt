// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CompletableDeferred

/**
 * A session store whose first load, the Auth plugin's own, waits for [release], keeping the plugin
 * `Initializing` as long as a test needs. Every load answers [stored], and each after the first
 * answers at once, as a real store would while the first is in flight; with [holdsEveryLoad], every
 * load waits for [release], as a store that hangs would.
 */
internal class FakeHeldSessionManager(
    private val stored: UserSession?,
    private val holdsEveryLoad: Boolean = false,
) : SessionManager {

    private val released = CompletableDeferred<Unit>()
    private var loads = 0

    /** Lets the plugin's load finish, so the session settles into [stored]. */
    fun release() {
        released.complete(Unit)
    }

    override suspend fun loadSession(): UserSession {
        if (loads++ == 0 || holdsEveryLoad) released.await()
        return stored ?: throw noStoredSession()
    }

    override suspend fun saveSession(session: UserSession) = Unit

    override suspend fun deleteSession() = Unit
}

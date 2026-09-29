// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CompletableDeferred

/**
 * A session store holding [stored], whose first load — the Auth plugin's own, as it starts — waits
 * until [release], so the plugin stays `Initializing` for as long as a test needs: a session still
 * restoring. Every later load answers at once, as a real store would while that one is in flight.
 */
internal class FakeHeldSessionManager(private val stored: UserSession?) : SessionManager {

    private val released = CompletableDeferred<Unit>()
    private var loads = 0

    /** Lets the plugin's load finish, so the session settles into [stored]. */
    fun release() {
        released.complete(Unit)
    }

    override suspend fun loadSession(): UserSession {
        if (loads++ == 0) released.await()
        return stored ?: throw NoSessionFoundException()
    }

    override suspend fun saveSession(session: UserSession) = Unit

    override suspend fun deleteSession() = Unit
}

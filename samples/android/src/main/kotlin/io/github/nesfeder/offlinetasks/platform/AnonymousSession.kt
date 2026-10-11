// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.platform

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.nesfeder.offlinetasks.data.UserSession
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.offline
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Where the app's anonymous session stands. */
sealed interface SessionState {
    /** Restoring a saved session, or signing in. */
    data object SigningIn : SessionState

    /** Signed in; every row and file belongs to this user. */
    data object SignedIn : SessionState

    /** Signing in failed; it is tried again when the network comes back. */
    data class Failed(val cause: Throwable) : SessionState
}

/**
 * Signs the app in anonymously the first time it runs, and again whenever it finds itself signed
 * out: the schema refuses every request without a signed-in user. Supabase keeps the session on the
 * device, so a returning user stays the same user, offline too.
 */
class AnonymousSession(private val supabase: SupabaseClient, private val scope: CoroutineScope) :
    UserSession {

    private val auth = supabase.auth
    private val failure = MutableStateFlow<Throwable?>(null)

    /** The session's state, for the Tasks and Sync screens. */
    val state: StateFlow<SessionState> =
        combine(auth.sessionStatus, failure) { status, error ->
                when (status) {
                    is SessionStatus.Authenticated -> SessionState.SignedIn
                    // A session that could not refresh offline is still this user's.
                    is SessionStatus.RefreshFailure -> SessionState.SignedIn
                    SessionStatus.Initializing -> SessionState.SigningIn
                    is SessionStatus.NotAuthenticated ->
                        error?.let { SessionState.Failed(it) } ?: SessionState.SigningIn
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, SessionState.SigningIn)

    override val userId: StateFlow<String?> =
        auth.sessionStatus
            .map { auth.currentUserOrNull()?.id }
            .stateIn(scope, SharingStarted.Eagerly, auth.currentUserOrNull()?.id)

    /** Signs in now if needed, and each time the network comes back while signed out. */
    fun start() {
        scope.launch {
            auth.awaitInitialization()
            // The library's view of the network: its monitor, or "Simulate offline" switched on.
            supabase.offline.syncStatus
                .map { it.network == NetworkStatus.ONLINE || it.network == NetworkStatus.UNKNOWN }
                .distinctUntilChanged()
                .filter { it }
                .collect { signInIfSignedOut() }
        }
    }

    override suspend fun signInAsNewUser() {
        auth.signOut()
        auth.signInAnonymously()
        failure.value = null
        userId.first { it != null }
    }

    private suspend fun signInIfSignedOut() {
        if (auth.currentSessionOrNull() != null) return
        try {
            auth.signInAnonymously()
            failure.value = null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failure.value = error
        }
    }
}

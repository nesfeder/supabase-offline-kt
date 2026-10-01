// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.platform

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The one [SupabaseClient] of the app, for the project in `local.properties`, or null when its URL
 * or key is missing (as on CI). Postgrest, Storage and Auth, as the library needs.
 */
fun createSampleSupabaseClient(url: String, publishableKey: String): SupabaseClient? {
    if (url.isBlank() || publishableKey.isBlank()) return null
    return createSupabaseClient(supabaseUrl = url, supabaseKey = publishableKey) {
        // Warnings and errors only: request logs name filter values.
        defaultLogLevel = LogLevel.WARNING
        install(Auth)
        install(Postgrest)
        install(Storage)
    }
}

/** Where the app's anonymous session stands. */
sealed interface SessionState {
    /** The app has no project URL or key, so it has no backend. */
    data object NotConfigured : SessionState

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
 * device, so a returning user stays the same user.
 */
class AnonymousSession(
    private val client: SupabaseClient?,
    private val scope: CoroutineScope,
    private val internetAvailability: Flow<Boolean>,
) {
    private val failure = MutableStateFlow<Throwable?>(null)

    /** The session's state, for the Sync screen. */
    val state: StateFlow<SessionState> =
        client?.let { supabase ->
            combine(supabase.auth.sessionStatus, failure) { status, error ->
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
        } ?: MutableStateFlow(SessionState.NotConfigured).asStateFlow()

    /** Signs in now if needed, and each time the internet comes back while signed out. */
    fun start() {
        val auth = client?.auth ?: return
        scope.launch {
            auth.awaitInitialization()
            internetAvailability.filter { it }.collect { signInIfSignedOut() }
        }
    }

    private suspend fun signInIfSignedOut() {
        val auth = client?.auth ?: return
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

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Who a client without the Auth plugin speaks for, told from the token its own access-token
 * provider returns, with `identityFromAccessToken` on. The token's `sub` claim names the user; it
 * is read, never verified, as the engine reads the token's `exp`.
 *
 * Every reader of the identity goes through [resolve], which asks the provider for its token, the
 * one the next request would carry, and publishes the answer in [state], or through [state] itself
 * where a read must not suspend. So a new token is seen wherever the client next decides whose data
 * it reads or writes: before a write is queued, before the cache is read or written, before each
 * queued write is sent, and once when the client opens. Between those, [state] holds the last
 * answer, and its changes drive the cache's owner, the replay of a user's queued writes and the
 * Realtime channel, as the Auth plugin's session status does.
 *
 * @param accessToken the client's provider, or `null` when it has none: every request then carries
 *   the project key, and the identity is nobody signed in.
 * @param projectKey the project's key, which a request carries when the provider returns none.
 * @param timeout how long one answer of the provider may take before it counts as a failure.
 */
internal class AccessTokenIdentity(
    private val accessToken: (suspend () -> String?)?,
    private val projectKey: String,
    private val engineLogger: EngineLogger,
    private val timeout: Duration = AUTH_SESSION_SETTLE_TIMEOUT,
) {

    /** Where the identity stands, as the provider last answered. */
    sealed interface State {

        /**
         * The identity data is written for and read as: the token's user, or nobody signed in, or,
         * while the provider fails, the identity it last named; `null` when none can be told.
         */
        val owner: QueueIdentity?

        /** The identity a request sent now goes out as; `null` unless the answer was a token. */
        val settled: QueueIdentity?

        /** The provider has not answered yet. */
        data object Unread : State {
            override val owner: QueueIdentity?
                get() = null

            override val settled: QueueIdentity?
                get() = null
        }

        /** The token names [identity]: a user by their `sub`, or nobody signed in. */
        data class Named(val identity: QueueIdentity) : State {
            override val owner: QueueIdentity
                get() = identity

            override val settled: QueueIdentity
                get() = identity
        }

        /** The token names no user: it carries no readable `sub`. Nothing is anyone's under it. */
        data object Unnamed : State {
            override val owner: QueueIdentity?
                get() = null

            override val settled: QueueIdentity?
                get() = null
        }

        /**
         * The provider failed, or took longer than its bound. Its requests fail too; [last], the
         * identity it last named, stays the owner of what is written and read meanwhile, and
         * nothing queued is sent until it answers again.
         */
        data class ProviderFailed(val last: QueueIdentity?) : State {
            override val owner: QueueIdentity?
                get() = last

            override val settled: QueueIdentity?
                get() = null
        }
    }

    private val current = MutableStateFlow<State>(State.Unread)

    /** The last answer, as it changes. */
    val state: StateFlow<State> = current.asStateFlow()

    /**
     * Asks the provider for its token now, publishes what it names, and returns it. A failure is
     * logged by its type and keeps the last identity as the owner; see [State.ProviderFailed].
     */
    suspend fun resolve(): State {
        val provider = accessToken ?: return publish(State.Named(QueueIdentity.Anonymous))
        val answer =
            try {
                withTimeoutOrNull(timeout) { TokenAnswer(provider()) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                engineLogger.debug {
                    "The access-token provider failed (${failure::class.simpleName}); the last " +
                        "identity it named stays the owner of local data"
                }
                null
            }
        if (answer == null) return publish(State.ProviderFailed(lastNamed()))
        val identity = identityOfAccessToken(answer.token, projectKey)
        return publish(if (identity == null) State.Unnamed else State.Named(identity))
    }

    /** The identity a request carrying [token] speaks for; see [identityOfAccessToken]. */
    fun identityOf(token: String?): QueueIdentity? = identityOfAccessToken(token, projectKey)

    /** The session a request sent now goes out under, as a read's response trust sees it. */
    fun requestSession(): RequestSession =
        when (val now = current.value) {
            State.Unread -> RequestSession.Restoring
            is State.Named ->
                if (now.identity == QueueIdentity.Anonymous) RequestSession.SignedOut
                else RequestSession.SignedIn(now.identity)
            State.Unnamed -> RequestSession.SignedIn(null)
            is State.ProviderFailed -> RequestSession.RefreshFailed
        }

    /** The identity last named, kept through a failure; `null` when none was. */
    private fun lastNamed(): QueueIdentity? =
        when (val now = current.value) {
            is State.Named -> now.identity
            is State.ProviderFailed -> now.last
            State.Unread,
            State.Unnamed -> null
        }

    /** Publishes [next], saying so when it names another identity than the last one did. */
    private fun publish(next: State): State {
        val previous = current.getAndUpdate { next }
        if (next is State.Named && previous.owner != null && previous.owner != next.identity) {
            engineLogger.info { "The access token names another identity than before" }
        }
        return next
    }

    /** One answer of the provider, so a `null` token is told apart from a timeout. */
    private class TokenAnswer(val token: String?)
}

/**
 * The identity a request carrying [token] speaks for, as a client that tells users apart by their
 * access token reads it: nobody signed in for no token or the project's [projectKey], the user its
 * `sub` claim names for a JWT that carries one, and `null`, no identity, for anything else.
 */
internal fun identityOfAccessToken(token: String?, projectKey: String): QueueIdentity? {
    if (token == null || token == projectKey) return QueueIdentity.Anonymous
    return jwtSubjectOrNull(token)
        ?.takeIf { subject -> subject.isNotBlank() }
        ?.let { subject -> QueueIdentity.User(subject) }
}

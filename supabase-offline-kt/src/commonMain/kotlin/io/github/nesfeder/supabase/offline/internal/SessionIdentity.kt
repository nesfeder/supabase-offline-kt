// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AUTH_SESSION_SETTLE_TIMEOUT
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AccessTokenIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentityReader
import io.github.nesfeder.supabase.offline.internal.mutationqueue.awaitSettledSession
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentReplayIdentity
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.internal.postgrest.requestSessionFor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whom the session speaks for, as each part of [OfflineClient] asks: the overlay and the counts, a
 * write, a replay, a read's request, and when that may have changed. With the Auth plugin it is
 * read from the session; with [Offline.Config.identityFromAccessToken] and no Auth plugin, from the
 * app's access token; otherwise it is the anonymous identity for good. [SessionCoordinator] acts on
 * its changes.
 *
 * Built before the cache components, which read the overlay's identity and its changes, so it holds
 * nothing they build. In memory it keeps one mirror: [overlayIdentity], the overlay's identity for
 * the last session status it was told for, told again when the status changes.
 */
@OptIn(SupabaseInternal::class)
internal class SessionIdentity(
    private val supabaseClient: SupabaseClient,
    config: Offline.Config,
    engineLogger: EngineLogger,
) {

    /**
     * The identity a write, a discard, a dismissal, a retry or a read's overlay is for, read once
     * per status.
     */
    private val queueIdentityReader = QueueIdentityReader(supabaseClient)

    /**
     * The identity told from the app's access token, for a client without the Auth plugin that
     * opened with [Offline.Config.identityFromAccessToken]; `null` otherwise. When set, every
     * reader of the identity below goes through it.
     */
    val tokenIdentity: AccessTokenIdentity? =
        if (!config.identityFromAccessToken || authPluginOrNull() != null) null
        else
            AccessTokenIdentity(
                accessToken = supabaseClient.accessToken,
                projectKey = supabaseClient.supabaseKey,
                engineLogger = engineLogger,
            )

    /** Whether the caches and the queue belong to one identity at a time: with Auth, or tokens. */
    val identityIsPerUser: Boolean = authPluginOrNull() != null || tokenIdentity != null

    /** The identity the overlay and counts are for, read once per session status. */
    private val overlayIdentity = MutableStateFlow<Pair<SessionStatus?, String?>?>(null)

    /**
     * A read or write found the cache another identity's:
     * [SessionCoordinator.keepCacheWithTheSession] looks again.
     */
    val cacheOwnerChecks =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    fun authPluginOrNull(): Auth? = supabaseClient.pluginManager.getPluginOrNull(Auth)

    /**
     * Emits whenever the session status changes, or the identity the access token names; once
     * without either.
     */
    fun sessionChanges(): Flow<Any?> =
        tokenIdentity?.state ?: authPluginOrNull()?.sessionStatus ?: flowOf(Unit)

    /**
     * The stored identity value for the current session status, computed once per status: while the
     * session restores or refreshes, telling it reads the stored session, through the reader the
     * writes share, for at most [AUTH_SESSION_SETTLE_TIMEOUT]. A store that takes longer leaves the
     * identity untold for that status: the caches are not served to it, so a read goes to the
     * network instead of waiting.
     */
    suspend fun overlayIdentityValue(): String? {
        // Asked of the provider each time: its next token may name another user
        tokenIdentity?.let { source ->
            return source.resolve().owner?.storedValue
        }
        val status = authPluginOrNull()?.sessionStatus?.value
        overlayIdentity.value?.let { (cachedStatus, identity) ->
            if (cachedStatus == status) return identity
        }
        val identity =
            withTimeoutOrNull(AUTH_SESSION_SETTLE_TIMEOUT) {
                    StoredIdentity(queueIdentityReader.current()?.storedValue)
                }
                ?.value
        overlayIdentity.value = status to identity
        return identity
    }

    /**
     * An identity read from the stored session, `null` when it names none, as a timeout's result.
     */
    private class StoredIdentity(val value: String?)

    /**
     * The identity a replay sent now would go out as, read fresh; `null` while the session
     * restores, its refresh failed, or the token names no user. See [currentReplayIdentity].
     */
    suspend fun replayIdentityNow(): QueueIdentity? {
        val source = tokenIdentity ?: return supabaseClient.currentReplayIdentity()
        return source.resolve().settled
    }

    /** Waits, bounded, for a settled session; with a token, asks its provider once. */
    suspend fun awaitSettledReplaySession(): Boolean {
        val source = tokenIdentity ?: return supabaseClient.awaitSettledSession()
        return source.resolve().settled != null
    }

    /** Whether a replay sent now goes out under a settled identity, without waiting or asking. */
    fun isReplaySessionSettledNow(): Boolean {
        val source = tokenIdentity ?: return supabaseClient.currentReplayIdentity() != null
        return source.state.value.settled != null
    }

    /**
     * The identity a write made now is queued for; a write, a discard, a dismissal and a retry are
     * all for it. With a token, asked of the provider each time.
     */
    suspend fun writeOwner(): QueueIdentity? {
        val source = tokenIdentity ?: return queueIdentityReader.current()
        return source.resolve().owner
    }

    /** The session a read sent now goes out under; the identity is the mutation queue's own. */
    fun requestSession(): RequestSession =
        tokenIdentity?.requestSession()
            ?: requestSessionFor(authPluginOrNull()?.sessionStatus?.value)
}

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.auth.status.SessionStatus
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.replayIdentityFor

/** The session a request goes out under, as far as trusting its response goes. */
internal sealed interface RequestSession {

    /**
     * Signed in: requests carry the user's token.
     *
     * @property identity the user, or `null` when the session names none.
     */
    data class SignedIn(val identity: QueueIdentity?) : RequestSession

    /** The Auth plugin is installed and nobody is signed in: requests carry the anon key. */
    data object SignedOut : RequestSession

    /** No Auth plugin: requests carry the anon key, or the token the app's own provider gives. */
    data object WithoutAuth : RequestSession

    /** The stored session is still loading: requests carry the anon key meanwhile. */
    data object Restoring : RequestSession

    /** The session's token refresh failed: requests carry the anon key until it recovers. */
    data object RefreshFailed : RequestSession
}

/**
 * The [RequestSession] a request made under [status] goes out as; [status] `null` is a client
 * without the Auth plugin. The identity is the mutation queue's own ([replayIdentityFor]), so reads
 * and replays agree on who a request speaks for.
 */
internal fun requestSessionFor(status: SessionStatus?): RequestSession = when (status) {
    null -> RequestSession.WithoutAuth
    is SessionStatus.Authenticated -> RequestSession.SignedIn(replayIdentityFor(status))
    is SessionStatus.NotAuthenticated -> RequestSession.SignedOut
    is SessionStatus.Initializing -> RequestSession.Restoring
    is SessionStatus.RefreshFailure -> RequestSession.RefreshFailed
}

/** The identity a settled [session] speaks for; `null` while it restores, after a failed refresh, or without a user. */
internal fun settledIdentityOf(session: RequestSession): QueueIdentity? = when (session) {
    is RequestSession.SignedIn -> session.identity
    RequestSession.SignedOut, RequestSession.WithoutAuth -> QueueIdentity.Anonymous
    RequestSession.Restoring, RequestSession.RefreshFailed -> null
}

/** When a network response may replace what the query cache holds. */
internal enum class ResponseTrustPolicy {

    /**
     * A response is trusted when its request went out under a settled identity that had not changed
     * by the time it answered: signed in, signed out, or a client without the Auth plugin. A session
     * that restores or whose refresh failed sends the anon key, so nothing it returns is written.
     */
    SETTLED_SESSION,

    /**
     * Only a signed-in session produces authoritative empty results: an empty response is written
     * only when the session was signed in before and after the request. A non-empty one is written
     * too, signed out or without the Auth plugin, but never while a stored session restores or its
     * refresh has failed, when the anon key's narrower answer would overwrite the user's rows.
     */
    AUTHENTICATED_SESSION_ONLY,
    ;

    /**
     * Whether a response fetched between [before] and [after] may be written to the cache.
     *
     * @param isEmpty whether the server's own body held no rows.
     */
    fun mayCache(before: RequestSession, after: RequestSession, isEmpty: Boolean): Boolean = when (this) {
        SETTLED_SESSION -> settledIdentityOf(before)?.let { identity -> identity == settledIdentityOf(after) } ?: false
        AUTHENTICATED_SESSION_ONLY -> when {
            isUnsettledStoredSession(before) || isUnsettledStoredSession(after) -> false
            else -> !isEmpty || (before is RequestSession.SignedIn && after is RequestSession.SignedIn)
        }
    }

    /** Whether a reactive read's refresh takes such a response as a failure and tries again. */
    fun refreshRetries(before: RequestSession, after: RequestSession, isEmpty: Boolean): Boolean = when (this) {
        SETTLED_SESSION -> !mayCache(before, after, isEmpty)
        AUTHENTICATED_SESSION_ONLY -> if (isEmpty) before !is RequestSession.SignedIn else !mayCache(before, after, isEmpty = false)
    }

    /**
     * Whether a one-shot read that got an untrusted response asks again once the session has moved:
     * after it stops restoring, or at once when it settled into another identity during the request.
     */
    fun oneShotAsksAgain(before: RequestSession, after: RequestSession, isEmpty: Boolean): Boolean = when (this) {
        SETTLED_SESSION -> before is RequestSession.Restoring || (settledIdentityOf(after) != null && after != before)
        AUTHENTICATED_SESSION_ONLY -> isEmpty && before is RequestSession.Restoring
    }
}

/** Whether [session] belongs to a stored user whose token is not on the wire: restoring, or after a failed refresh. */
private fun isUnsettledStoredSession(session: RequestSession): Boolean =
    session is RequestSession.Restoring || session is RequestSession.RefreshFailed

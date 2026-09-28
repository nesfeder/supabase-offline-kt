package io.github.nesfeder.supabase.offline.internal.outbox

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.io.encoding.Base64

/**
 * Whose writes the queue holds. Replay only runs under the identity that queued the writes, so one
 * account's offline writes are never sent under another account's session.
 */
internal sealed interface QueueIdentity {

    /** How the identity is persisted in the queue-owner reserved row. */
    val storedValue: String

    /**
     * A signed-in user.
     *
     * @property userId The user's id — the JWT `sub`.
     */
    data class User(val userId: String) : QueueIdentity {
        override val storedValue: String get() = "$USER_PREFIX$userId"
    }

    /** Nobody signed in: the anon key, or a client without the Auth plugin. */
    data object Anonymous : QueueIdentity {
        override val storedValue: String = ANONYMOUS_VALUE
    }

    private companion object {
        const val USER_PREFIX = "user:"
        const val ANONYMOUS_VALUE = "anonymous"
    }
}

/**
 * The identity the next request from this client is made for, or `null` when it cannot be told.
 *
 * - Without the Auth plugin there is no session model at all: [QueueIdentity.Anonymous].
 * - `Authenticated`: the session's user.
 * - `Initializing` and `RefreshFailure` are the *same* user whose session is restoring or whose
 *   token refresh has not landed (the usual state while offline): the stored session's user.
 * - `NotAuthenticated`: [QueueIdentity.Anonymous].
 *
 * Clients that authenticate through their own `accessToken` provider, without the Auth plugin,
 * resolve to [QueueIdentity.Anonymous]: the engine cannot tell their users apart.
 *
 * @return The identity, or `null` when the stored session could not be read or names no user.
 */
internal suspend fun SupabaseClient.currentQueueIdentity(): QueueIdentity? {
    val authPlugin: Auth = try {
        auth
    } catch (_: IllegalStateException) {
        return QueueIdentity.Anonymous
    }
    return try {
        queueIdentityFor(authPlugin.sessionStatus.value) { authPlugin.sessionManager.loadSession() }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        // The stored session could not be read. Better to say "unknown" than to guess an owner.
        null
    }
}

/**
 * The pure mapping behind [currentQueueIdentity].
 *
 * @param status The session status, read once.
 * @param storedSession Reads the persisted session; only called for a restoring or refreshing one.
 * @return The identity, or `null` when a session is present but names no user.
 */
internal suspend fun queueIdentityFor(
    status: SessionStatus,
    storedSession: suspend () -> UserSession?,
): QueueIdentity? = when (status) {
    is SessionStatus.Authenticated -> userOf(status.session)
    is SessionStatus.Initializing, is SessionStatus.RefreshFailure -> {
        val session = storedSession()
        if (session == null) QueueIdentity.Anonymous else userOf(session)
    }
    is SessionStatus.NotAuthenticated -> QueueIdentity.Anonymous
}

private fun userOf(session: UserSession): QueueIdentity? {
    val userId = session.user?.id ?: jwtSubjectOrNull(session.accessToken) ?: return null
    return QueueIdentity.User(userId)
}

/**
 * The identity a replay sent now would go out as, or `null` when the session is not settled.
 *
 * Unlike [currentQueueIdentity], a restoring or refreshing session is not the stored user here:
 * its requests would carry the anon key, so neither that user's writes nor the anonymous
 * identity's may be sent under it.
 *
 * @return The identity, or `null` while the session restores or refreshes, or names no user.
 */
internal fun SupabaseClient.currentReplayIdentity(): QueueIdentity? {
    val authPlugin: Auth = try {
        auth
    } catch (_: IllegalStateException) {
        return QueueIdentity.Anonymous
    }
    return replayIdentityFor(authPlugin.sessionStatus.value)
}

/**
 * The pure mapping behind [currentReplayIdentity].
 *
 * @param status The session status, read once.
 * @return The identity a request made under [status] goes out as, or `null` when unsettled.
 */
internal fun replayIdentityFor(status: SessionStatus): QueueIdentity? = when (status) {
    is SessionStatus.Authenticated -> userOf(status.session)
    is SessionStatus.NotAuthenticated -> QueueIdentity.Anonymous
    is SessionStatus.Initializing, is SessionStatus.RefreshFailure -> null
}

/** Base64url without padding, as JWT segments are encoded. */
private val JwtSegmentBase64: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)

/**
 * The `sub` claim of a JWT, read without verifying it: the engine only needs to tell two sessions
 * apart, not to trust the token.
 *
 * @param token A JWT.
 * @return The subject, or `null` when [token] is not a JWT carrying one.
 */
internal fun jwtSubjectOrNull(token: String): String? {
    val payloadSegment = token.split('.').getOrNull(1) ?: return null
    return try {
        val payload = Json.parseToJsonElement(JwtSegmentBase64.decode(payloadSegment).decodeToString())
        ((payload as? JsonObject)?.get("sub") as? JsonPrimitive)?.contentOrNull
    } catch (_: IllegalArgumentException) {
        null
    }
}

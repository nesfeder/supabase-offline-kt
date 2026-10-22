// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Keeps a joined channel's token current for a client without the Auth plugin. supabase-kt sends a
 * refreshed token to its channels only when Auth reports one, and the server closes a channel whose
 * token has expired; so, with the app's own token provider, this asks the provider for its token,
 * reads its `exp` claim, and hands it to Realtime for its channels: once at the start, since the
 * token the join went out with is not seen here, and then as soon as the provider returns a token
 * that expires later than the one last sent. The provider is asked again [lead] before the sent
 * token's expiry, and more and more often as the expiry nears, so a token it rotates late still
 * reaches the channel before the server closes it. A provider that fails is asked again after
 * [retryWait].
 *
 * @param accessToken the client's token provider.
 * @param refresh hands a token to Realtime, which sends it to its channels.
 * @param clock the time the expiry is read against.
 * @param lead how long before the sent token's expiry the provider is asked again.
 * @param retryWait the longest wait between two asks near the expiry; the wait after a failure, and
 *   after the sent token has expired.
 */
internal class RealtimeTokenRefresher(
    private val accessToken: suspend () -> String?,
    private val refresh: suspend (token: String) -> Unit,
    private val clock: Clock,
    private val engineLogger: EngineLogger,
    private val lead: Duration = 1.minutes,
    private val retryWait: Duration = 30.seconds,
) {

    /**
     * Keeps the channel's token current until canceled. Returns when the provider returns no token,
     * or one whose expiry cannot be read: the channel's rejoin covers an expiry then.
     */
    suspend fun keepFresh() {
        var sentExpiry: Instant? = null
        while (true) {
            val token =
                try {
                    accessToken()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    engineLogger.warning {
                        "Realtime: the access-token provider failed " +
                            "(${failure::class.simpleName}); it is asked again in $retryWait"
                    }
                    delay(retryWait)
                    continue
                } ?: return
            val expiry = jwtExpiryOrNull(token)
            if (expiry == null) {
                engineLogger.debug {
                    "Realtime: the access token carries no readable expiry; a channel it joined " +
                        "is joined again when the server closes it"
                }
                return
            }
            val lastSent = sentExpiry
            if (lastSent == null || expiry > lastSent) {
                if (refreshOnce(token)) {
                    sentExpiry = expiry
                    engineLogger.debug { "Realtime: sent the provider's token to the channel" }
                } else {
                    delay(retryWait)
                }
                continue
            }
            delay(nextAskIn(lastSent - clock.now()))
        }
    }

    /**
     * How long to wait before asking the provider again, with [remaining] until the sent token
     * expires: until [lead] before it, then at most [retryWait] and at most half of what is left,
     * never under a second; once it has expired, [retryWait].
     */
    private fun nextAskIn(remaining: Duration): Duration =
        when {
            remaining > lead -> remaining - lead
            remaining <= Duration.ZERO -> retryWait
            else -> minOf(retryWait, maxOf(remaining / 2, NEAR_EXPIRY_FLOOR))
        }

    /** Hands [token] to Realtime once; whether it went through. */
    private suspend fun refreshOnce(token: String): Boolean =
        try {
            refresh(token)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Realtime: refreshing the channel's token failed (${failure::class.simpleName})"
            }
            false
        }

    private companion object {
        /** The shortest wait between two asks of the provider near the expiry. */
        val NEAR_EXPIRY_FLOOR: Duration = 1.seconds
    }
}

/**
 * The `exp` claim of [token], a JWT, as an instant; `null` when the token is not a JWT with a
 * numeric `exp`. Reads the payload only: nothing is verified.
 */
internal fun jwtExpiryOrNull(token: String): Instant? {
    val parts = token.split('.')
    if (parts.size != 3) return null
    val payload =
        try {
            Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT_OPTIONAL)
                .decode(parts[1])
                .decodeToString()
        } catch (malformed: IllegalArgumentException) {
            return null
        }
    val claims =
        try {
            EngineJson.parseToJsonElement(payload) as? JsonObject
        } catch (malformed: SerializationException) {
            return null
        } ?: return null
    val seconds = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: return null
    return Instant.fromEpochSeconds(seconds)
}

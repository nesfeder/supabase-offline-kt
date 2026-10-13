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
 * token has expired; so, with the app's own token provider, this reads the `exp` claim of the token
 * the provider returns and asks Realtime to resolve a fresh one a little before it.
 *
 * @param accessToken the client's token provider.
 * @param refresh asks Realtime to resolve the provider's token again and send it to its channels.
 * @param clock the time the expiry is read against.
 * @param lead how long before the expiry the refresh is asked for.
 * @param retryWait the wait after a refresh that brought no later expiry, or after a failure.
 */
internal class RealtimeTokenRefresher(
    private val accessToken: suspend () -> String?,
    private val refresh: suspend () -> Unit,
    private val clock: Clock,
    private val engineLogger: EngineLogger,
    private val lead: Duration = 1.minutes,
    private val retryWait: Duration = 30.seconds,
) {

    /**
     * Refreshes the token before each expiry until canceled. Returns when the provider returns no
     * token, or one whose expiry cannot be read: the channel's rejoin covers an expiry then.
     */
    suspend fun keepFresh() {
        var lastExpiry: Instant? = null
        while (true) {
            val expiry = expiryOrNull() ?: return
            if (lastExpiry != null && expiry <= lastExpiry) {
                // The provider has not refreshed yet: ask again later, never in a tight loop
                delay(retryWait)
            } else {
                delay((expiry - clock.now() - lead).coerceAtLeast(Duration.ZERO))
            }
            lastExpiry = expiry
            if (refreshOnce()) {
                engineLogger.debug { "Realtime: sent the provider's token again before it expires" }
            } else {
                delay(retryWait)
            }
        }
    }

    /** The current token's expiry, or `null` when there is no token or no readable expiry. */
    private suspend fun expiryOrNull(): Instant? {
        val token =
            try {
                accessToken()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                engineLogger.warning {
                    "Realtime: the access-token provider failed " +
                        "(${failure::class.simpleName}); the channel's token is not refreshed"
                }
                return null
            } ?: return null
        val expiry = jwtExpiryOrNull(token)
        if (expiry == null) {
            engineLogger.debug {
                "Realtime: the access token carries no readable expiry; a channel it joined is " +
                    "joined again when the server closes it"
            }
        }
        return expiry
    }

    /** Asks Realtime for the refresh once; whether it went through. */
    private suspend fun refreshOnce(): Boolean =
        try {
            refresh()
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Realtime: refreshing the channel's token failed (${failure::class.simpleName})"
            }
            false
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

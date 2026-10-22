// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Without the Auth plugin, the provider's token is sent to the channel once at the start and then
 * whenever the provider returns one that expires later: the provider is asked a minute before the
 * sent token's expiry, and more often as the expiry nears, so a token rotated late goes out as soon
 * as it is returned; a provider or a send that fails is asked again after the retry wait; a token
 * without a readable expiry schedules nothing.
 */
class RealtimeTokenRefreshTest {

    private val logs = RecordingLogSink()

    /** When each token was sent, in scheduler milliseconds, with its expiry in seconds. */
    private val sent = mutableListOf<Pair<Long, Long>>()

    /** The scheduler's time as the refresher's clock. */
    private fun TestScope.clock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }

    /** A refresher over [accessToken] that records each send, with the test's clock. */
    private fun TestScope.newRefresher(
        accessToken: suspend () -> String?,
        refresh: suspend (String) -> Unit = { token -> record(token) },
    ): RealtimeTokenRefresher =
        RealtimeTokenRefresher(
            accessToken = accessToken,
            refresh = refresh,
            clock = clock(),
            engineLogger = logs.asEngineLogger(),
            lead = 1.minutes,
            retryWait = 30.seconds,
        )

    private fun TestScope.record(token: String) {
        sent += testScheduler.currentTime to jwtExpiryOrNull(token)!!.epochSeconds
    }

    @Test
    fun the_token_is_sent_at_the_start_and_again_a_minute_before_each_expiry() = runTest {
        // Given — a provider whose token expires in ten minutes, and that has a fresh one, good
        // for thirty, once asked within the last minute
        val refresher =
            newRefresher(
                accessToken = {
                    when {
                        testScheduler.currentTime < 540_000L -> jwt(expiresAtSeconds = 600)
                        testScheduler.currentTime < 1_740_000L -> jwt(expiresAtSeconds = 1800)
                        else -> jwt(expiresAtSeconds = 3600)
                    }
                }
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        runCurrent()

        // Then — the token the join went out with is sent once, so its expiry is known here
        assertEquals(listOf(0L to 600L), sent)

        // When / Then — nothing until a minute before the expiry, then the fresh token, then the
        // next one a minute before its expiry
        advanceTimeBy(9.minutes - 1.seconds)
        runCurrent()
        assertEquals(1, sent.size)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(listOf(0L to 600L, 540_000L to 1800L), sent)
        advanceTimeBy(20.minutes)
        runCurrent()
        assertEquals(listOf(0L to 600L, 540_000L to 1800L, 1_740_000L to 3600L), sent)
        keeping.cancel()
    }

    @Test
    fun a_token_rotated_late_is_sent_as_soon_as_the_provider_returns_it() = runTest {
        // Given — a provider that still returns the old token a minute before its expiry and
        // thirty seconds later, and rotates it only 25 seconds before the expiry
        val refresher =
            newRefresher(
                accessToken = {
                    if (testScheduler.currentTime < 575_000L) jwt(expiresAtSeconds = 600)
                    else jwt(expiresAtSeconds = 7200)
                }
            )

        // When — asked a minute before, then after the retry wait, then after half of what is
        // left
        val keeping = launch { refresher.keepFresh() }
        advanceTimeBy(9.minutes + 30.seconds)
        runCurrent()

        // Then — the old token was not sent again
        assertEquals(listOf(0L to 600L), sent)

        // When — the next ask, fifteen seconds later, finds the rotated token
        advanceTimeBy(15.seconds)
        runCurrent()

        // Then — sent at once, well before the old expiry, so the server never closes the channel
        assertEquals(listOf(0L to 600L, 585_000L to 7200L), sent)
        keeping.cancel()
    }

    @Test
    fun a_provider_that_fails_is_asked_again_after_the_retry_wait() = runTest {
        // Given — a provider that fails the first time it is asked
        var asks = 0
        val refresher =
            newRefresher(
                accessToken = {
                    if (asks++ == 0) throw IllegalStateException("secret token text")
                    jwt(expiresAtSeconds = 600)
                }
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        runCurrent()

        // Then — the failure is logged by its type, never its text, and nothing is sent yet
        assertEquals(emptyList(), sent)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("IllegalStateException" in warning, warning)
        assertTrue("secret" !in warning, warning)

        // When — the retry wait is over
        advanceTimeBy(30.seconds)
        runCurrent()

        // Then — asked again, and the token sent
        assertEquals(listOf(30_000L to 600L), sent)
        keeping.cancel()
    }

    @Test
    fun a_send_that_fails_is_logged_by_type_and_tried_again_after_the_retry_wait() = runTest {
        // Given — a send that fails the first time
        var sends = 0
        val refresher =
            newRefresher(
                accessToken = { jwt(expiresAtSeconds = 600) },
                refresh = { token ->
                    if (sends++ == 0) throw IllegalStateException("secret token text")
                    record(token)
                },
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        runCurrent()
        assertEquals(emptyList(), sent)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("IllegalStateException" in warning, warning)
        assertTrue("secret" !in warning, warning)
        advanceTimeBy(30.seconds)
        runCurrent()

        // Then — sent after the wait, and not again while the provider holds the same token
        assertEquals(listOf(30_000L to 600L), sent)
        advanceTimeBy(5.minutes)
        runCurrent()
        assertEquals(1, sent.size)
        keeping.cancel()
    }

    @Test
    fun an_expired_token_the_provider_keeps_returning_is_asked_for_at_the_retry_wait() = runTest {
        // Given — a provider stuck on a token that expires in two minutes
        var asks = 0
        val refresher =
            newRefresher(
                accessToken = {
                    asks++
                    jwt(expiresAtSeconds = 120)
                }
            )

        // When — the expiry comes and goes
        val keeping = launch { refresher.keepFresh() }
        advanceTimeBy(2.minutes)
        runCurrent()
        val asksAtExpiry = asks
        advanceTimeBy(5.minutes)
        runCurrent()

        // Then — asked within the last minute more and more often, never under a second apart,
        // and every thirty seconds once expired; the token sent once
        assertTrue(asksAtExpiry in 6..12, "$asksAtExpiry asks before the expiry")
        assertEquals(asksAtExpiry + 10, asks)
        assertEquals(listOf(0L to 120L), sent)
        keeping.cancel()
    }

    @Test
    fun a_token_without_a_readable_expiry_schedules_nothing() = runTest {
        // Given
        val refresher = newRefresher(accessToken = { "an-opaque-token" })

        // When
        refresher.keepFresh()
        advanceTimeBy(2.minutes)
        runCurrent()

        // Then — returned, nothing sent, said so at DEBUG only
        assertEquals(emptyList(), sent)
        assertTrue(logs.entriesAt(EngineLogLevel.DEBUG).isNotEmpty())
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isEmpty())
    }

    @Test
    fun the_expiry_is_read_from_a_jwt_with_or_without_padding_and_from_nothing_else() {
        // Given / When / Then
        assertEquals(Instant.fromEpochSeconds(600), jwtExpiryOrNull(jwt(expiresAtSeconds = 600)))
        assertEquals(
            Instant.fromEpochSeconds(600),
            jwtExpiryOrNull(jwt(expiresAtSeconds = 600, padded = true)),
        )
        assertNull(jwtExpiryOrNull("not.a.jwt"))
        assertNull(jwtExpiryOrNull("only-one-part"))
        assertNull(jwtExpiryOrNull(jwt(expiresAtSeconds = null)))
        // A payload that is not base64, not JSON, not an object, or whose exp is not a number
        val header = jwt(expiresAtSeconds = 600).substringBefore('.')
        assertNull(jwtExpiryOrNull("$header.%%%.signature"))
        assertNull(jwtExpiryOrNull("$header.${payload("not json")}.signature"))
        assertNull(jwtExpiryOrNull("$header.${payload("[1, 2]")}.signature"))
        assertNull(jwtExpiryOrNull("$header.${payload("""{"exp":"soon"}""")}.signature"))
    }

    /** [claims] as a JWT payload segment. */
    private fun payload(claims: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(claims.encodeToByteArray())

    /** A JWT whose payload carries [expiresAtSeconds] as `exp`, or no `exp` when `null`. */
    private fun jwt(expiresAtSeconds: Long?, padded: Boolean = false): String {
        val encoder =
            Base64.UrlSafe.withPadding(
                if (padded) Base64.PaddingOption.PRESENT else Base64.PaddingOption.ABSENT
            )
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val claims =
            if (expiresAtSeconds == null) """{"sub":"user"}"""
            else """{"sub":"user","exp":$expiresAtSeconds}"""
        val payload = encoder.encode(claims.encodeToByteArray())
        return "$header.$payload.signature"
    }
}

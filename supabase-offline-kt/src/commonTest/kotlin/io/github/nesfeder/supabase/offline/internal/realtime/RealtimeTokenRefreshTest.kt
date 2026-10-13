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
 * Without the Auth plugin, the channel's token is sent again a little before the token the provider
 * returns expires, and again before each later expiry; a provider that has not refreshed yet is
 * asked again later; a token without a readable expiry schedules nothing.
 */
class RealtimeTokenRefreshTest {

    private val logs = RecordingLogSink()
    private val refreshedAt = mutableListOf<Long>()

    /** The scheduler's time as the refresher's clock. */
    private fun TestScope.clock(): Clock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(testScheduler.currentTime)
        }

    @Test
    fun the_token_is_sent_again_a_minute_before_each_expiry() = runTest {
        // Given — a token that expires in ten minutes, then one that expires in thirty
        var token = jwt(expiresAtSeconds = 600)
        val refresher =
            RealtimeTokenRefresher(
                accessToken = { token },
                refresh = {
                    refreshedAt += testScheduler.currentTime
                    token = jwt(expiresAtSeconds = 1800)
                },
                clock = clock(),
                engineLogger = logs.asEngineLogger(),
                lead = 1.minutes,
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        advanceTimeBy(9.minutes - 1.seconds)
        runCurrent()
        assertEquals(emptyList(), refreshedAt)
        advanceTimeBy(1.seconds)
        runCurrent()

        // Then — refreshed at nine minutes, and again at twenty-nine
        assertEquals(listOf(540_000L), refreshedAt)
        advanceTimeBy(20.minutes)
        runCurrent()
        assertEquals(listOf(540_000L, 1_740_000L), refreshedAt)
        keeping.cancel()
    }

    @Test
    fun a_provider_that_has_not_refreshed_yet_is_asked_again_later() = runTest {
        // Given — a provider that still returns the old token after the first refresh
        var refreshes = 0
        val stale = jwt(expiresAtSeconds = 600)
        val refresher =
            RealtimeTokenRefresher(
                accessToken = { if (refreshes < 2) stale else jwt(expiresAtSeconds = 7200) },
                refresh = {
                    refreshedAt += testScheduler.currentTime
                    refreshes++
                },
                clock = clock(),
                engineLogger = logs.asEngineLogger(),
                lead = 1.minutes,
                retryWait = 30.seconds,
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        advanceTimeBy(9.minutes + 31.seconds)
        runCurrent()

        // Then — asked again after the retry wait, never in a tight loop, then before the new
        // token's expiry
        assertEquals(listOf(540_000L, 570_000L), refreshedAt)
        advanceTimeBy(7141.seconds - (9.minutes + 31.seconds))
        runCurrent()
        assertEquals(listOf(540_000L, 570_000L, 7_140_000L), refreshedAt)
        keeping.cancel()
    }

    @Test
    fun a_token_without_a_readable_expiry_schedules_nothing() = runTest {
        // Given
        val refresher =
            RealtimeTokenRefresher(
                accessToken = { "an-opaque-token" },
                refresh = { refreshedAt += testScheduler.currentTime },
                clock = clock(),
                engineLogger = logs.asEngineLogger(),
            )

        // When
        refresher.keepFresh()
        advanceTimeBy(2.minutes)
        runCurrent()

        // Then — returned, nothing refreshed, said so at DEBUG only
        assertEquals(emptyList(), refreshedAt)
        assertTrue(logs.entriesAt(EngineLogLevel.DEBUG).isNotEmpty())
        assertTrue(logs.entriesAt(EngineLogLevel.WARNING).isEmpty())
    }

    @Test
    fun a_provider_that_fails_is_logged_by_type_and_nothing_is_scheduled() = runTest {
        // Given
        val refresher =
            RealtimeTokenRefresher(
                accessToken = { throw IllegalStateException("secret token text") },
                refresh = { refreshedAt += testScheduler.currentTime },
                clock = clock(),
                engineLogger = logs.asEngineLogger(),
            )

        // When
        refresher.keepFresh()

        // Then
        assertEquals(emptyList(), refreshedAt)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("IllegalStateException" in warning, warning)
        assertTrue("secret" !in warning, warning)
    }

    @Test
    fun a_refresh_that_fails_is_logged_by_type_and_asked_again_after_the_retry_wait() = runTest {
        // Given — a token that expires in ten minutes, and a refresh that fails the first time
        var refreshes = 0
        val refresher =
            RealtimeTokenRefresher(
                accessToken = { jwt(expiresAtSeconds = 600) },
                refresh = {
                    refreshedAt += testScheduler.currentTime
                    if (refreshes++ == 0) throw IllegalStateException("secret token text")
                },
                clock = clock(),
                engineLogger = logs.asEngineLogger(),
                lead = 1.minutes,
                retryWait = 30.seconds,
            )

        // When
        val keeping = launch { refresher.keepFresh() }
        advanceTimeBy(9.minutes + 1.seconds)
        runCurrent()

        // Then — the failure is logged by its type, never its text, and the refresh is asked for
        // again after the wait (and the provider's unchanged expiry waits once more)
        assertEquals(listOf(540_000L), refreshedAt)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("IllegalStateException" in warning, warning)
        assertTrue("secret" !in warning, warning)
        advanceTimeBy(1.minutes)
        runCurrent()
        assertEquals(listOf(540_000L, 600_000L), refreshedAt)
        keeping.cancel()
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

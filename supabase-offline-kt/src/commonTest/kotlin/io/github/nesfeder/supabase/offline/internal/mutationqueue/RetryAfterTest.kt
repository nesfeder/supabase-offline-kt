// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.retryAfterOrNull
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * A server that answers with `Retry-After` is not asked again sooner by the library's own passes:
 * the wait after such a failure is at least what the server asked for, in seconds or as an HTTP
 * date, up to an hour.
 */
class RetryAfterTest {

    private val logger = EngineLogger(null, EngineLogLevel.INFO)

    /** The failure supabase-kt raises for a `503` that carries [retryAfter]. */
    private suspend fun unavailable(retryAfter: String): Exception {
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = """{"code":"PGRST001","message":"busy"}""",
                        status = HttpStatusCode.ServiceUnavailable,
                        headers =
                            headersOf(
                                HttpHeaders.ContentType to listOf("application/json"),
                                HttpHeaders.RetryAfter to listOf(retryAfter),
                            ),
                    )
                }
            )
        return try {
            server.supabaseClient().postgrest.from("notes").select()
            error("the server answered")
        } catch (failure: Exception) {
            failure
        }
    }

    @Test
    fun an_edge_does_not_cut_short_the_wait_the_server_asked_for() = runTest {
        // Given — a pass stopped on a 503 asking for 30 seconds
        val failure = unavailable("30")
        val passTimes = mutableListOf<Long>()
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passTimes += testScheduler.currentTime
                SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(failure))
            }
        automaticReplay.requestPass()
        runCurrent()

        // When — the network comes back a second later
        advanceTimeBy(1.seconds)
        automaticReplay.requestPassOnEdge()
        advanceTimeBy(29.seconds - 1.milliseconds)
        val beforeTheWait = passTimes.size
        advanceTimeBy(2.milliseconds)

        // Then — the reconnection's pass still waited for what the server asked
        assertEquals(1, beforeTheWait)
        assertEquals(listOf(0L, 30_000L), passTimes)
    }

    @Test
    fun the_next_pass_waits_at_least_as_long_as_the_server_asked() = runTest {
        // Given — every pass stops on a 503 asking for 30 seconds
        val failure = unavailable("30")
        val passTimes = mutableListOf<Long>()
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passTimes += testScheduler.currentTime
                SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(failure))
            }

        // When — a pass, and another asked for at once
        automaticReplay.requestPass()
        runCurrent()
        automaticReplay.requestPass()
        advanceTimeBy(30.seconds - 1.milliseconds)
        val beforeTheWait = passTimes.size
        advanceTimeBy(2.milliseconds)

        // Then — not the one second the backoff alone would wait
        assertEquals(1, beforeTheWait)
        assertEquals(listOf(0L, 30_000L), passTimes)
    }

    @Test
    fun a_retry_after_is_read_in_seconds_or_as_an_http_date() = runTest {
        // Given
        val now = { 1_445_412_480_000L } // Wed, 21 Oct 2015 07:28:00 GMT

        // When
        val inSeconds = unavailable("120").retryAfterOrNull(now)
        val asDate = unavailable("Wed, 21 Oct 2015 07:30:00 GMT").retryAfterOrNull(now)
        val unreadable = unavailable("soon").retryAfterOrNull(now)

        // Then
        assertEquals(2.minutes, inSeconds)
        assertEquals(2.minutes, asDate)
        assertNull(unreadable)
    }

    @Test
    fun a_server_cannot_hold_the_library_back_for_more_than_an_hour() = runTest {
        // Given — a server asking for a day
        val failure = unavailable("86400")
        val passTimes = mutableListOf<Long>()
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passTimes += testScheduler.currentTime
                SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(failure))
            }

        // When
        automaticReplay.requestPass()
        runCurrent()
        automaticReplay.requestPass()
        advanceTimeBy(61.minutes)

        // Then
        assertEquals(listOf(0L, 3_600_000L), passTimes)
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A network-first read with a warm cache sends its SELECT without supabase-kt's retries, so a
 * transport failure falls back to the cache at once, and retries a `503` or `520` itself. It must
 * retry exactly as supabase-kt does: the same statuses, waits, attempt count and `x-retry-count`
 * headers. Each case runs one scripted sequence through supabase-kt's own retries (a network-only
 * read) and through the warm path, and compares them, so an upstream change fails here.
 */
class RetryScheduleContractTest {

    /** One attempt as the server saw it: when, on the test's clock, and its retry number. */
    private data class Attempt(val atMillis: Long, val retryCount: String?)

    /**
     * The attempts [read] made against a server answering [statuses] in turn and then `200`, with
     * supabase-kt's `maxRetries` at [maxRetries], after the list was cached.
     */
    private suspend fun TestScope.attemptsOf(
        statuses: List<HttpStatusCode>,
        maxRetries: Int,
        read: suspend (OfflinePostgrestQueryBuilder) -> Unit,
    ): List<Attempt> {
        var remaining = statuses
        var recording = false
        val attempts = mutableListOf<Attempt>()
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    val status = remaining.firstOrNull()?.takeIf { recording }
                    if (recording)
                        attempts += Attempt(testScheduler.currentTime, request.retryCount)
                    if (status == null) {
                        okJson(ROWS)
                    } else {
                        remaining = remaining.drop(1)
                        postgrestError(status, code = "", message = "unavailable")
                    }
                },
                engineDispatcher = StandardTestDispatcher(testScheduler),
            )
        val items =
            newTestOfflineClient(
                    supabase =
                        newTestSupabaseClient(engine = server.engine) {
                            install(Postgrest) { this.maxRetries = maxRetries }
                        },
                    coordinatorScope = idleCoroutineScope(),
                )
                .from(TABLE)
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        recording = true
        val startedAt = testScheduler.currentTime
        read(items)
        return attempts.map { attempt -> attempt.copy(atMillis = attempt.atMillis - startedAt) }
    }

    /** Runs [read], keeping what it throws from ending the test. */
    private suspend fun ignoringFailure(read: suspend () -> Unit) {
        try {
            read()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The attempts are what is compared
        }
    }

    /** The attempts supabase-kt's retries and the warm path make for [statuses]. */
    private suspend fun TestScope.bothPaths(
        statuses: List<HttpStatusCode>,
        maxRetries: Int = 3,
    ): Pair<List<Attempt>, List<Attempt>> {
        val upstream =
            attemptsOf(statuses, maxRetries) { items ->
                ignoringFailure { items.select(readStrategy = ReadStrategy.NetworkOnly) }
            }
        val warm =
            attemptsOf(statuses, maxRetries) { items ->
                ignoringFailure { items.select(readStrategy = ReadStrategy.NetworkFirst()) }
            }
        return upstream to warm
    }

    @Test
    fun a_service_unavailable_sequence_is_retried_as_upstream_retries_it() = runTest {
        // Given / When — three 503s, then the answer
        val (upstream, warm) = bothPaths(List(3) { HttpStatusCode.ServiceUnavailable })

        // Then
        assertEquals(4, upstream.size, "$upstream")
        assertEquals(upstream, warm)
    }

    @Test
    fun an_unknown_error_from_the_edge_is_retried_as_upstream_retries_it() = runTest {
        // Given / When — three 520s, then the answer
        val (upstream, warm) = bothPaths(List(3) { HttpStatusCode(520, "Unknown Error") })

        // Then
        assertEquals(4, upstream.size, "$upstream")
        assertEquals(upstream, warm)
    }

    @Test
    fun the_wait_grows_to_upstreams_cap_as_upstream_grows_it() = runTest {
        // Given / When — seven 503s under seven retries, past where the wait stops doubling
        val (upstream, warm) =
            bothPaths(List(7) { HttpStatusCode.ServiceUnavailable }, maxRetries = 7)

        // Then
        assertEquals(8, upstream.size, "$upstream")
        assertEquals(upstream, warm)
    }

    @Test
    fun retries_stop_where_upstreams_stop() = runTest {
        // Given / When — more 503s than retries
        val (upstream, warm) = bothPaths(List(6) { HttpStatusCode.ServiceUnavailable })

        // Then
        assertTrue(upstream.size in 2..6, "$upstream")
        assertEquals(upstream, warm)
    }

    @Test
    fun a_status_upstream_does_not_retry_is_not_retried() = runTest {
        // Given / When — a 500
        val (upstream, warm) = bothPaths(listOf(HttpStatusCode.InternalServerError))

        // Then
        assertEquals(1, upstream.size, "$upstream")
        assertEquals(upstream, warm)
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1}]"""
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * How long a read waits on supabase-kt's own retries, measured on the real upstream client with its
 * default `maxRetries` (3: waits of 1 s, 2 s and 4 s between four attempts of a `GET`). The server
 * answers on the test's scheduler, so every wait is on the test's clock.
 */
class RetryDelayTest {

    /**
     * What the server does with the next request: answer the list, fail the transport, or a status.
     */
    private var answer: () -> HttpStatusCode? = { HttpStatusCode.OK }

    private fun TestScope.newItems(): Pair<RecordingPostgrestServer, OfflinePostgrestQueryBuilder> {
        val server =
            RecordingPostgrestServer(
                answer = {
                    when (val status = answer()) {
                        null -> connectivityFailure()
                        HttpStatusCode.OK -> okJson(ROWS)
                        else -> postgrestError(status, code = "", message = "unavailable")
                    }
                },
                engineDispatcher = StandardTestDispatcher(testScheduler),
            )
        // The upstream default: nothing here sets maxRetries
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClient(engine = server.engine),
                store = newTestOfflineStore(),
                coordinatorScope = idleCoroutineScope(),
            )
        return server to client.from(TABLE)
    }

    /** Runs [read], returning what it gave or threw and how long it took on the test's clock. */
    private suspend fun <T> TestScope.timed(read: suspend () -> T): Pair<Result<T>, Duration> {
        val startedAt = currentTime
        val result = runCatching { read() }
        return result to (currentTime - startedAt).milliseconds
    }

    @Test
    fun a_network_first_read_with_a_warm_cache_falls_back_as_soon_as_the_network_fails() = runTest {
        // Given — the list cached, then no connection
        val (server, items) = newItems()
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        answer = { null }
        val sentBefore = server.requests.size

        // When
        val (read, waited) = timed { items.select(readStrategy = ReadStrategy.NetworkFirst()) }

        // Then — the cached list, with no wait for retries the cache makes pointless
        assertEquals(ROWS, read.getOrThrow().data)
        val attempts = server.requests.size - sentBefore
        assertTrue(
            waited == Duration.ZERO && attempts == 1,
            "waited $waited over $attempts attempts before the fallback",
        )
    }

    @Test
    fun a_network_first_read_with_a_cold_cache_keeps_upstreams_retries() = runTest {
        // Given — nothing cached, no connection
        val (server, items) = newItems()
        answer = { null }

        // When
        val (read, waited) = timed { items.select(readStrategy = ReadStrategy.NetworkFirst()) }

        // Then — nothing to fall back to, so the read tries as hard as a network-only read
        assertTrue(read.exceptionOrNull()?.isNetworkOrConnectivityFailure() == true, "gave $read")
        assertEquals(7.seconds, waited)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun a_network_only_read_keeps_upstreams_retries() = runTest {
        // Given — the list cached, then no connection
        val (server, items) = newItems()
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        answer = { null }
        val sentBefore = server.requests.size

        // When
        val (read, waited) = timed { items.select(readStrategy = ReadStrategy.NetworkOnly) }

        // Then
        assertTrue(read.exceptionOrNull()?.isNetworkOrConnectivityFailure() == true, "gave $read")
        assertEquals(7.seconds, waited)
        assertEquals(4, server.requests.size - sentBefore)
    }

    @Test
    fun a_network_first_read_with_a_warm_cache_still_rides_out_a_server_that_is_briefly_unavailable() =
        runTest {
            // Given — the list cached, then a server that answers 503 three times before it
            // recovers
            val (server, items) = newItems()
            items.select(readStrategy = ReadStrategy.NetworkOnly)
            var unavailable = 3
            answer = {
                if (unavailable-- > 0) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK
            }
            val sentBefore = server.requests.size

            // When
            val (read, waited) = timed { items.select(readStrategy = ReadStrategy.NetworkFirst()) }

            // Then — the fresh answer, after upstream's own waits, as before
            assertEquals(ROWS, read.getOrThrow().data)
            assertEquals(7.seconds, waited)
            assertEquals(4, server.requests.size - sentBefore)
        }

    @Test
    fun a_network_first_read_that_turns_retries_off_is_not_retried() = runTest {
        // Given — the list cached, then a server that answers 503
        val (server, items) = newItems()
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        answer = { HttpStatusCode.ServiceUnavailable }
        val sentBefore = server.requests.size

        // When — the app asks for no retries on this read
        val (read, waited) =
            timed { items.select(readStrategy = ReadStrategy.NetworkFirst()) { noRetry() } }

        // Then — the server's error at once, from one request
        assertIs<PostgrestRestException>(read.exceptionOrNull())
        assertEquals(Duration.ZERO, waited)
        assertEquals(1, server.requests.size - sentBefore)
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1,"title":"a"}]"""
    }
}

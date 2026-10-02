// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * How many times a call runs the app's request lambda: a read once to learn its shape and once for
 * each request it sends, so a cache hit runs it once; a write under the default strategy three
 * times, to read it, to tell a dry run and to send it; an `OnlineOnly` write once.
 */
class RequestLambdaRunsTest {

    /** Whether the server can be reached. */
    private var online = true

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                okJson(if (request.method == "GET") """[{"id":1,"title":"a"}]""" else "[]")
            }
        )

    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                store =
                    newTestOfflineStore(nowMillis = { Clock.System.now().toEpochMilliseconds() }),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)

    /** Runs of the request lambdas handed to the calls under test. */
    private var runs = 0

    private val update = Json.parseToJsonElement("""{"title":"b"}""") as JsonObject
    private val rows = Json.parseToJsonElement("""[{"id":2,"title":"b"}]""") as JsonArray

    @Test
    fun a_cache_hit_runs_the_request_once() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("id", 1) } }
        val sent = server.requests.size

        // When
        items.select(readStrategy = ReadStrategy.CacheFirst(maxAge = 1.hours)) {
            runs++
            filter { eq("id", 1) }
        }

        // Then — served from the cache, the lambda run once
        assertEquals(sent, server.requests.size)
        assertEquals(1, runs)
    }

    @Test
    fun a_network_read_runs_the_request_once_to_learn_its_shape_and_once_to_send_it() = runTest {
        // When
        items.select(readStrategy = ReadStrategy.NetworkOnly) {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(2, runs)
    }

    @Test
    fun a_network_first_read_over_a_cached_list_runs_the_request_twice() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("id", 1) } }

        // When
        items.select(readStrategy = ReadStrategy.NetworkFirst()) {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(2, runs)
    }

    @Test
    fun an_update_sent_at_once_runs_the_request_three_times() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        items.update(update) {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun an_update_that_asks_for_its_rows_back_runs_the_request_three_times() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        items.update(update) {
            runs++
            filter { eq("id", 1) }
            select()
        }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun an_insert_sent_at_once_runs_the_request_three_times() = runTest {
        // When
        items.insert(rows) { runs++ }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun an_upsert_sent_at_once_runs_the_request_three_times() = runTest {
        // When
        items.upsert(rows) { runs++ }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun a_delete_sent_at_once_runs_the_request_three_times() = runTest {
        // When
        items.delete {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun a_write_queued_offline_runs_the_request_three_times() = runTest {
        // Given
        online = false

        // When
        items.update(update) {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(3, runs)
    }

    @Test
    fun an_online_only_write_runs_the_request_once() = runTest {
        // When
        items.update(update, mutationStrategy = MutationStrategy.OnlineOnly) {
            runs++
            filter { eq("id", 1) }
        }

        // Then
        assertEquals(1, runs)
    }

    private companion object {
        const val TABLE = "items"
    }
}

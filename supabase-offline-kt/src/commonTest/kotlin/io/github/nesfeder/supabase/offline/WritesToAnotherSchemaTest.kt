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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The mutation queue replays to the client's default schema only, so a write to another schema
 * under the default strategy is sent at once and never queued: online it is applied; offline it
 * fails with an error that names the schema and still reads as a connectivity failure. It does not
 * wait for writes queued to the default schema.
 */
class WritesToAnotherSchemaTest {

    /** Whether the server can be reached. */
    private var online = true

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                okJson(if (request.method == "GET") """[{"id":1,"done":false}]""" else "[]")
            }
        )
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = newTestOfflineStore(nowMillis = { Clock.System.now().toEpochMilliseconds() }),
            coordinatorScope = idleCoroutineScope(),
        )

    private fun writesTo(schema: String) =
        server.requests.filter { request ->
            request.method != "GET" && request.contentProfile == schema
        }

    @Test
    fun online_it_is_sent_to_that_schema() = runTest {
        // When
        val outcome =
            client.from(OTHER_SCHEMA, TABLE).updateJson(
                Json.parseToJsonElement("""{"done":true}""") as JsonObject
            ) {
                filter { eq("id", 1) }
            }

        // Then
        assertTrue(outcome is MutationOutcome.Applied, "$outcome")
        assertEquals(1, writesTo(OTHER_SCHEMA).size)
    }

    @Test
    fun offline_it_fails_naming_the_schema_and_nothing_is_queued() = runTest {
        // Given
        online = false

        // When
        val failure = runCatching {
            client
                .from(OTHER_SCHEMA, TABLE)
                .insertJson(Json.parseToJsonElement("""[{"id":2}]""") as JsonArray)
        }
            .exceptionOrNull()

        // Then — a connectivity failure, as apps already handle it, that says why nothing waits
        assertTrue(failure != null && failure.isNetworkOrConnectivityFailure(), "$failure")
        assertTrue(OTHER_SCHEMA in failure?.message.orEmpty(), "${failure?.message}")
        assertEquals(0, client.pendingMutationCount())
    }

    @Test
    fun it_does_not_wait_for_writes_queued_to_the_default_schema() = runTest {
        // Given — a write to the default schema queued offline
        online = false
        client.from(TABLE).insertJson(Json.parseToJsonElement("""[{"id":3}]""") as JsonArray)
        online = true

        // When — a write to another schema, before the queue is replayed
        client
            .from(OTHER_SCHEMA, TABLE)
            .insertJson(Json.parseToJsonElement("""[{"id":4}]""") as JsonArray)

        // Then — sent at once, ahead of the queued one
        assertEquals(1, writesTo(OTHER_SCHEMA).size)
        assertEquals(1, client.pendingMutationCount())
    }

    @Test
    fun the_cached_queries_of_that_schema_are_fetched_again() = runTest {
        // Given — a list of the other schema cached
        val list = client.from(OTHER_SCHEMA, TABLE)
        list.select(readStrategy = ReadStrategy.NetworkOnly)
        val readsBefore = server.requests.count { request -> request.method == "GET" }

        // When
        list.updateJson(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter { eq("id", 1) }
        }
        list.select(readStrategy = ReadStrategy.CacheFirst(cacheDuration = 1.hours))

        // Then — the list was asked for again
        assertEquals(readsBefore + 1, server.requests.count { request -> request.method == "GET" })
    }

    private companion object {
        const val TABLE = "notes"
        const val OTHER_SCHEMA = "private"
    }
}

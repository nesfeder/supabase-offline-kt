// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A table in a schema other than the client's default is its own cached query: it never shares a
 * cache slot with the same-named table in the default schema. An `OnlineOnly` write to it is sent
 * there; a write under the default strategy is too, as `WritesToAnotherSchemaTest` pins.
 */
class NonDefaultSchemaTest {

    /**
     * Answers a read with one row naming the schema it was asked in; `online` off, it cannot
     * connect.
     */
    private var online = true
    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                okJson(
                    if (request.method == "GET")
                        """[{"id":1,"schema":"${request.acceptProfile}"}]"""
                    else "[]"
                )
            }
        )
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            coordinatorScope = idleCoroutineScope(),
        )

    @Test
    fun the_same_table_in_two_schemas_is_cached_apart() = runTest {
        // Given — both read online
        client.from(OTHER_SCHEMA, TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        val other =
            client.from(OTHER_SCHEMA, TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data
        val default = client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data

        // Then
        assertEquals("""[{"id":1,"schema":"$OTHER_SCHEMA"}]""", other)
        assertEquals("""[{"id":1,"schema":"public"}]""", default)
    }

    @Test
    fun a_schema_set_in_the_request_is_cached_apart_too() = runTest {
        // Given
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) { schema = OTHER_SCHEMA }
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        val other =
            client
                .from(TABLE)
                .select(readStrategy = ReadStrategy.CacheOnly()) { schema = OTHER_SCHEMA }
                .data

        // Then
        assertEquals("""[{"id":1,"schema":"$OTHER_SCHEMA"}]""", other)
    }

    @Test
    fun an_online_only_write_to_another_schema_is_sent_to_it() = runTest {
        // When
        val outcome =
            client
                .from(OTHER_SCHEMA, TABLE)
                .insert(rows("""[{"id":2}]"""), mutationStrategy = MutationStrategy.OnlineOnly)

        // Then
        assertTrue(outcome is MutationOutcome.Applied)
        assertEquals(
            OTHER_SCHEMA,
            server.requests.single { request -> request.method == "POST" }.contentProfile,
        )
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
        const val OTHER_SCHEMA = "private"
    }
}

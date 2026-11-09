// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.everyFlowReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Two top-level filters on one column are sent, as supabase-kt sends them: every value of the
 * parameter, `age=gte.1&age=lte.10`, which PostgREST ANDs. A read sends both and its cached query
 * admits a local row only when it meets both; an update or a delete sends both, and one queued
 * offline captures both and replays both, so it never reaches more rows than it named. One `and {
 * }` group still sends both in one parameter, and filters on different columns are untouched.
 */
class SameColumnFiltersTest {

    /** Whether the server answers; off, every request fails to connect. */
    private var online = true

    private val server =
        RecordingPostgrestServer(answer = { if (online) okJson(ROWS) else connectivityFailure() })

    private val mutationStrategies =
        listOf(MutationStrategy.OnlineOnly, MutationStrategy.QueueIfOffline)

    /** The window `1 <= age <= 10`, written as two top-level filters on `age`. */
    private val ageWindow: PostgrestRequestBuilder.() -> Unit = {
        filter {
            gte(COLUMN, 1)
            lte(COLUMN, 10)
        }
    }

    private fun newClient(): OfflineClient =
        newTestOfflineClient(supabase = server.supabaseClient())

    /** Asserts every request the server received carried both values of the window, in order. */
    private fun assertEveryRequestSentTheWindow() {
        server.requests.forEach { request ->
            assertEquals(
                listOf("gte.1", "lte.10"),
                request.queryValues[COLUMN],
                "${request.method} ${request.encodedQuery}",
            )
        }
    }

    @Test
    fun a_one_shot_read_with_two_filters_on_one_column_sends_both_and_caches_the_answer() =
        runTest {
            // Given
            val client = newClient()
            val strategies =
                listOf(
                    ReadStrategy.NetworkOnly,
                    ReadStrategy.NetworkFirst(),
                    ReadStrategy.CacheFirst(),
                )

            // When
            strategies.forEach { strategy ->
                client.from(TABLE).select(readStrategy = strategy, request = ageWindow)
            }
            online = false
            val cached =
                client
                    .from(TABLE)
                    .select(readStrategy = ReadStrategy.CacheOnly(), request = ageWindow)

            // Then — sent with both values, and the cache answers the same query offline
            assertEquals(listOf(1), idsOf(cached.data))
            assertEquals(true, server.requests.isNotEmpty())
            assertEveryRequestSentTheWindow()
            client.close()
        }

    @Test
    fun a_live_read_with_two_filters_on_one_column_sends_both() = runTest {
        // Given
        val client = newClient()

        // When
        everyFlowReadStrategy
            .filter { strategy -> strategy != FlowReadStrategy.CacheOnly }
            .forEach { strategy ->
                client
                    .from(TABLE)
                    .selectAsFlow(readStrategy = strategy, request = ageWindow)
                    .first()
            }

        // Then
        assertEquals(true, server.requests.isNotEmpty())
        assertEveryRequestSentTheWindow()
        client.close()
    }

    @Test
    fun an_update_or_a_delete_with_two_filters_on_one_column_sends_both() = runTest {
        // Given
        val client = newClient()

        // When
        mutationStrategies.forEach { strategy ->
            val update = client.from(TABLE).update({ set("title", "renamed") }, strategy, ageWindow)
            val delete = client.from(TABLE).delete(strategy, ageWindow)

            // Then
            assertIs<MutationOutcome.Applied>(update)
            assertIs<MutationOutcome.Applied>(delete)
        }
        assertEquals(
            listOf("PATCH", "DELETE", "PATCH", "DELETE"),
            server.requests.map { it.method },
        )
        assertEveryRequestSentTheWindow()
        client.close()
    }

    @Test
    fun a_queued_update_or_delete_with_two_filters_on_one_column_replays_both() = runTest {
        // Given — an update and a delete through the window, queued offline
        // (no replay pass of the engine's own races the one under test)
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
        online = false
        val update = client.from(TABLE).update({ set("title", "renamed") }, request = ageWindow)
        val delete = client.from(TABLE).delete(request = ageWindow)
        assertIs<MutationOutcome.Queued>(update)
        assertIs<MutationOutcome.Queued>(delete)
        assertEquals(2, client.pendingMutationCount())

        // When — back online, the queue replays
        online = true
        server.requests.clear()
        val synced = client.syncPendingMutations().syncedCount

        // Then — each is sent with both values, as it was written
        assertEquals(2, synced)
        assertEquals(listOf("PATCH", "DELETE"), server.requests.map { it.method })
        assertEveryRequestSentTheWindow()
        client.close()
    }

    @Test
    fun a_cached_query_with_two_filters_on_one_column_admits_a_local_row_meeting_both() = runTest {
        // Given — `status <> 'a' AND status <> 'b'` cached; then no connection
        val client = newClient()
        val neither: PostgrestRequestBuilder.() -> Unit = {
            filter {
                neq("status", "a")
                neq("status", "b")
            }
        }
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = neither)
        online = false

        // When — rows of status a, b and c are queued
        listOf(2 to "a", 3 to "b", 4 to "c").forEach { (id, status) ->
            client
                .from(TABLE)
                .insert(
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("id", id)
                                put("age", 5)
                                put("status", status)
                            }
                        )
                    )
                )
        }

        // Then — only the row meeting both conditions shows
        val shown =
            client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly(), request = neither)
        assertEquals(setOf(1, 4), idsOf(shown.data).toSet())
        assertEquals(listOf("neq.a", "neq.b"), server.requests.first().queryValues["status"])
        client.close()
    }

    @Test
    fun the_two_filters_in_one_and_group_are_both_sent() = runTest {
        // Given
        val client = newClient()
        val window: PostgrestRequestBuilder.() -> Unit = {
            filter {
                and {
                    gte(COLUMN, 1)
                    lte(COLUMN, 10)
                }
            }
        }

        // When
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = window)
        val deleted = client.from(TABLE).delete(MutationStrategy.OnlineOnly, window)

        // Then — one parameter carrying both conditions, for the read and the write alike
        assertIs<MutationOutcome.Applied>(deleted)
        assertEquals(listOf("GET", "DELETE"), server.requests.map { it.method })
        server.requests.forEach { request ->
            assertEquals("(age.gte.1,age.lte.10)", request.query["and"])
            assertFalse(COLUMN in request.query)
        }
        client.close()
    }

    @Test
    fun filters_on_different_columns_and_single_valued_parameters_are_untouched() = runTest {
        // Given
        val client = newClient()

        // When — two columns filtered, two orders (merged into one parameter) and a limit
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                gte(COLUMN, 1)
                lte("height", 10)
            }
            order(COLUMN, Order.ASCENDING)
            order("name", Order.DESCENDING)
            limit(5)
        }

        // Then
        val sent = server.requests.single().query
        assertEquals("gte.1", sent[COLUMN])
        assertEquals("lte.10", sent["height"])
        assertEquals("age.asc.nullslast,name.desc.nullslast", sent["order"])
        assertEquals("5", sent["limit"])
        client.close()
    }

    private fun idsOf(data: String): List<Int> =
        Json.parseToJsonElement(data).jsonArray.map { element ->
            element.jsonObject.getValue("id").jsonPrimitive.content.toInt()
        }

    private companion object {
        const val TABLE = "people"
        const val COLUMN = "age"
        const val ROWS = """[{"id":1,"age":5,"status":"c"}]"""
    }
}

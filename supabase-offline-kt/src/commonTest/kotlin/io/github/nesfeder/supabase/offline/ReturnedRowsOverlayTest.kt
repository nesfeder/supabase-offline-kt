// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A write that asked for its rows back (`select()`) is patched into the cache with what the server
 * returned, which carries what the server set itself — a trigger's `updated_at`, a generated `id` —
 * as an INSERT's already was. A write that returned nothing is patched with its body.
 */
class ReturnedRowsOverlayTest {

    /** What the server answers a write with, by HTTP method. */
    private var writeAnswer: (method: String, accept: String) -> String = { _, _ -> "" }

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (request.method == "GET") {
                    okJson(
                        """[{"id":1,"list_id":"A","title":"old","done":false,"updated_at":"t0"}]"""
                    )
                } else {
                    okJson(writeAnswer(request.method, request.accept.orEmpty()))
                }
            }
        )
    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)
    private val listA: SelectRequestBuilder.() -> Unit = { filter { eq("list_id", "A") } }

    private suspend fun cachedRow(): JsonObject =
        Json.parseToJsonElement(
                items.select(readStrategy = ReadStrategy.CacheOnly(), request = listA).data
            )
            .jsonArray
            .single()
            .jsonObject

    private fun JsonObject.text(column: String): String = getValue(column).jsonPrimitive.content

    @Test
    fun an_update_that_returns_its_row_leaves_the_servers_columns_in_the_cache() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        writeAnswer = { _, _ ->
            """[{"id":1,"list_id":"A","title":"new","done":false,"updated_at":"t1"}]"""
        }

        // When
        items.update(Json.parseToJsonElement("""{"title":"new"}""")) {
            filter { eq("id", 1) }
            select()
        }

        // Then — the trigger's timestamp as well as the title
        val row = cachedRow()
        assertEquals("new", row.text("title"))
        assertEquals("t1", row.text("updated_at"))
    }

    @Test
    fun an_update_that_returns_one_object_leaves_the_servers_columns_in_the_cache() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        writeAnswer = { _, _ ->
            """{"id":1,"list_id":"A","title":"new","done":false,"updated_at":"t1"}"""
        }

        // When
        items.update(Json.parseToJsonElement("""{"title":"new"}""")) {
            filter { eq("id", 1) }
            select()
            single()
        }

        // Then
        assertEquals("t1", cachedRow().text("updated_at"))
    }

    @Test
    fun an_update_that_returns_some_columns_keeps_the_rest_of_its_body() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        writeAnswer = { _, _ -> """[{"id":1,"title":"new"}]""" }

        // When
        items.update(Json.parseToJsonElement("""{"title":"new","done":true}""")) {
            filter { eq("id", 1) }
            select(Columns.raw("id,title"))
        }

        // Then
        val row = cachedRow()
        assertEquals("new", row.text("title"))
        assertEquals("true", row.text("done"))
        assertEquals("t0", row.text("updated_at"))
    }

    @Test
    fun an_update_whose_filter_the_cache_cannot_match_is_still_patched_from_the_rows_it_returned() =
        runTest {
            // Given
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
            writeAnswer = { _, _ ->
                """[{"id":1,"list_id":"A","title":"new","done":false,"updated_at":"t1"}]"""
            }

            // When — a filter the strict capture cannot represent
            items.update(Json.parseToJsonElement("""{"title":"new"}""")) {
                filter {
                    or {
                        eq("id", 1)
                        eq("id", 2)
                    }
                }
                select()
            }

            // Then — the returned row names what changed, whatever the filter
            assertEquals("t1", cachedRow().text("updated_at"))
        }

    @Test
    fun an_insert_that_returns_one_object_leaves_the_servers_columns_in_the_cache() = runTest {
        // Given — an empty list A
        server.answer = { request ->
            if (request.method == "GET") okJson("[]")
            else okJson(writeAnswer(request.method, request.accept.orEmpty()))
        }
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        writeAnswer = { _, _ ->
            """{"id":7,"list_id":"A","title":"new","done":false,"updated_at":"t1"}"""
        }

        // When — the insert asks for its row back as one object
        items.insert(Json.parseToJsonElement("""[{"list_id":"A","title":"new"}]""") as JsonArray) {
            select()
            single()
        }

        // Then — the generated id and the trigger's timestamp
        val row = cachedRow()
        assertEquals("7", row.text("id"))
        assertEquals("t1", row.text("updated_at"))
    }

    @Test
    fun an_update_that_returns_nothing_is_patched_with_its_body() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        writeAnswer = { _, _ -> "" }

        // When
        items.update(Json.parseToJsonElement("""{"title":"new"}""")) { filter { eq("id", 1) } }

        // Then
        val row = cachedRow()
        assertEquals("new", row.text("title"))
        assertEquals("t0", row.text("updated_at"))
    }

    private companion object {
        const val TABLE = "items"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * What a pending write does not do to the lists it shows in, as the behaviour pages say: it is
 * appended past a list's order and limit, and on every page it matches; it carries only the columns
 * it wrote; one whose filter the engine cannot evaluate changes nothing shown; an upsert keys on
 * `id` unless it names its conflict columns; an ignore-duplicates upsert shows as though it landed.
 */
class OverlayLimitsTest {

    /** Whether the server can be reached. */
    private var online = true

    /** What the server answers a read with, by its query parameters. */
    private var rowsFor: (Map<String, String>) -> String = { "[]" }

    /** What the server answers a write with: the rows it returns when asked for them. */
    private var writtenRows = "[]"

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                okJson(if (request.method == "GET") rowsFor(request.query) else writtenRows)
            }
        )
    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private suspend fun cached(
        columns: Columns = Columns.ALL,
        request: SelectRequestBuilder.() -> Unit = {},
    ): String = items.select(columns, ReadStrategy.CacheOnly(), request).data

    private fun page(from: Long): SelectRequestBuilder.() -> Unit = {
        order("n", Order.DESCENDING)
        range(from, from + 1)
    }

    @Test
    fun a_queued_row_is_appended_past_the_order_and_limit_of_every_page_it_matches() = runTest {
        // Given — two newest-first pages of two rows cached
        rowsFor = { query ->
            if (query["offset"] == "2") """[{"id":3,"n":3},{"id":2,"n":2}]"""
            else """[{"id":5,"n":5},{"id":4,"n":4}]"""
        }
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = page(0))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = page(2))
        online = false

        // When — a row newer than every other is queued
        items.insert(rows("""[{"id":9,"n":9}]"""))

        // Then — last on each page, each page past its limit
        assertEquals(
            """[{"id":5,"n":5},{"id":4,"n":4},{"id":9,"n":9}]""",
            cached(request = page(0)),
        )
        assertEquals(
            """[{"id":3,"n":3},{"id":2,"n":2},{"id":9,"n":9}]""",
            cached(request = page(2)),
        )
    }

    @Test
    fun a_queued_row_carries_only_the_columns_it_wrote() = runTest {
        // Given — a list read with an embedded author and a renamed column
        val columns = Columns.raw("id,heading:title,author:profiles(name)")
        rowsFor = { """[{"id":1,"heading":"a","author":{"name":"x"}}]""" }
        items.select(columns, ReadStrategy.NetworkOnly)
        online = false

        // When
        items.insert(rows("""[{"id":2,"title":"b"}]"""))

        // Then — no author, and `title`, not `heading`
        assertEquals(
            """[{"id":1,"heading":"a","author":{"name":"x"}},{"id":2,"title":"b"}]""",
            cached(columns),
        )
    }

    @Test
    fun a_pending_update_whose_filter_the_engine_cannot_evaluate_changes_nothing_shown() = runTest {
        // Given
        rowsFor = { """[{"id":1,"n":1,"done":false},{"id":2,"n":7,"done":false}]""" }
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        online = false

        // When — `lt` on a number is left to the server
        items.update(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter { lt("n", 5) }
        }

        // Then — shown unchanged until it syncs and the list is fetched
        assertEquals("""[{"id":1,"n":1,"done":false},{"id":2,"n":7,"done":false}]""", cached())
    }

    @Test
    fun an_upsert_to_a_table_without_an_id_column_that_names_no_conflict_columns_adds_a_row() =
        runTest {
            // Given — a table keyed by `key`
            rowsFor = { """[{"key":"theme","value":"light"}]""" }
            items.select(readStrategy = ReadStrategy.NetworkOnly)
            online = false

            // When
            items.upsert(rows("""[{"key":"theme","value":"dark"}]"""))

            // Then — keyed on `id`, which neither row has: a second row
            assertEquals(
                """[{"key":"theme","value":"light"},{"key":"theme","value":"dark"}]""",
                cached(),
            )
        }

    @Test
    fun an_upsert_that_names_its_conflict_columns_merges_by_them() = runTest {
        // Given — a table keyed by `key`
        rowsFor = { """[{"key":"theme","value":"light"}]""" }
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        online = false

        // When
        items.upsert(rows("""[{"key":"theme","value":"dark"}]""")) { onConflict = "key" }

        // Then
        assertEquals("""[{"key":"theme","value":"dark"}]""", cached())
    }

    @Test
    fun an_ignore_duplicates_upsert_shows_a_row_the_server_may_keep_elsewhere() = runTest {
        // Given — list A cached; row x is in list B on the server, not cached
        rowsFor = { """[{"id":"a","list_id":"A"}]""" }
        val listA: SelectRequestBuilder.() -> Unit = { filter { eq("list_id", "A") } }
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listA)
        online = false

        // When
        items.upsert(rows("""[{"id":"x","list_id":"A"}]""")) { ignoreDuplicates = true }

        // Then — shown in A, though the server will keep x where it is
        assertEquals(
            """[{"id":"a","list_id":"A"},{"id":"x","list_id":"A"}]""",
            cached(request = listA),
        )
    }

    @Test
    fun an_online_update_of_a_row_no_cached_list_holds_brings_it_into_none() = runTest {
        // Given — the list of tasks done cached; x held by no cached list
        rowsFor = { query ->
            if (query["done"] == "eq.true") """[{"id":"y","done":true}]""" else "[]"
        }
        items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("done", true) } }

        // When — online, x is marked done, and the server sends the whole row back
        writtenRows = """[{"id":"x","done":true}]"""
        items.update(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter { eq("id", "x") }
            select()
        }

        // Then — the list shows it only once fetched again
        assertEquals("""[{"id":"y","done":true}]""", cached { filter { eq("done", true) } })
    }

    private companion object {
        const val TABLE = "items"
    }
}

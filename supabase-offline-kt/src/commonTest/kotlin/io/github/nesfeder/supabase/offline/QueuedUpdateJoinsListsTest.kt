// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A queued UPDATE that makes a row the server sent start matching another cached list shows it
 * there, while it waits and after it replays until that list is fetched again, when the list was
 * read with the same columns as the query that holds the row.
 */
class QueuedUpdateJoinsListsTest {

    /** Whether the server can be reached. */
    private var online = true

    /** What the server answers for the list of open tasks. */
    private var openRows = OPEN

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                when {
                    request.method != "GET" -> okJson("")
                    request.query["done"] == "eq.false" && request.query["select"] == "*" ->
                        okJson(openRows)
                    request.query["done"] == "eq.true" -> okJson(DONE)
                    request.query["select"] == PICKER -> okJson(PICKED)
                    else -> okJson("[]")
                }
            }
        )

    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = newTestOfflineStore(),
            coordinatorScope = idleCoroutineScope(),
        )
    private val items = client.from(TABLE)

    private fun list(done: Boolean): SelectRequestBuilder.() -> Unit = {
        filter { eq("done", done) }
    }

    private suspend fun cachedList(done: Boolean): String =
        items.select(readStrategy = ReadStrategy.CacheOnly(), request = list(done)).data

    private suspend fun markDoneOffline() {
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = list(false))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = list(true))
        online = false
        items.update(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter { eq("id", "x") }
        }
    }

    @Test
    fun a_queued_update_moves_a_server_row_into_the_list_it_starts_matching() = runTest {
        // Given / When — x marked done offline
        markDoneOffline()

        // Then — it left "open" and shows in "done", one-shot and reactive
        assertEquals("[]", cachedList(done = false))
        assertEquals(DONE_WITH_X, cachedList(done = true))
        assertEquals(
            DONE_WITH_X,
            items
                .selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = list(true))
                .first()
                .result
                ?.data,
        )
    }

    @Test
    fun a_replayed_update_keeps_the_row_in_the_list_it_joined_until_that_list_is_fetched() =
        runTest {
            // Given — x marked done offline
            markDoneOffline()

            // When — it replays, before either list is fetched again
            online = true
            client.syncPendingMutations().syncedCount

            // Then
            assertEquals("[]", cachedList(done = false))
            assertEquals(DONE_WITH_X, cachedList(done = true))
        }

    @Test
    fun a_row_read_with_other_columns_does_not_join() = runTest {
        // Given — x held only by a picker reading fewer columns, and the "done" list
        items.select(columns = Columns.raw(PICKER), readStrategy = ReadStrategy.NetworkOnly)
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = list(true))
        online = false

        // When
        items.update(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
            filter { eq("id", "x") }
        }

        // Then — "done" does not show the partial row
        assertEquals(DONE, cachedList(done = true))
    }

    @Test
    fun a_queued_upsert_of_part_of_a_row_then_an_update_that_moves_it_brings_the_whole_row() =
        runTest {
            // Given — x open and flagged "normal"; both lists cached
            openRows = OPEN_FLAGGED
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = list(false))
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = list(true))
            online = false

            // When — offline, an upsert of some of x's columns flags it, and an update marks every
            // urgent task done
            items.upsert(Json.parseToJsonElement(URGENT_X) as JsonArray)
            items.update(Json.parseToJsonElement("""{"done":true}""") as JsonObject) {
                filter { eq("flag", "urgent") }
            }

            // Then — x joins "done" whole, with the title only the cached row carries
            assertEquals(DONE_WITH_WHOLE_URGENT_X, cachedList(done = true))
            assertEquals("[]", cachedList(done = false))
        }

    private companion object {
        const val TABLE = "tasks"
        const val PICKER = "id,title"
        const val OPEN = """[{"id":"x","title":"x title","done":false}]"""
        const val DONE = """[{"id":"y","title":"y title","done":true}]"""
        const val PICKED = """[{"id":"x","title":"x title"}]"""
        const val DONE_WITH_X =
            """[{"id":"y","title":"y title","done":true},{"id":"x","title":"x title","done":true}]"""
        const val OPEN_FLAGGED = """[{"id":"x","title":"x title","done":false,"flag":"normal"}]"""
        const val URGENT_X = """[{"id":"x","done":false,"flag":"urgent"}]"""
        const val DONE_WITH_WHOLE_URGENT_X =
            """[{"id":"y","title":"y title","done":true},""" +
                """{"id":"x","title":"x title","done":true,"flag":"urgent"}]"""
    }
}

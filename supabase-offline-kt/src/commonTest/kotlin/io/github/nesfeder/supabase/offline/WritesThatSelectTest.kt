// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Columns
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

/**
 * A write that asks for rows back (`select()`) is patched into the cache from what the server
 * returned, together with what the caller wrote: an INSERT or UPSERT returning fewer columns keeps
 * the columns it wrote, and an UPDATE that returned no row changed none.
 */
class WritesThatSelectTest {

    /** What the server answers a write with. */
    private var writeAnswer = "[]"

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                when (request.method) {
                    "GET" -> okJson("""[{"id":1,"title":"old"}]""")
                    else -> okJson(writeAnswer)
                }
            }
        )

    private val items =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)

    private suspend fun cachedList(): String =
        items.select(readStrategy = ReadStrategy.CacheOnly()).data

    @Test
    fun an_insert_that_selects_its_id_keeps_the_columns_it_wrote() = runTest {
        // Given — the list cached, and a server that returns the new row's id only
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        writeAnswer = """[{"id":2}]"""

        // When
        items.insert(Json.parseToJsonElement("""[{"title":"new"}]""") as JsonArray) {
            select(Columns.list("id"))
        }

        // Then — the id the server gave and the title the caller wrote
        assertEquals("""[{"id":1,"title":"old"},{"id":2,"title":"new"}]""", cachedList())
    }

    @Test
    fun an_update_that_selects_and_gets_no_row_back_changes_nothing() = runTest {
        // Given — the list cached, and a server whose RLS lets the update reach no row
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        writeAnswer = "[]"

        // When
        items.update(Json.parseToJsonElement("""{"title":"new"}""") as JsonObject) {
            filter { eq("id", 1) }
            select()
        }

        // Then — the row as the server has it
        assertEquals("""[{"id":1,"title":"old"}]""", cachedList())
    }

    @Test
    fun an_update_that_selects_changes_the_rows_it_got_back() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        writeAnswer = """[{"id":1,"title":"new","updated_by":"server"}]"""

        // When
        items.update(Json.parseToJsonElement("""{"title":"new"}""") as JsonObject) {
            filter { eq("id", 1) }
            select()
        }

        // Then
        assertEquals("""[{"id":1,"title":"new","updated_by":"server"}]""", cachedList())
    }

    private companion object {
        const val TABLE = "items"
    }
}

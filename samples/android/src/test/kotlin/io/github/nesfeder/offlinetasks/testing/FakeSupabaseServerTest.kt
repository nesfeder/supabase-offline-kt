// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.nesfeder.offlinetasks.data.Tables
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The fake server's writes as the version precondition needs them: every `eq` filter picks the
 * rows, the rows come back when asked for, and each change stamps a new `updated_at`.
 */
class FakeSupabaseServerTest {

    private val server = FakeSupabaseServer()
    private val supabase = server.supabaseClient()

    @AfterTest
    fun closeClient() {
        runBlocking { supabase.close() }
    }

    @Test
    fun an_update_preconditioned_on_the_current_version_changes_the_row_and_returns_it() =
        runBlocking {
            // Given
            server.insertRow(Tables.TASKS, task(VERSION))

            // When
            val returned =
                supabase
                    .from(Tables.TASKS)
                    .update(buildJsonObject { put("title", "Mine") }) {
                        select()
                        filter {
                            eq("id", "t1")
                            eq("updated_at", VERSION)
                        }
                    }
                    .decodeList<JsonObject>()

            // Then — changed, sent back, and at a new version
            val row = returned.single()
            assertEquals("Mine", row.text("title"))
            assertNotEquals(VERSION, row.text("updated_at"))
            assertEquals(row, server.rows(Tables.TASKS).single())
        }

    @Test
    fun an_update_preconditioned_on_an_old_version_changes_nothing_and_returns_no_rows() =
        runBlocking {
            // Given — the row moved on the server after the device read it
            server.insertRow(Tables.TASKS, task(VERSION))
            server.replaceRow(Tables.TASKS, "t1", buildJsonObject { put("title", "Theirs") })
            val moved = server.rows(Tables.TASKS).single()

            // When
            val returned =
                supabase
                    .from(Tables.TASKS)
                    .update(buildJsonObject { put("title", "Mine") }) {
                        select()
                        filter {
                            eq("id", "t1")
                            eq("updated_at", VERSION)
                        }
                    }
                    .data

            // Then — an empty list, and the server's row as the other writer left it
            assertEquals("[]", returned)
            assertEquals(moved, server.rows(Tables.TASKS).single())
            assertEquals("Theirs", moved.text("title"))
            assertNotEquals(VERSION, moved.text("updated_at"))
        }

    @Test
    fun a_delete_preconditioned_on_an_old_version_keeps_the_row() = runBlocking {
        // Given
        server.insertRow(Tables.TASKS, task(VERSION))
        server.replaceRow(Tables.TASKS, "t1", buildJsonObject { put("done", true) })

        // When
        val returned =
            supabase
                .from(Tables.TASKS)
                .delete {
                    select()
                    filter {
                        eq("id", "t1")
                        eq("updated_at", VERSION)
                    }
                }
                .data

        // Then
        assertEquals("[]", returned)
        assertEquals(1, server.rows(Tables.TASKS).size)
    }

    @Test
    fun a_returned_row_holds_only_the_selected_columns() = runBlocking {
        // Given
        server.insertRow(Tables.TASKS, task(VERSION))

        // When
        val returned =
            supabase
                .from(Tables.TASKS)
                .update(buildJsonObject { put("title", "Mine") }) {
                    select(Columns.list("id", "updated_at"))
                    filter { eq("id", "t1") }
                }
                .decodeList<JsonObject>()

        // Then
        assertEquals(setOf("id", "updated_at"), returned.single().keys)
    }

    @Test
    fun every_stamp_is_later_than_the_one_before() {
        // Given
        server.insertRow(Tables.TASKS, task(VERSION))

        // When — two changes in quick succession
        server.replaceRow(Tables.TASKS, "t1", buildJsonObject { put("title", "One") })
        val first = Instant.parse(server.rows(Tables.TASKS).single().text("updated_at").orEmpty())
        server.replaceRow(Tables.TASKS, "t1", buildJsonObject { put("title", "Two") })
        val second = Instant.parse(server.rows(Tables.TASKS).single().text("updated_at").orEmpty())

        // Then
        assertTrue(first < second, "$first then $second")
    }

    private fun task(version: String): JsonObject = buildJsonObject {
        put("id", "t1")
        put("user_id", "u1")
        put("title", "Base")
        put("done", false)
        put("created_at", "2026-10-01T09:00:00Z")
        put("updated_at", version)
    }

    private fun JsonObject.text(column: String): String? = get(column)?.jsonPrimitive?.content

    private companion object {
        /** A version as Postgres writes a `timestamptz`, its `+` sent percent-encoded. */
        const val VERSION = "2026-10-01T09:00:00.000001+00:00"
    }
}

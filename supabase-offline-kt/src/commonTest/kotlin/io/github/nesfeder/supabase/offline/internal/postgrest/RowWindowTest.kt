// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a request says of the order and the window of its rows, which decides whether a server
 * change laid over its answer leaves every row where the server would put it.
 */
class RowWindowTest {

    @Test
    fun a_request_with_no_order_or_window_reads_every_row_unordered() {
        assertEquals(RowWindow.UNORDERED, windowOf {})
    }

    @Test
    fun every_column_the_order_names_is_read() {
        // When — a request ordered by two columns, as supabase-kt sends them
        val window = windowOf {
            order("rank", Order.DESCENDING)
            order("title", Order.ASCENDING, nullsFirst = true)
        }

        // Then
        assertEquals(setOf("rank", "title"), window.orderColumns)
        assertTrue(window.readsEveryOrderColumn)
        assertFalse(window.isWindowed)
    }

    @Test
    fun an_order_by_a_json_path_or_a_quoted_name_is_not_one_it_reads() {
        assertFalse(windowOf { params["order"] = listOf("data->>name.asc") }.readsEveryOrderColumn)
        assertFalse(windowOf { params["order"] = listOf("\"my.col\".asc") }.readsEveryOrderColumn)
    }

    @Test
    fun a_limit_an_offset_or_a_range_header_reads_a_window() {
        assertTrue(windowOf { limit(10) }.isWindowed)
        assertTrue(windowOf { range(20L..29L) }.isWindowed)
        assertTrue(windowOf { headers.append("Range", "items=0-9") }.isWindowed)
    }

    @Test
    fun an_embedded_tables_order_and_window_are_left_out() {
        // When — the order and limit are on the comments each row embeds
        val window = windowOf {
            order("created_at", Order.ASCENDING, referencedTable = "comments")
            limit(5, referencedTable = "comments")
        }

        // Then — the query's own rows are neither ordered nor windowed
        assertEquals(RowWindow.UNORDERED, window)
    }

    @Test
    fun a_row_may_move_only_when_a_column_the_order_reads_changes() {
        val before = row("rank" to "1", "title" to "Old")
        val window = RowWindow(orderColumns = setOf("rank"))

        assertFalse(window.mayMove(before, row("rank" to "1", "title" to "New")))
        assertTrue(window.mayMove(before, row("rank" to "2", "title" to "Old")))
        assertFalse(RowWindow.UNORDERED.mayMove(before, row("rank" to "2")))
        val unread = RowWindow(orderColumns = emptySet(), readsEveryOrderColumn = false)
        assertTrue(unread.mayMove(before, row("rank" to "1", "title" to "New")))
    }

    private fun windowOf(request: SelectRequestBuilder.() -> Unit): RowWindow =
        rowWindowOf(SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(request))

    private fun row(vararg columns: Pair<String, String>): JsonObject =
        JsonObject(columns.associate { (name, value) -> name to JsonPrimitive(value) })
}

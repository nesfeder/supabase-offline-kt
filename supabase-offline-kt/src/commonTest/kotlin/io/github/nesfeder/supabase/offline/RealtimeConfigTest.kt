// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTableFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `realtime { }` names the tables the plugin keeps current, with their schema and filter; the
 * blocks add up; a blank table, a table named twice for one schema, and a filter Realtime cannot
 * evaluate are refused when named.
 */
class RealtimeConfigTest {

    @Test
    fun the_blocks_add_up_and_carry_each_tables_schema_and_filter() {
        // Given
        val config = Offline.Config()
        val filter = RealtimeFilter("status", FilterOperator.EQ, "open")

        // When
        config.realtime {
            table("tasks")
            table("audit", schema = "app", filter = filter)
        }
        config.realtime { table("notes") }

        // Then — as named, and as the engine follows them
        assertEquals(
            listOf(
                RealtimeTable("tasks", null, null),
                RealtimeTable("audit", "app", filter),
                RealtimeTable("notes", null, null),
            ),
            config.realtimeTables,
        )
        assertEquals(
            listOf(
                WatchedTable("tasks"),
                WatchedTable(
                    "audit",
                    "app",
                    WatchedTableFilter("status", FilterOperator.EQ, "open"),
                ),
                WatchedTable("notes"),
            ),
            config.watchedTables,
        )
    }

    @Test
    fun nothing_named_opens_no_channel() {
        // Given / When / Then
        val config = Offline.Config()
        assertTrue(config.realtimeTables.isEmpty())
        assertTrue(config.watchedTables.isEmpty())
    }

    @Test
    fun a_blank_table_is_refused() {
        // Given / When / Then
        val failure =
            assertFailsWith<IllegalArgumentException> { Offline.Config().realtime { table(" ") } }
        assertTrue("name" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun a_table_named_twice_for_one_schema_is_refused_but_two_schemas_may_share_a_name() {
        // Given
        val config = Offline.Config()

        // When / Then — the same name in another schema is another table
        config.realtime {
            table("audit")
            table("audit", schema = "app")
        }
        assertEquals(2, config.realtimeTables.size)
        val failure =
            assertFailsWith<IllegalArgumentException> { config.realtime { table("audit") } }
        assertTrue("twice" in failure.message.orEmpty(), failure.message)
        assertEquals(2, config.realtimeTables.size)
    }

    @Test
    fun a_filter_realtime_cannot_evaluate_is_refused_when_made() {
        // Given / When / Then
        val unsupported =
            assertFailsWith<IllegalArgumentException> {
                RealtimeFilter("title", FilterOperator.LIKE, "%milk%")
            }
        assertTrue("like" in unsupported.message.orEmpty(), unsupported.message)
        assertFailsWith<IllegalArgumentException> { RealtimeFilter("", FilterOperator.EQ, "x") }
        assertEquals(
            RealtimeFilter("count", FilterOperator.IN, listOf(1, 2)),
            RealtimeFilter("count", FilterOperator.IN, listOf(1, 2)),
        )
    }
}

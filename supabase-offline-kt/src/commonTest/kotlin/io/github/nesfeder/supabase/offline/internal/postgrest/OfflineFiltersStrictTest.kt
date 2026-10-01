// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Pins [captureFilterExactly] / [captureFilterExactly]: the strict capture used on the
 * offline-queue (replay) path must throw [UncapturableFilterException] on any filter it cannot
 * represent faithfully — `not.`-negated operators, `or` / `and` logical groups, and foreign-table
 * dotted keys — rather than silently dropping the condition. The lenient [captureFilter] is
 * asserted unchanged for the same inputs to document exactly the over-broad-replay behavior the
 * strict path exists to prevent.
 */
class OfflineFiltersStrictTest {

    private val schema = "public"

    // CAMEL_CASE_TO_SNAKE_CASE (not SERIAL_NAME, which is JVM/Android-only) so the test runs on the
    // iOS/Native target too. Column names here are already lowercase, so no conversion is applied —
    // only the string-based filter DSL populates params, which is all the descriptor builders read.
    private val conversion = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE

    @Test
    fun `strict builder captures a simple eq filter faithfully`() {
        // Given / When
        val descriptor =
            captureFilterExactly(schema, conversion) {
                filter { eq("status", "active") }
            }

        // Then
        val condition = descriptor?.conditions?.single()
        assertEquals("status", condition?.column)
        assertEquals("eq", condition?.operatorId)
        assertEquals("active", condition?.value)
    }

    @Test
    fun `strict builder returns null for a genuinely filter-free request`() {
        // Given / When
        val descriptor = captureFilterExactly(schema, conversion) {}

        // Then
        assertNull(descriptor)
    }

    @Test
    fun `strict builder throws on a not-prefixed operator`() {
        // Given a negated filter, whose value encodes as "not.eq.archived"
        // When / Then
        assertFailsWith<UncapturableFilterException> {
            captureFilterExactly(schema, conversion) {
                filter { filterNot("status", FilterOperator.EQ, "archived") }
            }
        }
    }

    @Test
    fun `strict builder throws on an or logical group`() {
        // Given / When / Then
        assertFailsWith<UncapturableFilterException> {
            captureFilterExactly(schema, conversion) {
                filter {
                    or {
                        eq("status", "active")
                        eq("status", "pending")
                    }
                }
            }
        }
    }

    @Test
    fun `strict builder throws on a foreign-table dotted key`() {
        // Given / When / Then
        assertFailsWith<UncapturableFilterException> {
            captureFilterExactly(schema, conversion) {
                filter { eq("author.name", "ada") }
            }
        }
    }

    @Test
    fun `lenient builder silently drops the not-prefixed operator producing a null descriptor`() {
        // Given the same negated filter the strict path rejects
        // When the lenient path runs
        val descriptor =
            captureFilter(schema, conversion) {
                filter { filterNot("status", FilterOperator.EQ, "archived") }
            }

        // Then it drops the condition entirely — a null descriptor that would replay unfiltered.
        // This is the exact lossy behavior the strict path exists to prevent on the replay path.
        assertNull(descriptor)
    }

    @Test
    fun `lenient builder still captures a simple eq filter unchanged`() {
        // Given / When
        val descriptor =
            captureFilter(schema, conversion) {
                filter { eq("status", "active") }
            }

        // Then
        val condition = descriptor?.conditions?.single()
        assertEquals("status", condition?.column)
        assertEquals("eq", condition?.operatorId)
        assertEquals("active", condition?.value)
    }

    @Test
    fun `strict builder ignores the select projection instead of refusing the write`() {
        // Given — a write that asks for the updated row back, which puts `select=*` in params.
        // Its value is not `op.value` shaped, so the operator check used to reject it — and since
        // the queue branch turns a refusal into a rethrow of the original network failure, an
        // ordinary `update { filter { … }; select() }` made offline failed instead of queuing.
        // When
        val descriptor =
            captureFilterExactly(schema, conversion) {
                filter { eq("id", "project-1") }
                select()
            }

        // Then — the projection is skipped and the real filter is captured in full, so the
        // mutation queues and replays scoped to exactly the row the caller targeted
        val condition = descriptor?.conditions?.single()
        assertEquals("id", condition?.column)
        assertEquals("eq", condition?.operatorId)
        assertEquals("project-1", condition?.value)
    }

    @Test
    fun `strict builder captures the exact shape an edit write uses`() {
        // Given — `select()` *and* `single()` together, which is what a write that reads its row
        // back issues. `single()` is an Accept header rather than a query parameter today; if a
        // supabase-kt upgrade ever made it a parameter, strict capture would start refusing to
        // queue and every offline edit would fail outright again, silently. This is the case that
        // notices.
        // When
        val descriptor =
            captureFilterExactly(schema, conversion) {
                filter {
                    eq("id", "project-1")
                    eq("user_id", "user-1")
                }
                select()
                single()
            }

        // Then — both scoping conditions survive, and nothing else joins them
        assertEquals(
            listOf("id" to "project-1", "user_id" to "user-1"),
            descriptor?.conditions?.map { condition -> condition.column to condition.value },
        )
        assertEquals(listOf("eq", "eq"), descriptor?.conditions?.map { it.operatorId })
    }

    @Test
    fun `strict builder still throws on a row-limiting parameter`() {
        // Given — `range` emits limit/offset, which genuinely narrow the rows a mutation reaches.
        // Skipping them would replay against more rows than the caller wrote, so unlike a
        // projection they must stay fail-closed.
        // When / Then
        assertFailsWith<UncapturableFilterException> {
            captureFilterExactly(schema, conversion) {
                filter { eq("id", "project-1") }
                range(0L, 4L)
            }
        }
    }
}

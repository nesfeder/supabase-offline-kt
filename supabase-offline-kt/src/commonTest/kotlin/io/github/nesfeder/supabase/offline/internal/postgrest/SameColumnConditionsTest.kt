// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.cache.keyNamedBy
import io.github.nesfeder.supabase.offline.internal.mutationqueue.pinnedRowKeyOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Two filters on one column, which supabase-kt sends as one parameter with two values: every reader
 * of a request's parameters takes both, in order, and the readers that look for the one row a write
 * names never take a column filtered twice for a key.
 */
class SameColumnConditionsTest {

    private fun builder(request: PostgrestRequestBuilder.() -> Unit): SelectRequestBuilder =
        SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(request)

    private val window: PostgrestRequestBuilder.() -> Unit = {
        filter {
            gte("age", 1)
            lte("age", 10)
        }
    }

    private val bothConditions =
        listOf(
            FilterDescriptor.Condition("age", "gte", "1"),
            FilterDescriptor.Condition("age", "lte", "10"),
        )

    @Test
    fun both_capture_paths_take_one_condition_per_value_in_order() {
        // Given / When
        val lenient = assertNotNull(captureFilter(builder(window)))
        val strict = assertNotNull(captureFilterExactly(builder(window)))

        // Then
        assertEquals(bothConditions, lenient.conditions)
        assertEquals(bothConditions, strict.conditions)
        assertEquals(FilterDescriptor.Mode.AND, strict.mode)
    }

    @Test
    fun the_strict_capture_refuses_a_second_value_it_cannot_represent() {
        // Given — a plain filter and a negated one on one column
        val request = builder {
            filter {
                eq("age", 5)
                filterNot("age", FilterOperator.EQ, 6)
            }
        }

        // When / Then — the negated value is not dropped, which would broaden the replay
        assertFailsWith<UncapturableFilterException> { captureFilterExactly(request) }
        assertEquals(
            listOf(FilterDescriptor.Condition("age", "eq", "5")),
            captureFilter(request)?.conditions,
        )
    }

    @Test
    fun the_cache_key_holds_every_value_in_order() {
        // Given / When
        val key =
            cacheKeyOf(
                "people",
                builderFactory = { SelectRequestBuilder("public", PropertyConversionMethod.NONE) },
                request = window,
            )

        // Then
        assertEquals("people?age=gte.1&age=lte.10", key)
    }

    @Test
    fun a_key_column_filtered_twice_names_no_single_row() {
        // Given — `id = 1 AND id = 2`, which no row meets, and `id = 1 AND id >= 0`
        val contradictory =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        FilterDescriptor.Condition("id", "eq", "1"),
                        FilterDescriptor.Condition("id", "eq", "2"),
                    ),
            )
        val narrowed =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        FilterDescriptor.Condition("id", "eq", "1"),
                        FilterDescriptor.Condition("id", "gte", "0"),
                    ),
            )

        // When / Then — the overlay's single-row reading takes neither
        assertNull(keyNamedBy(contradictory, listOf("id")))
        assertNull(keyNamedBy(narrowed, listOf("id")))
        // The queue's pinned row takes no key from two `eq` on its column, and the one `eq` of
        // the narrowed filter, whose rows are among that row's
        assertNull(pinnedRowKeyOf(contradictory, listOf("id")))
        assertEquals(
            buildJsonObject { put("id", JsonPrimitive("1")) },
            pinnedRowKeyOf(narrowed, listOf("id")),
        )
    }
}

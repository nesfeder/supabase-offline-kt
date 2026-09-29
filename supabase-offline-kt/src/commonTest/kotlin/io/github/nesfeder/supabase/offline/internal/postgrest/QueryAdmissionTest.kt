// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject

/**
 * How a query's shape is captured from its request, which decides the rows the cached query admits:
 * each filter on a plain column, its first value only, taken whole as supabase-kt sends it; the
 * select list as sent; and whether anything else filters the query.
 */
class QueryAdmissionTest {

    private fun shapeOf(
        columns: Columns = Columns.ALL,
        request: SelectRequestBuilder.() -> Unit,
    ): QueryShape =
        queryShapeOf(
            columns,
            SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply(request),
        )

    @Test
    fun an_unfiltered_query_has_no_conditions() {
        // Given / When
        val shape = shapeOf {}

        // Then
        assertEquals(
            QueryShape(select = "*", conditions = emptyList(), hasOtherFilters = false),
            shape,
        )
    }

    @Test
    fun filters_are_kept_in_order_with_their_values_whole() {
        // Given / When — values holding the characters a cache key uses to separate its parts
        val shape = shapeOf {
            filter {
                eq("company", "AT&T")
                neq("owner", " Bob ")
                isIn("team", listOf("R&D", "on,hold"))
                eq("note", "p|range=0-5&x=eq.y")
            }
        }

        // Then
        assertEquals(
            listOf(
                FilterDescriptor.Condition("company", "eq", "AT&T"),
                FilterDescriptor.Condition("owner", "neq", " Bob "),
                FilterDescriptor.Condition("team", "in", "(R&D,\"on,hold\")"),
                FilterDescriptor.Condition("note", "eq", "p|range=0-5&x=eq.y"),
            ),
            shape.conditions,
        )
        assertFalse(shape.hasOtherFilters)
    }

    @Test
    fun only_the_first_value_of_a_column_counts_as_supabase_kt_sends_only_that_one() {
        // Given / When
        val shape = shapeOf {
            filter {
                eq("status", "a")
                eq("status", "b")
            }
        }

        // Then
        assertEquals(listOf(FilterDescriptor.Condition("status", "eq", "a")), shape.conditions)
    }

    @Test
    fun the_select_list_order_limit_offset_and_range_are_not_conditions() {
        // Given / When
        val shape =
            shapeOf(Columns.raw("id,projects(name)")) {
                filter { eq("list_id", "A") }
                order("position", Order.ASCENDING)
                limit(20, referencedTable = "projects")
                range(0, 19)
            }

        // Then
        assertEquals(listOf(FilterDescriptor.Condition("list_id", "eq", "A")), shape.conditions)
        assertEquals("id,projects(name)", shape.select)
        assertFalse(shape.hasOtherFilters)
    }

    @Test
    fun a_filter_the_engine_does_not_evaluate_is_kept_and_admits_every_row() {
        // Given / When
        val shape = shapeOf {
            filter {
                gt("priority", 1)
                filterNot("done", FilterOperator.EQ, true)
            }
        }

        // Then — kept, so the columns a query filters on are known, and never excluding a row
        assertEquals(
            listOf("priority", "done"),
            shape.conditions.map { condition -> condition.column },
        )
        assertTrue(shape.admission.admits(buildJsonObject {}))
    }

    @Test
    fun logical_groups_referenced_tables_json_paths_and_casts_are_other_filters() {
        // Given / When
        val shapes =
            listOf<SelectRequestBuilder.() -> Unit>(
                    {
                        filter {
                            or {
                                eq("a", 1)
                                eq("b", 2)
                            }
                        }
                    },
                    { filter { and(negate = true) { eq("a", 1) } } },
                    { filter { eq("lists.name", "Home") } },
                    { filter { eq("data->>kind", "idea") } },
                    { filter { eq("created_at::date", "2026-01-01") } },
                )
                .map { request -> shapeOf(request = request) }

        // Then
        shapes.forEach { shape ->
            assertTrue(shape.hasOtherFilters, "$shape")
            assertEquals(emptyList(), shape.conditions)
        }
    }
}

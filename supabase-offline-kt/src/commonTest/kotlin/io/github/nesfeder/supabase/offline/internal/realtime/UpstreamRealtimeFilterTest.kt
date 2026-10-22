// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresChangeFilter
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What supabase-kt itself writes into a channel's join for a Realtime filter: an `in` with a list
 * is a PostgREST list, while an `in` with anything else (a set included), and any other operator
 * with a collection, is the value's text, which the server refuses. `RealtimeFilter` refuses those
 * shapes when made; when upstream renders a collection as a list, this fails.
 */
class UpstreamRealtimeFilterTest {

    @Test
    fun realtime_kt_renders_a_list_as_a_list_and_any_other_value_as_its_text() {
        // Given / When
        fun rendered(operator: FilterOperator, value: Any): String? =
            PostgresChangeFilter("*", "public").apply { filter("id", operator, value) }.filter

        // Then
        assertEquals(
            listOf("id=in.(1,2)", "id=in.\"[1, 2]\"", "id=in.7", "id=eq.[1]", "id=eq.7"),
            listOf(
                rendered(FilterOperator.IN, listOf(1, 2)),
                rendered(FilterOperator.IN, setOf(1, 2)),
                rendered(FilterOperator.IN, 7),
                rendered(FilterOperator.EQ, listOf(1)),
                rendered(FilterOperator.EQ, 7),
            ),
            "supabase-kt no longer renders these shapes so. Revisit what RealtimeFilter requires " +
                "of a value, and the Swift bridge's matching refusal, before moving the pin.",
        )
    }
}

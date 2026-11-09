// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FilterOperator.ISDISTINCT` arrived in supabase-kt 3.8.0, and inside a group supabase-kt escapes
 * its value as it does `eq`'s, so a value holding `"` or `\` and none of `,.:()` goes out escaped
 * but unquoted there, and is refused as `eq`'s is; at the top level it is sent as written.
 */
class IsDistinctEscapedValueTest {

    @Test
    fun an_isdistinct_value_holding_a_quote_is_refused_in_a_group_and_sent_at_the_top_level() {
        // Given
        val inGroup =
            SelectRequestBuilder(SCHEMA, CONVERSION).apply {
                filter {
                    or {
                        filter("status", FilterOperator.ISDISTINCT, "a\"b")
                        eq("done", true)
                    }
                }
            }
        val topLevel =
            SelectRequestBuilder(SCHEMA, CONVERSION).apply {
                filter { filter("status", FilterOperator.ISDISTINCT, "a\"b") }
            }

        // Then — supabase-kt escapes the group's value without quoting it, and the refusal names
        // the column and the operator, never the value
        assertEquals("""(status.isdistinct.a\"b,done.eq.true)""", inGroup.params["or"]?.single())
        val failure =
            assertFailsWith<IllegalArgumentException> { requireReadableEscapedValues(inGroup) }
        assertTrue("`isdistinct`" in failure.message.orEmpty(), failure.message)
        assertTrue("`status`" in failure.message.orEmpty(), failure.message)
        assertFalse("a\"b" in failure.message.orEmpty(), failure.message)

        // Then — the top-level value is sent as written, and passes
        assertEquals("""isdistinct.a"b""", topLevel.params["status"]?.single())
        requireReadableEscapedValues(topLevel)
    }

    private companion object {
        const val SCHEMA = "public"
        val CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE
    }
}

package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How [admissionOf] reads a cache key: the conditions it keeps are exactly the `eq`, `neq`, `in` and
 * `is` filters on plain columns, each column's first value only, taken as written; everything else
 * in the key is left out, and so never excludes a row.
 */
class QueryAdmissionTest {

    private fun conditionsOf(cacheKey: String): List<FilterDescriptor.Condition> = admissionOf(cacheKey).conditions

    @Test
    fun an_unfiltered_or_unparseable_key_has_no_conditions() {
        assertEquals(emptyList(), conditionsOf("notes"))
        assertEquals(emptyList(), conditionsOf(""))
        assertEquals(emptyList(), conditionsOf("reports-period-sig"))
    }

    @Test
    fun evaluated_filters_are_kept_in_order_with_their_values_as_written() {
        assertEquals(
            listOf(
                FilterDescriptor.Condition("created_at", "eq", "2026-01-01T00:00:00.000Z"),
                FilterDescriptor.Condition("owner", "neq", " Bob "),
                FilterDescriptor.Condition("status", "in", "(open,\"on,hold\")"),
                FilterDescriptor.Condition("title", "eq", "'X'"),
            ),
            conditionsOf("notes?created_at=eq.2026-01-01T00:00:00.000Z&owner=neq. Bob &status=in.(open,\"on,hold\")&title=eq.'X'"),
        )
    }

    @Test
    fun only_the_first_value_of_a_column_counts_as_supabase_kt_sends_only_that_one() {
        assertEquals(listOf(FilterDescriptor.Condition("status", "eq", "a")), conditionsOf("notes?status=eq.a&status=eq.b"))
    }

    @Test
    fun projection_order_limit_offset_and_range_are_not_conditions() {
        assertEquals(
            listOf(FilterDescriptor.Condition("list_id", "eq", "A")),
            conditionsOf("notes?limit=20&list_id=eq.A&offset=0&order=position.asc.nullslast&select=id,projects(name)|range=0-19"),
        )
    }

    @Test
    fun unevaluated_filters_are_left_out() {
        val key = "notes?and=(a.eq.1)&comments.order=x&data->>kind=eq.idea&done=not.eq.true&lists.name=eq.Home" +
            "&or=(a.eq.1)&priority=gt.1&title=ilike.*a*&custom=x&flag"
        assertEquals(emptyList(), conditionsOf(key))
    }
}

package io.github.nesfeder.supabase.internal.postgrest.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OrderClauseParserTest {

    @Test
    fun fromRaw_null_returnsEmptyList() {
        // Given
        val orderParamValues: List<String>? = null

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(emptyList(), result)
    }

    @Test
    fun fromRaw_emptyList_returnsEmptyList() {
        // Given
        val orderParamValues = emptyList<String>()

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(emptyList(), result)
    }

    @Test
    fun fromRaw_blankOnly_returnsEmptyList() {
        // Given
        val orderParamValues = listOf("   ", "")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(emptyList(), result)
    }

    @Test
    fun fromRaw_singleAscTwoPart_returnsAscAndNullsLast() {
        // Given
        val orderParamValues = listOf("updated_at.asc")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(
            listOf(OrderClause("updated_at", OrderDirection.ASC, NullsOrder.NULLS_LAST)),
            clauses,
        )
    }

    @Test
    fun fromRaw_singleDesc_caseInsensitive() {
        // Given
        val orderParamValues = listOf("NAME.DESC")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(
            listOf(OrderClause("NAME", OrderDirection.DESC, NullsOrder.NULLS_LAST)),
            clauses,
        )
    }

    @Test
    fun fromRaw_threePart_nullsFirst() {
        // Given
        val orderParamValues = listOf("name.asc.nullsfirst")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(
            listOf(OrderClause("name", OrderDirection.ASC, NullsOrder.NULLS_FIRST)),
            clauses,
        )
    }

    @Test
    fun fromRaw_threePart_nullsLast() {
        // Given
        val orderParamValues = listOf("name.desc.nullslast")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(
            listOf(OrderClause("name", OrderDirection.DESC, NullsOrder.NULLS_LAST)),
            clauses,
        )
    }

    @Test
    fun fromRaw_multipleSegmentsCommaSeparated() {
        // Given
        val orderParamValues = listOf("a.asc,b.desc.nullsfirst")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(2, clauses?.size)
        assertEquals(OrderClause("a", OrderDirection.ASC, NullsOrder.NULLS_LAST), clauses!![0])
        assertEquals(OrderClause("b", OrderDirection.DESC, NullsOrder.NULLS_FIRST), clauses[1])
    }

    @Test
    fun fromRaw_invalidDirection_returnsNull() {
        // Given
        val orderParamValues = listOf("col.invalid")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertNull(result)
    }

    @Test
    fun fromRaw_invalidNullsToken_returnsNull() {
        // Given
        val orderParamValues = listOf("col.asc.nullsbad")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertNull(result)
    }

    @Test
    fun fromRaw_invalidNullsTokenAfterValidSegment_returnsNull() {
        // Given
        val orderParamValues = listOf("valid.asc,col.desc.nullsbad")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertNull(result)
    }

    @Test
    fun fromRaw_fourPartSegment_returnsNull() {
        // Given
        val orderParamValues = listOf("a.b.c.d")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertNull(result)
    }

    @Test
    fun fromRaw_emptyColumnPart_returnsNull() {
        // Given
        val orderParamValues = listOf(".asc")

        // When
        val result = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertNull(result)
    }

    @Test
    fun fromRaw_emptySegmentBetweenCommas_isIgnoredAndParsesRest() {
        // Given
        val orderParamValues = listOf("a.asc,,b.desc")

        // When
        val clauses = OrderClauseParser.fromRaw(orderParamValues)

        // Then
        assertEquals(
            listOf(
                OrderClause("a", OrderDirection.ASC, NullsOrder.NULLS_LAST),
                OrderClause("b", OrderDirection.DESC, NullsOrder.NULLS_LAST),
            ),
            clauses,
        )
    }

    @Test
    fun orderDirection_parseOrNull_validAndInvalid() {
        // Given — valid and invalid direction tokens

        // When / Then
        assertEquals(OrderDirection.ASC, OrderDirection.parseOrNull("asc"))
        assertEquals(OrderDirection.DESC, OrderDirection.parseOrNull("DESC"))
        assertNull(OrderDirection.parseOrNull("invalid"))
    }

    @Test
    fun nullsOrder_parseOrNull_validAndInvalid() {
        // Given — valid and invalid nulls-order tokens

        // When / Then
        assertEquals(NullsOrder.NULLS_FIRST, NullsOrder.parseOrNull("nullsfirst"))
        assertEquals(NullsOrder.NULLS_LAST, NullsOrder.parseOrNull("NULLSLAST"))
        assertNull(NullsOrder.parseOrNull("nullsmiddle"))
    }

    @Test
    fun orderDirection_rawValue_matchesEnumNameUsage() {
        // Given — enum entries expose non-empty names and raw values

        // When / Then
        assertTrue(OrderDirection.entries.all { it.name.isNotEmpty() && it.rawValue.isNotEmpty() })
        assertTrue(NullsOrder.entries.all { it.rawValue.isNotEmpty() })
    }
}

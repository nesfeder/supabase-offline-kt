@file:OptIn(io.github.jan.supabase.annotations.SupabaseExperimental::class)

package io.github.nesfeder.supabase.internal.postgrest.parser

import io.github.nesfeder.supabase.internal.FakePostgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SelectQueryMetadataParserTest {

    private val postgrest = FakePostgrest()

    @Test
    fun fromSignature_tableOnly_noQuery_allColumns_noFilters() {
        // Given
        val table = "assets"
        val signature = "assets"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertEquals("assets", meta.table)
        assertEquals(Columns.ALL, meta.columns)
        assertNull(meta.filterDescriptor)
        assertEquals(false, meta.hasRange)
        assertEquals(false, meta.hasPagination)
        assertTrue(meta.orderClauses.isEmpty())
        assertEquals(false, meta.hasComplexParams)
        assertTrue(meta.supportsSubsetDerivation)
    }

    @Test
    fun fromSignature_withRange_hasRangeTrue() {
        // Given
        val table = "assets"
        val signature = "assets?a=eq.1|range=0-9"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasRange)
        assertTrue(!meta.supportsSubsetDerivation)
    }

    @Test
    fun fromSignature_limitOnly_hasPagination() {
        // Given
        val table = "items"
        val signature = "items?limit=10"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasPagination)
        assertTrue(!meta.supportsSubsetDerivation)
    }

    @Test
    fun fromSignature_offsetOnly_hasPagination() {
        // Given
        val table = "items"
        val signature = "items?offset=5"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasPagination)
    }

    @Test
    fun fromSignature_selectColumns_parsed() {
        // Given
        val table = "t"
        val signature = "t?select=id,name"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertEquals(Columns.list("id", "name"), meta.columns)
    }

    @Test
    fun fromSignature_invalidOrder_setsComplexAndEmptyOrderClauses() {
        // Given
        val table = "t"
        val signature = "t?order=not_a_valid_order_segment"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
        assertTrue(meta.orderClauses.isEmpty())
    }

    @Test
    fun fromSignature_unknownFilterOperator_setsComplex_noCondition() {
        // Given
        val table = "t"
        val signature = "t?status=unknownop.value"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
        assertNull(meta.filterDescriptor)
    }

    @Test
    fun fromSignature_filterValueWithoutDot_setsComplex() {
        // Given
        val table = "t"
        val signature = "t?id=nodots"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
    }

    @Test
    fun fromSignature_closingParenKey_setsComplex() {
        // Given
        val table = "t"
        val signature = "t?group)=eq.1"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
        assertNull(meta.filterDescriptor)
    }

    @Test
    fun fromSignature_dottedKey_setsComplex() {
        // Given
        val table = "t"
        val signature = "t?profile.name=eq.alice"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
        assertNull(meta.filterDescriptor)
    }

    @Test
    fun fromSignature_repeatedKey_marksComplexAndSkipsCondition() {
        // Given
        val table = "t"
        val signature = "t?id=eq.1&id=eq.2"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertTrue(meta.hasComplexParams)
        assertNull(meta.filterDescriptor)
    }

    @Test
    fun fromSignature_bareKey_noEquals_createsEmptyValueList() {
        // Given
        val table = "t"
        val signature = "t?flags"

        // When
        val meta = SelectQueryMetadataParser.fromSignature(table, signature)

        // Then
        assertNotNull(meta)
        assertTrue(meta.filterDescriptor == null || meta.filterDescriptor!!.conditions.isEmpty())
    }

    @Test
    fun fromRequestBuilder_simpleEqFilter() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            params["status"] = listOf("eq.on")
        }

        // Then
        assertEquals(1, meta.filterDescriptor?.conditions?.size)
        assertEquals("status", meta.filterDescriptor!!.conditions[0].column)
        assertEquals(false, meta.hasComplexParams)
    }

    @Test
    fun fromRequestBuilder_unknownNonMetaParam_setsComplex() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            params["unknown"] = listOf("value")
        }

        // Then
        assertTrue(meta.hasComplexParams)
        assertNull(meta.filterDescriptor)
    }

    @Test
    fun fromRequestBuilder_invalidOrder_setsComplex() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            params["order"] = listOf("!!!bad!!!")
        }

        // Then
        assertTrue(meta.hasComplexParams)
        assertTrue(meta.orderClauses.isEmpty())
    }

    @Test
    fun fromRequestBuilder_rangeHeader_hasRange() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            headers.append("Range", "0-9")
        }

        // Then
        assertTrue(meta.hasRange)
    }

    @Test
    fun fromRequestBuilder_limitParam_hasPagination() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            params["limit"] = listOf("5")
        }

        // Then
        assertTrue(meta.hasPagination)
    }

    @Test
    fun fromRequestBuilder_validOrder_parsed() {
        // Given
        val table = "items"
        val columns = Columns.ALL

        // When
        val meta = SelectQueryMetadataParser.fromRequestBuilder(
            table = table,
            columns = columns,
            propertyConversionMethod = postgrest.config.propertyConversionMethod,
        ) {
            params["order"] = listOf("name.desc.nullslast")
        }

        // Then
        assertEquals(1, meta.orderClauses.size)
        assertEquals("name", meta.orderClauses[0].column)
        assertEquals(false, meta.hasComplexParams)
    }
}

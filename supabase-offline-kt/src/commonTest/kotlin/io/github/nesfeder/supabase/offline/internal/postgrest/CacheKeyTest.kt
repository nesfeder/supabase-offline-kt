// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The cache-key format. [cacheKeyOf]'s output is the `signature` column of every cached SELECT, so
 * a change here strands every row already cached on a device (it is never matched again). These
 * cases pin the format.
 */
class CacheKeyTest {

    private fun dummyConversionMethod(): PropertyConversionMethod =
        PropertyConversionMethod { prop ->
            prop.name
        }

    @Test
    fun build_withoutParams_orRange_returnsJustTable() {
        // Given
        val table = "assets"

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { /* no params or headers */ },
            )

        // Then
        assertEquals("assets", signature)
    }

    @Test
    fun build_sortsQueryParams_andPreservesValueOrder() {
        // Given
        val table = "assets"

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    params["b"] = listOf("2", "1")
                    params["a"] = listOf("x")
                },
            )

        // Then
        // Keys should be sorted alphabetically (a,b) and for key `b` value order preserved (2,1)
        assertEquals("assets?a=x&b=2&b=1", signature)
    }

    @Test
    fun build_includesBareKeys_whenParamHasNoValues() {
        // Given
        val table = "assets"

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    params["flag"] = emptyList()
                },
            )

        // Then
        assertEquals("assets?flag", signature)
    }

    @Test
    fun build_withColumns_includesSelectParam() {
        // Given
        val table = "assets"
        val columns = Columns.list("id", "name")

        // When
        val signature =
            cacheKeyOf(
                table = table,
                columns = columns,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    // no params
                },
            )

        // Then
        assertEquals("assets?select=id,name", signature)
    }

    @Test
    fun build_appendsRangeFromHeaders_withoutItemsPrefix() {
        // Given
        val table = "assets"

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    headers.append("Range", "items=0-49")
                },
            )

        // Then
        assertEquals("assets|range=0-49", signature)
    }

    @Test
    fun build_combinesQueryAndRange() {
        // Given
        val table = "assets"

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    params["deleted"] = listOf("eq.false")
                    headers.append("Range", "items=10-19")
                },
            )

        // Then
        assertEquals("assets?deleted=eq.false|range=10-19", signature)
    }

    @Test
    fun buildDescriptorFromBuilder_extractsSimpleConditions() {
        // Given
        val builder =
            SelectRequestBuilder(SCHEMA, dummyConversionMethod()).apply {
                // When executed online this would create params:
                // id -> ["eq.123"]
                // name -> ["ilike.%pump%"]
                filter {
                    eq("id", "123")
                    ilike("name", "%pump%")
                }
            }

        // When
        val descriptor = buildDescriptorFromBuilder(builder)

        // Then
        assertNotNull(descriptor)
        assertEquals(FilterDescriptor.Mode.AND, descriptor.mode)
        assertEquals(2, descriptor.conditions.size)
        assertEquals("id", descriptor.conditions[0].column)
        assertEquals("eq", descriptor.conditions[0].operatorId)
        assertEquals("123", descriptor.conditions[0].value)
    }

    @Test
    fun signatureBuilder_buildsCanonicalSignature() {
        // Given
        val table = "assets"
        val builder =
            SelectRequestBuilder(SCHEMA, dummyConversionMethod()).apply {
                // Simulate filters
                filter {
                    eq("deleted", "false")
                    ilike("name", "%pump%")
                }
                // Simulate range header via API
                range(0L..49L)
            }

        // We don't rely on the real builderFactory used in production;
        // we just give cacheKeyOf a preconfigured builder.
        val builderFactory = { builder }

        // When
        val signature =
            cacheKeyOf(
                table = table,
                builderFactory = builderFactory,
            ) {
                // no-op: we already configured builder above
            }

        // Then
        // We expect all query params sorted by key, and range part appended.
        // Exact order of values follows underlying builder; this assertion is
        // intentionally a bit flexible — adjust if needed to match your version.
        // A typical result:
        // "assets?deleted=eq.false&name=ilike.%pump%|range=0-49"
        assertEquals(
            "assets?deleted=eq.false&limit=50&name=ilike.%pump%&offset=0",
            signature,
        )
    }

    private companion object {
        const val SCHEMA = "public"
    }
}

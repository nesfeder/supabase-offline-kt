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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The cache-key format. [cacheKeyOf]'s output is the `cacheKey` column of every cached SELECT, so a
 * change here strands every row already cached on a device (it is never matched again). These cases
 * pin the format.
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
        val cacheKey =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { /* no params or headers */ },
            )

        // Then
        assertEquals("assets", cacheKey)
    }

    @Test
    fun build_sortsQueryParams_andPreservesValueOrder() {
        // Given
        val table = "assets"

        // When
        val cacheKey =
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
        assertEquals("assets?a=x&b=2&b=1", cacheKey)
    }

    @Test
    fun build_includesBareKeys_whenParamHasNoValues() {
        // Given
        val table = "assets"

        // When
        val cacheKey =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    params["flag"] = emptyList()
                },
            )

        // Then
        assertEquals("assets?flag", cacheKey)
    }

    @Test
    fun build_withColumns_includesSelectParam() {
        // Given
        val table = "assets"
        val columns = Columns.list("id", "name")

        // When
        val cacheKey =
            cacheKeyOf(
                table = table,
                columns = columns,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    // no params
                },
            )

        // Then
        assertEquals("assets?select=id,name", cacheKey)
    }

    @Test
    fun build_appendsRangeFromHeaders_withoutItemsPrefix() {
        // Given
        val table = "assets"

        // When
        val cacheKey =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    headers.append("Range", "items=0-49")
                },
            )

        // Then
        assertEquals("assets|range=0-49", cacheKey)
    }

    @Test
    fun build_combinesQueryAndRange() {
        // Given
        val table = "assets"

        // When
        val cacheKey =
            cacheKeyOf(
                table = table,
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    params["deleted"] = listOf("eq.false")
                    headers.append("Range", "items=10-19")
                },
            )

        // Then
        assertEquals("assets?deleted=eq.false|range=10-19", cacheKey)
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
        val descriptor = captureFilter(builder)

        // Then
        assertNotNull(descriptor)
        assertEquals(FilterDescriptor.Mode.AND, descriptor.mode)
        assertEquals(2, descriptor.conditions.size)
        assertEquals("id", descriptor.conditions[0].column)
        assertEquals("eq", descriptor.conditions[0].operatorId)
        assertEquals("123", descriptor.conditions[0].value)
    }

    @Test
    fun cacheKeyOf_buildsCanonicalCacheKey() {
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
        val cacheKey =
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
            cacheKey,
        )
    }

    @Test
    fun a_value_holding_the_parameter_separators_keys_apart_from_two_parameters() {
        // Given — one value that reads as two parameters, and the two parameters
        val oneValue =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { filter { eq("a", "x&b=eq.y") } },
            )
        val twoParameters =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    filter {
                        eq("a", "x")
                        eq("b", "y")
                    }
                },
            )

        // Then — the two parameters keep the key they always had; the value's key ends in a
        // digest of its parts, read apart
        assertEquals("assets?a=eq.x&b=eq.y", twoParameters)
        assertTrue(oneValue.startsWith("assets?a=eq.x&b=eq.y|digest="), oneValue)
        assertEquals(16, oneValue.substringAfter("|digest=").length, oneValue)
        assertNotEquals(twoParameters, oneValue)
    }

    @Test
    fun a_value_holding_a_range_keys_apart_from_the_range_header() {
        // Given — one value that reads as a parameter and a range, and that parameter with a range
        val oneValue =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { filter { eq("a", "x|range=0-9") } },
            )
        val withRange =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    filter { eq("a", "x") }
                    headers.append("Range", "items=0-9")
                },
            )

        // Then
        assertEquals("assets?a=eq.x|range=0-9", withRange)
        assertTrue(oneValue.startsWith("assets?a=eq.x|range=0-9|digest="), oneValue)
        assertNotEquals(withRange, oneValue)
    }

    @Test
    fun a_range_holding_a_separator_gets_a_digest_too() {
        // Given — a raw Range header whose window holds a separator
        val cacheKey =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { headers.append("Range", "items=0-9|x=y") },
            )

        // Then
        assertTrue(cacheKey.startsWith("assets|range=0-9|x=y|digest="), cacheKey)
    }

    @Test
    fun the_digest_is_the_same_for_the_same_request_and_differs_between_requests() {
        // Given
        fun keyOf(value: String): String =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = { filter { eq("a", value) } },
            )

        // Then — a device finds its own key again, and another value has another one
        assertEquals(keyOf("x&y"), keyOf("x&y"))
        assertNotEquals(keyOf("x&y"), keyOf("x&z"))
        assertNotEquals(keyOf("x=y"), keyOf("x|y"))
    }

    @Test
    fun a_key_without_a_separator_in_its_parts_is_unchanged() {
        // Given — values with the characters a key may hold apart from its separators
        val cacheKey =
            cacheKeyOf(
                table = "assets",
                builderFactory = { SelectRequestBuilder(SCHEMA, dummyConversionMethod()) },
                request = {
                    filter {
                        ilike("name", "%pump#2%")
                        eq("note", "a?b:c")
                    }
                    headers.append("Range", "items=0-49")
                },
            )

        // Then — byte for byte the key this request always had
        assertEquals("assets?name=ilike.%pump#2%&note=eq.a?b:c|range=0-49", cacheKey)
    }

    private companion object {
        const val SCHEMA = "public"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor.Condition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * The filter engine's encode/decode round trip and its replay onto a request builder
 * ([replayOnto]), plus the lenient descriptor capture used for queued inserts and upserts.
 */
class OfflineFilterTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A concrete request builder. `PostgrestRequestBuilder` is abstract since supabase-kt 3.6; the
     * engine replays onto a `SelectRequestBuilder` the same way.
     */
    private fun requestBuilder(): SelectRequestBuilder =
        SelectRequestBuilder(SCHEMA, PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE)

    @Test
    fun encodeDecode_roundTrip() {
        // Given
        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = listOf(Condition(column = "id", operatorId = "eq", value = "1")),
            )

        // When
        val encoded = encodeFilter(descriptor, json)
        val decoded = decodeFilterOrNull(encoded, json)

        // Then
        assertNotNull(decoded)
        assertEquals(descriptor, decoded)
    }

    @Test
    fun decodeOrNull_invalidJson_returnsNull() {
        // Given
        val invalidJson = "{not valid"

        // When
        val decoded = decodeFilterOrNull(invalidJson, json)

        // Then
        assertEquals(null, decoded)
    }

    // ────────────────────────────────────────────────────────────────
    // New tests: replayOnto / replayCondition coverage
    // ────────────────────────────────────────────────────────────────

    @Test
    fun applyTo_andMode_appliesConditionsToParams() {
        // Given
        val builder = requestBuilder()
        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        Condition("id", FilterOperator.EQ.identifier, "123"),
                        Condition("name", FilterOperator.ILIKE.identifier, "%pump%"),
                    ),
            )

        // When
        replayOnto(builder, descriptor)

        // Then
        val params = builder.params
        // We don't assert the exact "eq.123" string, just that the keys exist
        // and hold some filter expression.
        val idValues = params["id"]
        val nameValues = params["name"]

        assertNotNull(idValues)
        assertTrue(idValues.isNotEmpty())
        assertTrue(idValues.first().startsWith("eq."))

        assertNotNull(nameValues)
        assertTrue(nameValues.isNotEmpty())
        assertTrue(nameValues.first().startsWith("ilike."))
    }

    @Test
    fun applyTo_orMode_wrapsConditionsInOrBlock() {
        // Given
        val builder = requestBuilder()
        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.OR,
                conditions =
                    listOf(
                        Condition("status", FilterOperator.EQ.identifier, "active"),
                        Condition("status", FilterOperator.EQ.identifier, "inactive"),
                    ),
            )

        // When
        replayOnto(builder, descriptor)

        // Then
        val params = builder.params
        // Supabase-kt encodes OR groups into an "or" query param.
        assertTrue(params.isNotEmpty())
        assertTrue(
            "or" in params.keys,
            "Expected an 'or' query param when using OR mode, but got keys=${params.keys}",
        )
    }

    @Test
    fun applyTo_allOperators_populatesParamsForEachCondition() {
        // Given
        val builder = requestBuilder()

        val conditions =
            listOf(
                // Basic comparisons
                Condition("col_eq", FilterOperator.EQ.identifier, "1"),
                Condition("col_neq", FilterOperator.NEQ.identifier, "1"),
                Condition("col_gt", FilterOperator.GT.identifier, "10"),
                Condition("col_gte", FilterOperator.GTE.identifier, "10"),
                Condition("col_lt", FilterOperator.LT.identifier, "10"),
                Condition("col_lte", FilterOperator.LTE.identifier, "10"),

                // Pattern / text
                Condition("col_like", FilterOperator.LIKE.identifier, "%foo%"),
                Condition("col_ilike", FilterOperator.ILIKE.identifier, "%foo%"),
                Condition("col_match", FilterOperator.MATCH.identifier, "%foo%"),
                Condition("col_imatch", FilterOperator.IMATCH.identifier, "%foo%"),

                // IS
                Condition("col_is_true", FilterOperator.IS.identifier, "true"),
                Condition("col_is_null", FilterOperator.IS.identifier, "null"),

                // Array-like / set operators
                // JSON array form
                Condition("col_in", FilterOperator.IN.identifier, """["a","b"]"""),
                Condition("col_cs", FilterOperator.CS.identifier, """["a","b"]"""),
                Condition("col_cd", FilterOperator.CD.identifier, """["a","b"]"""),
                Condition("col_ov", FilterOperator.OV.identifier, """["a","b"]"""),
                // Comma-separated form (to hit parseArray's second branch)
                Condition("col_in_csv", FilterOperator.IN.identifier, "a,b"),

                // Range operators - JSON array form
                Condition("col_sl", FilterOperator.SL.identifier, """["1","2"]"""),
                Condition("col_sr", FilterOperator.SR.identifier, """["1","2"]"""),
                Condition("col_nxl", FilterOperator.NXL.identifier, """["1","2"]"""),
                Condition("col_nxr", FilterOperator.NXR.identifier, """["1","2"]"""),
                Condition("col_adj", FilterOperator.ADJ.identifier, """["1","2"]"""),
                // Comma-separated form (second branch of parseRange)
                Condition("col_sr_csv", FilterOperator.SR.identifier, "1,2"),

                // Full-text search
                Condition("col_fts", FilterOperator.FTS.identifier, "term"),
                Condition("col_plfts", FilterOperator.PLFTS.identifier, "term"),
                Condition("col_phfts", FilterOperator.PHFTS.identifier, "term"),
                Condition("col_wfts", FilterOperator.WFTS.identifier, "term"),
            )

        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = conditions,
            )

        // When
        replayOnto(builder, descriptor)

        // Then
        val params = builder.params
        assertTrue(params.isNotEmpty(), "Expected some query params to be set by replayOnto")

        // We don't need to assert exact operator encodings; just that each
        // column we configured produced *some* param entry, which implies
        // the appropriate branch in replayCondition ran.
        val expectedColumns =
            listOf(
                "col_eq",
                "col_neq",
                "col_gt",
                "col_gte",
                "col_lt",
                "col_lte",
                "col_like",
                "col_ilike",
                "col_match",
                "col_imatch",
                "col_is_true",
                "col_is_null",
                "col_in",
                "col_cs",
                "col_cd",
                "col_ov",
                "col_in_csv",
                "col_sl",
                "col_sr",
                "col_nxl",
                "col_nxr",
                "col_adj",
                "col_sr_csv",
                "col_fts",
                "col_plfts",
                "col_phfts",
                "col_wfts",
            )

        expectedColumns.forEach { column ->
            assertTrue(
                column in params.keys,
                "Expected params to contain key for column '$column', but keys=${params.keys}",
            )
        }
    }

    @Test
    fun buildDescriptorFromRequest_extractsSimpleEqAndIlike() {
        // Given / When
        val descriptor =
            captureFilter(SCHEMA, PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE) {
                filter {
                    eq("id", "1")
                    ilike("name", "%pump%")
                }
            }

        // Then
        assertNotNull(descriptor)
        assertEquals(FilterDescriptor.Mode.AND, descriptor.mode)
        // Order follows insertion order in builder.params iteration; assert as a set by mapping
        val pairs = descriptor.conditions.associate { it.column to (it.operatorId to it.value) }
        assertEquals("eq" to "1", pairs["id"])
        assertEquals("ilike" to "%pump%", pairs["name"])
    }

    @Test
    fun buildDescriptorFromBuilder_ignoresComplexOrExpression_returnsNull() {
        // Given
        val builder =
            requestBuilder().apply {
                filter {
                    or {
                        eq("a", "1")
                        eq("b", "2")
                    }
                }
            }

        // When
        val descriptor = captureFilter(builder)

        // Then: OR expression is encoded into a single param key like "or=(...)" which is ignored
        assertNull(descriptor)
    }

    /**
     * Inverted: the capture used to trim quotes (and whitespace) from values, so a queued write
     * replayed a different value than the caller sent. It now stores the value exactly as encoded.
     */
    @Test
    fun buildDescriptorFromBuilder_keepsQuotesInValues() {
        // Given
        val builder =
            requestBuilder().apply {
                // Manually inject a param with quotes to simulate eq."123"
                params["id"] = mutableListOf("eq.\"123\"")
                // Single quotes too
                params["title"] = mutableListOf("ilike.'foo%'")
            }

        // When
        val descriptor = captureFilter(builder)

        // Then
        assertNotNull(descriptor)
        val map = descriptor.conditions.associateBy({ it.column }, { it.value })
        assertEquals("\"123\"", map["id"])
        assertEquals("'foo%'", map["title"])
    }

    /**
     * Inverted from the previous standalone engine, which dropped an empty `in` list on replay.
     * Dropping it broadens a queued UPDATE / DELETE to every row the rest of the filter (or RLS)
     * allows, so the engine now replays an empty list as the caller wrote it — `in.()`, which
     * matches nothing. A value that is not a list at all still does not apply, and [replaysFully]
     * reports it so the syncer refuses the replay instead.
     */
    @Test
    fun applyTo_inOperator_appliesEmptyListAsMatchNothing() {
        // Given — an empty `in` list, stored exactly as supabase-kt encodes it
        val emptyList =
            FilterDescriptor(conditions = listOf(Condition("id", operatorId = "in", value = "()")))
        val notAList =
            FilterDescriptor(conditions = listOf(Condition("id", operatorId = "in", value = "")))
        val emptyListBuilder = requestBuilder()
        val notAListBuilder = requestBuilder()

        // When
        val appliedEmptyList = replayOnto(emptyListBuilder, emptyList)
        val appliedNotAList = replayOnto(notAListBuilder, notAList)

        // Then — the empty list replays as match-nothing; the non-list is refused, never dropped
        assertEquals(1, appliedEmptyList)
        assertEquals(listOf("in.()"), emptyListBuilder.params["id"])
        assertTrue(replaysFully(emptyList))
        assertEquals(0, appliedNotAList)
        assertEquals(null, notAListBuilder.params["id"])
        assertFalse(replaysFully(notAList))
    }

    private companion object {
        const val SCHEMA = "public"
    }
}

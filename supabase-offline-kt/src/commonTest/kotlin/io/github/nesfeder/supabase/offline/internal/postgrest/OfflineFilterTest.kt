@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor.Condition
import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter engine's encode/decode round trip, its offline row matching ([FilterEngine.matchesRow])
 * and its replay onto a request builder ([FilterEngine.applyTo]), plus the lenient descriptor
 * capture used for the online overlay.
 */
class OfflineFilterTest {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A concrete request builder. `PostgrestRequestBuilder` is abstract since supabase-kt 3.6;
     * the engine replays onto a `SelectRequestBuilder` the same way.
     */
    private fun requestBuilder(): SelectRequestBuilder =
        SelectRequestBuilder(SCHEMA, PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE)

    @Test
    fun encodeDecode_roundTrip() {
        // Given
        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                Condition(column = "id", operatorId = "eq", value = "1")
            )
        )

        // When
        val encoded = FilterEngine.encode(descriptor, json)
        val decoded = FilterEngine.decodeOrNull(encoded, json)

        // Then
        assertNotNull(decoded)
        assertEquals(descriptor, decoded)
    }

    @Test
    fun decodeOrNull_invalidJson_returnsNull() {
        // Given
        val invalidJson = "{not valid"

        // When
        val decoded = FilterEngine.decodeOrNull(invalidJson, json)

        // Then
        assertEquals(null, decoded)
    }

    @Test
    fun matchesRow_andModeAndBasicOperators() {
        // Given
        val row = buildJsonObject {
            put("id", "123")
            put("name", "Pump A")
            put("count", 10)
            put("flag", true)
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                Condition("id", "eq", "123"),
                Condition("count", "gt", "5"),
                Condition("name", "ilike", "%pump%"),
                Condition("flag", "is", "true")
            )
        )

        // When
        val matches = FilterEngine.matchesRow(row, descriptor)

        // Then
        assertTrue(matches)
    }

    @Test
    fun matchesRow_orMode() {
        // Given
        val row = buildJsonObject {
            put("status", "active")
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.OR,
            conditions = listOf(
                Condition("status", "eq", "inactive"),
                Condition("status", "eq", "active")
            )
        )

        // When
        val matches = FilterEngine.matchesRow(row, descriptor)

        // Then
        assertTrue(matches)
    }

    @Test
    fun matchesRow_inOperator() {
        // Given
        val row = buildJsonObject {
            put("id", "b")
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                Condition("id", "in", "(a,b,c)")
            )
        )

        // When
        val matches = FilterEngine.matchesRow(row, descriptor)

        // Then
        assertTrue(matches)
    }

    @Test
    fun matchesRow_unsupportedOperatorsReturnFalse() {
        // Given
        val row = buildJsonObject {
            put("payload", "anything")
        }

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                Condition("payload", "cs", "[1,2,3]") // containment not supported offline
            )
        )

        // When
        val matches = FilterEngine.matchesRow(row, descriptor)

        // Then
        assertFalse(matches)
    }

    @Test
    fun matchesRow_completeness() {
        // Given / When / Then — each check builds a one-column row and a one-condition filter
        // Helper to check
        fun check(op: String, col: String, rowVal: Any?, filterVal: String, expected: Boolean) {
            val row = buildJsonObject {
                when (rowVal) {
                    null -> put(col, JsonNull)
                    is Number -> put(col, rowVal)
                    is Boolean -> put(col, rowVal)
                    else -> put(col, rowVal.toString())
                }
            }
            val descriptor = FilterDescriptor(
                conditions = listOf(Condition(col, op, filterVal))
            )
            assertEquals(
                expected,
                FilterEngine.matchesRow(row, descriptor),
                "Failed for op=$op, val=$rowVal, filter=$filterVal"
            )
        }

        // EQ
        check("eq", "c", "foo", "foo", true)
        check("eq", "c", "foo", "bar", false)
        check("eq", "c", 10, "10", true)

        // NEQ
        check("neq", "c", "foo", "bar", true)
        check("neq", "c", "foo", "foo", false)

        // GT, GTE, LT, LTE (Numeric)
        check("gt", "c", 10, "5", true)
        check("gt", "c", 10, "10", false)
        check("gt", "c", 10, "15", false)

        check("gte", "c", 10, "5", true)
        check("gte", "c", 10, "10", true)
        check("gte", "c", 10, "15", false)

        check("lt", "c", 10, "15", true)
        check("lt", "c", 10, "10", false)
        check("lt", "c", 10, "5", false)

        check("lte", "c", 10, "15", true)
        check("lte", "c", 10, "10", true)
        check("lte", "c", 10, "5", false)

        // Non-numeric GT/etc should return false
        check("gt", "c", "foo", "5", false)

        // LIKE, ILIKE, MATCH, IMATCH
        listOf("like", "ilike", "match", "imatch").forEach { op ->
            check(op, "c", "HelloWorld", "hello", true)
            check(op, "c", "HelloWorld", "%world%", true)
            check(op, "c", "HelloWorld", "xyz", false)
        }

        // IS
        check("is", "c", null, "null", true)
        check("is", "c", "val", "null", false)
        check("is", "c", true, "true", true)
        check("is", "c", false, "true", false)
        check("is", "c", false, "false", true)
        check("is", "c", "something", "something", true) // Fallback equality

        // IN
        check("in", "c", "foo", "(foo,bar)", true)
        check("in", "c", "baz", "(foo,bar)", false)

        // Unsupported - all return false
        val unsupported = listOf(
            "cs", "cd", "sl", "sr", "nxl", "nxr", "adj", "ov",
            "fts", "plfts", "phfts", "wfts"
        )
        unsupported.forEach { op ->
            check(op, "c", "whatever", "whatever", false)
        }
    }

    // ────────────────────────────────────────────────────────────────
    // New tests: applyTo / applyCondition coverage
    // ────────────────────────────────────────────────────────────────

    @Test
    fun applyTo_andMode_appliesConditionsToParams() {
        // Given
        val builder = requestBuilder()
        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                Condition("id", FilterOperator.EQ.identifier, "123"),
                Condition("name", FilterOperator.ILIKE.identifier, "%pump%")
            )
        )

        // When
        FilterEngine.applyTo(builder, descriptor)

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
        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.OR,
            conditions = listOf(
                Condition("status", FilterOperator.EQ.identifier, "active"),
                Condition("status", FilterOperator.EQ.identifier, "inactive"),
            )
        )

        // When
        FilterEngine.applyTo(builder, descriptor)

        // Then
        val params = builder.params
        // Supabase-kt encodes OR groups into an "or" query param.
        assertTrue(params.isNotEmpty())
        assertTrue(
            "or" in params.keys,
            "Expected an 'or' query param when using OR mode, but got keys=${params.keys}"
        )
    }

    @Test
    fun applyTo_allOperators_populatesParamsForEachCondition() {
        // Given
        val builder = requestBuilder()

        val conditions = listOf(
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

        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = conditions
        )

        // When
        FilterEngine.applyTo(builder, descriptor)

        // Then
        val params = builder.params
        assertTrue(params.isNotEmpty(), "Expected some query params to be set by applyTo")

        // We don't need to assert exact operator encodings; just that each
        // column we configured produced *some* param entry, which implies
        // the appropriate branch in applyCondition ran.
        val expectedColumns = listOf(
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

        expectedColumns.forEach { col ->
            assertTrue(
                col in params.keys,
                "Expected params to contain key for column '$col', but keys=${params.keys}"
            )
        }
    }

    @Test
    fun buildDescriptorFromRequest_extractsSimpleEqAndIlike() {
        // Given / When
        val descriptor =
            buildDescriptorFromRequest(SCHEMA, PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE) {
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
        val descriptor = buildDescriptorFromBuilder(builder)

        // Then: OR expression is encoded into a single param key like "or=(...)" which is ignored
        assertNull(descriptor)
    }

    @Test
    fun buildDescriptorFromBuilder_trimsQuotesFromValues() {
        // Given
        val builder =
            requestBuilder().apply {
            // Manually inject a param with quotes to simulate eq."123"
            params["id"] = mutableListOf("eq.\"123\"")
            // Single quotes too
            params["title"] = mutableListOf("ilike.'foo%'")
        }

        // When
        val descriptor = buildDescriptorFromBuilder(builder)

        // Then
        assertNotNull(descriptor)
        val map = descriptor.conditions.associateBy({ it.column }, { it.value })
        assertEquals("123", map["id"])
        assertEquals("foo%", map["title"])
    }

    /**
     * Inverted from the previous standalone engine, which dropped an empty `in` list on replay.
     * Dropping it broadens a queued UPDATE / DELETE to every row the rest of the filter (or RLS)
     * allows, so the engine now replays an empty list as the caller wrote it — `in.()`, which
     * matches nothing. A value that is not a list at all still does not apply, and
     * [FilterEngine.replaysFully] reports it so the syncer refuses the replay instead.
     */
    @Test
    fun applyTo_inOperator_appliesEmptyListAsMatchNothing() {
        // Given — an empty `in` list, stored exactly as supabase-kt encodes it
        val emptyList = FilterDescriptor(conditions = listOf(Condition("id", operatorId = "in", value = "()")))
        val notAList = FilterDescriptor(conditions = listOf(Condition("id", operatorId = "in", value = "")))
        val emptyListBuilder = requestBuilder()
        val notAListBuilder = requestBuilder()

        // When
        val appliedEmptyList = FilterEngine.applyTo(emptyListBuilder, emptyList)
        val appliedNotAList = FilterEngine.applyTo(notAListBuilder, notAList)

        // Then — the empty list replays as match-nothing; the non-list is refused, never dropped
        assertEquals(1, appliedEmptyList)
        assertEquals(listOf("in.()"), emptyListBuilder.params["id"])
        assertTrue(FilterEngine.replaysFully(emptyList))
        assertEquals(0, appliedNotAList)
        assertEquals(null, notAListBuilder.params["id"])
        assertFalse(FilterEngine.replaysFully(notAList))
    }

    private companion object {
        const val SCHEMA = "public"
    }
}

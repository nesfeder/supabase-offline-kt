package io.github.nesfeder.supabase.internal.postgrest

import io.github.nesfeder.supabase.internal.postgrest.parser.NullsOrder
import io.github.nesfeder.supabase.internal.postgrest.parser.OrderClause
import io.github.nesfeder.supabase.internal.postgrest.parser.OrderDirection
import io.github.nesfeder.supabase.internal.postgrest.parser.SelectQueryMetadataParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CacheSubsetResolverTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ─────────────────────────── canProvideSubsetFor ───────────────────────────

    @Test
    fun canProvideSubsetFor_differentSelectLists_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?select=id")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?select=name")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_bothMetadataDisableSubsetDerivation_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?limit=10")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?or=(id.eq.1)")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_noFilterCandidate_subsetWithFilter_returnsTrue() {
        // Given — cached: all assets (no WHERE); requested: assets WHERE account_id = abcd123
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_broadFilterCandidate_narrowerRequested_returnsTrue() {
        // Given — cached: WHERE account_id = abcd123; requested: WHERE account_id = abcd123 AND id = 1234
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_exactSameFilter_returnsTrue() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateFilterNarrowerThanRequested_returnsFalse() {
        // Given — cached: WHERE account_id = abcd123 AND id = 1234 (narrower)
        // Requested: WHERE account_id = abcd123 (broader) → candidate cannot serve this
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_differentFilterValue_returnsFalse() {
        // Given — cached: WHERE account_id = abcd123; requested: WHERE account_id = xyz789 AND id = 1234
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.xyz789&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateHasOrder_returnsTrue() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&order=name.asc.nullslast")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_requestedHasOrder_returnsTrue() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234&order=name.asc.nullslast")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateHasRange_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123|range=0-49")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_requestedHasRange_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234|range=0-9")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateHasPagination_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&limit=10&offset=0")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_requestedHasPagination_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234&limit=5")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateHasComplexParams_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?or=(account_id.eq.abcd123)")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateColumnsSubset_requestedColumnsAll_returnsFalse() {
        // Given — candidate cached id,name only; requested needs ALL
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?select=id,name")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_candidateAllColumns_requestedSpecificColumns_returnsTrue() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234&select=id,name")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_sameScopedColumns_returnsTrue() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&select=id,name")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234&select=id,name")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertTrue(canProvide)
    }

    @Test
    fun canProvideSubsetFor_differentTables_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("other", "other?account_id=eq.abcd123")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_requestedHasNoFilter_candidateHasFilter_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    @Test
    fun canProvideSubsetFor_requestedHasComplexParams_returnsFalse() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?or=(account_id.eq.abcd123)")

        // When
        val canProvide = candidate.canProvideSubsetFor(requested)

        // Then
        assertFalse(canProvide)
    }

    // ─────────────────────────── compareJsonObjectsForOrder / JsonElement.compareTo ───────────────────────────

    @Test
    fun compareJsonObjectsForOrder_multiClause_usesSecondClauseOnTie() {
        // Given
        val clauses = listOf(
            OrderClause("name", OrderDirection.ASC, NullsOrder.NULLS_LAST),
            OrderClause("id", OrderDirection.ASC, NullsOrder.NULLS_LAST),
        )
        val left = buildJsonObject {
            put("name", JsonPrimitive("A"))
            put("id", JsonPrimitive("2"))
        }
        val right = buildJsonObject {
            put("name", JsonPrimitive("A"))
            put("id", JsonPrimitive("1"))
        }

        // When
        val cmpLeftRight = compareJsonObjectsForOrder(left, right, clauses)
        val cmpLeftLeft = compareJsonObjectsForOrder(left, left, clauses)

        // Then
        assertTrue(cmpLeftRight > 0)
        assertEquals(0, cmpLeftLeft)
    }

    @Test
    fun compareJsonObjectsForOrder_desc_reversesPrimitiveOrder() {
        // Given
        val clauses = listOf(OrderClause("n", OrderDirection.DESC, NullsOrder.NULLS_LAST))
        val low = buildJsonObject { put("n", JsonPrimitive(1)) }
        val high = buildJsonObject { put("n", JsonPrimitive(2)) }

        // When
        val cmp = compareJsonObjectsForOrder(low, high, clauses)

        // Then
        assertTrue(cmp > 0)
    }

    @Test
    fun jsonElement_compareTo_bothNullOrJsonNull_equal() {
        // Given — null / JsonNull pairs under different nulls-order settings

        // When / Then
        assertEquals(0, JsonNull.compareTo(JsonNull, NullsOrder.NULLS_LAST))
        assertEquals(0, null.compareTo(null, NullsOrder.NULLS_FIRST))
    }

    @Test
    fun jsonElement_compareTo_nullVsValue_respectsNullsOrder() {
        // Given — one side null, the other a string primitive

        // When / Then
        assertTrue(null.compareTo(JsonPrimitive("x"), NullsOrder.NULLS_FIRST) < 0)
        assertTrue(null.compareTo(JsonPrimitive("x"), NullsOrder.NULLS_LAST) > 0)
        assertTrue(JsonPrimitive("x").compareTo(null, NullsOrder.NULLS_FIRST) > 0)
        assertTrue(JsonPrimitive("x").compareTo(null, NullsOrder.NULLS_LAST) < 0)
    }

    @Test
    fun jsonElement_compareTo_numericAndBooleanBranches() {
        // Given — primitives that exercise long, double, and boolean comparison paths

        // When / Then
        assertTrue(JsonPrimitive("2").compareTo(JsonPrimitive("10"), NullsOrder.NULLS_LAST) < 0)
        assertTrue(JsonPrimitive("1.5").compareTo(JsonPrimitive("2.5"), NullsOrder.NULLS_LAST) < 0)
        assertTrue(JsonPrimitive("true").compareTo(JsonPrimitive("false"), NullsOrder.NULLS_LAST) > 0)
    }

    @Test
    fun jsonElement_compareTo_mixedPrimitiveTypesFallThroughToTextCompare() {
        // Given — one side parses as a richer primitive type and the other does not

        // When / Then
        assertTrue(JsonPrimitive("1").compareTo(JsonPrimitive("one"), NullsOrder.NULLS_LAST) < 0)
        assertTrue(JsonPrimitive("1.5").compareTo(JsonPrimitive("one"), NullsOrder.NULLS_LAST) < 0)
        assertTrue(JsonPrimitive("true").compareTo(JsonPrimitive("maybe"), NullsOrder.NULLS_LAST) > 0)
    }

    @Test
    fun jsonElement_compareTo_nonPrimitiveFallsBackToStringCompare() {
        // Given
        val left = buildJsonObject { put("k", JsonPrimitive("1")) }
        val right = buildJsonObject { put("k", JsonPrimitive("2")) }

        // When
        val cmp = left.compareTo(right, NullsOrder.NULLS_LAST)

        // Then
        assertTrue(cmp != 0)
    }

    @Test
    fun jsonElement_compareTo_nonPrimitivePrimitives_useStringFallback() {
        // Given
        val arr = buildJsonArray { add(JsonPrimitive(1)) }

        // When
        val cmp = arr.compareTo(JsonPrimitive("z"), NullsOrder.NULLS_LAST)

        // Then
        assertTrue(cmp != 0)
    }

    @Test
    fun compareJsonObjectsForOrder_comparesNonPrimitiveCellViaFallback() {
        // Given
        val clauses = listOf(OrderClause("tags", OrderDirection.ASC, NullsOrder.NULLS_LAST))
        val left = buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("a")) }) }
        val right = buildJsonObject { put("tags", buildJsonArray { add(JsonPrimitive("b")) }) }

        // When
        val cmp = compareJsonObjectsForOrder(left, right, clauses)

        // Then
        assertTrue(cmp < 0)
    }

    @Test
    fun jsonElement_compareTo_equalPrimitivesReturnZero() {
        // Given — identical string primitives

        // When / Then
        assertEquals(0, JsonPrimitive("same").compareTo(JsonPrimitive("same"), NullsOrder.NULLS_LAST))
        assertEquals(0, JsonPrimitive("7").compareTo(JsonPrimitive("7"), NullsOrder.NULLS_LAST))
    }

    @Test
    fun jsonElement_compareTo_stringContentComparedWhenNotNumeric() {
        // Given — non-numeric strings compared lexicographically

        // When / Then
        assertTrue(JsonPrimitive("apple").compareTo(JsonPrimitive("banana"), NullsOrder.NULLS_LAST) < 0)
    }

    // ─────────────────────────── deriveSubsetPayload ───────────────────────────

    @Test
    fun deriveSubsetPayload_nestedArrayYieldsNoRows() {
        // Given
        val meta = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val nested = """[[{"id":"1"}]]"""

        // When
        val result = deriveSubsetPayload(nested, meta, meta, json)

        // Then
        assertEquals("[]", result)
    }

    @Test
    fun deriveSubsetPayload_filtersRowsByExtraCondition_withoutReordering() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.9999")
        val broadPayload = """[{"id":"1234"},{"id":"9999"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"id":"9999"}]""", result)
    }

    @Test
    fun deriveSubsetPayload_candidateHasFilterRequestedHasNoFilter_returnsOriginalRows() {
        // Given — this shape is not a valid subset relationship, but exercises the raw derivation helper's
        // null requested-filter branch directly.
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val broadPayload = """[{"id":"1234"},{"id":"9999"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals(broadPayload, result)
    }

    @Test
    fun deriveSubsetPayload_candidateHasNoFilterRequestedHasFilter_filtersRows() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?id=eq.9999")
        val broadPayload = """[{"id":"1234"},{"id":"9999"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"id":"9999"}]""", result)
    }

    @Test
    fun deriveSubsetPayload_filtersRowsByExtraCondition() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")
        val broadPayload = """[{"id":"1234","name":"Pump"},{"id":"9999","name":"Other"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertNotNull(result)
        assertTrue(result.contains("\"1234\""))
        assertFalse(result.contains("\"9999\""))
    }

    @Test
    fun deriveSubsetPayload_sortsByRequestedOrderWhenNoExtraFilter() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature(
            "assets",
            "assets?account_id=eq.abcd123&order=name.asc.nullslast",
        )
        val broadPayload = """[{"id":"2","name":"B"},{"id":"1","name":"A"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"id":"1","name":"A"},{"id":"2","name":"B"}]""", result)
    }

    @Test
    fun deriveSubsetPayload_sortsDescByRequestedOrder() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature(
            "assets",
            "assets?account_id=eq.abcd123&order=n.desc.nullslast",
        )
        val broadPayload = """[{"n":1},{"n":2}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"n":2},{"n":1}]""", result)
    }

    @Test
    fun deriveSubsetPayload_ignoresNonObjectArrayElements() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.2")
        val broadPayload = """[{"id":"1"},{"id":"2"},"skip",5,null]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"id":"2"}]""", result)
    }

    @Test
    fun deriveSubsetPayload_sortsFilteredRowsByRequestedOrder() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature(
            "assets",
            "assets?account_id=eq.abcd123&status=eq.on&order=name.asc.nullslast",
        )
        val broadPayload = """[{"id":"2","account_id":"abcd123","status":"on","name":"B"},{"id":"1","account_id":"abcd123","status":"on","name":"A"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("""[{"id":"1","account_id":"abcd123","status":"on","name":"A"},{"id":"2","account_id":"abcd123","status":"on","name":"B"}]""", result)
    }

    @Test
    fun deriveSubsetPayload_noExtraConditions_returnsFullPayload() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val broadPayload = """[{"id":"1234"},{"id":"9999"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals(broadPayload, result)
    }

    @Test
    fun deriveSubsetPayload_invalidJsonPayload_returnsNull() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?id=eq.1234")

        // When
        val result = deriveSubsetPayload("not-json", candidate, requested, json)

        // Then
        assertNull(result)
    }

    @Test
    fun deriveSubsetPayload_jsonObjectNotArray_returnsNull() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?id=eq.1234")

        // When
        val result = deriveSubsetPayload("""{"id":"1234"}""", candidate, requested, json)

        // Then
        assertNull(result)
    }

    @Test
    fun deriveSubsetPayload_emptyBroadPayload_returnsEmptyArray() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.1234")

        // When
        val result = deriveSubsetPayload("[]", candidate, requested, json)

        // Then
        assertEquals("[]", result)
    }

    @Test
    fun deriveSubsetPayload_noRowMatchesExtraCondition_returnsEmptyArray() {
        // Given
        val candidate = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123")
        val requested = SelectQueryMetadataParser.fromSignature("assets", "assets?account_id=eq.abcd123&id=eq.nonexistent")
        val broadPayload = """[{"id":"1234","account_id":"abcd123"}]"""

        // When
        val result = deriveSubsetPayload(broadPayload, candidate, requested, json)

        // Then
        assertEquals("[]", result)
    }
}

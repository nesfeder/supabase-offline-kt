package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Which locally written rows a cached query admits: a row that fails a condition the engine can
 * evaluate (`eq`, `neq`, `in`, `is`, with Postgres null semantics) is excluded whatever the query's
 * order or limit; anything the engine cannot evaluate admits the row.
 */
class AdmissionSemanticsTest {

    private fun admits(cacheKey: String, row: JsonObject): Boolean = assertNotNull(admissionOf(cacheKey)).admits(row)

    private fun rowWith(column: String, value: JsonPrimitive): JsonObject = buildJsonObject {
        put("id", "row-1")
        put(column, value)
    }

    // Order and limit do not switch the evaluation off

    @Test
    fun an_ordered_limited_query_excludes_a_row_of_another_list() {
        // Given
        val key = "items?limit=20&list_id=eq.A&offset=40&order=position.asc.nullslast|range=0-19"

        // When / Then
        assertFalse(admits(key, rowWith("list_id", JsonPrimitive("B"))))
        assertTrue(admits(key, rowWith("list_id", JsonPrimitive("A"))))
    }

    @Test
    fun a_condition_that_fails_excludes_the_row_even_beside_one_the_engine_cannot_evaluate() {
        // Given — an evaluable condition that says no and a pattern condition that cannot be evaluated
        val key = "items?list_id=eq.A&title=like.*milk*"

        // When / Then
        assertFalse(admits(key, buildJsonObject { put("list_id", "B"); put("title", "milk") }))
    }

    // Null semantics

    @Test
    fun eq_against_a_null_column_excludes_the_row() {
        assertFalse(admits("items?list_id=eq.A", rowWith("list_id", JsonNull)))
    }

    @Test
    fun neq_against_a_null_column_excludes_the_row() {
        // Given — in SQL, `NULL <> 'A'` is unknown, and unknown excludes
        val row = rowWith("list_id", JsonNull)

        // When / Then
        assertFalse(admits("items?list_id=neq.A", row))
    }

    @Test
    fun neq_admits_a_different_value_and_excludes_the_same_one() {
        assertTrue(admits("items?status=neq.archived", rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits("items?status=neq.archived", rowWith("status", JsonPrimitive("archived"))))
    }

    @Test
    fun is_null_admits_a_null_column_and_excludes_a_value() {
        assertTrue(admits("items?deleted_at=is.null", rowWith("deleted_at", JsonNull)))
        assertFalse(admits("items?deleted_at=is.null", rowWith("deleted_at", JsonPrimitive("2026-01-01T00:00:00Z"))))
    }

    @Test
    fun is_true_and_is_false_compare_booleans_and_exclude_null() {
        assertTrue(admits("items?pinned=is.true", rowWith("pinned", JsonPrimitive(true))))
        assertFalse(admits("items?pinned=is.true", rowWith("pinned", JsonPrimitive(false))))
        assertFalse(admits("items?pinned=is.true", rowWith("pinned", JsonNull)))
        assertTrue(admits("items?pinned=is.false", rowWith("pinned", JsonPrimitive(false))))
        assertFalse(admits("items?pinned=is.false", rowWith("pinned", JsonNull)))
    }

    @Test
    fun in_without_null_admits_a_member_and_excludes_everything_else() {
        val key = "items?status=in.(open,blocked)"
        assertTrue(admits(key, rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits(key, rowWith("status", JsonPrimitive("done"))))
        assertFalse(admits(key, rowWith("status", JsonNull)))
    }

    @Test
    fun in_with_null_never_admits_a_null_column() {
        // Given — `col IN ('open', NULL)` is unknown for a NULL column, and for any non-member
        val key = "items?status=in.(open,null)"

        // When / Then
        assertTrue(admits(key, rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits(key, rowWith("status", JsonNull)))
        assertFalse(admits(key, rowWith("status", JsonPrimitive("done"))))
    }

    @Test
    fun in_with_quoted_members_admits_a_member_holding_a_comma() {
        assertTrue(admits("items?tag=in.(\"a,b\",plain)", rowWith("tag", JsonPrimitive("a,b"))))
        assertFalse(admits("items?tag=in.(\"a,b\",plain)", rowWith("tag", JsonPrimitive("a"))))
    }

    @Test
    fun an_empty_in_list_admits_nothing() {
        assertFalse(admits("items?status=in.()", rowWith("status", JsonPrimitive("open"))))
    }

    // What the engine cannot tell admits the row

    @Test
    fun a_row_without_the_filtered_column_is_admitted() {
        // Given — the column is filled by a server default, e.g. the signed-in user's id
        val row = buildJsonObject { put("id", "row-1"); put("title", "new") }

        // When / Then
        assertTrue(admits("items?owner_id=eq.u-1&order=created_at.desc.nullslast", row))
    }

    @Test
    fun a_uuid_that_differs_only_in_letter_case_is_admitted() {
        // Given — a Swift UUID string is uppercase; the server's is lowercase
        val row = rowWith("list_id", JsonPrimitive("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"))

        // When / Then
        assertTrue(admits("items?list_id=eq.3F1C2D4E-5A6B-4C7D-8E9F-0A1B2C3D4E5F", row))
    }

    @Test
    fun a_different_uuid_is_excluded() {
        val row = rowWith("list_id", JsonPrimitive("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"))
        assertFalse(admits("items?list_id=eq.00000000-5a6b-4c7d-8e9f-0a1b2c3d4e5f", row))
    }

    @Test
    fun numbers_compare_by_value() {
        assertTrue(admits("items?priority=eq.5", rowWith("priority", JsonPrimitive(5.0))))
        assertFalse(admits("items?priority=eq.5", rowWith("priority", JsonPrimitive(6))))
        assertTrue(admits("items?priority=in.(1,2,5)", rowWith("priority", JsonPrimitive(5))))
    }

    @Test
    fun booleans_compare_by_value() {
        assertTrue(admits("items?done=eq.false", rowWith("done", JsonPrimitive(false))))
        assertFalse(admits("items?done=eq.false", rowWith("done", JsonPrimitive(true))))
    }

    @Test
    fun an_array_or_object_column_is_admitted() {
        val arrayRow = buildJsonObject { put("id", "row-1"); put("tags", buildJsonArray { add(JsonPrimitive("x")) }) }
        assertTrue(admits("items?tags=eq.x", arrayRow))
    }

    @Test
    fun every_family_the_engine_cannot_evaluate_admits_the_row() {
        // Given — a row each condition would exclude if the engine guessed
        val row = buildJsonObject {
            put("id", "row-1")
            put("done", false)
            put("archived", false)
            put("priority", 0)
            put("title", "nothing matches")
            put("body", "nothing matches")
            put("tags", buildJsonArray { add(JsonPrimitive("home")) })
            put("window", "[1,2)")
            put("created_at", "2020-01-01T00:00:00Z")
            put("data", buildJsonObject { put("kind", "other") })
        }
        val unknownFamilies = listOf(
            "items?or=(done.eq.true,archived.eq.true)",
            "items?and=(priority.gte.1,priority.lte.3)",
            "items?not.or=(done.eq.false,archived.eq.false)",
            "items?done=not.eq.false",
            "items?priority=gt.5",
            "items?priority=gte.5",
            "items?priority=lt.-5",
            "items?created_at=lte.2019-01-01",
            "items?window=sl.(5,10)",
            "items?window=adj.(5,10)",
            "items?title=like.*milk*",
            "items?title=ilike.*milk*",
            "items?title=match.^milk$",
            "items?title=imatch.^MILK$",
            "items?body=fts.milk",
            "items?body=plfts(english).milk",
            "items?tags=cs.{work}",
            "items?tags=cd.{work}",
            "items?tags=ov.{work}",
            "items?status=isdistinct.open",
            "items?lists.name=eq.Home&select=*,lists!inner(name)",
            "items?comments.or=(author.eq.a)&select=id,comments(body)",
            "items?data->>kind=eq.idea",
            "items?created_at::date=eq.2026-01-01",
        )

        // When / Then
        unknownFamilies.forEach { key -> assertTrue(admits(key, row), "$key excluded a row it cannot evaluate") }
    }
}

@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which locally written rows a cached query admits: a row that fails a condition the engine can
 * evaluate (`eq`, `neq`, `in`, `is`, with Postgres null semantics) is excluded whatever the query's
 * order or limit; anything the engine cannot evaluate admits the row.
 */
class AdmissionSemanticsTest {

    /** The shape of a SELECT whose builder holds [params], as supabase-kt holds a request's filters. */
    private fun shapeOf(vararg params: Pair<String, String>): QueryShape {
        val builder = SelectRequestBuilder("public", PropertyConversionMethod.NONE)
        params.forEach { (name, value) -> builder.params[name] = builder.params[name].orEmpty() + value }
        return queryShapeOf(Columns.ALL, builder)
    }

    private fun admits(shape: QueryShape, row: JsonObject): Boolean = shape.admission.admits(row)

    private fun rowWith(column: String, value: JsonPrimitive): JsonObject = buildJsonObject {
        put("id", "row-1")
        put(column, value)
    }

    // Order and limit do not switch the evaluation off

    @Test
    fun an_ordered_limited_query_excludes_a_row_of_another_list() {
        // Given
        val shape = shapeOf("limit" to "20", "list_id" to "eq.A", "offset" to "40", "order" to "position.asc.nullslast")

        // When / Then
        assertFalse(admits(shape, rowWith("list_id", JsonPrimitive("B"))))
        assertTrue(admits(shape, rowWith("list_id", JsonPrimitive("A"))))
    }

    @Test
    fun a_condition_that_fails_excludes_the_row_even_beside_one_the_engine_cannot_evaluate() {
        // Given — an evaluable condition that says no and a pattern condition that cannot be evaluated
        val shape = shapeOf("list_id" to "eq.A", "title" to "like.*milk*")

        // When / Then
        assertFalse(admits(shape, buildJsonObject { put("list_id", "B"); put("title", "milk") }))
    }

    // Null semantics

    @Test
    fun eq_against_a_null_column_excludes_the_row() {
        assertFalse(admits(shapeOf("list_id" to "eq.A"), rowWith("list_id", JsonNull)))
    }

    @Test
    fun neq_against_a_null_column_excludes_the_row() {
        // Given — in SQL, `NULL <> 'A'` is unknown, and unknown excludes
        val row = rowWith("list_id", JsonNull)

        // When / Then
        assertFalse(admits(shapeOf("list_id" to "neq.A"), row))
    }

    @Test
    fun neq_admits_a_different_value_and_excludes_the_same_one() {
        assertTrue(admits(shapeOf("status" to "neq.archived"), rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits(shapeOf("status" to "neq.archived"), rowWith("status", JsonPrimitive("archived"))))
    }

    @Test
    fun is_null_admits_a_null_column_and_excludes_a_value() {
        assertTrue(admits(shapeOf("deleted_at" to "is.null"), rowWith("deleted_at", JsonNull)))
        assertFalse(admits(shapeOf("deleted_at" to "is.null"), rowWith("deleted_at", JsonPrimitive("2026-01-01T00:00:00Z"))))
    }

    @Test
    fun is_true_and_is_false_compare_booleans_and_exclude_null() {
        assertTrue(admits(shapeOf("pinned" to "is.true"), rowWith("pinned", JsonPrimitive(true))))
        assertFalse(admits(shapeOf("pinned" to "is.true"), rowWith("pinned", JsonPrimitive(false))))
        assertFalse(admits(shapeOf("pinned" to "is.true"), rowWith("pinned", JsonNull)))
        assertTrue(admits(shapeOf("pinned" to "is.false"), rowWith("pinned", JsonPrimitive(false))))
        assertFalse(admits(shapeOf("pinned" to "is.false"), rowWith("pinned", JsonNull)))
    }

    @Test
    fun in_without_null_admits_a_member_and_excludes_everything_else() {
        val shape = shapeOf("status" to "in.(open,blocked)")
        assertTrue(admits(shape, rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits(shape, rowWith("status", JsonPrimitive("done"))))
        assertFalse(admits(shape, rowWith("status", JsonNull)))
    }

    @Test
    fun in_with_null_never_admits_a_null_column() {
        // Given — `col IN ('open', NULL)` is unknown for a NULL column, and for any non-member
        val shape = shapeOf("status" to "in.(open,null)")

        // When / Then
        assertTrue(admits(shape, rowWith("status", JsonPrimitive("open"))))
        assertFalse(admits(shape, rowWith("status", JsonNull)))
        assertFalse(admits(shape, rowWith("status", JsonPrimitive("done"))))
    }

    @Test
    fun in_with_quoted_members_admits_a_member_holding_a_comma() {
        assertTrue(admits(shapeOf("tag" to "in.(\"a,b\",plain)"), rowWith("tag", JsonPrimitive("a,b"))))
        assertFalse(admits(shapeOf("tag" to "in.(\"a,b\",plain)"), rowWith("tag", JsonPrimitive("a"))))
    }

    @Test
    fun an_empty_in_list_admits_nothing() {
        assertFalse(admits(shapeOf("status" to "in.()"), rowWith("status", JsonPrimitive("open"))))
    }

    // What the engine cannot tell admits the row

    @Test
    fun a_row_without_the_filtered_column_is_admitted() {
        // Given — the column is filled by a server default, e.g. the signed-in user's id
        val row = buildJsonObject { put("id", "row-1"); put("title", "new") }

        // When / Then
        assertTrue(admits(shapeOf("owner_id" to "eq.u-1", "order" to "created_at.desc.nullslast"), row))
    }

    @Test
    fun a_uuid_that_differs_only_in_letter_case_is_admitted() {
        // Given — a Swift UUID string is uppercase; the server's is lowercase
        val row = rowWith("list_id", JsonPrimitive("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"))

        // When / Then
        assertTrue(admits(shapeOf("list_id" to "eq.3F1C2D4E-5A6B-4C7D-8E9F-0A1B2C3D4E5F"), row))
    }

    @Test
    fun a_different_uuid_is_excluded() {
        val row = rowWith("list_id", JsonPrimitive("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"))
        assertFalse(admits(shapeOf("list_id" to "eq.00000000-5a6b-4c7d-8e9f-0a1b2c3d4e5f"), row))
    }

    @Test
    fun numbers_compare_by_value() {
        assertTrue(admits(shapeOf("priority" to "eq.5"), rowWith("priority", JsonPrimitive(5.0))))
        assertFalse(admits(shapeOf("priority" to "eq.5"), rowWith("priority", JsonPrimitive(6))))
        assertTrue(admits(shapeOf("priority" to "in.(1,2,5)"), rowWith("priority", JsonPrimitive(5))))
    }

    @Test
    fun booleans_compare_by_value() {
        assertTrue(admits(shapeOf("done" to "eq.false"), rowWith("done", JsonPrimitive(false))))
        assertFalse(admits(shapeOf("done" to "eq.false"), rowWith("done", JsonPrimitive(true))))
    }

    @Test
    fun an_array_or_object_column_is_admitted() {
        val arrayRow = buildJsonObject { put("id", "row-1"); put("tags", buildJsonArray { add(JsonPrimitive("x")) }) }
        assertTrue(admits(shapeOf("tags" to "eq.x"), arrayRow))
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
            shapeOf("or" to "(done.eq.true,archived.eq.true)"),
            shapeOf("and" to "(priority.gte.1,priority.lte.3)"),
            shapeOf("not.or" to "(done.eq.false,archived.eq.false)"),
            shapeOf("done" to "not.eq.false"),
            shapeOf("priority" to "gt.5"),
            shapeOf("priority" to "gte.5"),
            shapeOf("priority" to "lt.-5"),
            shapeOf("created_at" to "lte.2019-01-01"),
            shapeOf("window" to "sl.(5,10)"),
            shapeOf("window" to "adj.(5,10)"),
            shapeOf("title" to "like.*milk*"),
            shapeOf("title" to "ilike.*milk*"),
            shapeOf("title" to "match.^milk$"),
            shapeOf("title" to "imatch.^MILK$"),
            shapeOf("body" to "fts.milk"),
            shapeOf("body" to "plfts(english).milk"),
            shapeOf("tags" to "cs.{work}"),
            shapeOf("tags" to "cd.{work}"),
            shapeOf("tags" to "ov.{work}"),
            shapeOf("status" to "isdistinct.open"),
            shapeOf("lists.name" to "eq.Home", "select" to "*,lists!inner(name)"),
            shapeOf("comments.or" to "(author.eq.a)", "select" to "id,comments(body)"),
            shapeOf("data->>kind" to "eq.idea"),
            shapeOf("created_at::date" to "eq.2026-01-01"),
        )

        // When / Then
        unknownFamilies.forEach { shape -> assertTrue(admits(shape, row), "$shape excluded a row it cannot evaluate") }
    }
}

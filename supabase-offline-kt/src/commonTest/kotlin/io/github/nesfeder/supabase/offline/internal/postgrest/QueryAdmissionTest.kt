package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [admissionOf] and [QueryAdmission.admits] — the query-visibility gate
 * that stops the mutation overlay from appending INSERT/UPSERT rows into cached payloads whose
 * filter excludes them.
 *
 * The contract is conservative: a signature is only [QueryAdmission.isExhaustive] when its full
 * WHERE clause is captured and there is no windowing; anything we can't model faithfully falls
 * back to the legacy always-apply behavior so we never hide a row the server would return.
 */
class QueryAdmissionTest {

    // ── Exhaustive filter capture ─────────────────────────────────────────────

    @Test
    fun plain_eq_filter_is_exhaustive_with_single_condition() {
        // Given / When
        val result = admissionOf("notes?type=eq.OPEN")

        // Then
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertEquals(
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = listOf(FilterDescriptor.Condition("type", "eq", "OPEN")),
            ),
            result.descriptor,
        )
    }

    @Test
    fun multi_condition_and_filter_is_exhaustive() {
        // Given / When
        val result = admissionOf("notes?type=eq.OPEN&user_id=eq.user-1")

        // Then — both conditions captured in order, combined with AND
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertEquals(
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = listOf(
                    FilterDescriptor.Condition("type", "eq", "OPEN"),
                    FilterDescriptor.Condition("user_id", "eq", "user-1"),
                ),
            ),
            result.descriptor,
        )
    }

    @Test
    fun eq_value_with_dots_is_preserved() {
        // Given / When — a timestamp value carries dots the `op.value` split must not swallow
        val result = admissionOf("notes?created_at=eq.2026-01-01T00:00:00.000Z")

        // Then — the whole value is kept and, being an equality filter, stays exhaustive
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertEquals(
            listOf(FilterDescriptor.Condition("created_at", "eq", "2026-01-01T00:00:00.000Z")),
            result.descriptor?.conditions,
        )
    }

    @Test
    fun neq_filter_is_exhaustive() {
        // Given / When — `neq` is the other equality operator the admit gate evaluates faithfully
        val result = admissionOf("notes?type=neq.DONE")

        // Then
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertEquals(
            listOf(FilterDescriptor.Condition("type", "neq", "DONE")),
            result.descriptor?.conditions,
        )
    }

    @Test
    fun unfiltered_query_is_exhaustive_with_null_descriptor() {
        // Given / When — no '?' means SELECT * (returns every row)
        val result = admissionOf("notes")

        // Then
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertNull(result.descriptor)
    }

    @Test
    fun select_projection_does_not_break_exhaustiveness() {
        // Given / When — `select` only chooses columns; it never changes row membership
        val result = admissionOf("notes?select=id,name&user_id=eq.user-1")

        // Then
        assertNotNull(result)
        assertTrue(result.isExhaustive)
        assertEquals(
            listOf(FilterDescriptor.Condition("user_id", "eq", "user-1")),
            result.descriptor?.conditions,
        )
    }

    @Test
    fun embedded_select_with_parens_does_not_break_exhaustiveness() {
        // Given / When — an embedded resource projection `projects(name)` lives under the `select`
        // key, which is handled before the paren check, so it must not flip exhaustiveness
        val result = admissionOf("notes?select=id,projects(name)&user_id=eq.user-1")

        // Then
        assertNotNull(result)
        assertTrue(result.isExhaustive)
    }

    // ── Windowing → non-exhaustive ────────────────────────────────────────────

    @Test
    fun range_suffix_marks_non_exhaustive_but_keeps_descriptor() {
        // Given / When
        val result = admissionOf("notes?user_id=eq.user-1|range=0-49")

        // Then — the filter is still recovered, but the positional window blocks exhaustiveness
        assertNotNull(result)
        assertFalse(result.isExhaustive)
        assertEquals(
            listOf(FilterDescriptor.Condition("user_id", "eq", "user-1")),
            result.descriptor?.conditions,
        )
    }

    @Test
    fun order_param_marks_non_exhaustive() {
        // Given / When
        val result = admissionOf("notes?user_id=eq.user-1&order=created_at.desc")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun limit_param_marks_non_exhaustive() {
        // Given / When
        val result = admissionOf("notes?user_id=eq.user-1&limit=10")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun offset_param_marks_non_exhaustive() {
        // Given / When — pagination offset is a positional window like limit/range
        val result = admissionOf("notes?user_id=eq.user-1&offset=20")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    // ── Unmodeled predicates → non-exhaustive ─────────────────────────────────

    @Test
    fun or_group_with_value_parens_marks_non_exhaustive() {
        // Given / When — a logical OR group we don't model offline
        val result = admissionOf("notes?or=(type.eq.OPEN,type.eq.DONE)&user_id=eq.user-1")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun and_group_with_key_parens_marks_non_exhaustive() {
        // Given / When — regardless of whether the paren lands in the key or the value, a logical
        // group must not be treated as exhaustive
        val result = admissionOf("notes?and(type.eq.OPEN,user_id.eq.user-1)=")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun dotted_foreign_key_marks_non_exhaustive() {
        // Given / When — a foreign-table filter `projects.brand` we don't model offline
        val result = admissionOf("notes?projects.brand=eq.nike&user_id=eq.user-1")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun not_negated_operator_marks_non_exhaustive() {
        // Given / When — `not.eq` negation; the `not` operator id is unrecognized
        val result = admissionOf("notes?type=not.eq.OPEN")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun unrecognized_operator_marks_non_exhaustive() {
        // Given / When — a made-up operator id
        val result = admissionOf("notes?type=bogusop.OPEN")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    // ── Recognized but not admit-evaluable → non-exhaustive ───────────────────
    // FilterEngine.matchesRow cannot faithfully reproduce these predicates offline: it answers
    // `false` for rows the server would return, which as an admit gate would *drop* those rows.
    // Marking the signature non-exhaustive falls back to over-applying, honoring the never-under-
    // apply invariant.

    @Test
    fun comparison_operator_on_timestamp_marks_non_exhaustive() {
        // Given / When — `gte` on an ISO timestamp: matchesRow parses both sides as Double, fails,
        // and returns false for every row, so an exhaustive descriptor would hide freshly-inserted
        // rows the server returns
        val result = admissionOf("notes?created_at=gte.2026-01-01T00:00:00.000Z")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun overlaps_operator_marks_non_exhaustive() {
        // Given / When — `ov` encodes as `{a,b}` (no parens to trip the group check) and matchesRow
        // returns false unconditionally for it
        val result = admissionOf("notes?tags=ov.{home,work}")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun full_text_search_operator_marks_non_exhaustive() {
        // Given / When — `fts` encodes as a bare word (no parens) and matchesRow returns false
        // unconditionally for it
        val result = admissionOf("notes?notes=fts.urgent")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun ilike_pattern_operator_marks_non_exhaustive() {
        // Given / When — matchesRow approximates `ilike` with a substring check that misses
        // interior-`%` matches, so it can drop rows the server returns
        val result = admissionOf("notes?name=ilike.%urgent%")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun is_operator_marks_non_exhaustive() {
        // Given / When — `is.null` matchesRow returns false for a row that simply omits the column,
        // which a freshly-inserted sparse row frequently does
        val result = admissionOf("notes?deleted_at=is.null")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    @Test
    fun eq_mixed_with_comparison_marks_non_exhaustive() {
        // Given / When — a faithful `eq` alongside a non-evaluable `gte`; the eq must not let the
        // signature claim exhaustiveness while the gte half is unmodeled
        val result = admissionOf("notes?user_id=eq.user-1&created_at=gte.2026-01-01")

        // Then
        assertNotNull(result)
        assertFalse(result.isExhaustive)
    }

    // ── Blank / unparseable → null ────────────────────────────────────────────

    @Test
    fun blank_signature_returns_null() {
        // Given / When / Then
        assertNull(admissionOf(""))
        assertNull(admissionOf("   "))
    }

    // ── admits() gate ─────────────────────────────────────────────────────────

    @Test
    fun exhaustive_filter_admits_matching_row_and_rejects_non_matching() {
        // Given — an exhaustive `type=eq.OPEN` query
        val filters = admissionOf("notes?type=eq.OPEN")
        assertNotNull(filters)

        // When / Then — a OPEN row belongs; a DONE row does not
        assertTrue(filters.admits(rowWithType("OPEN")))
        assertFalse(filters.admits(rowWithType("DONE")))
    }

    @Test
    fun non_exhaustive_filter_admits_everything() {
        // Given — a windowed query is non-exhaustive
        val filters = admissionOf("notes?type=eq.OPEN&limit=10")
        assertNotNull(filters)

        // When / Then — even a non-matching row is admitted (legacy always-apply behavior)
        assertTrue(filters.admits(rowWithType("DONE")))
    }

    @Test
    fun exhaustive_unfiltered_query_admits_everything() {
        // Given — an unfiltered SELECT * (exhaustive, null descriptor)
        val filters = admissionOf("notes")
        assertNotNull(filters)

        // When / Then
        assertTrue(filters.admits(rowWithType("DONE")))
    }

    @Test
    fun non_evaluable_operator_signature_admits_everything() {
        // Given — a full-text-search filter matchesRow cannot reproduce; it is non-exhaustive, so
        // the gate must over-apply rather than drop rows the server would return
        val filters = admissionOf("notes?notes=fts.urgent")
        assertNotNull(filters)

        // When / Then — every row is admitted, matching the legacy always-append behavior
        assertTrue(filters.admits(rowWithType("OPEN")))
        assertTrue(filters.admits(rowWithType("DONE")))
    }

    @Test
    fun exhaustive_filter_admits_row_missing_the_filtered_column() {
        // Given — an exhaustive `type=eq.OPEN` query
        val filters = admissionOf("notes?type=eq.OPEN")
        assertNotNull(filters)

        // When — a row that lacks the `type` column entirely. FilterEngine.matchesRow returns
        // false for an absent column, so the gate rejects it. This documents that behavior.
        val rowWithoutType: JsonObject = buildJsonObject { put("id", "x") }

        // Then
        assertFalse(filters.admits(rowWithoutType))
    }

    private fun rowWithType(type: String): JsonObject = buildJsonObject {
        put("id", "row-$type")
        put("type", type)
    }
}

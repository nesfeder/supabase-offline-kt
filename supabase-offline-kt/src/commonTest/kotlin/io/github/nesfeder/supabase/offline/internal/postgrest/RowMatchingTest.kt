// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * The predicate engine: what it can tell about one row against one condition. It evaluates `eq`,
 * `neq`, `in` and `is` with Postgres null semantics, and answers unknown for everything else and
 * for any value some column type could read differently.
 */
class RowMatchingTest {

    private fun match(operator: String, value: JsonElement, literal: String): RowMatch =
        rowMatch(
            buildJsonObject { put("c", value) },
            FilterDescriptor.Condition("c", operator, literal),
        )

    private fun text(value: String) = JsonPrimitive(value)

    @Test
    fun eq_compares_text_numbers_and_booleans_by_value() {
        assertEquals(RowMatch.MATCHES, match("eq", text("open"), "open"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("open"), "done"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(10), "10"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(10.0), "1e1"))
        assertEquals(RowMatch.EXCLUDED, match("eq", JsonPrimitive(10), "10.5"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(-0.0), "0"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(true), "true"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(true), "t"))
        assertEquals(RowMatch.EXCLUDED, match("eq", JsonPrimitive(false), "yes"))
    }

    @Test
    fun a_literal_the_column_type_cannot_read_is_unknown() {
        assertEquals(RowMatch.UNKNOWN, match("eq", JsonPrimitive(10), "ten"))
        assertEquals(RowMatch.UNKNOWN, match("eq", JsonPrimitive(true), "o"))
    }

    @Test
    fun text_that_some_type_could_read_as_equal_is_unknown() {
        assertEquals(RowMatch.UNKNOWN, match("eq", text("Open"), "open"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("abc  "), "abc"))
        assertEquals(
            RowMatch.UNKNOWN,
            match("eq", text("2026-01-01T10:00:00+00:00"), "2026-01-01T10:00:00Z"),
        )
        assertEquals(RowMatch.UNKNOWN, match("eq", text("2026-01-01"), "today"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("1 day"), "24:00:00"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("café"), "cafe"))
        assertEquals(
            RowMatch.UNKNOWN,
            match(
                "eq",
                text("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"),
                "{3F1C2D4E5A6B4C7D8E9F0A1B2C3D4E5F}",
            ),
        )
        assertEquals(RowMatch.UNKNOWN, match("eq", text("P1D"), "PT24H"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("ab:cd:ef:01:02:03"), "ab-cd-ef-01-02-03"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("infinity"), "2026-01-01"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("cafe"), "café"))
    }

    @Test
    fun text_that_no_type_reads_as_equal_is_excluded() {
        assertEquals(RowMatch.EXCLUDED, match("eq", text("in_progress"), "done"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("008"), "7"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("2026-01-01"), "2026-01-02"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text(""), "a"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("a"), "a,b"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("Groceries"), "Home Office"))
        assertEquals(
            RowMatch.EXCLUDED,
            match(
                "eq",
                text("3f1c2d4e-5a6b-4c7d-8e9f-0a1b2c3d4e5f"),
                "00000000-5a6b-4c7d-8e9f-0a1b2c3d4e5f",
            ),
        )
    }

    @Test
    fun a_null_column_is_excluded_by_eq_neq_and_in() {
        assertEquals(RowMatch.EXCLUDED, match("eq", JsonNull, "a"))
        assertEquals(RowMatch.EXCLUDED, match("neq", JsonNull, "a"))
        assertEquals(RowMatch.EXCLUDED, match("in", JsonNull, "(a,b)"))
        assertEquals(RowMatch.EXCLUDED, match("in", JsonNull, "(a,null)"))
    }

    @Test
    fun neq_is_the_opposite_of_eq_except_for_null_and_unknown() {
        assertEquals(RowMatch.MATCHES, match("neq", text("open"), "done"))
        assertEquals(RowMatch.EXCLUDED, match("neq", text("open"), "open"))
        assertEquals(RowMatch.UNKNOWN, match("neq", text("Open"), "open"))
    }

    @Test
    fun in_matches_a_member_and_is_unknown_when_a_member_is_unknown() {
        assertEquals(RowMatch.MATCHES, match("in", text("b"), "(a,b,c)"))
        assertEquals(RowMatch.EXCLUDED, match("in", text("d"), "(a,b,c)"))
        assertEquals(RowMatch.UNKNOWN, match("in", text("B"), "(a,b,c)"))
        assertEquals(RowMatch.EXCLUDED, match("in", text("a"), "()"))
        assertEquals(RowMatch.UNKNOWN, match("in", text("null"), "(a,null)"))
        assertEquals(RowMatch.EXCLUDED, match("in", JsonPrimitive(3), "(1,2,null)"))
    }

    @Test
    fun is_reads_null_not_null_true_false_and_unknown() {
        assertEquals(RowMatch.MATCHES, match("is", JsonNull, "null"))
        assertEquals(RowMatch.EXCLUDED, match("is", text("x"), "null"))
        assertEquals(RowMatch.MATCHES, match("is", text("x"), "not_null"))
        assertEquals(RowMatch.EXCLUDED, match("is", JsonNull, "not_null"))
        assertEquals(RowMatch.MATCHES, match("is", JsonPrimitive(true), "true"))
        assertEquals(RowMatch.EXCLUDED, match("is", JsonNull, "true"))
        assertEquals(RowMatch.MATCHES, match("is", JsonPrimitive(false), "false"))
        assertEquals(RowMatch.MATCHES, match("is", JsonNull, "unknown"))
        assertEquals(RowMatch.EXCLUDED, match("is", JsonPrimitive(true), "unknown"))
        assertEquals(RowMatch.UNKNOWN, match("is", text("true"), "true"))
    }

    @Test
    fun every_other_operator_is_unknown() {
        listOf(
                "gt",
                "gte",
                "lt",
                "lte",
                "like",
                "ilike",
                "match",
                "imatch",
                "cs",
                "cd",
                "ov",
                "sl",
                "sr",
                "nxl",
                "nxr",
                "adj",
                "fts",
                "plfts",
                "phfts",
                "wfts",
                "isdistinct",
                "not",
            )
            .forEach { operator ->
                assertEquals(RowMatch.UNKNOWN, match(operator, text("a"), "a"), operator)
            }
    }

    @Test
    fun a_missing_column_an_array_and_an_object_are_unknown() {
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(buildJsonObject {}, FilterDescriptor.Condition("c", "eq", "a")),
        )
        assertEquals(RowMatch.UNKNOWN, match("eq", buildJsonArray { add(text("a")) }, "a"))
        assertEquals(RowMatch.UNKNOWN, match("in", buildJsonObject { put("k", text("a")) }, "(a)"))
    }

    @Test
    fun a_referenced_table_column_a_json_path_and_a_cast_are_unknown() {
        val row = buildJsonObject {
            put("lists.name", text("Home"))
            put("data->>kind", text("idea"))
            put("created_at::date", text("2026-01-01"))
        }
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(row, FilterDescriptor.Condition("lists.name", "eq", "Other")),
        )
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(row, FilterDescriptor.Condition("data->>kind", "eq", "other")),
        )
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(row, FilterDescriptor.Condition("created_at::date", "eq", "2026-01-02")),
        )
    }

    @Test
    fun conditions_combine_with_sql_and_and_or() {
        val row = buildJsonObject {
            put("a", text("x"))
            put("b", text("y"))
        }
        val excluded = FilterDescriptor.Condition("a", "eq", "z")
        val unknown = FilterDescriptor.Condition("b", "like", "y")
        val matched = FilterDescriptor.Condition("a", "eq", "x")
        assertEquals(
            RowMatch.EXCLUDED,
            rowMatch(row, FilterDescriptor(FilterDescriptor.Mode.AND, listOf(unknown, excluded))),
        )
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(row, FilterDescriptor(FilterDescriptor.Mode.AND, listOf(unknown, matched))),
        )
        assertEquals(
            RowMatch.MATCHES,
            rowMatch(row, FilterDescriptor(FilterDescriptor.Mode.OR, listOf(unknown, matched))),
        )
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(row, FilterDescriptor(FilterDescriptor.Mode.OR, listOf(unknown, excluded))),
        )
    }
}

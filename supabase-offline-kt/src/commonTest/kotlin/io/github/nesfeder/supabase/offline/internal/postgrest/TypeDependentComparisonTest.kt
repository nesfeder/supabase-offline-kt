// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.MutationOverlay
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A comparison whose answer depends on the column's type is unknown: the same JSON value compares
 * equal to a literal under one type Postgres could store it as and unequal under another. Unknown
 * admits a row to a list, and never lets a pending UPDATE or DELETE touch it.
 */
class TypeDependentComparisonTest {

    private fun match(operator: String, value: JsonElement, literal: String): RowMatch =
        rowMatch(
            buildJsonObject { put("c", value) },
            FilterDescriptor.Condition("c", operator, literal),
        )

    private fun text(value: String) = JsonPrimitive(value)

    @Test
    fun digits_equal_but_for_leading_zeros_are_unknown() {
        // An integer written as text matches; text does not
        assertEquals(RowMatch.UNKNOWN, match("eq", text("007"), "7"))
        assertEquals(RowMatch.UNKNOWN, match("neq", text("007"), "7"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("008"), "7"))
    }

    @Test
    fun numbers_equal_as_a_double_or_a_float_are_unknown() {
        // float8 or real match; numeric does not
        assertEquals(
            RowMatch.UNKNOWN,
            match("eq", JsonPrimitive(0.1), "0.1000000000000000055511151231257827"),
        )
        assertEquals(RowMatch.UNKNOWN, match("eq", JsonPrimitive(0.30000001192092896), "0.3"))
        assertEquals(RowMatch.EXCLUDED, match("eq", JsonPrimitive(0.5), "0.25"))
        assertEquals(RowMatch.MATCHES, match("eq", JsonPrimitive(0.5), "0.50"))
    }

    @Test
    fun boolean_words_written_as_text_are_unknown_when_they_mean_the_same() {
        // A boolean written as text matches; text does not
        assertEquals(RowMatch.UNKNOWN, match("eq", text("true"), "t"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("yes"), "true"))
        assertEquals(RowMatch.UNKNOWN, match("neq", text("yes"), "true"))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("true"), "false"))
    }

    @Test
    fun a_quoted_literal_equal_to_a_text_value_is_unknown() {
        // A jsonb string matches the quoted literal; text does not
        assertEquals(RowMatch.UNKNOWN, match("eq", text("abc"), "\"abc\""))
        assertEquals(RowMatch.EXCLUDED, match("eq", text("abd"), "\"abc\""))
    }

    @Test
    fun inf_is_a_float_input_not_text() {
        assertEquals(RowMatch.UNKNOWN, match("eq", text("inf"), "Infinity"))
        assertEquals(RowMatch.UNKNOWN, match("eq", text("-inf"), "-Infinity"))
    }

    @Test
    fun mac_addresses_written_with_other_separators_are_unknown() {
        assertEquals(
            RowMatch.UNKNOWN,
            match("eq", text("ab-cd-ef-01-02-03"), "ab:cd:ef:01:02:03"),
        )
        assertEquals(
            RowMatch.EXCLUDED,
            match("eq", text("ab-cd-ef-01-02-03"), "ab:cd:ef:01:02:04"),
        )
    }

    @Test
    fun a_pending_update_or_delete_never_touches_a_row_it_cannot_tell() {
        // Given — a row whose code an integer column may hold as 7
        val rows =
            listOf<JsonElement>(
                buildJsonObject {
                    put("id", text("r1"))
                    put("code", text("007"))
                }
            )
        val overlay =
            MutationOverlay(
                json = Json,
                admission = QueryAdmission.EVERY_ROW,
                primaryKeys = DefaultPrimaryKeys,
                mayAlreadyHold = { false },
            )
        val byCode =
            encodeFilter(
                FilterDescriptor(
                    conditions = listOf(FilterDescriptor.Condition("code", "eq", "7"))
                ),
                Json,
            )

        // When
        val updated =
            overlay.overlayRows(
                rows,
                listOf(mutation(MutationOperation.UPDATE, """{"done":true}""", byCode)),
            )
        val deleted =
            overlay.overlayRows(rows, listOf(mutation(MutationOperation.DELETE, "{}", byCode)))

        // Then — untouched
        assertEquals(rows, updated.visible)
        assertEquals(rows, deleted.visible)
    }

    private fun mutation(operation: MutationOperation, body: String, filter: String) =
        MutationRow(
            table = "codes",
            operation = operation.storedName,
            bodyJson = body,
            filterJson = filter,
            createdAtMs = 0L,
            syncedAtMs = null,
        )
}

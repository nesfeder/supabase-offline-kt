// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A patch finds the rows an UPDATE naming one key reaches by that key only when `eq` on it is
 * decided by content: equal content (one UUID in any case) matches, anything else is excluded. The
 * gate is checked here against [rowMatch] itself, over values of every shape `eq` reads otherwise.
 */
class EqualityDecidedByContentTest {

    @Test
    fun where_the_gate_passes_eq_matches_exactly_the_values_written_alike() {
        // Given / When / Then — over random sets of values, every pair where the gate passes
        var passed = 0
        val mismatches = mutableListOf<String>()
        repeat(SETS) { seed ->
            val random = Random(seed)
            val family = random.nextInt(3)
            val values = List(2 + random.nextInt(4)) { random.value(family) }
            if (!equalityDecidedByContent(values.asSequence(), asUuid = true)) return@repeat
            passed++
            for (row in values) for (literal in values) {
                val content = (literal as JsonPrimitive).content
                val match =
                    rowMatch(
                        JsonObject(mapOf(KEY to row!!)),
                        FilterDescriptor(
                            conditions = listOf(FilterDescriptor.Condition(KEY, "eq", content))
                        ),
                        uuidColumns = setOf(KEY),
                    )
                val alike =
                    keyOf(JsonObject(mapOf(KEY to row)), listOf(KEY)) ==
                        keyOf(JsonObject(mapOf(KEY to JsonPrimitive(content))), listOf(KEY))
                val expected = if (alike) RowMatch.MATCHES else RowMatch.EXCLUDED
                if (match != expected) mismatches += "$row eq $content: $match, not $expected"
            }
        }
        assertEquals(emptyList(), mismatches.take(3))
        assertTrue(passed > SETS / 10, "the gate passed too few sets: $passed")
    }

    @Test
    fun values_eq_may_judge_unknown_do_not_pass() {
        // Given — pairs `eq` reads as unknown, or values of more than one kind
        val refused =
            listOf(
                listOf(JsonPrimitive("Walk"), JsonPrimitive("walk")),
                listOf(JsonPrimitive("5"), JsonPrimitive("05")),
                listOf(JsonPrimitive(5), JsonPrimitive("walk")),
                listOf(JsonPrimitive(5.5), JsonPrimitive(5)),
                listOf(JsonPrimitive("yes"), JsonPrimitive("no")),
                listOf(JsonPrimitive("aabbccddeeff"), JsonPrimitive("walk")),
                listOf(JsonPrimitive("walk "), JsonPrimitive("run")),
                listOf(JsonPrimitive(true), JsonPrimitive(false)),
                listOf(JsonNull, JsonPrimitive(1)),
                listOf(null, JsonPrimitive(1)),
                listOf(JsonPrimitive(-1), JsonPrimitive(1)),
                listOf(JsonPrimitive(UUID), JsonPrimitive(1)),
            )

        // When / Then
        refused.forEach { values ->
            assertFalse(equalityDecidedByContent(values.asSequence(), asUuid = true), "$values")
        }
        assertFalse(
            equalityDecidedByContent(sequenceOf(JsonPrimitive(UUID)), asUuid = false),
            "a UUID in a column not read as uuid",
        )
    }

    /**
     * A value of one of the shapes `eq` reads, mostly of one family per set — whole numbers, UUIDs
     * or text — now and then of a shape `eq` may judge unknown, or of another family.
     */
    private fun Random.value(family: Int): JsonElement? {
        if (nextInt(8) == 0) return oddValue()
        return when (family) {
            0 -> if (nextBoolean()) JsonPrimitive(nextInt(4)) else JsonPrimitive("${nextInt(4)}")
            1 ->
                JsonPrimitive(
                    when (nextInt(3)) {
                        0 -> if (nextBoolean()) UUID else OTHER_UUID
                        1 -> UUID.uppercase()
                        else -> "{$OTHER_UUID}"
                    }
                )
            else -> JsonPrimitive("item-${nextInt(4)}")
        }
    }

    /** A value `eq` may judge unknown against a value of its family, or one of another family. */
    private fun Random.oddValue(): JsonElement? =
        when (nextInt(11)) {
            0 -> JsonPrimitive("0${nextInt(4)}")
            1 -> JsonPrimitive(nextInt(4) + 0.5)
            2 -> JsonPrimitive("${nextInt(4)}e0")
            3 -> JsonPrimitive("Item-${nextInt(4)}")
            4 -> JsonPrimitive(listOf("yes", "no", "on", "t", "tr").random(this))
            5 -> JsonPrimitive(listOf("aa:bb:cc:dd:ee:ff", "aabbccddeeff", "today").random(this))
            6 -> JsonPrimitive("item-${nextInt(4)} ")
            7 -> JsonPrimitive(nextBoolean())
            8 -> if (nextBoolean()) JsonNull else null
            9 -> JsonPrimitive(-1 - nextInt(3))
            else -> JsonPrimitive(if (nextBoolean()) UUID else "item-${nextInt(4)}")
        }

    private companion object {
        const val KEY = "id"
        const val SETS = 20_000
        const val UUID = "8f14e45f-ceea-467f-a0e5-9ab1c2d3e4f5"
        const val OTHER_UUID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    }
}

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The delta rebase writes a value in the form the caller wrote it. An integer column the caller
 * wrote as an integer gets an integer back — `13`, never `13.0`, which Postgres refuses for an
 * `int` column (`22P02`), and which the queue would then have dropped as a permanent failure.
 */
class NumericDeltaRebaseIntegralTest {

    @Test
    fun an_integral_rebase_writes_an_integer() {
        // Given — snapshot 10, the user made it 11 (+1), another device moved the server to 12
        val body = json("""{"likes":11}""")

        // When
        val rebased = rebaseNumericDeltas(
            body = body,
            serverRow = json("""{"id":1,"likes":12}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "likes", snapshotValue = 10.0)),
        )

        // Then
        assertEquals("""{"likes":13}""", rebased.toString())
    }

    @Test
    fun an_integral_rebase_against_a_server_value_written_as_a_whole_decimal_stays_an_integer() {
        // Given — PostgREST can render a whole number with a trailing `.0`
        val body = json("""{"likes":11}""")

        // When
        val rebased = rebaseNumericDeltas(
            body = body,
            serverRow = json("""{"likes":12.0}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "likes", snapshotValue = 10.0)),
        )

        // Then
        assertEquals("""{"likes":13}""", rebased.toString())
    }

    @Test
    fun an_integral_rebase_clamped_to_its_floor_stays_an_integer() {
        // Given — a downward intent that would carry the column below zero
        val body = json("""{"likes":0}""")

        // When
        val rebased = rebaseNumericDeltas(
            body = body,
            serverRow = json("""{"likes":2}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "likes", snapshotValue = 5.0, minValue = 0.0)),
        )

        // Then
        assertEquals("""{"likes":0}""", rebased.toString())
    }

    @Test
    fun an_integral_rebase_keeps_precision_beyond_two_to_the_fifty_third() {
        // Given — a bigint counter past the range a double represents exactly
        val body = json("""{"total":9007199254740995}""")

        // When
        val rebased = rebaseNumericDeltas(
            body = body,
            serverRow = json("""{"total":9007199254740997}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "total", snapshotValue = 9007199254740994.0)),
        )

        // Then — 9007199254740997 + (9007199254740995 - 9007199254740994) = 9007199254740998
        assertEquals("""{"total":9007199254740998}""", rebased.toString())
    }

    @Test
    fun a_decimal_the_caller_wrote_stays_a_decimal() {
        // Given — the caller wrote a decimal value
        val body = json("""{"minutes":9000.5}""")

        // When
        val rebased = rebaseNumericDeltas(
            body = body,
            serverRow = json("""{"minutes":8500}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "minutes", snapshotValue = 8000.0)),
        )

        // Then
        assertEquals("""{"minutes":9500.5}""", rebased.toString())
    }

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A mutation that fails part-way through the overlay, after it has changed rows, is taken back
 * whole: what is shown, held aside and written is exactly what overlaying the other mutations alone
 * gives, and the next mutation still applies.
 */
class OverlayUndoTest {

    /** Counts reads of trapped rows; the read that brings [remaining] to zero throws. */
    private class Trap {
        var remaining: Int = Int.MAX_VALUE
    }

    /** A row whose every column read counts against [trap]. */
    private class TrappedColumns(
        private val columns: Map<String, JsonElement>,
        private val trap: Trap,
    ) : Map<String, JsonElement> by columns {
        override fun get(key: String): JsonElement? {
            trap.remaining--
            check(trap.remaining != 0) { "a read of a trapped row failed" }
            return columns[key]
        }
    }

    /** What an overlay left, encoded so trapped and plain rows compare by content. */
    private data class Outcome(
        val visible: List<String>,
        val held: List<String>,
        val written: List<String>,
    )

    private fun OverlaidRows.outcome() =
        Outcome(
            visible = visible.map { row -> row.toString() },
            held = held.map { row -> row.toString() },
            written = written.map { row -> row.toString() },
        )

    /**
     * Overlays [mutations] onto [rows], returning what it left and the ids of those that failed.
     */
    private fun overlay(
        rows: List<JsonElement>,
        known: List<JsonObject>,
        mutations: List<MutationRow>,
        admission: QueryAdmission,
        mayAlreadyHold: Set<Long>,
    ): Pair<Outcome, List<Long>> {
        val failed = mutableListOf<Long>()
        val overlay =
            MutationOverlay(
                json = OfflineJson,
                admission = admission,
                primaryKeys = DefaultPrimaryKeys,
                mayAlreadyHold = { mutation -> mutation.id in mayAlreadyHold },
                onMutationFailure = { mutation, _ -> failed += mutation.id },
            )
        return overlay.overlayRows(rows, mutations, known).outcome() to failed
    }

    private fun row(id: Int, list: String) =
        JsonObject(mapOf("id" to JsonPrimitive(id), "list" to JsonPrimitive(list)))

    private fun byColumn(column: String, operator: String, value: String): String =
        FilterEngine.encode(
            FilterDescriptor(
                conditions = listOf(FilterDescriptor.Condition(column, operator, value))
            ),
            OfflineJson,
        )

    @Test
    fun an_insert_failing_on_its_third_row_leaves_nothing_and_the_next_mutation_applies() {
        // Given — two shown rows, one trapped; an insert the server may hold already reads every
        // shown row's key for each of its rows, so the trapped row's third read fails after two
        // rows are placed
        val trap = Trap()
        val trapped = JsonObject(TrappedColumns(row(1, "A"), trap))
        val rows = listOf<JsonElement>(trapped, row(2, "A"))
        val insert =
            MutationRow(
                id = 1,
                table = TABLE,
                operation = MutationOperation.INSERT,
                bodyJson = """[{"id":3,"list":"A"},{"id":4,"list":"A"},{"id":5,"list":"A"}]""",
                filterJson = null,
                createdAtMs = 1,
            )
        val update =
            MutationRow(
                id = 2,
                table = TABLE,
                operation = MutationOperation.UPDATE,
                bodyJson = """{"list":"B"}""",
                filterJson = byColumn("id", "eq", "2"),
                createdAtMs = 2,
            )
        val listA = QueryAdmission(listOf(FilterDescriptor.Condition("list", "eq", "A")))

        // When
        trap.remaining = 3
        val (outcome, failed) = overlay(rows, emptyList(), listOf(insert, update), listA, setOf(1L))

        // Then — the insert left nothing, and the update applied as it does alone
        val (alone, _) =
            overlay(listOf(row(1, "A"), row(2, "A")), emptyList(), listOf(update), listA, setOf(1L))
        assertEquals(listOf(1L), failed)
        assertEquals(alone, outcome)
        assertEquals(listOf("""{"id":1,"list":"A"}"""), outcome.visible)
        assertEquals(listOf("""{"id":2,"list":"B"}"""), outcome.held)
    }

    @Test
    fun any_mutation_failing_part_way_leaves_what_the_others_alone_give() {
        // Given — random rows, rows known from elsewhere and mutations, a trap at every read in
        // turn
        var failures = 0
        repeat(SCENARIOS) { seed ->
            val random = Random(seed)
            val trap = Trap()
            val plain = List(random.nextInt(0, 6)) { row(random.nextInt(1, 7), random.list()) }
            val knownPlain = List(random.nextInt(0, 4)) { row(random.nextInt(1, 7), random.list()) }
            val mutations = List(random.nextInt(1, 5)) { index -> random.mutation(index + 1L) }
            val mayAlreadyHold = mutations.filter { random.nextBoolean() }.map { it.id }.toSet()
            val admission = QueryAdmission(listOf(FilterDescriptor.Condition("list", "eq", "A")))
            for (trapAt in 1..TRAP_POSITIONS) {
                trap.remaining = trapAt
                val rows = plain.map { row -> JsonObject(TrappedColumns(row, trap)) }
                val known = knownPlain.map { row -> JsonObject(TrappedColumns(row, trap)) }

                // When
                val (outcome, failed) = overlay(rows, known, mutations, admission, mayAlreadyHold)

                // Then — as the mutations that did not fail give, overlaid without a trap
                val (expected, _) =
                    overlay(
                        plain,
                        knownPlain,
                        mutations.filter { it.id !in failed },
                        admission,
                        mayAlreadyHold,
                    )
                assertEquals(expected, outcome, "seed=$seed trapAt=$trapAt failed=$failed")
                failures += failed.size
            }
        }
        assertTrue(failures > 0, "no mutation failed part-way; the trap never fired")
    }

    private fun Random.list(): String = if (nextBoolean()) "A" else "B"

    private fun Random.mutation(id: Long): MutationRow =
        when (nextInt(4)) {
            0 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.INSERT,
                    bodyJson =
                        JsonArray(List(nextInt(1, 4)) { row(nextInt(1, 7), list()) }).toString(),
                    filterJson = null,
                    createdAtMs = id,
                )
            1 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.UPSERT,
                    bodyJson =
                        JsonArray(List(nextInt(1, 4)) { row(nextInt(1, 7), list()) }).toString(),
                    filterJson = null,
                    createdAtMs = id,
                )
            2 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.UPDATE,
                    bodyJson = """{"list":"${list()}"}""",
                    filterJson =
                        if (nextBoolean()) byColumn("id", "eq", nextInt(1, 7).toString())
                        else byColumn("list", "neq", list()),
                    createdAtMs = id,
                )
            else ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.DELETE,
                    bodyJson = "{}",
                    filterJson = byColumn("id", "eq", nextInt(1, 7).toString()),
                    createdAtMs = id,
                )
        }

    private companion object {
        const val TABLE = "rows"
        const val SCENARIOS = 300
        const val TRAP_POSITIONS = 25
    }
}

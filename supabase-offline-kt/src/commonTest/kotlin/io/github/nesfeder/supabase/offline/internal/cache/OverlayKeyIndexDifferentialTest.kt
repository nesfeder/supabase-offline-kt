// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The overlay finds a shown row by key through an index kept beside rows that never move; this
 * compares it, over random rows and mutations, with the same rules applied by scanning the rows in
 * order. Keys are written as a `uuid` column reads them (case, braces, hyphens), as numbers and as
 * text; some rows lack a key, and some elements are not rows at all.
 */
class OverlayKeyIndexDifferentialTest {

    @Test
    fun the_indexed_overlay_shows_what_scanning_the_rows_shows() {
        // Given / When / Then — every scenario, and enough of them that each kind of lookup ran
        var indexedLookups = 0
        repeat(SCENARIOS) { seed ->
            val random = Random(seed)
            val rows = List(random.nextInt(0, 12)) { random.element() }
            val known = List(random.nextInt(0, 4)) { random.row() }
            val mutations = List(random.nextInt(1, 8)) { index -> random.mutation(index + 1L) }
            val mayAlreadyHold = mutations.filter { random.nextBoolean() }.map { it.id }.toSet()
            val placesKeyless = random.nextBoolean()
            val admission = QueryAdmission(listOf(FilterDescriptor.Condition("list", "eq", "A")))
            val indexed =
                MutationOverlay(
                        json = OfflineJson,
                        admission = admission,
                        primaryKeys = DefaultPrimaryKeys,
                        mayAlreadyHold = { mutation -> mutation.id in mayAlreadyHold },
                        placesKeylessUpsertRows = placesKeyless,
                    )
                    .overlayRows(rows, mutations, known)
            val scanned =
                ScanningOverlay(admission, placesKeyless) { it.id in mayAlreadyHold }
                    .overlay(rows, known, mutations)
            assertEquals(scanned.text(), indexed.text(), "seed=$seed")
            indexedLookups += mutations.count { mutation ->
                mutation.operation == MutationOperation.UPSERT || mutation.id in mayAlreadyHold
            }
        }
        assertTrue(indexedLookups > SCENARIOS, "too few lookups by key: $indexedLookups")
    }

    private fun OverlaidRows.text(): List<List<String>> =
        listOf(
            visible.map { it.toString() },
            held.map { it.toString() },
            written.map { it.toString() },
        )

    /** The overlay's rules applied by scanning the rows in order, with no index: the oracle. */
    private class ScanningOverlay(
        private val admission: QueryAdmission,
        private val placesKeyless: Boolean,
        private val mayAlreadyHold: (MutationRow) -> Boolean,
    ) {
        private val visible = mutableListOf<JsonElement>()
        private val held = mutableListOf<JsonObject>()
        private val written = mutableListOf<JsonObject>()

        fun overlay(
            rows: List<JsonElement>,
            known: List<JsonObject>,
            mutations: List<MutationRow>,
        ): OverlaidRows {
            visible += rows
            held += known
            mutations.forEach { mutation ->
                when (mutation.operation) {
                    MutationOperation.INSERT -> insert(mutation)
                    MutationOperation.UPSERT -> upsert(mutation)
                    MutationOperation.UPDATE -> update(mutation)
                    MutationOperation.DELETE -> delete(mutation)
                }
            }
            return OverlaidRows(visible, held, written)
        }

        private fun bodyOf(mutation: MutationRow): List<JsonElement> =
            OfflineJson.parseToJsonElement(mutation.bodyJson) as JsonArray

        private fun insert(mutation: MutationRow) {
            val keyColumns = DefaultPrimaryKeys.of(mutation.table)
            bodyOf(mutation).forEach { element ->
                val row = element as? JsonObject ?: return@forEach visible.plusAssign(element)
                if (mayAlreadyHold(mutation) && indexOfKey(visible, row, keyColumns) != null)
                    return@forEach
                place(row)
            }
        }

        private fun upsert(mutation: MutationRow) {
            val keyColumns = conflictColumnsOf(mutation, DefaultPrimaryKeys)
            val replaces = mutation.ignoreDuplicates != true
            bodyOf(mutation).forEach { element ->
                val row = element as? JsonObject ?: return@forEach visible.plusAssign(element)
                if (primaryKeyOf(row, keyColumns) == null) {
                    if (placesKeyless) place(row)
                    return@forEach
                }
                val shown = indexOfKey(visible, row, keyColumns)
                val kept = if (shown == null) indexOfKey(held, row, keyColumns) else null
                when {
                    shown != null ->
                        if (replaces)
                            replaceShown(shown, JsonObject(visible[shown] as JsonObject + row))
                    kept != null -> if (replaces) replaceHeld(kept, JsonObject(held[kept] + row))
                    else -> place(row)
                }
            }
        }

        private fun update(mutation: MutationRow) {
            val filter = RowFilter(filterOf(mutation), setOf("id"))
            val patch = OfflineJson.parseToJsonElement(mutation.bodyJson) as JsonObject
            if (!filter.canMatch) return
            val heldBefore = held.size
            visible.indices.reversed().forEach { index ->
                val row = visible[index] as? JsonObject ?: return@forEach
                if (filter.match(row) == RowMatch.MATCHES)
                    replaceShown(index, JsonObject(row + patch))
            }
            (0 until heldBefore).reversed().forEach { index ->
                val row = held[index]
                if (filter.match(row) == RowMatch.MATCHES)
                    replaceHeld(index, JsonObject(row + patch))
            }
        }

        private fun delete(mutation: MutationRow) {
            val filter = RowFilter(filterOf(mutation), setOf("id"))
            if (!filter.canMatch) return
            visible.indices.reversed().forEach { index ->
                val row = visible[index]
                if (row is JsonObject && filter.match(row) == RowMatch.MATCHES)
                    visible.removeAt(index)
            }
            held.indices.reversed().forEach { index ->
                if (filter.match(held[index]) == RowMatch.MATCHES) held.removeAt(index)
            }
        }

        private fun filterOf(mutation: MutationRow): FilterDescriptor =
            checkNotNull(FilterEngine.decodeOrNull(mutation.filterJson, OfflineJson))

        private fun place(row: JsonObject) {
            written += row
            if (admission.admits(row)) visible += row else held += row
        }

        private fun replaceShown(index: Int, row: JsonObject) {
            written += row
            if (admission.admits(row)) {
                visible[index] = row
            } else {
                visible.removeAt(index)
                held += row
            }
        }

        private fun replaceHeld(index: Int, row: JsonObject) {
            written += row
            if (!admission.admits(held[index]) && admission.admits(row)) {
                held.removeAt(index)
                visible += row
            } else {
                held[index] = row
            }
        }
    }

    /** A key as written in many ways: one UUID in several spellings, numbers, text, or none. */
    private fun Random.key(): JsonPrimitive? =
        when (nextInt(7)) {
            0 -> JsonPrimitive(UUID)
            1 -> JsonPrimitive(UUID.uppercase())
            2 -> JsonPrimitive("{" + UUID.replace("-", "") + "}")
            3 -> JsonPrimitive(nextInt(1, 4))
            4 -> JsonPrimitive("k${nextInt(1, 4)}")
            5 -> JsonPrimitive(OTHER_UUID)
            else -> null
        }

    private fun Random.row(): JsonObject {
        val columns = mutableMapOf<String, JsonElement>("list" to JsonPrimitive(list()))
        key()?.let { key -> columns["id"] = key }
        if (nextBoolean()) columns["code"] = JsonPrimitive("c${nextInt(1, 3)}")
        return JsonObject(columns)
    }

    private fun Random.element(): JsonElement =
        when (nextInt(12)) {
            0 -> JsonNull
            1 -> JsonPrimitive("not a row")
            else -> row()
        }

    private fun Random.list(): String = if (nextBoolean()) "A" else "B"

    private fun Random.mutation(id: Long): MutationRow =
        when (nextInt(4)) {
            0 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.INSERT,
                    bodyJson = JsonArray(List(nextInt(1, 4)) { row() }).toString(),
                    filterJson = null,
                    createdAtMs = id,
                )
            1 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.UPSERT,
                    bodyJson = JsonArray(List(nextInt(1, 4)) { row() }).toString(),
                    filterJson = null,
                    createdAtMs = id,
                    onConflict = if (nextInt(3) == 0) "code" else null,
                    ignoreDuplicates = nextInt(4) == 0,
                )
            2 ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.UPDATE,
                    bodyJson = """{"list":"${list()}"}""",
                    filterJson =
                        if (nextBoolean()) byColumn("id", "eq", UUID.uppercase())
                        else byColumn("list", "neq", list()),
                    createdAtMs = id,
                )
            else ->
                MutationRow(
                    id = id,
                    table = TABLE,
                    operation = MutationOperation.DELETE,
                    bodyJson = "{}",
                    filterJson = byColumn("id", "eq", nextInt(1, 4).toString()),
                    createdAtMs = id,
                )
        }

    private fun byColumn(column: String, operator: String, value: String): String =
        FilterEngine.encode(
            FilterDescriptor(
                conditions = listOf(FilterDescriptor.Condition(column, operator, value))
            ),
            OfflineJson,
        )

    private companion object {
        const val TABLE = "notes"
        const val SCENARIOS = 2_000
        const val UUID = "8f14e45f-ceea-467f-a0e5-9ab1c2d3e4f5"
        const val OTHER_UUID = "1b4e28ba-2fa1-11d2-883f-0016d3cca427"
    }
}

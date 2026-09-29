// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Overlays queued mutations onto a cached query payload in memory, so a read shows writes the
 * server has not confirmed yet. A mutation that cannot be applied is skipped on its own and
 * reported.
 *
 * Rows go into the query only when [admission] admits them, and a row an UPDATE or UPSERT stops
 * matching leaves it. Rows the query does not show are held aside while the mutations run, so a
 * later one that makes such a row start matching brings it in once. An UPDATE or DELETE touches
 * only rows its filter provably matches ([rowMatch]); an UPSERT matches rows by its conflict
 * columns, or the key.
 *
 * @param admission which rows the cached query admits.
 * @param primaryKeys each table's key columns.
 * @param mayAlreadyHold whether an INSERT's rows may already be in the payload, which is then
 *   checked by key before a row is added: true for a mutation the server may already hold.
 * @param placesKeylessUpsertRows whether an UPSERT row without its conflict columns is added as a
 *   new row; without them nothing tells whether it replaces one.
 * @param onMutationFailure told of each skipped mutation, for logging; never its body.
 */
internal class MutationOverlay(
    private val json: Json,
    private val admission: QueryAdmission,
    private val primaryKeys: PrimaryKeys,
    private val mayAlreadyHold: (MutationRow) -> Boolean,
    private val placesKeylessUpsertRows: Boolean = true,
    private val onMutationFailure: (MutationRow, Exception) -> Unit = { _, _ -> },
) {

    /**
     * [mutations], oldest first, overlaid onto [payload]. [payload] comes back unchanged when it is
     * not a JSON array, and when there is nothing to overlay.
     */
    fun overlay(payload: String, mutations: List<MutationRow>): String {
        if (mutations.isEmpty()) return payload
        val rows = payloadRowsOf(json, payload) ?: return payload
        return encode(overlayRows(rows, mutations).visible)
    }

    /**
     * [mutations] overlaid onto [rows], in order; one that throws is undone, so it leaves nothing
     * behind.
     *
     * @param knownRows rows the query does not show that the engine knows from elsewhere, such as
     *   the table's other cached queries. A mutation brings one into the query only when it makes
     *   the row start matching: one the query admitted already is outside its window, not missing.
     */
    fun overlayRows(
        rows: List<JsonElement>,
        mutations: List<MutationRow>,
        knownRows: List<JsonObject> = emptyList(),
    ): OverlaidRows {
        val working = Working(OverlaidRows(visible = rows, held = knownRows, written = emptyList()))
        mutations.forEach { mutation ->
            try {
                working.overlay(mutation)
                working.keep()
            } catch (failure: Exception) {
                working.undo()
                onMutationFailure(mutation, failure)
            }
        }
        return working.snapshot()
    }

    /** [rows] as a payload. */
    fun encode(rows: List<JsonElement>): String =
        json.encodeToString(ListSerializer(JsonElement.serializer()), rows)

    /**
     * The rows as the mutations change them. Each change is journaled until [keep] or [undo], so a
     * mutation that fails half-way is taken back without copying every row for every mutation.
     */
    private inner class Working(start: OverlaidRows) {
        private val visible = start.visible.toMutableList()
        private val held = start.held.toMutableList()
        private val written = start.written.toMutableList()

        /** How to take back each change of the current mutation, in the order made. */
        private val journal = mutableListOf<() -> Unit>()

        fun snapshot(): OverlaidRows =
            OverlaidRows(visible = visible, held = held, written = written)

        /** Keeps the current mutation's changes. */
        fun keep() {
            journal.clear()
        }

        /** Takes back the current mutation's changes, newest first. */
        fun undo() {
            journal.asReversed().forEach { change -> change() }
            journal.clear()
        }

        /** Adds [element] at the end, to be removed on [undo]. */
        private fun <T> MutableList<T>.append(element: T) {
            add(element)
            journal += { removeAt(lastIndex) }
        }

        /** Sets [element] at [index], to be put back on [undo]. */
        private fun <T> MutableList<T>.replace(index: Int, element: T) {
            val old = set(index, element)
            journal += { set(index, old) }
        }

        /** Removes the element at [index], to be reinserted there on [undo]. */
        private fun <T> MutableList<T>.removeWithUndo(index: Int) {
            val old = removeAt(index)
            journal += { add(index, old) }
        }

        fun overlay(mutation: MutationRow) {
            when (mutation.operation) {
                MutationOperation.INSERT -> insert(mutation)
                MutationOperation.UPSERT -> upsert(mutation)
                MutationOperation.UPDATE -> update(mutation)
                MutationOperation.DELETE -> delete(mutation)
            }
        }

        private fun insert(mutation: MutationRow) {
            val keyColumns = primaryKeys.of(mutation.table)
            val dedupe = mayAlreadyHold(mutation)
            bodyRowsOf(mutation).forEach { element ->
                val row = element as? JsonObject
                if (row == null) {
                    visible.append(element)
                    return@forEach
                }
                if (dedupe && indexOfKey(visible, row, keyColumns) != null) return@forEach
                place(row)
            }
        }

        private fun upsert(mutation: MutationRow) {
            val keyColumns = conflictColumnsOf(mutation)
            val ignoreDuplicates = mutation.ignoreDuplicates == true
            bodyRowsOf(mutation).forEach { element ->
                val row = element as? JsonObject
                if (row == null) {
                    visible.append(element)
                    return@forEach
                }
                if (primaryKeyOf(row, keyColumns) == null) {
                    if (placesKeylessUpsertRows) place(row)
                    return@forEach
                }
                val shown = indexOfKey(visible, row, keyColumns)
                val kept = if (shown == null) indexOfKey(held, row, keyColumns) else null
                when {
                    shown != null ->
                        if (!ignoreDuplicates)
                            replaceShown(shown, merge(visible[shown] as JsonObject, row))
                    kept != null -> if (!ignoreDuplicates) replaceHeld(kept, merge(held[kept], row))
                    else -> place(row)
                }
            }
        }

        private fun update(mutation: MutationRow) {
            val filter = filterOf(mutation) ?: return
            val patch = json.parseToJsonElement(mutation.bodyJson) as? JsonObject ?: return
            // Rows the loop below moves aside land after these, and are not visited twice
            val heldBefore = held.size
            visible.indices.reversed().forEach { index ->
                val row = visible[index] as? JsonObject ?: return@forEach
                if (rowMatch(row, filter) == RowMatch.MATCHES)
                    replaceShown(index, merge(row, patch))
            }
            (0 until heldBefore).reversed().forEach { index ->
                val row = held[index]
                if (rowMatch(row, filter) == RowMatch.MATCHES) replaceHeld(index, merge(row, patch))
            }
        }

        private fun delete(mutation: MutationRow) {
            val filter = filterOf(mutation) ?: return
            visible.indices.reversed().forEach { index ->
                val row = visible[index]
                if (row is JsonObject && rowMatch(row, filter) == RowMatch.MATCHES)
                    visible.removeWithUndo(index)
            }
            held.indices.reversed().forEach { index ->
                if (rowMatch(held[index], filter) == RowMatch.MATCHES) held.removeWithUndo(index)
            }
        }

        /** Adds a row the mutation wrote: shown when the query admits it, held otherwise. */
        private fun place(row: JsonObject) {
            written.append(row)
            if (admission.admits(row)) visible.append(row) else held.append(row)
        }

        /**
         * Replaces the shown row at [index] with [row], moving it aside when the query no longer
         * admits it.
         */
        private fun replaceShown(index: Int, row: JsonObject) {
            written.append(row)
            if (admission.admits(row)) {
                visible.replace(index, row)
            } else {
                visible.removeWithUndo(index)
                held.append(row)
            }
        }

        /**
         * Replaces the held row at [index] with [row], bringing it in when the change makes it
         * start matching.
         */
        private fun replaceHeld(index: Int, row: JsonObject) {
            written.append(row)
            if (!admission.admits(held[index]) && admission.admits(row)) {
                held.removeWithUndo(index)
                visible.append(row)
            } else {
                held.replace(index, row)
            }
        }
    }

    /** An INSERT or UPSERT body's rows: an array, or a single object. */
    private fun bodyRowsOf(mutation: MutationRow): List<JsonElement> =
        when (val body = json.parseToJsonElement(mutation.bodyJson)) {
            is JsonArray -> body
            is JsonObject -> listOf(body)
            else -> emptyList()
        }

    /** The columns an UPSERT matches existing rows on; see [conflictColumnsOf]. */
    private fun conflictColumnsOf(mutation: MutationRow): List<String> =
        conflictColumnsOf(mutation, primaryKeys)

    /**
     * The rows an UPDATE or DELETE targets; `null`, which touches nothing, when it has no readable
     * filter.
     */
    private fun filterOf(mutation: MutationRow): FilterDescriptor? =
        FilterEngine.decodeOrNull(mutation.filterJson, json)
}

/**
 * The rows after an overlay.
 *
 * @property visible the rows the query shows, in order.
 * @property held the rows the overlay knows that the query does not show.
 * @property written every row the mutations inserted or changed, as they left it, shown or not.
 */
internal data class OverlaidRows(
    val visible: List<JsonElement>,
    val held: List<JsonObject>,
    val written: List<JsonObject>,
)

/**
 * The columns an UPSERT matches existing rows on: its conflict columns, or its table's primary key
 * as [primaryKeys] names it.
 */
internal fun conflictColumnsOf(mutation: MutationRow, primaryKeys: PrimaryKeys): List<String> =
    mutation.onConflict
        ?.split(',')
        ?.map { column -> column.trim() }
        ?.filter { column -> column.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() } ?: primaryKeys.of(mutation.table)

/** The rows of [payload], or `null` when it is not a JSON array. */
internal fun payloadRowsOf(json: Json, payload: String): List<JsonElement>? =
    try {
        json.parseToJsonElement(payload) as? JsonArray
    } catch (_: IllegalArgumentException) {
        null
    }

/**
 * The rows a write's response carries: those of a JSON array, or the one object `single()` returns.
 * Empty for a response without rows (`return=minimal`) or one that is not JSON.
 */
internal fun returnedRowsOf(json: Json, responseData: String): List<JsonObject> {
    if (responseData.isBlank()) return emptyList()
    return when (
        val element =
            try {
                json.parseToJsonElement(responseData)
            } catch (_: IllegalArgumentException) {
                null
            }
    ) {
        is JsonArray -> element.filterIsInstance<JsonObject>()
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

/**
 * The index of the row in [rows] with [row]'s values in [keyColumns], or `null` when there is none
 * or [row] lacks one.
 */
internal fun indexOfKey(rows: List<JsonElement>, row: JsonObject, keyColumns: List<String>): Int? {
    val key = primaryKeyOf(row, keyColumns) ?: return null
    val index = rows.indexOfFirst { existing ->
        val existingKey = primaryKeyOf(existing, keyColumns)
        existingKey != null && isSameKey(key, existingKey)
    }
    return index.takeIf { it >= 0 }
}

/** Whether two keys name the same row, comparing values by their JSON content. */
private fun isSameKey(first: JsonObject, second: JsonObject): Boolean =
    first.keys == second.keys &&
        first.all { (column, value) ->
            (value as? JsonPrimitive)?.content == (second[column] as? JsonPrimitive)?.content
        }

/** [base] with [patch]'s columns written over it. */
private fun merge(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(base + patch)

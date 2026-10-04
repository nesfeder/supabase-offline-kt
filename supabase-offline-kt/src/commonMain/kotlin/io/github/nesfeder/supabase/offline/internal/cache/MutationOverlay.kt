// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.equalityDecidedByContent
import io.github.nesfeder.supabase.offline.internal.postgrest.isUuid
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.sameUuidDigits
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
 * @param bodyOf a mutation's body, parsed; one parse may serve many overlays.
 * @param filterFor an UPDATE's or DELETE's filter, decoded; `null` when it has none that reads.
 * @param keyed UPDATEs known to reach exactly the rows with one key each; they find those rows by
 *   key instead of reading every row.
 */
internal class MutationOverlay(
    private val json: Json,
    private val admission: QueryAdmission,
    private val primaryKeys: PrimaryKeys,
    private val mayAlreadyHold: (MutationRow) -> Boolean,
    private val placesKeylessUpsertRows: Boolean = true,
    private val onMutationFailure: (MutationRow, Exception) -> Unit = { _, _ -> },
    private val bodyOf: (MutationRow) -> JsonElement = { mutation ->
        json.parseToJsonElement(mutation.bodyJson)
    },
    private val filterFor: (MutationRow) -> FilterDescriptor? = { mutation ->
        decodeFilterOrNull(mutation.filterJson, json)
    },
    private val keyed: KeyedUpdates? = null,
) {

    /**
     * [mutations], oldest first, overlaid onto [payload]. [payload] comes back unchanged when it is
     * not a JSON array, and when there is nothing to overlay.
     *
     * @param knownRows rows of the table the engine knows from elsewhere; see [overlayRows]. Those
     *   [payload] holds already are left out.
     */
    fun overlay(
        payload: String,
        mutations: List<MutationRow>,
        knownRows: List<JsonObject> = emptyList(),
    ): String = overlayReportingChanges(payload, mutations, knownRows).payload

    /** [overlay], with the mutations that changed a row the query shows. */
    fun overlayReportingChanges(
        payload: String,
        mutations: List<MutationRow>,
        knownRows: List<JsonObject> = emptyList(),
    ): OverlaidPayload {
        if (mutations.isEmpty()) return OverlaidPayload(payload, emptySet())
        val rows = payloadRowsOf(json, payload) ?: return OverlaidPayload(payload, emptySet())
        val keyColumns = primaryKeys.of(mutations.first().table)
        val elsewhere =
            if (knownRows.isEmpty()) {
                knownRows
            } else {
                val shown =
                    rows.mapNotNullTo(HashSet()) { element ->
                        (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
                    }
                knownRows.filter { known -> keyOf(known, keyColumns) !in shown }
            }
        val overlaid = overlayRows(rows, mutations, elsewhere)
        return OverlaidPayload(encode(overlaid.visible), overlaid.shownChangedBy)
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
                working.keep(mutation.id)
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
     *
     * A shown row lives in a slot that never moves: one that leaves the query empties its slot, so
     * the rows can be found by key through an index built once per set of key columns and kept in
     * step with every change. An undo reads no row: it drops the indexes, built again when next
     * asked.
     */
    private inner class Working(start: OverlaidRows) {
        private val slots: MutableList<JsonElement?> = start.visible.toMutableList()
        private val held = start.held.toMutableList()
        private val written = start.written.toMutableList()

        /** How to take back each change of the current mutation, in the order made. */
        private val journal = mutableListOf<() -> Unit>()

        /** Key columns → each shown row's [keyOf] under them → its slots, lowest first. */
        private val keyIndexes = HashMap<List<String>, HashMap<List<String>, MutableList<Int>>>()

        /** Whether the current mutation changed a shown slot. */
        private var currentChangedShown = false

        /** The kept mutations that changed a shown slot. */
        private val shownChangedBy = HashSet<Long>()

        fun snapshot(): OverlaidRows =
            OverlaidRows(
                visible = slots.filterNotNull(),
                held = held,
                written = written,
                shownChangedBy = shownChangedBy,
            )

        /** Keeps the current mutation's changes, the mutation [mutationId]. */
        fun keep(mutationId: Long) {
            if (currentChangedShown) shownChangedBy += mutationId
            currentChangedShown = false
            journal.clear()
        }

        /** Takes back the current mutation's changes, newest first. */
        fun undo() {
            journal.asReversed().forEach { change -> change() }
            journal.clear()
            keyIndexes.clear()
            currentChangedShown = false
        }

        /**
         * The first slot showing a row with [row]'s values in [keyColumns], or `null` when none
         * does or [row] lacks one; see [indexOfKey], whose answer this is.
         */
        private fun firstShownSlotOf(row: JsonObject, keyColumns: List<String>): Int? {
            val key = keyOf(row, keyColumns) ?: return null
            return keyIndexOf(keyColumns)[key]?.firstOrNull()
        }

        /** The index of the shown rows under [keyColumns], built whole on first use. */
        private fun keyIndexOf(keyColumns: List<String>): HashMap<List<String>, MutableList<Int>> {
            keyIndexes[keyColumns]?.let { index ->
                return index
            }
            val index = HashMap<List<String>, MutableList<Int>>()
            slots.forEachIndexed { slot, element ->
                val key = (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
                if (key != null) index.getOrPut(key) { mutableListOf() } += slot
            }
            keyIndexes[keyColumns] = index
            return index
        }

        /**
         * Puts [element] in [slot], `null` to empty it, keeping every built index in step. Reads
         * the key columns of the rows leaving and entering the slot.
         */
        private fun setSlot(slot: Int, element: JsonElement?) {
            val old = slots[slot]
            keyIndexes.forEach { (keyColumns, index) ->
                (old as? JsonObject)
                    ?.let { row -> keyOf(row, keyColumns) }
                    ?.let { key ->
                        val holders = index.getValue(key)
                        holders.remove(slot)
                        if (holders.isEmpty()) index.remove(key)
                    }
                (element as? JsonObject)
                    ?.let { row -> keyOf(row, keyColumns) }
                    ?.let { key -> index.getOrPut(key) { mutableListOf() }.insertInOrder(slot) }
            }
            slots[slot] = element
        }

        /** Shows [element] after every row, to be removed on [undo]. */
        private fun show(element: JsonElement) {
            currentChangedShown = true
            slots.add(null)
            val slot = slots.lastIndex
            journal += { slots.removeAt(slot) }
            setSlot(slot, element)
        }

        /** Shows [element] in [slot] instead, to be put back on [undo]. */
        private fun replaceSlot(slot: Int, element: JsonElement?) {
            currentChangedShown = true
            val old = slots[slot]
            journal += { slots[slot] = old }
            setSlot(slot, element)
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
            when (mutation.knownOperation) {
                MutationOperation.INSERT -> insert(mutation)
                MutationOperation.UPSERT -> upsert(mutation)
                MutationOperation.UPDATE -> update(mutation)
                MutationOperation.DELETE -> delete(mutation)
                // An operation this build does not write changes no row; a pass refuses it
                null -> Unit
            }
        }

        private fun insert(mutation: MutationRow) {
            val keyColumns = primaryKeys.of(mutation.table)
            val dedupe = mayAlreadyHold(mutation)
            bodyRowsOf(mutation).forEach { element ->
                val row = element as? JsonObject
                if (row == null) {
                    show(element)
                    return@forEach
                }
                if (dedupe && firstShownSlotOf(row, keyColumns) != null) return@forEach
                place(row)
            }
        }

        private fun upsert(mutation: MutationRow) {
            val keyColumns = conflictColumnsOf(mutation)
            val ignoreDuplicates = mutation.ignoreDuplicates == true
            bodyRowsOf(mutation).forEach { element ->
                val row = element as? JsonObject
                if (row == null) {
                    show(element)
                    return@forEach
                }
                if (primaryKeyOf(row, keyColumns) == null) {
                    if (placesKeylessUpsertRows) place(row)
                    return@forEach
                }
                val shown = firstShownSlotOf(row, keyColumns)
                val kept = if (shown == null) indexOfKey(held, row, keyColumns) else null
                when {
                    shown != null ->
                        if (!ignoreDuplicates)
                            replaceShown(shown, merge(slots[shown] as JsonObject, row))
                    kept != null -> if (!ignoreDuplicates) replaceHeld(kept, merge(held[kept], row))
                    else -> place(row)
                }
            }
        }

        private fun update(mutation: MutationRow) {
            val descriptor = filterFor(mutation) ?: return
            val keyedBy = keyed
            val key = keyedBy?.keys?.get(mutation.id)
            if (keyedBy != null && key != null) {
                val patch = bodyOf(mutation) as? JsonObject ?: return
                return updateByKey(descriptor, patch, key, keyedBy.keyColumns)
            }
            val filter = RowFilter(descriptor, primaryKeys.of(mutation.table).toSet())
            val patch = bodyOf(mutation) as? JsonObject ?: return
            // No row can match: none is visited
            if (!filter.canMatch) return
            // Rows the loop below moves aside land after these, and are not visited twice
            val heldBefore = held.size
            slots.indices.reversed().forEach { slot ->
                val row = slots[slot] as? JsonObject ?: return@forEach
                if (filter.match(row) == RowMatch.MATCHES) replaceShown(slot, merge(row, patch))
            }
            (0 until heldBefore).reversed().forEach { index ->
                val row = held[index]
                if (filter.match(row) == RowMatch.MATCHES) replaceHeld(index, merge(row, patch))
            }
        }

        /**
         * [update] for an UPDATE of [keyed], which matches the rows with [key] and no others:
         * visits them in the order a scan of every row would.
         */
        private fun updateByKey(
            descriptor: FilterDescriptor,
            patch: JsonObject,
            key: List<String>,
            keyColumns: List<String>,
        ) {
            val heldBefore = held.size
            val shown = keyIndexOf(keyColumns)[key]?.toList().orEmpty()
            val heldWithKey =
                (0 until heldBefore).filter { index ->
                    keyOf(held[index], keyColumns) == key
                }
            if (shown.isEmpty() && heldWithKey.isEmpty()) return
            val filter = RowFilter(descriptor, keyColumns.toSet())
            shown.asReversed().forEach { slot ->
                val row = slots[slot] as JsonObject
                if (filter.match(row) == RowMatch.MATCHES) replaceShown(slot, merge(row, patch))
            }
            heldWithKey.asReversed().forEach { index ->
                val row = held[index]
                if (filter.match(row) == RowMatch.MATCHES) replaceHeld(index, merge(row, patch))
            }
        }

        private fun delete(mutation: MutationRow) {
            val filter =
                RowFilter(filterFor(mutation) ?: return, primaryKeys.of(mutation.table).toSet())
            // No row can match: none is visited
            if (!filter.canMatch) return
            slots.indices.reversed().forEach { slot ->
                val row = slots[slot]
                if (row is JsonObject && filter.match(row) == RowMatch.MATCHES)
                    replaceSlot(slot, null)
            }
            held.indices.reversed().forEach { index ->
                if (filter.match(held[index]) == RowMatch.MATCHES) held.removeWithUndo(index)
            }
        }

        /** Adds a row the mutation wrote: shown when the query admits it, held otherwise. */
        private fun place(row: JsonObject) {
            written.append(row)
            if (admission.admits(row)) show(row) else held.append(row)
        }

        /**
         * Replaces the row shown in [slot] with [row], moving it aside when the query no longer
         * admits it.
         */
        private fun replaceShown(slot: Int, row: JsonObject) {
            written.append(row)
            if (admission.admits(row)) {
                replaceSlot(slot, row)
            } else {
                replaceSlot(slot, null)
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
                show(row)
            } else {
                held.replace(index, row)
            }
        }
    }

    /** An INSERT or UPSERT body's rows: an array, or a single object. */
    private fun bodyRowsOf(mutation: MutationRow): List<JsonElement> =
        when (val body = bodyOf(mutation)) {
            is JsonArray -> body
            is JsonObject -> listOf(body)
            else -> emptyList()
        }

    /** The columns an UPSERT matches existing rows on; see [conflictColumnsOf]. */
    private fun conflictColumnsOf(mutation: MutationRow): List<String> =
        conflictColumnsOf(mutation, primaryKeys)
}

/**
 * The rows after an overlay.
 *
 * @property visible the rows the query shows, in order.
 * @property held the rows the overlay knows that the query does not show.
 * @property written every row the mutations inserted or changed, as they left it, shown or not.
 * @property shownChangedBy the ids of the mutations that changed a row the query shows.
 */
internal data class OverlaidRows(
    val visible: List<JsonElement>,
    val held: List<JsonObject>,
    val written: List<JsonObject>,
    val shownChangedBy: Set<Long> = emptySet(),
)

/**
 * A payload with mutations overlaid.
 *
 * @property payload the rows the query shows, encoded.
 * @property shownChangedBy the ids of the mutations that changed one of them.
 */
internal data class OverlaidPayload(val payload: String, val shownChangedBy: Set<Long>)

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

/**
 * UPDATEs of one patch that each name one key ([keyNamedBy]) over values whose `eq` is decided by
 * content ([equalityDecidedByContent]): each reaches exactly the rows with its key, and no row's
 * reach is unknown.
 *
 * @property keyColumns the table's key columns, in order.
 * @property keys each such UPDATE's key, by its id.
 */
internal class KeyedUpdates(val keyColumns: List<String>, val keys: Map<Long, List<String>>)

/**
 * The [keyOf] under [keyColumns] that [filter] names when it is exactly one `eq` on each of them,
 * combined by AND; `null` for any other filter.
 */
internal fun keyNamedBy(filter: FilterDescriptor, keyColumns: List<String>): List<String>? {
    if (filter.mode != FilterDescriptor.Mode.AND || keyColumns.isEmpty()) return null
    if (filter.conditions.size != keyColumns.size) return null
    val values = HashMap<String, JsonElement>()
    for (condition in filter.conditions) {
        if (condition.operator != FilterOperator.EQ || condition.column !in keyColumns) return null
        if (values.put(condition.column, JsonPrimitive(condition.value)) != null) return null
    }
    return keyOf(JsonObject(values), keyColumns)
}

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
    val key = keyValuesOf(row, keyColumns) ?: return null
    // Read column by column: building each row's key would allocate once per row and lookup
    val index = rows.indexOfFirst { existing ->
        val existingRow = existing as? JsonObject
        existingRow != null &&
            keyColumns.indices.all { position ->
                sameKeyValue(key[position], keyValueOf(existingRow, keyColumns[position]))
            }
    }
    return index.takeIf { it >= 0 }
}

/**
 * [row]'s key in [keyColumns] as a value two rows share exactly when [indexOfKey] finds them the
 * same row — each value's JSON content, a UUID reduced to its digits in lower case — for hashing.
 * `null` when [row] lacks one.
 */
internal fun keyOf(row: JsonObject, keyColumns: List<String>): List<String>? {
    if (keyColumns.isEmpty()) return null
    return keyColumns.map { column ->
        val value = keyValueOf(row, column) ?: return null
        if (value.length in UUID_KEY_LENGTHS && isUuid(value)) {
            UUID_KEY_PREFIX + value.filter { character -> character.isLetterOrDigit() }.lowercase()
        } else {
            VALUE_KEY_PREFIX + value
        }
    }
}

/** Marks a [keyOf] part that is a UUID's digits, so no other value can equal it. */
private const val UUID_KEY_PREFIX = "u:"

/** Marks a [keyOf] part that is a value as written. */
private const val VALUE_KEY_PREFIX = "v:"

/** [row]'s values of [keyColumns], in order, or `null` when it lacks one; see [primaryKeyOf]. */
private fun keyValuesOf(row: JsonObject, keyColumns: List<String>): List<String>? {
    if (keyColumns.isEmpty()) return null
    return keyColumns.map { column -> keyValueOf(row, column) ?: return null }
}

/** [row]'s value of the key [column], as JSON content, or `null` when it carries none. */
private fun keyValueOf(row: JsonObject, column: String): String? {
    val value = row[column]
    return if (value !is JsonPrimitive || value is JsonNull) null else value.content
}

/**
 * Whether two key values, as JSON content, name one row: equal, or one UUID in any letter case, as
 * a `uuid` column compares them.
 */
private fun sameKeyValue(first: String?, second: String?): Boolean =
    first == second ||
        (first != null &&
            second != null &&
            first.length in UUID_KEY_LENGTHS &&
            second.length in UUID_KEY_LENGTHS &&
            isUuid(first) &&
            isUuid(second) &&
            sameUuidDigits(first, second))

/** The lengths a UUID key can be written in; see [isUuid]. */
private val UUID_KEY_LENGTHS: IntRange = 32..48

/** [base] with [patch]'s columns written over it. */
private fun merge(base: JsonObject, patch: JsonObject): JsonObject = JsonObject(base + patch)

/** Adds [value] where it keeps this ascending list ascending. */
private fun MutableList<Int>.insertInOrder(value: Int) {
    var position = size
    while (position > 0 && this[position - 1] > value) position--
    add(position, value)
}

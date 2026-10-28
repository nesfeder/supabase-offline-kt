// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.RowWindow
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the cache knows of a table's rows when a write the server applied is patched in, for one
 * cached query.
 *
 * @property rows the query's cached rows.
 * @property shape the query's shape; `null` when not known.
 * @property sameSelectRows rows the table's other queries hold, read with this query's
 *   [Projection].
 * @property otherSelectRows rows the table's other queries hold, read with another projection, or
 *   by a query whose shape is not known.
 * @property keyColumns the table's key columns, read as a `uuid` column reads them.
 */
internal class PatchTarget(
    val rows: List<JsonElement>,
    val shape: QueryShape?,
    val sameSelectRows: List<JsonObject>,
    val otherSelectRows: List<JsonObject>,
    val keyColumns: Set<String> = emptySet(),
) {
    /** The rows the query admits. */
    val admission: QueryAdmission
        get() = shape?.admission ?: QueryAdmission.EVERY_ROW
}

/**
 * Whether a patch of [writes] may leave [target] wrong, so the query is to be fetched again:
 * - an UPDATE or DELETE reaches a row the engine cannot tell it reaches (its filter is
 *   [RowMatch.UNKNOWN] for the row), in the query, or, for an UPDATE, one that may join it;
 * - an UPDATE brings in a row read with another [Projection], which may lack this list's columns or
 *   hold other embedded rows;
 * - an UPSERT row has no key to place it by, the query holds a row without it, or its key is a row
 *   read with another projection.
 *
 * @param conflictColumnsOf the columns an UPSERT matches rows on.
 * @param keyed UPDATEs known to reach exactly the rows with one key each, and no row whose reach is
 *   unknown: only a row read with another projection that one brings in can leave [target] wrong.
 */
internal fun patchMayLeaveWrong(
    writes: List<MutationRow>,
    target: PatchTarget,
    json: Json,
    keyed: KeyedUpdates? = null,
    conflictColumnsOf: (MutationRow) -> List<String>,
): Boolean {
    val otherByKey = lazy {
        keyed?.let { updates ->
            target.otherSelectRows.groupBy { row -> keyOf(row, updates.keyColumns) }
        }
    }
    return writes.any { write ->
        mayLeaveWrong(write, target, json, conflictColumnsOf, keyed, otherByKey)
    }
}

/**
 * [patchMayLeaveWrong] for one of the writes.
 *
 * @param otherByKey the rows read with another projection, by key, when [keyed] is known.
 */
private fun mayLeaveWrong(
    write: MutationRow,
    target: PatchTarget,
    json: Json,
    conflictColumnsOf: (MutationRow) -> List<String>,
    keyed: KeyedUpdates?,
    otherByKey: Lazy<Map<List<String>?, List<JsonObject>>?>,
): Boolean =
    when (write.knownOperation) {
        MutationOperation.UPDATE -> {
            val key = keyed?.keys?.get(write.id)
            if (key != null)
                keyedUpdateMayLeaveWrong(write, target, json, otherByKey.value?.get(key))
            else updateMayLeaveWrong(write, target, json)
        }
        MutationOperation.DELETE -> {
            val filter = filterOf(write, target, json)
            filter != null && target.rows.any { row -> reachIsUnknown(row, filter) }
        }
        MutationOperation.UPSERT -> upsertMayLeaveWrong(write, target, json, conflictColumnsOf)
        else -> false
    }

/**
 * [updateMayLeaveWrong] for an UPDATE that reaches exactly the rows with one key, [held] being
 * those read with another projection: only such a row it brings in can leave the query wrong.
 */
private fun keyedUpdateMayLeaveWrong(
    write: MutationRow,
    target: PatchTarget,
    json: Json,
    held: List<JsonObject>?,
): Boolean {
    if (held.isNullOrEmpty()) return false
    val patch = json.parseToJsonElement(write.bodyJson) as? JsonObject ?: return false
    val admission = target.admission
    return held.any { row -> !admission.admits(row) && admission.admits(JsonObject(row + patch)) }
}

private fun updateMayLeaveWrong(write: MutationRow, target: PatchTarget, json: Json): Boolean {
    val filter = filterOf(write, target, json) ?: return false
    val patch = json.parseToJsonElement(write.bodyJson) as? JsonObject ?: return false
    if (target.rows.any { row -> reachIsUnknown(row, filter) }) return true
    val mayJoin = mayGainRowsBy(patch, target.shape)
    if (mayJoin && target.sameSelectRows.any { row -> reachIsUnknown(row, filter) }) return true
    val admission = target.admission
    return target.otherSelectRows.any { row ->
        when (filter.match(row)) {
            RowMatch.MATCHES -> !admission.admits(row) && admission.admits(JsonObject(row + patch))
            RowMatch.UNKNOWN -> mayJoin
            RowMatch.EXCLUDED -> false
        }
    }
}

private fun upsertMayLeaveWrong(
    write: MutationRow,
    target: PatchTarget,
    json: Json,
    conflictColumnsOf: (MutationRow) -> List<String>,
): Boolean {
    val keyColumns = conflictColumnsOf(write)
    val body =
        when (val parsed = json.parseToJsonElement(write.bodyJson)) {
            is JsonArray -> parsed.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(parsed)
            else -> emptyList()
        }
    val admission = target.admission
    // A row the query holds without the conflict columns may be the one an UPSERT row replaces:
    // nothing tells, so the row may show twice, or stay where it no longer belongs
    val holdsKeylessRow = lazy {
        target.rows.any { row -> row is JsonObject && keyOf(row, keyColumns) == null }
    }
    return body.any { row ->
        if (primaryKeyOf(row, keyColumns) == null) return@any admission.admits(row)
        if (holdsKeylessRow.value) return@any true
        val elsewhere =
            target.otherSelectRows.firstOrNull { other ->
                indexOfKey(listOf(other), row, keyColumns) != null
            }
        elsewhere != null &&
            indexOfKey(target.rows, row, keyColumns) == null &&
            indexOfKey(target.sameSelectRows, row, keyColumns) == null &&
            admission.admits(JsonObject(elsewhere + row))
    }
}

/**
 * Whether [patched], server changes laid over [answer] that [patchMayLeaveWrong] passed, may still
 * differ from what the server would answer now: it shows a row [answer] does not hold, which the
 * server may no longer show this user or would place elsewhere in its order; or, by [window], a row
 * a change kept stands elsewhere in the server's order, or a row it removed would let another into
 * the window. Rows are matched by [keyColumns], the key server changes place rows by.
 */
internal fun rebaseOnlyApproximates(
    answer: List<JsonElement>,
    patched: List<JsonElement>,
    keyColumns: List<String>,
    window: RowWindow,
): Boolean {
    val held = HashMap<List<String>, JsonObject>()
    for (element in answer) {
        val row = element as? JsonObject ?: continue
        keyOf(row, keyColumns)?.let { key -> held[key] = row }
    }
    for (element in patched) {
        // A row without the key is one the answer held, untouched: a change finds rows by their key
        val row = element as? JsonObject ?: continue
        val key = keyOf(row, keyColumns) ?: continue
        val before = held[key] ?: return true
        if (before != row && window.mayMove(before, row)) return true
    }
    // Nothing was added, so fewer rows means a change took one out
    return window.isWindowed && patched.size < answer.size
}

/**
 * [write]'s filter, made ready once to judge every row of [target] ([RowFilter]), or `null` when it
 * has none that decodes.
 */
private fun filterOf(write: MutationRow, target: PatchTarget, json: Json): RowFilter? =
    decodeFilterOrNull(write.filterJson, json)?.let { filter ->
        RowFilter(filter, target.keyColumns)
    }

/**
 * Whether [filter]'s reach of [row] is unknown: a row, not a stray value, the engine cannot judge.
 */
private fun reachIsUnknown(row: JsonElement, filter: RowFilter): Boolean =
    row is JsonObject && filter.match(row) == RowMatch.UNKNOWN

/**
 * Whether an UPDATE setting [patch]'s columns may bring rows into a query of [shape]: its filters
 * name one of them, or cannot be read, and do not exclude [patch]'s values. Every row the UPDATE
 * changes carries those values, so a condition excluding them excludes each such row.
 */
internal fun mayGainRowsBy(patch: JsonObject, shape: QueryShape?): Boolean =
    shape == null ||
        ((shape.hasOtherFilters ||
            shape.conditions.any { condition -> condition.column in patch.keys }) &&
            shape.admission.admits(patch))

/**
 * What a row a query reads holds, as compared between queries: its select list without whitespace
 * (`*` when empty), and what it asks of the tables it embeds. Queries share rows only when equal.
 */
internal data class Projection(val select: String, val referencedTableOptions: List<String>)

/** [shape]'s [Projection]. */
internal fun projectionOf(shape: QueryShape): Projection =
    Projection(
        select = shape.select.filterNot { character -> character.isWhitespace() }.ifEmpty { "*" },
        referencedTableOptions = shape.referencedTableOptions,
    )

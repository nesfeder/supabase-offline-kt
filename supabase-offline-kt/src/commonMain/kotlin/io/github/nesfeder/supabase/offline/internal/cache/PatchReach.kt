// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryAdmission
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.rowMatch
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
 * @property sameSelectRows rows the table's other queries hold, read with this query's select list.
 * @property otherSelectRows rows the table's other queries hold, read with another select list, or
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
 * - an UPDATE brings in a row read with another select list, which lacks this list's columns;
 * - an UPSERT row has no key to place it by, or its key is a row read with another select list.
 *
 * @param conflictColumnsOf the columns an UPSERT matches rows on.
 */
internal fun patchMayLeaveWrong(
    writes: List<MutationRow>,
    target: PatchTarget,
    json: Json,
    conflictColumnsOf: (MutationRow) -> List<String>,
): Boolean = writes.any { write ->
    when (write.operation) {
        MutationOperation.UPDATE -> updateMayLeaveWrong(write, target, json)
        MutationOperation.DELETE -> {
            val filter = FilterEngine.decodeOrNull(write.filterJson, json)
            filter != null &&
                target.rows.any { row -> reachIsUnknown(row, filter, target.keyColumns) }
        }
        MutationOperation.UPSERT -> upsertMayLeaveWrong(write, target, json, conflictColumnsOf)
        else -> false
    }
}

private fun updateMayLeaveWrong(write: MutationRow, target: PatchTarget, json: Json): Boolean {
    val filter = FilterEngine.decodeOrNull(write.filterJson, json) ?: return false
    val patch = json.parseToJsonElement(write.bodyJson) as? JsonObject ?: return false
    if (target.rows.any { row -> reachIsUnknown(row, filter, target.keyColumns) }) return true
    val mayJoin = mayGainRowsBy(patch, target.shape)
    if (
        mayJoin &&
            target.sameSelectRows.any { row -> reachIsUnknown(row, filter, target.keyColumns) }
    )
        return true
    val admission = target.admission
    return target.otherSelectRows.any { row ->
        when (rowMatch(row, filter, target.keyColumns)) {
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
    return body.any { row ->
        if (primaryKeyOf(row, keyColumns) == null) return@any admission.admits(row)
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
 * Whether [filter]'s reach of [row] is unknown: a row, not a stray value, the engine cannot judge.
 */
private fun reachIsUnknown(
    row: JsonElement,
    filter: FilterDescriptor,
    keyColumns: Set<String>,
): Boolean = row is JsonObject && rowMatch(row, filter, keyColumns) == RowMatch.UNKNOWN

/**
 * Whether an UPDATE setting [patch]'s columns may bring rows into a query of [shape]: its filters
 * name one of them, or cannot be read.
 */
internal fun mayGainRowsBy(patch: JsonObject, shape: QueryShape?): Boolean =
    shape == null ||
        shape.hasOtherFilters ||
        shape.conditions.any { condition -> condition.column in patch.keys }

/** [shape]'s select list as compared between queries: without whitespace, `*` when empty. */
internal fun selectListOf(shape: QueryShape): String =
    shape.select.filterNot { character -> character.isWhitespace() }.ifEmpty { "*" }

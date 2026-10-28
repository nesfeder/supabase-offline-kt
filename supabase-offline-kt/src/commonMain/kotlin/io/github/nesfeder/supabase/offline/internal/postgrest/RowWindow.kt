// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.json.JsonObject

/**
 * How a query's request orders its rows, and whether it reads only a window of them: what decides
 * where the server would place a row a change moved, which the query's [QueryShape] does not say.
 * Read from the request for the answer in hand, and never kept.
 *
 * @property orderColumns the columns the request orders by; `null` when it sets no order.
 * @property readsEveryOrderColumn whether [orderColumns] holds every column the order names: not
 *   when it orders by a JSON path, a cast or a quoted name.
 * @property isWindowed whether the request reads a window of the rows: a `limit`, an `offset`, or a
 *   `Range` header.
 */
internal data class RowWindow(
    val orderColumns: Set<String>? = null,
    val readsEveryOrderColumn: Boolean = true,
    val isWindowed: Boolean = false,
) {

    /**
     * Whether [after], the row [before] as a change left it, may stand elsewhere in the server's
     * order: a column the order reads holds another value, or the order is not one this reads.
     */
    fun mayMove(before: JsonObject, after: JsonObject): Boolean {
        val columns = orderColumns ?: return false
        return !readsEveryOrderColumn || columns.any { column -> before[column] != after[column] }
    }

    companion object {
        /** No order and every row: what a write with no request in hand is judged by. */
        val UNORDERED: RowWindow = RowWindow()
    }
}

/**
 * The [RowWindow] of a SELECT whose [builder] its request has configured. An order or a window on
 * an embedded table orders or limits the rows it embeds, not the query's own, and is left out.
 */
internal fun rowWindowOf(builder: SelectRequestBuilder): RowWindow {
    val params = builder.params
    val terms = params["order"].orEmpty().flatMap { order -> order.split(',') }
    // `column.desc.nullslast`: the column is what comes before the first dot
    val columns = terms.map { term -> term.trim().substringBefore('.') }
    val readable = columns.filter { column -> isPlainColumn(column) && '"' !in column }
    return RowWindow(
        orderColumns = if (terms.isEmpty()) null else readable.toSet(),
        readsEveryOrderColumn = readable.size == columns.size,
        isWindowed =
            "limit" in params || "offset" in params || builder.headers.build()["Range"] != null,
    )
}

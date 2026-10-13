// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.RealtimeTable

/**
 * A table whose changes on the server the client follows and applies to its cache.
 *
 * @property table the table's name.
 * @property schema its schema, or `null` for the client's default schema.
 * @property filter one condition the server evaluates before sending a change, or `null` for every
 *   row.
 */
internal data class WatchedTable(
    val table: String,
    val schema: String? = null,
    val filter: WatchedTableFilter? = null,
)

/**
 * One condition Realtime evaluates on the server for a watched table: `eq`, `neq`, `gt`, `gte`,
 * `lt`, `lte` or `in`.
 *
 * @property column the column the condition reads.
 * @property operator the comparison, one of the seven Realtime supports.
 * @property value the value compared with, or the values for `in`.
 */
internal data class WatchedTableFilter(
    val column: String,
    val operator: FilterOperator,
    val value: Any,
)

/** The filter operators Realtime evaluates; any other is refused before a channel is joined. */
internal val REALTIME_FILTER_OPERATORS: Set<FilterOperator> =
    setOf(
        FilterOperator.EQ,
        FilterOperator.NEQ,
        FilterOperator.GT,
        FilterOperator.GTE,
        FilterOperator.LT,
        FilterOperator.LTE,
        FilterOperator.IN,
    )

/** This named table as the engine follows it. */
internal fun RealtimeTable.toWatchedTable(): WatchedTable =
    WatchedTable(
        table = table,
        schema = schema,
        filter =
            filter?.let { named -> WatchedTableFilter(named.column, named.operator, named.value) },
    )

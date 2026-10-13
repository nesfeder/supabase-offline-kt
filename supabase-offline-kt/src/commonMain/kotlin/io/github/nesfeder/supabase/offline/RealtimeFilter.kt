// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.internal.realtime.REALTIME_FILTER_OPERATORS

/**
 * One condition Realtime evaluates on the server before it sends a change of a table named in
 * `realtime { }`: a change of a row that does not meet it is not sent. Row-level security applies
 * as well, and narrows a table to the user's rows without a filter.
 *
 * @property column the column compared.
 * @property operator the comparison: `eq`, `neq`, `gt`, `gte`, `lt`, `lte` or `in`, the operators
 *   Realtime supports.
 * @property value what the column is compared with; for `in`, a list of values.
 * @throws IllegalArgumentException when the column is blank, or the operator is one Realtime does
 *   not support.
 */
public class RealtimeFilter(
    public val column: String,
    public val operator: FilterOperator,
    public val value: Any,
) {

    init {
        require(column.isNotBlank()) { "A Realtime filter needs a column" }
        require(operator in REALTIME_FILTER_OPERATORS) {
            "Realtime filters support eq, neq, gt, gte, lt, lte and in, not " +
                operator.name.lowercase()
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RealtimeFilter &&
            other.column == column &&
            other.operator == operator &&
            other.value == value

    override fun hashCode(): Int =
        (column.hashCode() * 31 + operator.hashCode()) * 31 + value.hashCode()

    override fun toString(): String =
        "RealtimeFilter(column=$column, operator=${operator.name.lowercase()}, value=$value)"
}

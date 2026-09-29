// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Which locally written rows a cached query admits: every row that none of its conditions excludes,
 * as far as [rowMatch] can tell. Order, limit, offset and range never switch that off; a condition
 * the engine does not evaluate never excludes a row.
 *
 * @property conditions the query's conditions, all of which must hold; see [QueryShape.conditions].
 */
internal class QueryAdmission(val conditions: List<FilterDescriptor.Condition>) {

    /** [conditions], ready to judge rows. */
    private val ready = conditions.map { condition -> RowCondition(condition, emptySet()) }

    /**
     * Whether [row] may appear in the query. A row that is not an object cannot be judged and is
     * admitted.
     */
    fun admits(row: JsonElement): Boolean {
        val rowObject = row as? JsonObject ?: return true
        return ready.none { condition -> condition.match(rowObject) == RowMatch.EXCLUDED }
    }

    /** Admissions that need no query. */
    companion object {

        /** Admits every row: a query whose filters are not known. */
        val EVERY_ROW: QueryAdmission = QueryAdmission(emptyList())
    }
}

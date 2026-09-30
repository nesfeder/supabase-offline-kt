// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import kotlinx.serialization.Serializable

/**
 * What the engine knows of a cached query, captured from its request when the query is made: the
 * columns it selects and the filters it sends. Which rows the query admits is judged from it; the
 * cache key is only a fingerprint of the query and is never read back.
 *
 * @property select the `select` list as sent; `*` when the request names none.
 * @property conditions the filters on plain columns: each column's first value, as sent, the only
 *   one supabase-kt sends.
 * @property hasOtherFilters whether the request also filters in a way kept out of [conditions]: a
 *   logical group, a referenced table's column, a JSON path or a cast.
 * @property referencedTableOptions what the request asks of the tables it embeds (a limit, an
 *   offset, an order, a filter), each `name=value`, sorted: they decide what an embedded row holds.
 */
@Serializable
internal data class QueryShape(
    val select: String,
    val conditions: List<FilterDescriptor.Condition>,
    val hasOtherFilters: Boolean,
    val referencedTableOptions: List<String> = emptyList(),
) {
    /** Which locally written rows the query admits. */
    val admission: QueryAdmission
        get() = QueryAdmission(conditions)
}

/**
 * The [QueryShape] of a SELECT of [columns] whose [builder] its request has configured. Read from
 * the builder's parameters one by one, as supabase-kt sends them, so a value holding `&` or `=` is
 * taken whole.
 */
internal fun queryShapeOf(columns: Columns, builder: SelectRequestBuilder): QueryShape {
    val conditions = mutableListOf<FilterDescriptor.Condition>()
    var hasOtherFilters = false
    val referencedTableOptions = mutableListOf<String>()
    builder.params.forEach { (name, values) ->
        // A referenced table's parameter: `comments.limit`, `comments.order`, `comments.author`
        if ('.' in name && name !in LOGICAL_GROUPS)
            values.firstOrNull()?.let { value -> referencedTableOptions += "$name=$value" }
        if (
            name in WINDOW_AND_PROJECTION_PARAMETERS ||
                name.substringAfterLast('.') in WINDOW_AND_PROJECTION_PARAMETERS
        )
            return@forEach
        val value = values.firstOrNull() ?: return@forEach
        val separator = value.indexOf('.')
        if (name in LOGICAL_GROUPS || !isPlainColumn(name) || separator <= 0) {
            hasOtherFilters = true
            return@forEach
        }
        conditions +=
            FilterDescriptor.Condition(
                column = name,
                operatorId = value.substring(0, separator),
                value = value.substring(separator + 1),
            )
    }
    return QueryShape(
        select = columns.value,
        conditions = conditions,
        hasOtherFilters = hasOtherFilters,
        referencedTableOptions = referencedTableOptions.sorted(),
    )
}

/**
 * Query parameters that pick a result's columns or window, never which rows qualify, on the table
 * or a referenced one.
 */
private val WINDOW_AND_PROJECTION_PARAMETERS: Set<String> =
    setOf("select", "order", "limit", "offset")

/** The query parameters that hold a logical group of conditions. */
private val LOGICAL_GROUPS: Set<String> = setOf("or", "and", "not.or", "not.and")

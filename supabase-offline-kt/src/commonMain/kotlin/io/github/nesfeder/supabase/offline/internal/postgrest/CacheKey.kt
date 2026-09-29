// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Columns.Companion.ALL
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder

/**
 * Where a SELECT's response is cached, and what the query is.
 *
 * @property table the query-cache table: the table itself in the client's default schema, and
 *   `schema.table` in any other, so the two never share cached rows or queued mutations.
 * @property cacheKey the cache key, which starts with [table].
 * @property shape the query's columns and filters, captured from its request.
 * @property allowsRetries whether the request lets supabase-kt retry it: it did not call
 *   `noRetry()`.
 */
internal data class CacheSlot(
    val table: String,
    val cacheKey: String,
    val shape: QueryShape,
    val allowsRetries: Boolean = true,
)

/**
 * The [CacheSlot] of a SELECT on [table]. The schema is the builder's once [request] has run, which
 * may set it; a blank one is the server's default.
 *
 * @param defaultSchema the client's default schema, whose keys carry no schema prefix.
 */
internal fun cacheSlotOf(
    table: String,
    columns: Columns,
    defaultSchema: String,
    builderFactory: () -> SelectRequestBuilder,
    request: SelectRequestBuilder.() -> Unit,
): CacheSlot = cacheSlotOf(table, columns, defaultSchema, builderFactory().apply(request))

/**
 * The [CacheSlot] of a SELECT on [table] whose [builder] its request has configured, and nothing
 * else yet: read before supabase-kt adds its own parameters.
 */
internal fun cacheSlotOf(
    table: String,
    columns: Columns,
    defaultSchema: String,
    builder: SelectRequestBuilder,
): CacheSlot {
    val schema = builder.schema
    val cachedTable = if (schema.isBlank() || schema == defaultSchema) table else "$schema.$table"
    return CacheSlot(
        table = cachedTable,
        cacheKey = keyOf(cachedTable, columns, builder),
        shape = queryShapeOf(columns, builder),
        allowsRetries = builder.retry,
    )
}

/**
 * The cache key of a SELECT: [table], its query parameters sorted by name, and its range, e.g.
 * `assets?deleted=eq.false&order=updatedAt.desc|range=0-49`. Stored in every cached row, so its
 * format does not change.
 */
internal fun cacheKeyOf(
    table: String,
    columns: Columns = ALL,
    builderFactory: () -> SelectRequestBuilder,
    request: SelectRequestBuilder.() -> Unit,
): String = keyOf(table, columns, builderFactory().apply(request))

/** The key [cacheKeyOf] describes, of a [builder] its request has already configured. */
private fun keyOf(table: String, columns: Columns, builder: SelectRequestBuilder): String {
    val params: MutableMap<String, List<String>> = builder.params.toMutableMap()
    if (columns.value != ALL.value) {
        params["select"] = listOf(columns.value)
    }

    // Keys sorted; each key's values kept in the order they were added
    val query =
        params.keys.sorted().joinToString("&") { key ->
            val values = params[key].orEmpty()
            if (values.isEmpty()) key else values.joinToString("&") { value -> "$key=$value" }
        }

    // A Range header's window, without its `items=` unit
    val rangePart =
        builder.headers.build()["Range"]?.let { range -> "|range=${range.removePrefix("items=")}" }
            ?: ""

    return buildString {
        append(table)
        if (query.isNotEmpty()) {
            append('?')
            append(query)
        }
        append(rangePart)
    }
}

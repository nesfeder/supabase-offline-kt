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
 * @property window how the request orders its rows and whether it reads a window of them.
 */
internal data class CacheSlot(
    val table: String,
    val cacheKey: String,
    val shape: QueryShape,
    val allowsRetries: Boolean = true,
    val window: RowWindow = RowWindow.UNORDERED,
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
        window = rowWindowOf(builder),
    )
}

/**
 * The cache key of a SELECT: [table], its query parameters sorted by name, and its range, e.g.
 * `assets?deleted=eq.false&order=updatedAt.desc|range=0-49`. Stored in every cached row, so its
 * format does not change. A parameter name or value, or a range, that holds one of the format's
 * separators (`&`, `=`, `|`) would read as two parameters, or as a range, and so share a key with
 * another query: such a key ends in [DIGEST_MARK] and a digest of its parts taken apart, which no
 * other query's key has. Every other key is as it always was.
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
    val names = params.keys.sorted()

    // Keys sorted; each key's values kept in the order they were added
    val query =
        names.joinToString("&") { key ->
            val values = params[key].orEmpty()
            if (values.isEmpty()) key else values.joinToString("&") { value -> "$key=$value" }
        }

    // A Range header's window, without its `items=` unit
    val range = builder.headers.build()["Range"]?.removePrefix("items=")
    val rangePart = range?.let { window -> "|range=$window" } ?: ""

    return buildString {
        append(table)
        if (query.isNotEmpty()) {
            append('?')
            append(query)
        }
        append(rangePart)
        // A part holding a separator reads as more than one: the digest tells the key from the one
        // its parts read as. Keys without one are left exactly as they were.
        if (
            names.any { name ->
                name.holdsSeparator() || params[name].orEmpty().any(String::holdsSeparator)
            } || range?.holdsSeparator() == true
        ) {
            append(DIGEST_MARK)
            append(digestOf(names, params, range))
        }
    }
}

/**
 * Begins the digest a key ends in when one of its parts holds a separator. `|` followed by anything
 * but `range=` is in no key without a digest, whose only `|` begins its range.
 */
private const val DIGEST_MARK = "|digest="

/** The characters that separate a key's parts: between parameters, in one, and before the range. */
private val SEPARATORS = charArrayOf('&', '=', '|')

private fun String.holdsSeparator(): Boolean = indexOfAny(SEPARATORS) >= 0

/**
 * A 64-bit FNV-1a digest, as sixteen hex digits, over the UTF-16 code units of [names] with each
 * one's values and [range], every part prefixed by its length, so parts holding separators are read
 * apart.
 */
private fun digestOf(
    names: List<String>,
    params: Map<String, List<String>>,
    range: String?,
): String {
    val parts = buildString {
        names.forEach { name ->
            appendPart(name)
            val values = params[name].orEmpty()
            append(values.size)
            append(':')
            values.forEach(::appendPart)
        }
        appendPart(range ?: "")
    }
    var hash = FNV_OFFSET_BASIS
    for (char in parts) hash = (hash xor char.code.toULong()) * FNV_PRIME
    return hash.toString(16).padStart(16, '0')
}

private fun StringBuilder.appendPart(part: String) {
    append(part.length)
    append(':')
    append(part)
}

private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL
private const val FNV_PRIME: ULong = 0x100000001b3uL

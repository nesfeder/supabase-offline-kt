// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.nesfeder.supabase.offline.internal.cache.indexOfKey
import io.github.nesfeder.supabase.offline.internal.cache.returnedRowsOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.primaryKeyOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * The rows to patch into the cache for an INSERT or UPSERT: those the server returned, each with
 * the columns the caller wrote in its place that the response leaves out; the request body when the
 * response holds no row (`return=minimal`, or not JSON).
 *
 * A returned row stands for the written row with its [keyColumns]. Failing that, when as many came
 * back as were written, it stands for the one at its position that carries no key, as a row whose
 * key the server assigns does not. The server's values win: they include what it assigned, such as
 * an `id` or `created_at`.
 */
internal fun preferResponseForCacheOverlay(
    requestBody: JsonArray,
    responseData: String,
    json: Json,
    keyColumns: List<String> = listOf("id"),
): String {
    val returned = returnedRowsOf(json, responseData)
    if (returned.isEmpty()) return requestBody.toString()
    val written = requestBody.filterIsInstance<JsonObject>()
    val merged = returned.mapIndexed { index, row ->
        val source =
            written.firstOrNull { candidate ->
                indexOfKey(listOf(row), candidate, keyColumns) != null
            }
                ?: written
                    .getOrNull(index)
                    ?.takeIf { returned.size == written.size }
                    ?.takeIf { candidate -> primaryKeyOf(candidate, keyColumns) == null }
        if (source == null) row else JsonObject(row + (source - row.keys))
    }
    return JsonArray(merged).toString()
}

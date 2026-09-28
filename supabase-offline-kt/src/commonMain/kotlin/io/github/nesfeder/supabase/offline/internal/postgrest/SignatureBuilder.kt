@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Columns.Companion.ALL
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.ktor.http.Headers

/**
 * Builds a deterministic signature for a PostgREST SELECT request.
 *
 * Derived from:
 *  - table name (provided by caller)
 *  - URL params accumulated on SelectRequestBuilder (filters, order, select, logical groups)
 *  - Range header ("items=start-end"), if present
 *
 * Example:
 *   assets?deleted=eq.false&name=ilike.%pump%&order=updatedAt.desc|range=0-49
 */
internal object SignatureBuilder {

    fun build(
        table: String,
        columns: Columns = ALL,
        builderFactory: () -> SelectRequestBuilder,
        request: SelectRequestBuilder.() -> Unit
    ): String {
        val tmp = builderFactory().apply(request)
        val params: MutableMap<String, List<String>> = tmp.params.toMutableMap()
        val headers: Headers = tmp.headers.build()

        if (columns.value != ALL.value) {
            params["select"] = listOf(columns.value)
        }

        // Canonicalize params: sort keys; preserve insertion-order of values
        val query = params.keys.sorted().joinToString("&") { key ->
            val values = params[key].orEmpty()
            if (values.isEmpty()) key
            else values.joinToString("&") { v -> "$key=$v" }
        }

        // Canonicalize Range (as |range=start-end)
        val rangePart = headers["Range"]?.let { r ->
            val canon = r.removePrefix("items=")
            "|range=$canon"
        } ?: ""

        return buildString {
            append(table)
            if (query.isNotEmpty()) {
                append('?'); append(query)
            }
            append(rangePart)
        }
    }
}
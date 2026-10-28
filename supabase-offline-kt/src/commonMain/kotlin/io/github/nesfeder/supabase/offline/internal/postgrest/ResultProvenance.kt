// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.ktor.http.Headers
import io.ktor.http.HeadersBuilder
import kotlin.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * Where a one-shot read's result came from travels on supabase-kt's final PostgrestResult in
 * response headers PostgREST never sends; the public provenance extensions read them back.
 */

/** Says whether the rows are the cache's (`cache`) or the network's (`network`). */
internal const val SOURCE_HEADER: String = "X-Supabase-Offline-Source"

/** When the rows were fetched from the server, in epoch milliseconds. */
internal const val FETCHED_AT_HEADER: String = "X-Supabase-Offline-Fetched-At"

/** Whether a write still in the mutation queue changed a row shown (`true` or `false`). */
internal const val PENDING_MUTATIONS_HEADER: String = "X-Supabase-Offline-Pending-Mutations"

/**
 * The keys of the rows such a write changed, as a JSON array of objects; absent when there are
 * none. JSON text escapes every control character, so it is a valid header value.
 */
internal const val PENDING_ROW_KEYS_HEADER: String = "X-Supabase-Offline-Pending-Row-Keys"

/** The [SOURCE_HEADER] value of rows served from the cache. */
private const val CACHE_SOURCE: String = "cache"

/** The [SOURCE_HEADER] value of rows the server just sent. */
private const val NETWORK_SOURCE: String = "network"

/**
 * Where a one-shot read's rows came from.
 *
 * @property isFromCache whether they are the cache's rather than the server's answer to this read.
 * @property fetchedAtMillis when they were fetched from the server, or `null` when not known.
 * @property hasPendingMutations whether a write still queued changed one of them.
 * @property pendingRowKeys the keys of the rows such a write changed, from the overlay that made
 *   the rows.
 */
internal data class ResultProvenance(
    val isFromCache: Boolean,
    val fetchedAtMillis: Long?,
    val hasPendingMutations: Boolean,
    val pendingRowKeys: Set<JsonObject> = emptySet(),
)

/**
 * A result of [data] stamped with [provenance], keeping [headers] (a count's `Content-Range`, for
 * one) and dropping any stamp they carried.
 */
internal fun provenancedResult(
    data: String,
    postgrest: Postgrest,
    provenance: ResultProvenance,
    headers: Headers = Headers.Empty,
): PostgrestResult {
    val stamped = HeadersBuilder()
    headers.forEach { name, values ->
        if (name.lowercase() !in PROVENANCE_HEADERS) stamped.appendAll(name, values)
    }
    stamped.append(SOURCE_HEADER, if (provenance.isFromCache) CACHE_SOURCE else NETWORK_SOURCE)
    provenance.fetchedAtMillis?.let { millis -> stamped.append(FETCHED_AT_HEADER, "$millis") }
    stamped.append(PENDING_MUTATIONS_HEADER, "${provenance.hasPendingMutations}")
    if (provenance.pendingRowKeys.isNotEmpty()) {
        stamped.append(
            PENDING_ROW_KEYS_HEADER,
            JsonArray(provenance.pendingRowKeys.toList()).toString(),
        )
    }
    return PostgrestResult(data, stamped.build(), postgrest)
}

/** Whether [result] was served from the cache, by its stamp; `false` when it carries none. */
internal fun isFromCacheStamped(result: PostgrestResult): Boolean =
    result.headers[SOURCE_HEADER] == CACHE_SOURCE

/** When [result]'s rows were fetched, by its stamp; `null` when it carries none. */
internal fun fetchedAtStamped(result: PostgrestResult): Instant? =
    result.headers[FETCHED_AT_HEADER]?.toLongOrNull()?.let(Instant::fromEpochMilliseconds)

/** Whether [result] shows a write still queued, by its stamp; `false` when it carries none. */
internal fun hasPendingMutationsStamped(result: PostgrestResult): Boolean =
    result.headers[PENDING_MUTATIONS_HEADER] == "true"

/**
 * The keys of the rows a write still queued changed in [result], by its stamp; empty when it
 * carries none, or one that does not read as a JSON array.
 */
internal fun pendingRowKeysStamped(result: PostgrestResult): Set<JsonObject> {
    val text = result.headers[PENDING_ROW_KEYS_HEADER] ?: return emptySet()
    val keys =
        try {
            Json.parseToJsonElement(text) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return emptySet()
    return keys.filterIsInstanceTo(LinkedHashSet())
}

/** The headers a provenance stamp is made of, lower case: header names match without case. */
private val PROVENANCE_HEADERS: Set<String> =
    setOf(SOURCE_HEADER, FETCHED_AT_HEADER, PENDING_MUTATIONS_HEADER, PENDING_ROW_KEYS_HEADER)
        .mapTo(HashSet()) { name -> name.lowercase() }

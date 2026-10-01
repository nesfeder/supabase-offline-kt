// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:Suppress("unused")

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.AccessTokenProvider
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.jan.supabase.postgrest.query.request.InsertRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.jan.supabase.postgrest.query.request.UpsertRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.internal.openPlatformClient
import io.github.nesfeder.supabase.offline.internal.toOfflineConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.serializer
import okio.Path

/**
 * Factory for creating a Supabase client preconfigured with PostgREST and wrapped in an
 * **offline-aware** client that transparently caches successful SELECT payloads and falls back to
 * them on network errors.
 *
 * ### Swift usage (build the offline client)
 *
 * ```swift
 * let url = AppSecrets.supabaseURL
 * let anon = AppSecrets.supabaseAnonKey
 * let client = SupabaseFactory.shared.offlineClient(supabaseUrl: url, supabaseAnonKey: anon)
 *
 * // Get a table builder (offline-aware):
 * let assets = client.get(table: "assets")
 * ```
 */
public object SupabaseFactory {

    /**
     * Create a **new** Supabase client (installing PostgREST) and wrap it with an offline-aware
     * client that uses KMP Room for local storage.
     *
     * @param supabaseUrl Your Supabase URL (e.g. "https://abc.supabase.co")
     * @param supabaseAnonKey Anon/API key
     * @param accessTokenProvider Optional provider for access tokens
     * @throws IllegalStateException when another open client already uses the same database or data
     *   directory; other failures to open it (a full disk, an unreadable file) are thrown too.
     *   Swift receives each as a thrown error.
     */
    @Throws(Exception::class)
    public fun offlineClientKt(
        supabaseUrl: String,
        supabaseAnonKey: String,
        accessTokenProvider: AccessTokenProvider? = null,
        offlineStoragePath: Path? = null,
        cacheSizeBytes: Long? = null,
        logger: OfflineLogger? = null,
        minLogLevel: OfflineLogLevel = OfflineLogLevel.INFO,
    ): Offline {
        val supabase =
            createSupabaseClient(
                supabaseUrl = supabaseUrl,
                supabaseKey = supabaseAnonKey,
            ) {
                accessToken = accessTokenProvider
                install(Postgrest)
                install(Storage)
            }
        val config = Offline.createConfig {
            cacheSizeBytes?.let { this.cacheSizeBytes = it }
            dataDirectory = offlineStoragePath?.toString()
            this.logger = logger
            this.minLogLevel = minLogLevel
        }
        return openPlatformClient(supabase, config)
    }
}

/**
 * Creates the offline client over an existing [SupabaseClient], outside its plugins.
 *
 * Install the plugin instead: `install(Offline) { … }` in `createSupabaseClient`, then
 * `supabase.offline`. It takes the same settings in [Offline.Config] and closes with the client.
 * This factory opens the same database in the same place, so an app moving to the plugin keeps its
 * cache and its unsent writes.
 *
 * The database is `Documents/supabase_offline.db` and downloaded files live under
 * `Documents/supabase_offline`, or both in [SupabaseOfflineConfig.offlineStoragePath] when that is
 * set; a database at the default location moves there. The database and the file cache are excluded
 * from iCloud and device backups: they are re-creatable, and one user's. One client per database:
 * while it is open, creating another on the same database throws. [Offline.close] releases it.
 *
 * @throws IllegalStateException when another open client already uses the same database or data
 *   directory; other failures to open it (a full disk, an unreadable file) are thrown too.
 */
@Deprecated(
    "Install the plugin instead: createSupabaseClient { install(Offline) { … } }, then use " +
        "supabase.offline. The settings move into install(Offline) { … }.",
    ReplaceWith("supabase.offline", "io.github.nesfeder.supabase.offline.offline"),
)
@Throws(Exception::class)
@Suppress("DEPRECATION")
public fun createSupabaseOfflineClient(
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig,
): Offline = openPlatformClient(supabase, config.toOfflineConfig())

/* ────────────────────────────────────────────────────────────────────────────── */
/*  Swift-friendly JSON helpers (Swift struct → JSON string / JsonArray / JsonEl) */
/* ────────────────────────────────────────────────────────────────────────────── */

/** Global JSON configured for Swift↔Kotlin bridging. */
public val SwiftJson: Json = Json {
    prettyPrint = false
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Encode any bridged Swift value to a JSON string using kotlinx.serialization.
 *
 * ### Swift
 *
 * ```swift
 * let json = SupabaseOffline.encodeToJsonString(value: mySwiftStruct)
 * ```
 */
public inline fun <reified T> encodeToJsonString(value: T): String = SwiftJson.encodeToString(value)

/**
 * Encode any bridged Swift value to a JsonElement (for updateJson, etc).
 *
 * ### Swift
 *
 * ```swift
 * let elem = SupabaseOffline.encodeToJsonElement(value: mySwiftStruct)
 * _ = try await qb.updateJson(body: elem)
 * ```
 */
public inline fun <reified T> encodeToJsonElement(value: T): JsonElement =
    SwiftJson.encodeToJsonElement(value)

/**
 * Encode a Swift List/Array to a JSON array string (for insert/upsert).
 *
 * ### Swift
 *
 * ```swift
 * let jsonArray = SupabaseOffline.encodeListToJsonString(list: [asset1, asset2])
 * _ = try await qb.insertJsonString(jsonArray: jsonArray,
 *                                   returningColumns: "id,name,status")
 * ```
 */
public inline fun <reified T> encodeListToJsonString(list: List<T>): String =
    SwiftJson.encodeToString(list)

/**
 * Encode a Swift List/Array to a JsonArray (for insertJson/upsertJson).
 *
 * ### Swift
 *
 * ```swift
 * let arr = SupabaseOffline.encodeListToJsonArray(list: [asset])
 * _ = try await qb.upsertJson(body: arr, onConflict: "serial_number")
 * ```
 */
public inline fun <reified T> encodeListToJsonArray(list: List<T>): JsonArray {
    val body = SwiftJson.encodeToJsonElement(ListSerializer(serializer()), list)
    return body.jsonArray
}

/**
 * Convenience: build a one-element JsonArray from a single JsonElement.
 *
 * ### Swift
 *
 * ```swift
 * let one = SupabaseOffline.singleElementArray(element: elem)
 * _ = try await qb.insertJson(body: one)
 * ```
 */
public fun singleElementArray(element: JsonElement): JsonArray = buildJsonArray { add(element) }

/* ── SELECT ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly SELECT for **ALL** columns.
 *
 * @param readStrategy The strategy for reading data (NetworkFirst, CacheFirst, etc.).
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.selectAllKt(
    readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
    orderBy: String? = null,
    ascending: Boolean = true,
    nullsFirst: Boolean = false,
    referencedTable: String? = null,
    limit: Int? = null,
    offset: Int? = null,
    count: Count? = null,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): PostgrestResult =
    select(columns = Columns.ALL, readStrategy = readStrategy) {
        applySwiftOptions(
            orderBy,
            ascending,
            nullsFirst,
            referencedTable,
            limit,
            offset,
            count,
            filter,
        )
    }

/**
 * Swift-friendly SELECT with a raw comma-separated column list (e.g., "id,name,status").
 *
 * @param readStrategy The strategy for reading data (NetworkFirst, CacheFirst, etc.).
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.selectRawKt(
    columns: String,
    readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
    orderBy: String? = null,
    ascending: Boolean = true,
    nullsFirst: Boolean = false,
    referencedTable: String? = null,
    limit: Int? = null,
    offset: Int? = null,
    count: Count? = null,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): PostgrestResult =
    select(columns = Columns.raw(columns), readStrategy = readStrategy) {
        applySwiftOptions(
            orderBy,
            ascending,
            nullsFirst,
            referencedTable,
            limit,
            offset,
            count,
            filter,
        )
    }

/**
 * Swift-friendly SELECT with a list of column names.
 *
 * @param readStrategy The strategy for reading data (NetworkFirst, CacheFirst, etc.).
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.selectColumnsKt(
    columns: List<String>,
    readStrategy: ReadStrategy = ReadStrategy.NetworkFirst(),
    orderBy: String? = null,
    ascending: Boolean = true,
    nullsFirst: Boolean = false,
    referencedTable: String? = null,
    limit: Int? = null,
    offset: Int? = null,
    count: Count? = null,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): PostgrestResult =
    selectRawKt(
        columns = columns.joinToString(","),
        readStrategy = readStrategy,
        orderBy = orderBy,
        ascending = ascending,
        nullsFirst = nullsFirst,
        referencedTable = referencedTable,
        limit = limit,
        offset = offset,
        count = count,
        filter = filter,
    )

/* ── INSERT ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly INSERT with a JSON **array** payload string.
 *
 * - `returningColumns` = null for Minimal; or pass "id,name" to return representation.
 *
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.insertJsonStringKt(
    jsonArray: String,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
): MutationOutcome {
    val arr = SwiftJson.parseToJsonElement(jsonArray) as JsonArray
    return insertJsonKt(arr, returningColumns, count, defaultToNull, mutationStrategy)
}

/**
 * Swift-friendly INSERT with a prebuilt [JsonArray].
 *
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.insertJsonKt(
    body: JsonArray,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
): MutationOutcome =
    insert(body, mutationStrategy) {
        applySwiftOptions(returningColumns, count, defaultToNull)
    }

/** Swift-friendly INSERT for a **single** Swift struct (Encodable bridged). */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insertOneKt(
    value: T,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: InsertRequestBuilder.() -> Unit = {},
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    val arr = buildJsonArray { add(elem) }
    return insert(arr, mutationStrategy) {
        applySwiftOptions(returningColumns, count, defaultToNull)
        request()
    }
}

/** Swift-friendly INSERT for a **list** of Swift structs. */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insertManyKt(
    values: List<T>,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: InsertRequestBuilder.() -> Unit = {},
): MutationOutcome {
    val body = SwiftJson.encodeToJsonElement(ListSerializer(serializer()), values).jsonArray
    return insert(body, mutationStrategy) {
        applySwiftOptions(returningColumns, count, defaultToNull)
        request()
    }
}

/* ── UPSERT ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly UPSERT with a JSON **array** payload string.
 *
 * Use `onConflict` for natural keys (e.g., "serial_number").
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.upsertJsonStringKt(
    jsonArray: String,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
): MutationOutcome {
    val arr = SwiftJson.parseToJsonElement(jsonArray) as JsonArray
    return upsertJsonKt(
        arr,
        onConflict,
        ignoreDuplicates,
        returningColumns,
        count,
        defaultToNull,
        mutationStrategy,
    )
}

/** Swift-friendly UPSERT with a prebuilt [JsonArray]. */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.upsertJsonKt(
    body: JsonArray,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
): MutationOutcome =
    upsert(body, mutationStrategy) {
        applySwiftOptions(onConflict, ignoreDuplicates, returningColumns, count, defaultToNull)
    }

/** Swift-friendly UPSERT for a single Swift struct. */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsertOneKt(
    value: T,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: UpsertRequestBuilder.() -> Unit = {},
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    val arr = buildJsonArray { add(elem) }
    return upsert(arr, mutationStrategy) {
        applySwiftOptions(onConflict, ignoreDuplicates, returningColumns, count, defaultToNull)
        request()
    }
}

/** Swift-friendly UPSERT for a list of Swift structs. */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsertManyKt(
    values: List<T>,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: UpsertRequestBuilder.() -> Unit = {},
): MutationOutcome {
    val body = SwiftJson.encodeToJsonElement(ListSerializer(serializer()), values).jsonArray
    return upsert(body, mutationStrategy) {
        applySwiftOptions(onConflict, ignoreDuplicates, returningColumns, count, defaultToNull)
        request()
    }
}

/* ── UPDATE ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly UPDATE using a JSON **object** string.
 *
 * Provide the row targeting via the `filter` closure.
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.updateJsonStringKt(
    jsonObject: String,
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): MutationOutcome {
    val body = SwiftJson.parseToJsonElement(jsonObject)
    return updateJsonKt(body, returningColumns, count, mutationStrategy, filter)
}

/** Swift-friendly UPDATE with a [JsonElement] body. */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.updateJsonKt(
    body: JsonElement,
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): MutationOutcome =
    update(body, mutationStrategy) {
        applySwiftOptions(returningColumns, count, filter)
    }

/** Swift-friendly UPDATE for a single Swift struct. */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.updateOneKt(
    value: T,
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: PostgrestRequestBuilder.() -> Unit = {},
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    return update(elem, mutationStrategy) {
        applySwiftOptions(returningColumns, count, null)
        request()
    }
}

/* ── DELETE ─────────────────────────────────────────────────────────────────── */

/** Swift-friendly DELETE with optional PostgREST filters. */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.deleteWhereKt(
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null,
): MutationOutcome =
    delete(mutationStrategy) {
        applySwiftOptions(returningColumns, count, filter)
    }

/* ────────────────────────────────────────────────────────────────────────────── */
/*  SHIM HELPER IMPLEMENTATIONS (correct builder calls & imports)                */
/* ────────────────────────────────────────────────────────────────────────────── */

/**
 * Applies the options the Swift-facing insert helpers expose to an INSERT request.
 *
 * @param returningColumns Columns to return, or `null` / empty for none.
 * @param count Row-count mode, or `null` for none.
 * @param defaultToNull Whether missing columns become `null` rather than their default; `null`
 *   keeps the builder's default.
 */
public fun InsertRequestBuilder.applySwiftOptions(
    returningColumns: String?,
    count: Count?,
    defaultToNull: Boolean?,
) {
    if (!returningColumns.isNullOrEmpty()) select(Columns.raw(returningColumns))
    if (count != null) count(count)
    if (defaultToNull != null) this.defaultToNull = defaultToNull
}

/**
 * Applies the options the Swift-facing upsert helpers expose to an UPSERT request.
 *
 * @param onConflict Columns that identify a conflicting row, or `null` for the primary key.
 * @param ignoreDuplicates Whether a conflicting row is left untouched rather than merged.
 * @param returningColumns Columns to return, or `null` / empty for none.
 * @param count Row-count mode, or `null` for none.
 * @param defaultToNull Whether missing columns become `null` rather than their default; `null`
 *   keeps the builder's default.
 */
public fun UpsertRequestBuilder.applySwiftOptions(
    onConflict: String?,
    ignoreDuplicates: Boolean,
    returningColumns: String?,
    count: Count?,
    defaultToNull: Boolean?,
) {
    if (onConflict != null) this.onConflict = onConflict
    this.ignoreDuplicates = ignoreDuplicates
    if (!returningColumns.isNullOrEmpty()) select(Columns.raw(returningColumns))
    if (count != null) count(count)
    if (defaultToNull != null) this.defaultToNull = defaultToNull
}

/**
 * Applies the options the Swift-facing update and delete helpers expose to a request.
 *
 * @param returningColumns Columns to return, or `null` / empty for none.
 * @param count Row-count mode, or `null` for none.
 * @param filter The rows to target, or `null` for none.
 */
public fun PostgrestRequestBuilder.applySwiftOptions(
    returningColumns: String?,
    count: Count?,
    filter: (PostgrestFilterBuilder.() -> Unit)?,
) {
    if (!returningColumns.isNullOrEmpty()) select(Columns.raw(returningColumns))
    if (count != null) count(count)
    if (filter != null) {
        filter { filter() }
    }
}

private fun SelectRequestBuilder.applySwiftOptions(
    orderBy: String?,
    ascending: Boolean,
    nullsFirst: Boolean,
    referencedTable: String?,
    limit: Int?,
    offset: Int?,
    count: Count?,
    filter: (PostgrestFilterBuilder.() -> Unit)?,
) {
    if (count != null) {
        count(count)
    }
    if (orderBy != null) {
        order(
            column = orderBy,
            order = if (ascending) Order.ASCENDING else Order.DESCENDING,
            nullsFirst = nullsFirst,
            referencedTable = referencedTable,
        )
    }
    if (limit != null || offset != null) {
        val start = (offset ?: 0).coerceAtLeast(0)
        val end = (start + ((limit ?: 0).coerceAtLeast(0) - 1)).coerceAtLeast(start)
        range(start.toLong()..end.toLong(), referencedTable)
    }
    if (filter != null) {
        filter { filter() }
    }
}

@file:Suppress("unused")

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.DEFAULT_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.internal.db.IosDbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.getDatabaseBuilder
import io.github.nesfeder.supabase.offline.internal.db.getRoomDatabase
import io.github.nesfeder.supabase.offline.internal.storage.PlatformBaseDirectory
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
 * Factory for creating a Supabase client preconfigured with PostgREST
 * and wrapped in an **offline-aware** client that transparently caches
 * successful SELECT payloads and falls back to them on network errors.
 *
 * ### Swift usage (build the offline client)
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
     * Create a **new** Supabase client (installing PostgREST) and wrap it with
     * an offline-aware client that uses KMP Room for local storage.
     *
     * @param supabaseUrl  Your Supabase URL (e.g. "https://abc.supabase.co")
     * @param supabaseAnonKey  Anon/API key
     * @param accessTokenProvider Optional provider for access tokens
     */
    public fun offlineClientKt(
        supabaseUrl: String,
        supabaseAnonKey: String,
        accessTokenProvider: AccessTokenProvider? = null,
        offlineStoragePath: Path? = null,
        cacheSizeBytes: Long? = null,
        logger: OfflineLogger? = null,
        minLogLevel: OfflineLogLevel = OfflineLogLevel.INFO
    ): SupabaseOfflineClient {
        val supabase = createSupabaseClient(
            supabaseUrl = supabaseUrl,
            supabaseKey = supabaseAnonKey
        ) {
            accessToken = accessTokenProvider
            install(Postgrest)
            install(Storage)
        }
        return createSupabaseOfflineClient(
            supabase = supabase,
            config = SupabaseOfflineConfig(
                cacheSizeBytes ?: DEFAULT_CACHE_SIZE_BYTES,
                offlineStoragePath = offlineStoragePath,
                logger = logger,
                minLogLevel = minLogLevel
            )
        )
    }
}

/**
 * Construct an offline-aware Supabase client reusing an **existing** [SupabaseClient].
 *
 * This wraps your client with:
 * - PostgREST passthrough for writes (insert/upsert/update/delete)
 * - SELECT that caches server payloads under stable signatures & falls back when offline
 *
 * ### Swift usage (pull assets)
 * ```swift
 * // Build once (e.g. in App start)
 * let offline = SupabaseFactory.shared.offlineClient(
 *   supabaseUrl: AppSecrets.supabaseURL,
 *   supabaseAnonKey: AppSecrets.supabaseAnonKey
 * )
 *
 * // Later in a ViewModel
 * let qb = offline.get(table: "assets")
 * let res = try await qb.selectAll(
 *   orderBy: "updated_at",
 *   ascending: false,
 *   limit: 50,
 *   offset: 0
 * ) { f in
 *   // Optional filters:
 *   // f.ilike(column: "name", pattern: "%pump%")
 *   // f.neq(column: "status", value: "archived")
 * }
 *
 * // Decode JSON -> Swift models
 * let data = Data(res.data.utf8)
 * let items = try JSONDecoder().decode([Asset].self, from: data)
 * ```
 */
public fun createSupabaseOfflineClient(
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig,
): SupabaseOfflineClient {
    val dbPathProvider = IosDbPathProvider()
    val dbPath = dbPathProvider.dbPath().toString()
    val builder = getDatabaseBuilder(dbPath)
    val db = getRoomDatabase(builder)       // shared KMP Room db

    // Resolve base directory for cached Storage files, preferring
    // configuration over the explicit parameter, then falling back
    // to the original platform default.
    val baseDirPath = config.offlineStoragePath ?: PlatformBaseDirectory().path
    val baseDirProvider = { baseDirPath }

    val maxDbSizeBytes = if (config.cacheSizeBytes == CACHE_SIZE_UNLIMITED) {
        // Unlimited cache: disable size-based eviction.
        null
    } else {
        // Enforce a minimum on-disk DB size so callers cannot configure a
        // limit below the typical minimum Room database file size.
        maxOf(config.cacheSizeBytes, MIN_CACHE_SIZE_BYTES)
    }

    val cacheSettings = OfflineCacheSettings(
        maxDbSizeBytes = maxDbSizeBytes,
        // Evict a small batch of rows per pass; the exact size reduction per
        // batch depends on payload sizes and SQLite page layout.
        evictionBatchSize = 10,
        // Try to keep at least ~50 MB of cached data even during aggressive
        // cleanup so we don't wipe everything under normal circumstances.
        minRetainedBytes = 50L * 1024L * 1024L,   // ~50 MB
    )

    val store = OfflineStore(
        objectDao = db.objectDao(),
        storageDao = db.storageDao(),
        mutationDao = db.mutationDao(),
        dbPathProvider = dbPathProvider,
        settings = cacheSettings,
    )

    return SupabaseOfflineClientImpl(
        supabase = supabase,
        store = store,
        baseDirectoryProvider = baseDirProvider,
        sdkLogger = SdkLogger(config.logger, config.minLogLevel),
        networkAvailability = config.networkAvailability,
    )
}

/* ────────────────────────────────────────────────────────────────────────────── */
/*  Swift-friendly JSON helpers (Swift struct → JSON string / JsonArray / JsonEl) */
/* ────────────────────────────────────────────────────────────────────────────── */

/**
 * Global JSON configured for Swift↔Kotlin bridging.
 */
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
 * ```swift
 * let json = SupabaseOffline.encodeToJsonString(value: mySwiftStruct)
 * ```
 */
public inline fun <reified T> encodeToJsonString(value: T): String =
    SwiftJson.encodeToString(value)

/**
 * Encode any bridged Swift value to a JsonElement (for updateJson, etc).
 *
 * ### Swift
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
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): PostgrestResult = select(columns = Columns.ALL, readStrategy = readStrategy) {
    applySwiftOptions(orderBy, ascending, nullsFirst, referencedTable, limit, offset, count, filter)
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
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): PostgrestResult = select(columns = Columns.raw(columns), readStrategy = readStrategy) {
    applySwiftOptions(orderBy, ascending, nullsFirst, referencedTable, limit, offset, count, filter)
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
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): PostgrestResult = selectRawKt(
    columns = columns.joinToString(","),
    readStrategy = readStrategy,
    orderBy = orderBy,
    ascending = ascending,
    nullsFirst = nullsFirst,
    referencedTable = referencedTable,
    limit = limit,
    offset = offset,
    count = count,
    filter = filter
)

/* ── INSERT ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly INSERT with a JSON **array** payload string.
 *
 * - `returningColumns` = null for Minimal; or pass "id,name" to return representation.
 * @param count Optional count algorithm (e.g. Count.EXACT).
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.insertJsonStringKt(
    jsonArray: String,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline
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
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline
): MutationOutcome = insertJson(body, mutationStrategy) {
    applySwiftOptions(returningColumns, count, defaultToNull)
}

/**
 * Swift-friendly INSERT for a **single** Swift struct (Encodable bridged).
 */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insertOneKt(
    value: T,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: InsertRequestBuilder.() -> Unit = {}
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    val arr = buildJsonArray { add(elem) }
    return insertJson(arr, mutationStrategy) {
        applySwiftOptions(returningColumns, count, defaultToNull)
        request()
    }
}

/**
 * Swift-friendly INSERT for a **list** of Swift structs.
 */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.insertManyKt(
    values: List<T>,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: InsertRequestBuilder.() -> Unit = {}
): MutationOutcome {
    val body = SwiftJson.encodeToJsonElement(ListSerializer(serializer()), values).jsonArray
    return insertJson(body, mutationStrategy) {
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
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline
): MutationOutcome {
    val arr = SwiftJson.parseToJsonElement(jsonArray) as JsonArray
    return upsertJsonKt(
        arr,
        onConflict,
        ignoreDuplicates,
        returningColumns,
        count,
        defaultToNull,
        mutationStrategy
    )
}

/**
 * Swift-friendly UPSERT with a prebuilt [JsonArray].
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.upsertJsonKt(
    body: JsonArray,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline
): MutationOutcome = upsertJson(body, mutationStrategy) {
    applySwiftOptions(onConflict, ignoreDuplicates, returningColumns, count, defaultToNull)
}

/**
 * Swift-friendly UPSERT for a single Swift struct.
 */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsertOneKt(
    value: T,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: UpsertRequestBuilder.() -> Unit = {}
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    val arr = buildJsonArray { add(elem) }
    return upsertJson(arr, mutationStrategy) {
        applySwiftOptions(onConflict, ignoreDuplicates, returningColumns, count, defaultToNull)
        request()
    }
}

/**
 * Swift-friendly UPSERT for a list of Swift structs.
 */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.upsertManyKt(
    values: List<T>,
    onConflict: String? = null,
    ignoreDuplicates: Boolean = false,
    returningColumns: String? = null,
    count: Count? = null,
    defaultToNull: Boolean? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: UpsertRequestBuilder.() -> Unit = {}
): MutationOutcome {
    val body = SwiftJson.encodeToJsonElement(ListSerializer(serializer()), values).jsonArray
    return upsertJson(body, mutationStrategy) {
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
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): MutationOutcome {
    val body = SwiftJson.parseToJsonElement(jsonObject)
    return updateJsonKt(body, returningColumns, count, mutationStrategy, filter)
}

/**
 * Swift-friendly UPDATE with a [JsonElement] body.
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.updateJsonKt(
    body: JsonElement,
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): MutationOutcome = updateJson(body, mutationStrategy) {
    applySwiftOptions(returningColumns, count, filter)
}

/**
 * Swift-friendly UPDATE for a single Swift struct.
 */
@Throws(Exception::class)
public suspend inline fun <reified T : Any> OfflinePostgrestQueryBuilder.updateOneKt(
    value: T,
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    crossinline request: PostgrestRequestBuilder.() -> Unit = {}
): MutationOutcome {
    val elem = SwiftJson.encodeToJsonElement(value)
    return updateJson(elem, mutationStrategy) {
        applySwiftOptions(returningColumns, count, null)
        request()
    }
}

/* ── DELETE ─────────────────────────────────────────────────────────────────── */

/**
 * Swift-friendly DELETE with optional PostgREST filters.
 */
@Throws(Exception::class)
public suspend fun OfflinePostgrestQueryBuilder.deleteWhereKt(
    returningColumns: String? = null,
    count: Count? = null,
    mutationStrategy: MutationStrategy = MutationStrategy.QueueIfOffline,
    filter: (PostgrestFilterBuilder.() -> Unit)? = null
): MutationOutcome = delete(mutationStrategy) {
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
    defaultToNull: Boolean?
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
    defaultToNull: Boolean?
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
    filter: (PostgrestFilterBuilder.() -> Unit)?
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
    filter: (PostgrestFilterBuilder.() -> Unit)?
) {
    if (count != null) {
        count(count)
    }
    if (orderBy != null) {
        order(
            column = orderBy,
            order = if (ascending) Order.ASCENDING else Order.DESCENDING,
            nullsFirst = nullsFirst,
            referencedTable = referencedTable
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
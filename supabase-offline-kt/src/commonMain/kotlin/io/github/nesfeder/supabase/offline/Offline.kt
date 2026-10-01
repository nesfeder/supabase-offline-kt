// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.encodeToJsonElement
import io.github.jan.supabase.plugins.SupabasePlugin
import io.github.jan.supabase.plugins.SupabasePluginProvider
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.Storage
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.OfflinePlugin
import io.github.nesfeder.supabase.offline.internal.openPlatformClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The offline plugin for supabase-kt: PostgREST reads served from a cache on the device, writes
 * queued while the network is down and sent when it is back, and Storage downloads kept on disk.
 *
 * Install it beside [Postgrest] (and [Storage], if you use it) and reach it as [offline], as
 * supabase-kt's own plugins are reached:
 * ```kotlin
 * val supabase = createSupabaseClient(url, key) {
 *     install(Auth)
 *     install(Postgrest)
 *     install(Storage)
 *     install(Offline) { cacheSizeBytes = 50L * 1024 * 1024 }
 * }
 * val todos = supabase.offline.from("todos").select { filter { eq("done", false) } }
 * ```
 *
 * Calls written against supabase-kt's `supabase.from(...)` and `supabase.storage.from(...)` compile
 * against `supabase.offline.from(...)` and `supabase.offline.storage.from(...)` with only the
 * receiver changed. The one difference a caller sees is that a write returns [MutationOutcome].
 *
 * The plugin opens its database when the client is created, and one database serves one client at a
 * time: creating a second client with the plugin over the same data directory throws until the
 * first is closed. It is closed with the client ([SupabaseClient.close]), or on its own with
 * [close].
 *
 * Only the library implements this interface; [OfflineInternal] marks it so. Tests build a client
 * with the `supabase-offline-kt-testing` artifact.
 */
@SubclassOptInRequired(OfflineInternal::class)
public interface Offline : SupabasePlugin<Offline.Config> {

    /** The client this plugin was installed in. */
    @Deprecated(
        "Renamed supabaseClient, as supabase-kt's plugins name it",
        ReplaceWith("supabaseClient"),
    )
    public val supabase: SupabaseClient
        get() = supabaseClient

    /**
     * The [table] in the client's default schema, read through the cache and written through the
     * mutation queue.
     *
     * @param table the table or view.
     * @return the table's builder.
     * @throws IllegalStateException when the client is closed, or [Postgrest] is not installed.
     */
    public fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * The [table] in [schema], read through the cache. Writes to a schema other than the client's
     * default are sent at once and never queued; see [MutationStrategy.QueueIfOffline].
     *
     * @param schema the schema.
     * @param table the table or view.
     * @return the table's builder.
     * @throws IllegalStateException when the client is closed, or [Postgrest] is not installed.
     */
    public fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /**
     * The [table] in the client's default schema; `offline["todos"]` is `offline.from("todos")`.
     *
     * @param table the table or view.
     * @return the table's builder.
     */
    public operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /**
     * The [table] in [schema]; `offline["app", "todos"]` is `offline.from("app", "todos")`.
     *
     * @param schema the schema.
     * @param table the table or view.
     * @return the table's builder.
     */
    public operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Calls the database function [function] on the server, as `supabase.postgrest.rpc` does.
     *
     * Network only: nothing is cached and nothing is queued, since a function may change data. It
     * fails offline with supabase-kt's own error.
     *
     * @param function the function's name.
     * @param request the request: the HTTP method, a count, filters on the result.
     * @return the server's response.
     */
    public suspend fun rpc(
        function: String,
        request: RpcRequestBuilder.() -> Unit = {},
    ): PostgrestResult

    /**
     * Calls the database function [function] with [parameters] on the server, as
     * `supabase.postgrest.rpc` does. Network only, like the overload without parameters.
     *
     * @param function the function's name.
     * @param parameters the function's arguments, by name.
     * @param request the request: the HTTP method, a count, filters on the result.
     * @return the server's response.
     */
    public suspend fun rpc(
        function: String,
        parameters: JsonObject,
        request: RpcRequestBuilder.() -> Unit = {},
    ): PostgrestResult

    /**
     * Storage buckets, with downloads kept on the device: `offline.storage.from("avatars")`, as
     * `supabase.storage.from("avatars")`. Needs [Storage] installed.
     */
    public val storage: OfflineStorage

    /**
     * The bucket [bucket], with downloads kept on the device.
     *
     * @param bucket the bucket's id.
     * @return the bucket.
     */
    @Deprecated(
        "Use storage.from(bucket), as supabase-kt's storage is reached",
        ReplaceWith("storage.from(bucket)"),
    )
    public fun storage(bucket: String): OfflineBucketApi = storage.from(bucket)

    /**
     * Sends the current user's queued writes, in every table, in the order they were made.
     *
     * The pass first waits, for a bounded time, for the session to settle (signed in, or signed
     * out); when it does not, nothing is sent and 0 is returned. Another user's writes are neither
     * sent nor counted. A failure that may pass (no connection, a `401`, `408`, `429` or `5xx`)
     * stops the pass with every remaining write still queued, so no write overtakes an earlier one;
     * it is not thrown. A write the server refuses for good (another `4xx`) is taken out of the
     * queue and kept on a rejected list, and the pass goes on.
     *
     * @return how many writes the server accepted.
     */
    @Throws(Exception::class) public suspend fun syncAllPendingMutations(): Int

    /**
     * Sends the current user's queued writes to [table], in the order they were made, as
     * [syncAllPendingMutations] does for every table.
     *
     * A `403` or `409` on a write with an earlier write of the same user pending in another table
     * is held rather than rejected: that earlier write may be the row it depends on. A failure that
     * may pass is thrown, with the write still queued.
     *
     * @param table the table.
     * @return how many writes the server accepted.
     */
    @Throws(Exception::class) public suspend fun syncPendingMutations(table: String): Int

    /**
     * How many of the current user's writes wait in the mutation queue, in every table.
     *
     * @return the count.
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(): Int

    /**
     * How many of the current user's writes wait in the mutation queue, in every table, as it
     * changes. Collected after [close], or live at it, the flow fails with [IllegalStateException].
     *
     * @return the flow of counts.
     */
    public fun pendingMutationCountFlow(): Flow<Int>

    /**
     * How many of the current user's writes to [table] wait in the mutation queue.
     *
     * @param table the table.
     * @return the count.
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(table: String): Int

    /**
     * How many of the current user's writes to [table] wait in the mutation queue, as it changes.
     * Collected after [close], or live at it, the flow fails with [IllegalStateException].
     *
     * @param table the table.
     * @return the flow of counts.
     */
    public fun pendingMutationCountFlow(table: String): Flow<Int>

    /**
     * Applies a row the server reports, such as a Realtime `postgres_changes` INSERT or UPDATE
     * record, to every cached query of [table]: the cached row with the same key is replaced, and a
     * query that admits the row and does not hold it gains it.
     *
     * Nothing happens when nothing of [table] is cached, or when [record] lacks a key column or
     * holds one as `null`, an array or an object: the row cannot be located, so the record is
     * logged and dropped.
     *
     * It acts on [table] in the client's default schema. A table of another schema, cached apart,
     * cannot be named here.
     *
     * This keeps the cache current between refreshes. It does not replace them: Realtime does not
     * replay what happened while the device was disconnected, so read again after a reconnect.
     *
     * @param table the table, in the client's default schema.
     * @param record the row as the server sent it.
     * @param primaryKeys the columns that identify the row, or `null` for the table's primary key
     *   as [Config.primaryKeys] gives it (`id` unless configured).
     */
    public suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>? = null,
    )

    /**
     * Removes a row the server reports deleted, such as the `old_record` of a Realtime
     * `postgres_changes` DELETE, from every cached query of [table].
     *
     * Nothing happens under the same conditions as for [applyServerRecord], and it too acts on
     * [table] in the client's default schema only.
     *
     * @param table the table, in the client's default schema.
     * @param oldRecord the deleted row's values, carrying at least its key.
     * @param primaryKeys the columns that identify the row, or `null` for the table's primary key
     *   as [Config.primaryKeys] gives it (`id` unless configured).
     */
    public suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>? = null,
    )

    /**
     * Deletes everything the library keeps on this device: cached query results, downloaded Storage
     * files, **and every write still waiting in the mutation queue, which is never sent**, with the
     * rejected list. It does not touch the server.
     *
     * Call it when a user signs out for good, switches account, or deletes their account, so one
     * user's rows, unsent writes and files never reach the next. With [Auth] installed, the cache
     * already belongs to one user at a time (it is dropped when the session settles as someone
     * else), but queued writes stay with the user who made them until this call.
     *
     * A replay pass in progress is cancelled first, and nothing queued is sent once clearing has
     * begun. A write already on the wire is neither applied to the cache nor queued when it
     * answers. A Storage download in flight may hand its bytes to its caller, or fail, but is not
     * written back. The deleted rows cannot be read back from the database's files either: the
     * database is rewritten without them.
     *
     * The client stays usable afterwards.
     */
    public suspend fun clearLocalData()

    /**
     * Deletes everything the library keeps on this device, unsent writes included; see
     * [clearLocalData].
     */
    @Deprecated(
        "Renamed clearLocalData: it deletes unsent writes as well as the cache",
        ReplaceWith("clearLocalData()"),
    )
    public suspend fun clearAllCache(): Unit = clearLocalData()

    /**
     * Closes the plugin and frees its database for another client. Nothing is deleted.
     * [SupabaseClient.close] calls it, after supabase-kt has closed its HTTP client; call this
     * first to let requests already on the wire answer.
     *
     * What ends, and when:
     * - At once, every new call fails with [IllegalStateException]: on the plugin, and on a
     *   builder, bucket or flow obtained from it.
     * - At once, every flow being collected stops, whatever its read strategy: its collector reads
     *   that error next. A channel the app holds through `produceIn` applied directly to the flow,
     *   and no longer reads, ends with it quietly, failing no scope; with an operator such as `map`
     *   between them, the error fails the producer.
     * - At once, the plugin's own work stops (refreshes, replay passes), and so does every Storage
     *   call and sync; their callers get the same error, never a cancellation. A replay pass with a
     *   queued write on the wire finishes that request and records its answer first, so the next
     *   client does not send the write again. A stopped Storage write may still have been applied,
     *   so the cached copies it touched are dropped.
     * - Every other call already running is left to finish: a PostgREST write, a clear, a one-shot
     *   `select` that goes to the network, an [rpc], a read of the cache, and the start or
     *   continuation of a resumable upload. A clear that has begun always completes, and a write
     *   whose answer arrives is applied and never sent again.
     *
     * Every call returns once the database is released, or after 10 seconds of real time at most,
     * even under a test's virtual clock. Past that, the database is released as soon as the last of
     * those calls finishes; until then, a new client on it fails with an error saying it is still
     * closing. Safe to call more than once, and from inside a collector of one of the plugin's
     * flows.
     */
    override suspend fun close()

    /**
     * How the plugin is set up, in `install(Offline) { … }`.
     *
     * Every setting has a default that suits most apps; set only what you need.
     */
    public class Config internal constructor() {

        /**
         * The most the cached query results may hold, in bytes, counted as the summed length of the
         * cached responses (not the database file, which SQLite never shrinks). When it is passed,
         * the results fetched longest ago are evicted first; a query a live reader shows is kept.
         * [CACHE_SIZE_UNLIMITED] turns eviction off. Below 4 KiB counts as 4 KiB. 100 MiB by
         * default.
         */
        public var cacheSizeBytes: Long = DEFAULT_CACHE_SIZE_BYTES

        /**
         * The most the Storage file cache may hold, in bytes. When a download passes it, the least
         * recently used files are deleted until it fits; a file being read, and one used in the
         * last 30 seconds, are kept. [CACHE_SIZE_UNLIMITED] turns eviction off; below zero counts
         * as zero. 256 MiB by default.
         */
        public var fileCacheSizeBytes: Long = DEFAULT_FILE_CACHE_SIZE_BYTES

        /**
         * The directory for everything the plugin keeps on the device: the database
         * (`supabase_offline.db`) and the Storage file cache. `null`, the default, keeps them where
         * the platform's convention puts them: the database in the app's database directory and the
         * files under `<filesDir>/supabase_offline` on Android, both under `Documents` on iOS.
         *
         * When the directory has no database yet and the default location has one, the first client
         * moves it here, unsent writes included; when it cannot, the default one stays in use and a
         * warning is logged.
         *
         * On Android, keep this directory out of Auto Backup: it holds one user's rows and writes.
         * On iOS the plugin excludes its files from backups itself.
         */
        public var dataDirectory: String? = null

        /**
         * The app's own connectivity signal, `true` while the device is online.
         *
         * Today the plugin uses it only to retry the refresh of a reactive read at once when the
         * signal turns online, instead of waiting out its backoff. A later release watches
         * connectivity itself, uses this signal in place of its own when set, and sends queued
         * writes when the network comes back; until then it is experimental. `null` by default.
         */
        @OfflineExperimental public var networkAvailability: Flow<Boolean>? = null

        /**
         * Whether the plugin sends queued writes by itself, without the app calling
         * [syncAllPendingMutations]. `true` by default.
         *
         * Experimental: in this release the plugin always does, whatever this says. A later release
         * makes `false` stop the passes it starts when the session settles and when the network
         * comes back; a write queued behind an earlier one is still sent by itself.
         */
        @OfflineExperimental public var autoSync: Boolean = true

        /**
         * Each table's primary-key columns, by table name in the client's default schema. A table
         * not named here has the key `id`.
         *
         * The key is how the cache recognises a row: where a queued or applied upsert lands, which
         * row a server record replaces, and which rows a queued write reports in
         * [PendingMutation.rowKeys]. Set it for every table whose key is not `id`, such as one with
         * a composite key. Use [primaryKey] to add one.
         */
        public val primaryKeys: MutableMap<String, List<String>> = mutableMapOf()

        /**
         * Sets [table]'s primary key to [columns], in [primaryKeys].
         *
         * @param table the table, in the client's default schema.
         * @param columns the key's columns, in any order; at least one.
         * @throws IllegalArgumentException when [columns] is empty.
         */
        public fun primaryKey(table: String, vararg columns: String) {
            require(columns.isNotEmpty()) { "A primary key needs at least one column" }
            primaryKeys[table] = columns.toList()
        }

        /**
         * Which responses from the server may replace what the cache holds;
         * [ResponseTrust.SettledSession] by default. See [ResponseTrust].
         */
        public var responseTrust: ResponseTrust = ResponseTrust.SettledSession

        /**
         * Where the plugin's log messages go, or `null` (the default) for nowhere. Messages at
         * [OfflineLogLevel.INFO] and above never carry rows, filter values or tokens.
         */
        public var logger: OfflineLogger? = null

        /** The least severe level [logger] receives; [OfflineLogLevel.INFO] by default. */
        public var minLogLevel: OfflineLogLevel = OfflineLogLevel.INFO

        /** Opens the plugin's local data: the platform's database, unless a test replaces it. */
        internal var openLocalData: (SupabaseClient, Config) -> OfflineClient = ::openPlatformClient
    }

    /** Installs the plugin: `install(Offline) { … }`. */
    public companion object : SupabasePluginProvider<Config, Offline> {

        /** The size that turns eviction off, for [Config.cacheSizeBytes] and its file twin. */
        public const val CACHE_SIZE_UNLIMITED: Long = -1L

        /** The key the plugin is installed under, `"offline"`. */
        override val key: String = "offline"

        /**
         * The configuration [init] writes.
         *
         * @throws IllegalArgumentException when a table's primary key names no column.
         */
        override fun createConfig(init: Config.() -> Unit): Config =
            Config().apply(init).also { config ->
                config.primaryKeys.forEach { (table, columns) ->
                    require(columns.isNotEmpty()) { "The primary key of $table names no column" }
                }
            }

        /**
         * The plugin for [supabaseClient]. It opens its database once every plugin of the client
         * exists, in [SupabasePlugin.init].
         */
        override fun create(supabaseClient: SupabaseClient, config: Config): Offline =
            OfflinePlugin(supabaseClient, config)

        /** The default of [Config.cacheSizeBytes]: 100 MiB. */
        internal const val DEFAULT_CACHE_SIZE_BYTES: Long = 100L * 1024L * 1024L

        /** The default of [Config.fileCacheSizeBytes]: 256 MiB. */
        internal const val DEFAULT_FILE_CACHE_SIZE_BYTES: Long = 256L * 1024L * 1024L

        /** The least [Config.cacheSizeBytes] counts as: one SQLite page. */
        internal const val MIN_CACHE_SIZE_BYTES: Long = 4_096L
    }
}

/**
 * The [Offline] plugin installed in this client.
 *
 * @throws IllegalStateException when the client was built without `install(Offline)`.
 */
public val SupabaseClient.offline: Offline
    get() = pluginManager.getPlugin(Offline)

/**
 * Calls the database function [function] with [parameters], encoded by the client's PostgREST
 * serializer, on the server, as supabase-kt's `Postgrest.rpc` with a typed argument does. Network
 * only; see [Offline.rpc].
 *
 * @param function the function's name.
 * @param parameters the function's arguments, an object that encodes to a JSON object.
 * @param request the request: the HTTP method, a count, filters on the result.
 * @return the server's response.
 */
public suspend inline fun <reified T : Any> Offline.rpc(
    function: String,
    parameters: T,
    noinline request: RpcRequestBuilder.() -> Unit = {},
): PostgrestResult =
    rpc(
        function,
        supabaseClient.postgrest.serializer.encodeToJsonElement(parameters).jsonObject,
        request,
    )

/**
 * Applies a row the server reports to every cached query of [table], encoded by the client's
 * PostgREST serializer; see [Offline.applyServerRecord].
 *
 * @param table the table, in the client's default schema.
 * @param record the row, an object that encodes to a JSON object.
 * @param primaryKeys the columns that identify the row, or `null` for the table's primary key.
 */
public suspend inline fun <reified T : Any> Offline.applyServerRecord(
    table: String,
    record: T,
    primaryKeys: List<String>? = null,
) {
    applyServerRecord(
        table,
        supabaseClient.postgrest.serializer.encodeToJsonElement(record).jsonObject,
        primaryKeys,
    )
}

package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.db.DbPathProvider
import io.github.nesfeder.supabase.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.storage.OfflineBucketApi
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.flow.Flow
import okio.Path

/**
 * Entry point for the Supabase Offline SDK.
 *
 * Wraps a regular [SupabaseClient] and exposes:
 * - Offline-aware PostgREST builders (`from(...)`, `get(...)`)
 * - Offline-aware Storage bucket access (`storage(...)`)
 * - Manual mutation sync APIs (`syncAllPendingMutations`, `syncPendingMutations`)
 * - Introspection helpers for pending mutations (counts)
 */
interface SupabaseOfflineClient {

    /** Underlying online Supabase client. */
    val supabase: SupabaseClient

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [table]
     * in the default schema.
     */
    fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [schema] and [table].
     */
    fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /** Operator sugar for `client["table"]` (default schema). */
    operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /** Operator sugar for `client["schema","table"]`. */
    operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Returns an offline-aware [BucketApi] wrapper for [bucket].
     *
     * Downloads are cached to disk and reused when offline.
     */
    fun storage(bucket: String): BucketApi

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    /**
     * Attempts to push **all** pending mutations (INSERT/UPSERT/UPDATE/DELETE)
     * for **all tables** to Supabase.
     *
     * - Mutations are replayed in creation order per table.
     * - A network / connectivity failure while syncing a table stops processing
     *   further mutations **for that table only**, leaving the remaining
     *   mutations in the queue.
     * - An unknown server / application error while syncing a specific table is
     *   isolated to that table; other tables will still be attempted. Those
     *   table-level errors are generally swallowed by this method, so callers
     *   only observe the count of successfully synced mutations.
     *
     * @return The number of mutations that were successfully synced (and removed
     *         from the local queue).
     *
     * This function may still throw in truly unexpected situations (for
     * example, if the underlying Supabase client or plugins are misconfigured).
     * Swift callers should wrap it in `try` / `catch`.
     */
    @Throws(Exception::class)
    suspend fun syncAllPendingMutations(): Int

    /**
     * Attempts to push all pending mutations for a single [table] to Supabase.
     *
     * - Mutations for this [table] are replayed in creation order.
     * - A network / connectivity failure stops syncing for this table and the
     *   current (and remaining) mutations stay in the queue.
     * - Permanent failures (e.g. permission or constraint violations) cause the
     *   failing mutation to be dropped, but syncing continues with subsequent
     *   mutations.
     * - Unknown server / application errors are propagated to the caller while
     *   keeping the failing mutation in the queue so it can be retried.
     *
     * @return The number of mutations that were successfully synced for [table].
     *
     * Swift callers should wrap this in `try` / `catch` to handle propagated
     * errors from the underlying Supabase client.
     */
    @Throws(Exception::class)
    suspend fun syncPendingMutations(table: String): Int

    /**
     * Returns the **total** number of pending mutations in the local queue
     * across all tables.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    @Throws(Exception::class)
    suspend fun pendingMutationCount(): Int

    /**
     * Returns a [Flow] that emits the **total** number of pending mutations
     * in the local queue across all tables whenever it changes.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    fun pendingMutationCountFlow(): Flow<Int>

    /**
     * Returns the number of pending mutations in the local queue for [table].
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    @Throws(Exception::class)
    suspend fun pendingMutationCount(table: String): Int

    /**
     * Returns a [Flow] that emits the number of pending mutations for [table]
     * whenever it changes.
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    fun pendingMutationCountFlow(table: String): Flow<Int>
}

/**
 * Internal implementation of [SupabaseOfflineClient].
 * Handles wiring of [OfflineStore] and [OfflinePostgrestQueryBuilder].
 */
@OptIn(SupabaseInternal::class)
internal class SupabaseOfflineClientImpl(
    override val supabase: SupabaseClient,
    private val store: OfflineStore,
    private val baseDirectoryProvider: () -> Path,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)
) : SupabaseOfflineClient {

    constructor(
        supabase: SupabaseClient,
        db: OfflineDatabase,
        dbPathProvider: DbPathProvider,
        settings: OfflineCacheSettings,
        baseDirectoryProvider: () -> Path,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO)
    ) : this(
        supabase = supabase,
        store = OfflineStore(
            objectDao = db.objectDao(),
            storageDao = db.storageDao(),
            mutationDao = db.mutationDao(),
            settings = settings,
            dbPathProvider = dbPathProvider
        ),
        baseDirectoryProvider = baseDirectoryProvider,
        sdkLogger = sdkLogger
    )

    private val mutationSyncer = OfflineMutationSyncer(store, supabase, sdkLogger = sdkLogger)

    override fun from(table: String): OfflinePostgrestQueryBuilder =
        from(supabase.postgrest.config.defaultSchema, table)

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder {
        ensurePostgrestInstalled()
        val delegate = supabase.postgrest.from(schema, table)
        return OfflinePostgrestQueryBuilderImpl(
            delegate = delegate,
            store = store,
            sdkLogger = sdkLogger
        )
    }

    override fun storage(bucket: String): BucketApi {
        ensureStorageInstalled()
        return OfflineBucketApi(
            delegate = supabase.storage.from(bucket),
            offlineStore = store,
            sdkLogger = sdkLogger,
            baseDirectoryProvider = baseDirectoryProvider,
        )
    }

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    override suspend fun syncAllPendingMutations(): Int =
        mutationSyncer.syncAllTables()

    override suspend fun syncPendingMutations(table: String): Int =
        mutationSyncer.syncTable(table = table)

    override suspend fun pendingMutationCount(): Int =
        store.pendingMutationCount()

    override fun pendingMutationCountFlow(): Flow<Int> =
        store.pendingMutationCountFlow()

    override suspend fun pendingMutationCount(table: String): Int =
        store.pendingMutationCountForTable(table)

    override fun pendingMutationCountFlow(table: String): Flow<Int> =
        store.pendingMutationCountForTableFlow(table)

    /* ───────────────────────────── Guards ───────────────────────────── */

    private fun ensurePostgrestInstalled() {
        runCatching { supabase.postgrest }.getOrElse {
            error("Postgrest plugin is not installed. Install with createSupabaseClient { install(Postgrest) }")
        }
    }

    private fun ensureStorageInstalled() {
        runCatching { supabase.storage }.getOrElse {
            error("Storage plugin is not installed. Install with createSupabaseClient { install(Storage) }")
        }
    }
}
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.postgrest.AuthSessionState
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator
import io.github.nesfeder.supabase.offline.internal.storage.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.storage.deleteRecursivelyIfExists
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
public interface SupabaseOfflineClient {

    /** Underlying online Supabase client. */
    public val supabase: SupabaseClient

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [table]
     * in the default schema.
     */
    public fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [schema] and [table].
     */
    public fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /** Operator sugar for `client["table"]` (default schema). */
    public operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /** Operator sugar for `client["schema","table"]`. */
    public operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Returns an offline-aware [BucketApi] wrapper for [bucket].
     *
     * Downloads are cached to disk and reused when offline.
     */
    public fun storage(bucket: String): BucketApi

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    /**
     * Attempts to push **all** pending mutations (INSERT/UPSERT/UPDATE/DELETE)
     * for **all tables** to Supabase.
     *
     * - The replay first awaits an **authenticated** session (bounded): in every
     *   other status — cold-start `Initializing`, signed out, or the post-offline
     *   `RefreshFailure` window — requests carry only the anon key, so an
     *   owner-scoped write would 403 against RLS and be dropped as a permanent
     *   failure. If no authenticated session arrives within the bound, the call
     *   returns 0 and leaves the queue intact for the next trigger. Clients
     *   without the Auth plugin skip the gate entirely (fail-open).
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
    public suspend fun syncAllPendingMutations(): Int

    /**
     * Attempts to push all pending mutations for a single [table] to Supabase.
     *
     * - Awaits an authenticated session first, exactly like
     *   [syncAllPendingMutations]; anything else defers the replay and returns 0.
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
    public suspend fun syncPendingMutations(table: String): Int

    /**
     * Returns the **total** number of pending mutations in the local queue
     * across all tables.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    public suspend fun pendingMutationCount(): Int

    /**
     * Returns a [Flow] that emits the **total** number of pending mutations
     * in the local queue across all tables whenever it changes.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    public fun pendingMutationCountFlow(): Flow<Int>

    /**
     * Returns the number of pending mutations in the local queue for [table].
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    public suspend fun pendingMutationCount(table: String): Int

    /**
     * Returns a [Flow] that emits the number of pending mutations for [table]
     * whenever it changes.
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    public fun pendingMutationCountFlow(table: String): Flow<Int>

    /**
     * Applies a server-delivered row (e.g. a Realtime `postgres_changes` INSERT/UPDATE record)
     * to every cached SELECT payload for [table], merging by [primaryKeys] with upsert
     * semantics: a cached row whose [primaryKeys] columns all equal [record]'s is patched in
     * place, otherwise [record] is appended.
     *
     * Realtime consumers call this instead of reconstructing the SDK's internal cache-key
     * format by hand — the SDK patches every cached signature for [table] (filter-aware where
     * the cache tracks a query's filters), so callers never see or build a cache signature.
     *
     * No-ops when [table] has no cached SELECT payloads (nothing is observing it), and when any
     * of [primaryKeys] is absent from [record] or maps to a non-primitive (null / array /
     * object) value — the row can't be located, so the event is logged and dropped rather than
     * risk patching the wrong rows.
     *
     * @param table The Supabase table name (e.g. `"todos"`).
     * @param record The full row object as delivered by the Realtime event.
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`.
     *   Pass the table's actual primary key — e.g. `["team_id", "member_id"]` for a table whose
     *   primary key is composite and has no surrogate `id` column.
     */
    public suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String> = listOf("id"),
    )

    /**
     * Removes a server-deleted row from every cached SELECT payload for [table], matching on
     * [primaryKeys] against [oldRecord] (e.g. the `old_record` of a Realtime `postgres_changes`
     * DELETE event).
     *
     * No-ops under the same conditions as [applyServerRecord]: no cached payloads for [table],
     * or a [primaryKeys] column absent from [oldRecord] or non-primitive.
     *
     * @param table The Supabase table name.
     * @param oldRecord The deleted row's prior column values, carrying at least [primaryKeys].
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`.
     */
    public suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String> = listOf("id"),
    )

    /**
     * Wipes the entire local offline cache: every cached SELECT payload, every cached
     * storage-object metadata row, every queued (pending or already-synced) mutation, **and**
     * every downloaded storage file under `<base>/storage/` on disk.
     *
     * This is a **local-only** operation — it does not touch the server. Identity-clearing
     * flows (logout, account switch, account deletion) must call this so one user's cached
     * rows, un-synced offline writes, and downloaded objects (e.g. avatars) never carry over
     * to the next user on the same device, and no personal data survives on disk after an
     * account is deleted.
     */
    public suspend fun clearAllCache()
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
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    /**
     * Optional platform-agnostic connectivity hint (`true` = online). Its false→true edges (plus
     * the SDK-internal auth-session edges) accelerate a stalled `CacheFirstThenNetwork` refresh —
     * a hint only, since the refresh self-heals on its capped schedule regardless. The caller maps
     * its own connectivity monitor into it, so the SDK takes no dependency on a connectivity type.
     */
    private val networkAvailability: Flow<Boolean>? = null,
    /**
     * Scope hosting the client-scoped single-flight refresh loops. Defaults to a process-lifetime
     * supervised scope; injectable so tests can supply a controlled scope.
     */
    coordinatorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SupabaseOfflineClient {

    constructor(
        supabase: SupabaseClient,
        db: OfflineDatabase,
        dbPathProvider: DbPathProvider,
        settings: OfflineCacheSettings,
        baseDirectoryProvider: () -> Path,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
        networkAvailability: Flow<Boolean>? = null,
        coordinatorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
        sdkLogger = sdkLogger,
        networkAvailability = networkAvailability,
        coordinatorScope = coordinatorScope,
    )

    private val mutationSyncer = OfflineMutationSyncer(store, supabase, sdkLogger = sdkLogger)

    /** Encodes the primary-key [FilterDescriptor] built for realtime records. */
    private val json = OfflineJson

    /**
     * Client-scoped coordinator shared by every builder this client creates: single-flight refresh
     * sharing per `(table, signature)` plus the live-signature registry that backs
     * materialize-on-write in [OfflineStore.applyLocalMutationToCache].
     */
    private val refreshCoordinator = RefreshCoordinator(coordinatorScope)

    /**
     * SDK-internal recovery accelerator merging auth-session-restored and connectivity-regained
     * edges. Forwarded to every builder to short-circuit a stalled refresh's backoff.
     */
    private val refreshAccelerator: Flow<Unit> = buildRefreshAccelerator(supabase, networkAvailability)

    init {
        // Let the store see which signatures are live so a server-successful mutation materializes
        // into a real cache row for a cache-less-but-observed subscription.
        store.liveSignatureProvider = refreshCoordinator::liveSignaturesFor
    }

    override fun from(table: String): OfflinePostgrestQueryBuilder =
        from(supabase.postgrest.config.defaultSchema, table)

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder {
        ensurePostgrestInstalled()
        val delegate = supabase.postgrest.from(schema, table)
        return OfflinePostgrestQueryBuilderImpl(
            delegate = delegate,
            store = store,
            sdkLogger = sdkLogger,
            authSessionState = ::authSessionState,
            refreshCoordinator = refreshCoordinator,
            accelerator = refreshAccelerator,
        )
    }

    /**
     * Maps the supabase-kt session status to the resolver's [AuthSessionState] snapshot,
     * which decides how an empty SELECT response is treated:
     *
     * - `Initializing` → [AuthSessionState.RESTORING]: the cold-start window where session
     *   restore from storage hasn't completed — a request may go out without a usable JWT,
     *   RLS filters every row, and the server returns `200 OK` with `[]`; the resolver
     *   retries that via [io.github.nesfeder.supabase.offline.internal.postgrest.TransientEmptyResponseException].
     * - `Authenticated` → [AuthSessionState.AUTHENTICATED]: empties are the server's real
     *   answer and may replace cached rows.
     * - Anything else (signed out, refresh failure) → [AuthSessionState.UNAUTHENTICATED]:
     *   supabase-kt attaches the user JWT only while `Authenticated` (its token resolution
     *   reads `currentSessionOrNull()`, which is null for every other status), so requests in
     *   these states go out on the anon key and their empties are RLS-shaped by construction
     *   — kept out of the cache, no retry. Logout's `clearAllCache` owns removing cached
     *   data. This mapping is exact, not conservative: `AUTHENTICATED` ⟺ user JWT on the
     *   wire. Do not relax it.
     *
     * Defensive in two ways: a) wraps the auth probe in `runCatching` so an SDK that hasn't
     * installed the Auth plugin (no session-status notion at all) still works, b) maps any
     * probe failure to `UNAUTHENTICATED` — the fail-safe value: reads are never blocked
     * behind the restore-retry (that takes `RESTORING`), and the empty-clobber guard stays
     * active exactly as it did before empties could be authoritative at all.
     */
    private fun authSessionState(): AuthSessionState = runCatching {
        when (supabase.auth.sessionStatus.value) {
            is SessionStatus.Initializing -> AuthSessionState.RESTORING
            is SessionStatus.Authenticated -> AuthSessionState.AUTHENTICATED
            else -> AuthSessionState.UNAUTHENTICATED
        }
    }.getOrDefault(AuthSessionState.UNAUTHENTICATED)

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

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>,
    ) {
        val filterJson = primaryKeyFilterJson(record, primaryKeys)
        if (filterJson == null) {
            sdkLogger.warning {
                "applyServerRecord($table): dropping record — primary key(s) $primaryKeys " +
                    "absent or non-primitive"
            }
            return
        }
        store.applyLocalMutationToCache(
            table = table,
            operation = MutationOperation.UPSERT,
            bodyJson = JsonArray(listOf(record)).toString(),
            filterJson = filterJson,
            onConflict = primaryKeys.joinToString(separator = ","),
        )
    }

    override suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>,
    ) {
        val filterJson = primaryKeyFilterJson(oldRecord, primaryKeys)
        if (filterJson == null) {
            sdkLogger.warning {
                "applyServerDelete($table): dropping record — primary key(s) $primaryKeys " +
                    "absent or non-primitive"
            }
            return
        }
        store.applyLocalMutationToCache(
            table = table,
            operation = MutationOperation.DELETE,
            bodyJson = "{}",
            filterJson = filterJson,
        )
    }

    /**
     * Builds the encoded [FilterDescriptor] that locates [record]'s row by its [primaryKeys],
     * as an AND of `eq` conditions — mirroring the on-conflict filter synthesis the overlay
     * uses for online upserts. Returns `null` when [primaryKeys] is empty, or any key is
     * absent from [record] or maps to a non-primitive (null / array / object) value, so the
     * caller can drop an unlocatable record instead of patching the wrong rows.
     */
    private fun primaryKeyFilterJson(record: JsonObject, primaryKeys: List<String>): String? {
        if (primaryKeys.isEmpty()) return null
        val conditions = primaryKeys.map { column ->
            val value = record[column]
            if (value !is JsonPrimitive || value is JsonNull) return null
            FilterDescriptor.Condition(
                column = column,
                operatorId = "eq",
                value = value.content,
            )
        }
        val descriptor = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = conditions,
        )
        return FilterEngine.encode(descriptor, json)
    }

    override suspend fun clearAllCache() {
        store.clearAll()
        // Room only holds storage-object metadata; the downloaded bytes live on disk under
        // <base>/storage/. Wipe that tree too so cached objects (e.g. avatars) don't survive
        // logout / account switch / account deletion.
        deleteRecursivelyIfExists(baseDirectoryProvider() / "storage")
    }

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
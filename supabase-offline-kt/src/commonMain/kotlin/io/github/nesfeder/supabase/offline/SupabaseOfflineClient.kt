package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.outbox.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.outbox.PendingMutation
import io.github.nesfeder.supabase.offline.internal.outbox.RejectedMutation
import io.github.nesfeder.supabase.offline.internal.outbox.SyncReport
import io.github.nesfeder.supabase.offline.internal.outbox.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.outbox.toRejectedMutation
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
     * - Every table's mutations form one queue, replayed in the order they were
     *   made, whatever the table.
     * - A failure that may pass — no connectivity, a `401`, `408`, `429`, `5xx` or
     *   an unexpected error — stops the whole pass and keeps every remaining
     *   mutation queued, so a later write never overtakes an earlier one (a child
     *   row never replays ahead of its parent). Nothing transient ever removes a
     *   mutation. Replay failures are not thrown from this method.
     * - A mutation the server refuses for good (another `4xx`) is taken out of the
     *   queue and kept on a rejected list with the server's reason, and the pass
     *   continues. A `403` or `409` on a mutation that has an earlier one still
     *   pending is held instead.
     * - Mutations queued under another identity than the current session's are
     *   not replayed.
     *
     * @return The number of mutations that were successfully synced.
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
     * - Mutations for this [table] are replayed in the order they were made.
     * - A network / connectivity failure stops syncing and the current (and
     *   remaining) mutations stay in the queue.
     * - Permanent failures (e.g. permission or constraint violations) move the
     *   failing mutation to the rejected list, and syncing continues with
     *   subsequent mutations. A `403` or `409` on a mutation that has an earlier
     *   mutation pending in another table is held instead: that earlier write may
     *   be the row it depends on.
     * - Other server / application errors (`401`, `408`, `429`, `5xx`, unexpected)
     *   are propagated to the caller while keeping the failing mutation in the
     *   queue so it can be retried.
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
    @Throws(Exception::class)
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
    @Throws(Exception::class)
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
     *
     * A replay pass in progress is cancelled first, and no queued write is sent once clearing has
     * begun. A write whose request was already in flight is neither overlaid onto the cache nor
     * queued when it returns.
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

    /** Hosts replay passes the engine starts on its own; see [requestSync]. */
    private val syncScope = coordinatorScope

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
        store.sdkLogger = sdkLogger
    }

    override fun from(table: String): OfflinePostgrestQueryBuilder =
        from(supabase.postgrest.config.defaultSchema, table)

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder {
        ensurePostgrestInstalled()
        require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
        val delegate = supabase.postgrest.from(schema, table)
        return OfflinePostgrestQueryBuilderImpl(
            delegate = delegate,
            store = store,
            sdkLogger = sdkLogger,
            authSessionState = ::authSessionState,
            refreshCoordinator = refreshCoordinator,
            accelerator = refreshAccelerator,
            queueIdentity = { supabase.currentQueueIdentity() },
            requestSync = ::requestSync,
        )
    }

    /**
     * Starts a replay pass in the background. Used when a write made online joined the queue behind
     * pending writes; a pass already running finishes first, then this one replays the rest.
     */
    private fun requestSync() {
        syncScope.launch {
            try {
                mutationSyncer.sync()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                sdkLogger.warning { "Background mutation replay failed: ${failure::class.simpleName}" }
            }
        }
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
        // Cancels a replay pass in flight and runs the wipe under the sync lock, so no queued write
        // of the outgoing identity reaches the server afterwards.
        mutationSyncer.clearLocalData { store.clearAll() }
        // Room only holds storage-object metadata; the downloaded bytes live on disk under
        // <base>/storage/. Wipe that tree too so cached objects (e.g. avatars) don't survive
        // logout / account switch / account deletion.
        deleteRecursivelyIfExists(baseDirectoryProvider() / "storage")
    }

    /* ─────────────────────────── Outbox (internal) ─────────────────────────── */

    /*
     * The outbox's ids, rejected list, identity and sync report, for the change that exposes them
     * publicly under the offline API sheet's names. Internal until then.
     */

    /**
     * Runs one replay pass and reports what it did; returns promptly when offline.
     *
     * @param table The table to replay, or `null` for the whole queue.
     * @return What the pass synced, rejected and left pending.
     */
    internal suspend fun syncPendingMutationsReport(table: String? = null): SyncReport =
        mutationSyncer.sync(table = table)

    /**
     * The pending mutations — of [table], or of every table — in replay order, as they change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending mutations.
     */
    internal fun pendingMutations(table: String? = null): Flow<List<PendingMutation>> =
        store.pendingMutationsFlow(table).map { rows ->
            rows.mapNotNull { row -> row.toPendingMutation(store.json, DefaultPrimaryKeys) }
        }

    /**
     * The persisted rejected mutations, oldest first, as they change. The list keeps the newest 100.
     *
     * @return A flow of rejected mutations; it fails if the persisted list cannot be decoded.
     */
    internal fun rejectedMutations(): Flow<List<RejectedMutation>> =
        store.outboxState.rejectionsFlow().map { entries ->
            entries.map { entry -> entry.toRejectedMutation(store.json, DefaultPrimaryKeys) }
        }

    /**
     * The persisted rejected mutations, oldest first.
     *
     * @return The list; it throws if the persisted list cannot be decoded.
     */
    internal suspend fun rejectedMutationList(): List<RejectedMutation> =
        store.outboxState.rejections().map { entry -> entry.toRejectedMutation(store.json, DefaultPrimaryKeys) }

    /**
     * Removes a rejected mutation from the list.
     *
     * @param id The rejected mutation's id.
     * @return `true` when it was on the list.
     */
    internal suspend fun dismissRejectedMutation(id: Long): Boolean = store.dismissRejectedMutation(id)

    /**
     * Puts a rejected mutation back at the tail of the queue and requests a replay pass.
     *
     * @param id The rejected mutation's id.
     * @return The new pending mutation's id, or `null` when there is no such rejection, or it —
     *   or the pending queue — belongs to another identity than the current session's.
     */
    internal suspend fun retryRejectedMutation(id: Long): Long? {
        val identity = supabase.currentQueueIdentity() ?: return null
        val newId = store.retryRejectedMutation(id, identity.storedValue) ?: return null
        requestSync()
        return newId
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
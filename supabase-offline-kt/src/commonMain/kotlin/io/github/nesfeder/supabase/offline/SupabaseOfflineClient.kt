// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.internal.ClientOperationGate
import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.childEngineScope
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.mutationqueue.BackgroundSyncTrigger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PendingMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncReport
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentReplayIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.replayIdentityFor
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.internal.newEngineScope
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.internal.postgrest.RefreshCoordinator
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.postgrest.installDryRunCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installReadShapeCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installTransportFailureCapture
import io.github.nesfeder.supabase.offline.internal.postgrest.readShapePostgrestOf
import io.github.nesfeder.supabase.offline.internal.postgrest.requestSessionFor
import io.github.nesfeder.supabase.offline.internal.storage.CachingBucketApi
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import io.github.nesfeder.supabase.offline.internal.storage.renderImagePathOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * Create one client per database and keep it for as long as the app uses it: a second client on the
 * same database fails to open until the first is [close]d.
 */
public interface SupabaseOfflineClient {

    /** Underlying online Supabase client. */
    public val supabase: SupabaseClient

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [table] in the
     * default schema.
     */
    public fun from(table: String): OfflinePostgrestQueryBuilder

    /**
     * Creates a new offline-aware [OfflinePostgrestQueryBuilder] for the given [schema] and
     * [table].
     */
    public fun from(schema: String, table: String): OfflinePostgrestQueryBuilder

    /** Operator sugar for `client["table"]` (default schema). */
    public operator fun get(table: String): OfflinePostgrestQueryBuilder = from(table)

    /** Operator sugar for `client["schema","table"]`. */
    public operator fun get(schema: String, table: String): OfflinePostgrestQueryBuilder =
        from(schema, table)

    /**
     * Returns the offline view of the Storage bucket [bucket]: every [BucketApi] operation, with
     * downloads cached on the device and served when offline. See [OfflineBucketApi].
     *
     * @param bucket the bucket identifier.
     * @return the bucket.
     */
    public fun storage(bucket: String): OfflineBucketApi

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    /**
     * Attempts to push **all** pending mutations (INSERT/UPSERT/UPDATE/DELETE) for **all tables**
     * to Supabase.
     *
     * - The replay first awaits a **settled** session (bounded): signed in, or signed out. While
     *   the session is `Initializing` or in the post-offline `RefreshFailure` window, requests
     *   would carry only the anon key whoever made the writes. If the session does not settle
     *   within the bound, the call returns 0 and leaves the queue intact for the next trigger.
     *   Clients without the Auth plugin skip the gate entirely (fail-open).
     * - The queue is kept per identity. Each mutation records who made it — a signed-in user, or
     *   nobody signed in — and a pass replays only the current session's mutations, across every
     *   table, in the order they were made. Another identity's mutations neither block it nor are
     *   sent by it: they replay when that identity is current again (signing out again replays the
     *   ones made signed out). The session is checked again before every request, so a sign-out or
     *   account switch stops the pass before its next one. Mutations queued by an earlier build of
     *   the engine, which recorded no owner, are adopted by the first identity to replay.
     * - A failure that may pass — no connectivity, a `401`, `408`, `429`, `5xx` or an unexpected
     *   error — stops the whole pass and keeps every remaining mutation queued, so a later write
     *   never overtakes an earlier one (a child row never replays ahead of its parent). Nothing
     *   transient ever removes a mutation. Replay failures are not thrown from this method.
     * - A mutation the server refuses for good (another `4xx`) is taken out of the queue and kept
     *   on a rejected list with the server's reason, and the pass continues.
     *
     * @return The number of mutations that were successfully synced.
     *
     * This function may still throw in truly unexpected situations (for example, if the underlying
     * Supabase client or plugins are misconfigured). Swift callers should wrap it in `try` /
     * `catch`.
     */
    @Throws(Exception::class) public suspend fun syncAllPendingMutations(): Int

    /**
     * Attempts to push all pending mutations for a single [table] to Supabase.
     *
     * - Awaits a settled session first, exactly like [syncAllPendingMutations]; an unsettled one
     *   defers the replay and returns 0.
     * - The current session's mutations for this [table] are replayed in the order they were made;
     *   another identity's are left alone.
     * - A network / connectivity failure stops syncing and the current (and remaining) mutations
     *   stay in the queue.
     * - Permanent failures (e.g. permission or constraint violations) move the failing mutation to
     *   the rejected list, and syncing continues with subsequent mutations. A `403` or `409` on a
     *   mutation that has an earlier mutation of the same identity pending in another table is held
     *   instead: that earlier write may be the row it depends on.
     * - Other server / application errors (`401`, `408`, `429`, `5xx`, unexpected) are propagated
     *   to the caller while keeping the failing mutation in the queue so it can be retried.
     *
     * @return The number of mutations that were successfully synced for [table].
     *
     * Swift callers should wrap this in `try` / `catch` to handle propagated errors from the
     * underlying Supabase client.
     */
    @Throws(Exception::class) public suspend fun syncPendingMutations(table: String): Int

    /**
     * Returns the number of the current session's pending mutations in the local queue across all
     * tables. Mutations another identity made — another user, or nobody signed in — are not
     * counted.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(): Int

    /**
     * Returns a [Flow] that emits the number of the current session's pending mutations across all
     * tables whenever it — or the session — changes.
     *
     * Useful for:
     * - Showing "N changes to sync" badges
     * - Deciding whether to show a "Sync" button
     */
    public fun pendingMutationCountFlow(): Flow<Int>

    /**
     * Returns the number of the current session's pending mutations for [table].
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    @Throws(Exception::class) public suspend fun pendingMutationCount(table: String): Int

    /**
     * Returns a [Flow] that emits the number of the current session's pending mutations for [table]
     * whenever it — or the session — changes.
     *
     * Useful for table-specific UI ("this screen has unsynced edits").
     */
    public fun pendingMutationCountFlow(table: String): Flow<Int>

    /**
     * Applies a server-delivered row (e.g. a Realtime `postgres_changes` INSERT/UPDATE record) to
     * every cached SELECT payload for [table], merging by [primaryKeys] with upsert semantics: a
     * cached row whose [primaryKeys] columns all equal [record]'s is patched in place, otherwise
     * [record] is appended.
     *
     * Realtime consumers call this instead of reconstructing the SDK's internal cache-key format by
     * hand — the SDK patches every cached signature for [table] (filter-aware where the cache
     * tracks a query's filters), so callers never see or build a cache signature.
     *
     * No-ops when [table] has no cached SELECT payloads (nothing is observing it), and when any of
     * [primaryKeys] is absent from [record] or maps to a non-primitive (null / array / object)
     * value — the row can't be located, so the event is logged and dropped rather than risk
     * patching the wrong rows.
     *
     * It acts on the cached queries of [table] in the client's default schema. A table in another
     * schema, whose queries are cached apart, cannot be named here yet.
     *
     * @param table The Supabase table name (e.g. `"todos"`), in the client's default schema.
     * @param record The full row object as delivered by the Realtime event.
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`. Pass
     *   the table's actual primary key — e.g. `["team_id", "member_id"]` for a table whose primary
     *   key is composite and has no surrogate `id` column.
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
     * No-ops under the same conditions as [applyServerRecord]: no cached payloads for [table], or a
     * [primaryKeys] column absent from [oldRecord] or non-primitive. Like it, it acts on [table] in
     * the client's default schema only.
     *
     * @param table The Supabase table name, in the client's default schema.
     * @param oldRecord The deleted row's prior column values, carrying at least [primaryKeys].
     * @param primaryKeys The column name(s) forming the row's identity; defaults to `["id"]`.
     */
    public suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String> = listOf("id"),
    )

    /**
     * Wipes the entire local offline cache. That is every cached SELECT payload, every cached
     * storage-object metadata row, every queued (pending or already-synced) mutation, **and** every
     * downloaded storage file under `<base>/storage/` on disk.
     *
     * This is a **local-only** operation — it does not touch the server. Identity-clearing flows
     * (logout, account switch, account deletion) must call this so one user's cached rows,
     * un-synced offline writes, and downloaded objects (e.g. avatars) never carry over to the next
     * user on the same device, and no personal data survives on disk after an account is deleted.
     *
     * With the Auth plugin, the cached queries and downloaded files also belong to one identity at
     * a time without this call: once the session settles as another user, or signed out, what was
     * cached before is no longer served, and is deleted and scrubbed from the database files as
     * this call does. Queued writes are kept then, each for the identity that made it; only this
     * call removes them. A session that is restoring, or whose refresh failed, keeps its cache.
     *
     * A replay pass in progress is cancelled first, and no queued write is sent once clearing has
     * begun. A write whose request was already in flight is neither overlaid onto the cache nor
     * queued when it returns, and a cache patch already under way when clearing begins writes
     * nothing after it. A Storage download still in flight may hand its bytes to its caller, or
     * fail, but it is not written back into the cache.
     *
     * The deleted rows are not left readable in the database files either: the database is rebuilt
     * without them and its write-ahead log emptied.
     */
    public suspend fun clearAllCache()

    /**
     * Releases the client and frees its database for another client. Nothing is deleted.
     *
     * What ends, and when:
     * - At once, every new call fails with [IllegalStateException] ("This SupabaseOfflineClient is
     *   closed"): on the client, and on a builder, bucket or flow obtained from it.
     * - At once, every flow being collected stops, whatever its read strategy: its collector reads
     *   that error next, even one stuck in its own body or one that has stopped reading.
     * - At once, the client's own work stops (refreshes, replay passes), and so do the calls that
     *   only wait on the network: syncs, downloads, uploads, signed URLs, list, info and exists.
     *   Their callers get the same error, never a cancellation. A stopped upload may still have
     *   been stored, so the cached copy it would have replaced is dropped.
     * - Every other call already running is left to finish: a clear, a PostgREST write, a read of
     *   the cache, a Storage delete, move or copy. A clear that has begun always completes, and a
     *   write whose answer arrives is applied and never sent again. The database is closed only
     *   once none of them is using it.
     *
     * Every call to `close()` returns once the database is released, or after 10 seconds of real
     * time at most, even under a test's virtual clock. Past that, the database is released as soon
     * as the last of those calls finishes; until then, creating a client on it fails with an error
     * saying it is still closing.
     *
     * A resumable upload obtained before `close()` is supabase-kt's own object and keeps uploading.
     * If it finishes after `close()`, the cached copy it replaced stays for the next client.
     *
     * Safe to call more than once, and from inside a collector of one of the client's flows. A
     * wrapper must forward it (delegation with `by` does), or the database stays claimed and the
     * wrapped client's work keeps running.
     */
    @Throws(Exception::class) public suspend fun close()
}

/**
 * Internal implementation of [SupabaseOfflineClient]. Handles wiring of [OfflineStore] and
 * [OfflinePostgrestQueryBuilder].
 */
@OptIn(SupabaseInternal::class)
internal class SupabaseOfflineClientImpl(
    override val supabase: SupabaseClient,
    private val store: OfflineStore,
    private val baseDirectoryProvider: () -> Path,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    /**
     * Optional platform-agnostic connectivity hint (`true` = online). Its false→true edges (plus
     * the SDK-internal auth-session edges) accelerate a stalled `CacheFirstThenNetwork` refresh — a
     * hint only, since the refresh self-heals on its capped schedule regardless. The caller maps
     * its own connectivity monitor into it, so the SDK takes no dependency on a connectivity type.
     */
    private val networkAvailability: Flow<Boolean>? = null,
    /**
     * Scope the client's own scope is made a child of. Defaults to a process-lifetime supervised
     * scope; injectable so tests can supply a controlled scope. [close] cancels only the client's
     * child scope, never this one.
     */
    coordinatorScope: CoroutineScope = newEngineScope(sdkLogger),
    /** Size bound of the Storage file cache in bytes, or `null` for none. */
    fileCacheMaxBytes: Long? = SupabaseOfflineConfig.DEFAULT_FILE_CACHE_SIZE_BYTES,
    /**
     * Whether to create the file cache's directories now and exclude them from backups. The
     * platform factories do; tests and the in-memory client, whose data directory is not theirs to
     * write into, do not.
     */
    prepareFileCacheDirectories: Boolean = false,
    /**
     * Rewrites the database without the rows [clearAllCache] deleted, so none can be read back from
     * its files. The factories pass the database's own; the in-memory store has no files.
     */
    private val scrubDatabase: suspend () -> Unit = {},
    /** Releases what the factory opened — the database and its registration — on [close]. */
    private val onClose: () -> Unit = {},
    /**
     * How long [close] waits for calls in flight; tests whose client work never runs shorten it.
     */
    private val closeWait: Duration = CLOSE_WAIT,
    /** Told, with the count of calls still in flight, when [close] returns before the release. */
    private val onStillClosing: (callsInFlight: () -> Int) -> Unit = {},
    /** Each table's primary-key columns; `id` for every table unless the app says otherwise. */
    private val primaryKeys: PrimaryKeys = DefaultPrimaryKeys,
    /** Which read responses may replace what the cache holds; see [ResponseTrustPolicy]. */
    private val responseTrust: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
) : SupabaseOfflineClient {

    /**
     * Where everything the client starts runs: a child of the injected scope, on its dispatcher.
     * [close] cancels it without touching the caller's scope.
     */
    private val clientScope: CoroutineScope = childEngineScope(coordinatorScope, sdkLogger)

    /**
     * The scope a close that outlived its wait finishes in; the injected one, which close leaves
     * running.
     */
    private val parentScope: CoroutineScope = coordinatorScope

    /** Every call's pass through the client, so [close] never closes the database under one. */
    private val operationGate = ClientOperationGate()

    /** Completed once the database is released: what every [close] waits for. */
    private val released = CompletableDeferred<Unit>()

    /** Taken by the one [releaseOnceIdle] that runs [onClose]. */
    private val releasing = MutableStateFlow(false)

    /** Taken by the one [close] that reports the release is late. */
    private val reportedStillClosing = MutableStateFlow(false)

    /**
     * The PostgREST instance every handle's read-shape check builds reads through, made on first
     * use and let go by [close]; see [readShapePostgrestOf].
     */
    private val readShapePostgrest = MutableStateFlow<Postgrest?>(null)

    /** Where [storage]'s buckets see an image transform's requests go; resolved on first use. */
    private val renderImagePath: String by lazy { renderImagePathOf(supabase) }

    /** The Storage file cache every bucket of this client shares. */
    private val fileCache =
        StorageFileCache(
            store = store,
            dataDirectoryProvider = baseDirectoryProvider,
            maxSizeBytes = fileCacheMaxBytes,
            sdkLogger = sdkLogger,
        )

    private val mutationQueueReplayer =
        MutationQueueReplayer(store, supabase, sdkLogger = sdkLogger, primaryKeys = primaryKeys)

    /**
     * Runs the replay passes the engine starts on its own, in the client's scope so [close] stops
     * them.
     */
    private val backgroundSync =
        BackgroundSyncTrigger(scope = clientScope, sdkLogger = sdkLogger) {
            mutationQueueReplayer.sync()
        }

    /** Encodes the primary-key [FilterDescriptor] built for realtime records. */
    private val json = OfflineJson

    /**
     * Client-scoped coordinator shared by every builder this client creates: single-flight refresh
     * sharing per `(table, signature)` plus the live-signature registry that backs
     * materialize-on-write in [OfflineStore.patchCachedQueries].
     */
    private val refreshCoordinator = RefreshCoordinator(clientScope)

    /**
     * SDK-internal recovery accelerator merging auth-session-restored and connectivity-regained
     * edges. Forwarded to every builder to short-circuit a stalled refresh's backoff.
     */
    private val refreshAccelerator: Flow<Unit> =
        buildRefreshAccelerator(supabase, networkAvailability, responseTrust)

    /** The identity the overlay and counts are for, read once per session status. */
    private val overlayIdentity = MutableStateFlow<Pair<SessionStatus?, String?>?>(null)

    /**
     * A read or write found the cache another identity's: [keepCacheWithTheSession] looks again.
     */
    private val cacheOwnerChecks =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        if (prepareFileCacheDirectories) fileCache.prepareDirectories()
        // Let the store see which signatures are live so a server-successful mutation materializes
        // into a real cache row for a cache-less-but-observed subscription.
        store.liveSignatureProvider = refreshCoordinator::liveSignaturesFor
        store.allLiveSignaturesProvider = refreshCoordinator::allLiveSignatures
        store.onQueriesMarkedStale = { table, signatures ->
            refreshCoordinator.requestRefresh(table, signatures)
        }
        store.sdkLogger = sdkLogger
        store.primaryKeys = primaryKeys
        // Overlays and counts show the current session's queued writes only.
        store.identityProvider = ::overlayIdentityValue
        store.identityChanges = sessionChanges()
        store.ownerlessMutationsAreAnonymous = authPluginOrNull() == null
        // The cache is one identity's at a time; without Auth there is only one
        store.cacheOwnedPerIdentity = authPluginOrNull() != null
        store.onCacheNotServed = { cacheOwnerChecks.tryEmit(Unit) }
        // Before the app sends anything through this client, so the hooks are on the pipeline once,
        // and not added while requests may be walking it.
        supabase.installDryRunCheck()
        supabase.installReadShapeCheck()
        supabase.installTransportFailureCapture()
        replayWhenTheSessionSettles()
        keepCacheWithTheSession()
    }

    private fun authPluginOrNull(): Auth? = supabase.pluginManager.getPluginOrNull(Auth)

    /** Emits whenever the session status changes; once, without the Auth plugin. */
    private fun sessionChanges(): Flow<Any?> = authPluginOrNull()?.sessionStatus ?: flowOf(Unit)

    /**
     * The stored identity value for the current session status, computed once per status: while the
     * session restores or refreshes, telling it reads the stored session.
     */
    private suspend fun overlayIdentityValue(): String? {
        val status = authPluginOrNull()?.sessionStatus?.value
        overlayIdentity.value?.let { (cachedStatus, identity) ->
            if (cachedStatus == status) return identity
        }
        val identity = supabase.currentQueueIdentity()?.storedValue
        overlayIdentity.value = status to identity
        return identity
    }

    /**
     * Starts a pass when the session settles into an identity with mutations queued, in the
     * client's scope so [close] stops it. Every new status counts, as a sign-out and sign-in it
     * never saw arrive as a new session of the same identity. A replay switch must stop it; see
     * [BackgroundSyncTrigger].
     */
    private fun replayWhenTheSessionSettles() {
        val auth = authPluginOrNull() ?: return
        clientScope.launch {
            auth.sessionStatus.collect { status ->
                val identity = replayIdentityFor(status) ?: return@collect
                if (store.pendingMutationsOf(identity.storedValue)?.isNotEmpty() == true)
                    backgroundSync.request()
            }
        }
    }

    /**
     * Keeps the cache the settled identity's, in the client's scope so [close] stops it: when the
     * session settles, or a read finds the cache another identity's, [settleCacheOwner] runs as a
     * call through the gate. A session that restores or whose refresh failed is not settled.
     */
    private fun keepCacheWithTheSession() {
        val auth = authPluginOrNull() ?: return
        clientScope.launch {
            merge(auth.sessionStatus.map {}, cacheOwnerChecks).conflate().collect {
                val identity = replayIdentityFor(auth.sessionStatus.value) ?: return@collect
                try {
                    operationGate.call { settleCacheOwner(identity) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    if (operationGate.isClosed) return@collect
                    sdkLogger.error {
                        "Could not drop the cache for the new session " +
                            "(${failure::class.simpleName}); it is not served until it is"
                    }
                }
            }
        }
    }

    /**
     * Makes the cache [identity]'s. A cache no identity owns is adopted by a user, and by nobody
     * signed in while it holds nothing; anything else another identity cached is dropped — the
     * cached queries and the Storage file cache, never the mutation queue — under [clearAllCache]'s
     * locks in its order, and live queries fetch again.
     */
    private suspend fun settleCacheOwner(identity: QueueIdentity) {
        if (
            store.cacheOwnerClaimingFor(identity.storedValue, claimsForUser = true) ==
                identity.storedValue
        )
            return
        // The send lock alone: no pass is stopped, as none of the queue goes
        mutationQueueReplayer.holdingSends {
            fileCache.clear {
                store.dropCacheFor(identity.storedValue)
                scrubDatabase()
            }
        }
        sdkLogger.info { "The session settled into another identity; the cache was emptied for it" }
        refreshCoordinator.liveTables().forEach { table ->
            refreshCoordinator.requestRefresh(table)
        }
    }

    override fun from(table: String): OfflinePostgrestQueryBuilder =
        from(supabase.postgrest.config.defaultSchema, table)

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder {
        ensureOpen()
        ensurePostgrestInstalled()
        require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
        val delegate = supabase.postgrest.from(schema, table)
        val readShape =
            readShapePostgrest.value
                ?: readShapePostgrestOf(supabase.postgrest).let { created ->
                    if (readShapePostgrest.compareAndSet(null, created)) created
                    else readShapePostgrest.value ?: created
                }
        return OfflinePostgrestQueryBuilderImpl(
            delegate = delegate,
            store = store,
            sdkLogger = sdkLogger,
            requestSession = ::requestSession,
            trustPolicy = responseTrust,
            refreshCoordinator = refreshCoordinator,
            accelerator = refreshAccelerator,
            queueIdentity = { supabase.currentQueueIdentity() },
            requestSync = backgroundSync::request,
            headSender = mutationQueueReplayer,
            operationGate = operationGate,
            readShapePostgrest = readShape,
        )
    }

    /** The session a read sent now goes out under; the identity is the mutation queue's own. */
    private fun requestSession(): RequestSession =
        requestSessionFor(authPluginOrNull()?.sessionStatus?.value)

    override fun storage(bucket: String): OfflineBucketApi {
        ensureOpen()
        ensureStorageInstalled()
        return CachingBucketApi(
            delegate = supabase.storage.from(bucket),
            cache = fileCache,
            sdkLogger = sdkLogger,
            backgroundScope = clientScope,
            operationGate = operationGate,
            renderImagePath = renderImagePath,
        )
    }

    /* ───────────────────────────── Sync APIs ───────────────────────────── */

    override suspend fun syncAllPendingMutations(): Int =
        operationGate.cancellableCall(clientScope) { mutationQueueReplayer.syncAllTables() }

    override suspend fun syncPendingMutations(table: String): Int =
        operationGate.cancellableCall(clientScope) {
            mutationQueueReplayer.syncTable(table = table)
        }

    override suspend fun pendingMutationCount(): Int = operationGate.call {
        store.pendingMutationCount()
    }

    override fun pendingMutationCountFlow(): Flow<Int> {
        ensureOpen()
        return operationGate.callCollecting(store.pendingMutationCountFlow())
    }

    override suspend fun pendingMutationCount(table: String): Int = operationGate.call {
        store.pendingMutationCountForTable(table)
    }

    override fun pendingMutationCountFlow(table: String): Flow<Int> {
        ensureOpen()
        return operationGate.callCollecting(store.pendingMutationCountForTableFlow(table))
    }

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>,
    ): Unit = operationGate.call {
        val filterJson = primaryKeyFilterJson(record, primaryKeys)
        if (filterJson == null) {
            sdkLogger.warning {
                "applyServerRecord($table): dropping record — primary key(s) $primaryKeys " +
                    "absent or non-primitive"
            }
            return@call
        }
        store.patchCachedQueries(
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
    ): Unit = operationGate.call {
        val filterJson = primaryKeyFilterJson(oldRecord, primaryKeys)
        if (filterJson == null) {
            sdkLogger.warning {
                "applyServerDelete($table): dropping record — primary key(s) $primaryKeys " +
                    "absent or non-primitive"
            }
            return@call
        }
        store.patchCachedQueries(
            table = table,
            operation = MutationOperation.DELETE,
            bodyJson = "{}",
            filterJson = filterJson,
        )
    }

    /**
     * Builds the encoded [FilterDescriptor] that locates [record]'s row by its [primaryKeys], as an
     * AND of `eq` conditions — mirroring the on-conflict filter synthesis the overlay uses for
     * online upserts. Returns `null` when [primaryKeys] is empty, or any key is absent from
     * [record] or maps to a non-primitive (null / array / object) value, so the caller can drop an
     * unlocatable record instead of patching the wrong rows.
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
        val descriptor =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions = conditions,
            )
        return FilterEngine.encode(descriptor, json)
    }

    /**
     * Clears once, locks outermost first: the replayer's send lock, after it cancels passes in
     * flight; the file cache's lock; the store's, around [OfflineStore.clearAll]'s one transaction.
     * Signed out, that transaction records the emptied cache as nobody's; a user's first use claims
     * it, so no user id is written back by the clear. The scrub runs after that transaction, and
     * the cache files go last.
     */
    override suspend fun clearAllCache(): Unit = operationGate.call {
        val owner =
            QueueIdentity.Anonymous.storedValue.takeIf {
                authPluginOrNull() != null &&
                    supabase.currentReplayIdentity() == QueueIdentity.Anonymous
            }
        mutationQueueReplayer.clearLocalData {
            fileCache.clear {
                store.clearAll(owner)
                scrubDatabase()
            }
        }
    }

    /**
     * The first call shuts the gate, which ends every collection, and cancels the client's work and
     * the calls it may stop. Every call then waits, up to [closeWait], for the release.
     */
    override suspend fun close() {
        if (operationGate.close()) clientScope.coroutineContext.job.cancel()
        readShapePostgrest.value = null
        // On a real clock whatever calls it: under a test scheduler the caller's clock skips ahead
        val releasedInTime =
            withContext(NonCancellable + Dispatchers.Default) {
                withTimeoutOrNull(closeWait) { releaseOnceIdle() } != null
            }
        if (
            releasedInTime ||
                released.isCompleted ||
                !reportedStillClosing.compareAndSet(false, true)
        )
            return
        sdkLogger.warning {
            "close() waited $closeWait for ${operationGate.inFlight} call(s) still using the database; " +
                "it is released when they finish"
        }
        onStillClosing { operationGate.inFlight }
        // A child of the scope the client was given, which close() never cancels
        if (parentScope.isActive)
            parentScope.launch { withContext(NonCancellable) { releaseOnceIdle() } }
    }

    /**
     * Waits until the client's work and every call have ended, then releases the database, once.
     */
    private suspend fun releaseOnceIdle() {
        clientScope.coroutineContext.job.join()
        operationGate.awaitNoCallsInFlight()
        if (!releasing.compareAndSet(expect = false, update = true)) return released.await()
        try {
            onClose()
        } finally {
            released.complete(Unit)
        }
    }

    private fun ensureOpen() {
        operationGate.ensureOpen()
    }

    /* ──────────────────────── Mutation queue (internal) ──────────────────────── */

    /*
     * The mutation queue's ids, rejected list, identity and sync report, for the change that
     * exposes them publicly under the offline API sheet's names. Internal until then.
     */

    /**
     * Runs one replay pass and reports what it did; returns promptly when offline.
     *
     * @param table The table to replay, or `null` for the whole queue.
     * @return What the pass synced, rejected and left pending.
     */
    internal suspend fun syncPendingMutationsReport(table: String? = null): SyncReport =
        operationGate.cancellableCall(clientScope) { mutationQueueReplayer.sync(table = table) }

    /**
     * The current session's pending mutations — of [table], or of every table — in replay order, as
     * they change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending mutations.
     */
    internal fun pendingMutations(table: String? = null): Flow<List<PendingMutation>> {
        ensureOpen()
        return operationGate.callCollecting(
            store.pendingMutationsFlow(table).map { rows ->
                rows.mapNotNull { row -> row.toPendingMutation(store.json, primaryKeys) }
            }
        )
    }

    /**
     * The persisted rejected mutations, oldest first, as they change. The list keeps the
     * newest 100.
     *
     * @return A flow of rejected mutations; it fails if the persisted list cannot be decoded.
     */
    internal fun rejectedMutations(): Flow<List<RejectedMutation>> {
        ensureOpen()
        return operationGate.callCollecting(
            store.mutationQueueState.rejectionsFlow().map { entries ->
                entries.map { entry -> entry.toRejectedMutation(store.json, primaryKeys) }
            }
        )
    }

    /**
     * The persisted rejected mutations, oldest first.
     *
     * @return The list; it throws if the persisted list cannot be decoded.
     */
    internal suspend fun rejectedMutationList(): List<RejectedMutation> = operationGate.call {
        store.mutationQueueState.rejections().map { entry ->
            entry.toRejectedMutation(store.json, primaryKeys)
        }
    }

    /**
     * Removes a rejected mutation from the list.
     *
     * @param id The rejected mutation's id.
     * @return `true` when it was on the list.
     */
    internal suspend fun dismissRejectedMutation(id: Long): Boolean = operationGate.call {
        store.dismissRejectedMutation(id)
    }

    /**
     * Puts a rejected mutation back at the tail of the queue and requests a replay pass.
     *
     * @param id The rejected mutation's id.
     * @return The new pending mutation's id, or `null` when there is no such rejection, or it — or
     *   the pending queue — belongs to another identity than the current session's.
     */
    internal suspend fun retryRejectedMutation(id: Long): Long? = operationGate.call {
        val identity = supabase.currentQueueIdentity() ?: return@call null
        val newId = store.retryRejectedMutation(id, identity.storedValue) ?: return@call null
        backgroundSync.request()
        newId
    }

    /**
     * Discards one of the current session's pending mutations without sending it: it leaves the
     * queue, and the cache overlay stops showing it. The way out of a write the server keeps
     * failing with a `5xx`, which otherwise blocks every later write of the same identity.
     *
     * @param id The pending mutation's id.
     * @return `true` when it was pending, the current session's, and not being sent right now.
     */
    internal suspend fun discardPendingMutation(id: Long): Boolean = operationGate.call {
        val identity = supabase.currentQueueIdentity() ?: return@call false
        mutationQueueReplayer.discardPendingMutation(id, identity)
    }

    /* ───────────────────────────── Guards ───────────────────────────── */

    internal companion object {
        /**
         * How long [close] waits for the calls in flight before it returns without closing the
         * database.
         */
        val CLOSE_WAIT: Duration = 10.seconds
    }

    private fun ensurePostgrestInstalled() {
        runCatching { supabase.postgrest }
            .getOrElse {
                error(
                    "Postgrest plugin is not installed. Install with createSupabaseClient { install(Postgrest) }"
                )
            }
    }

    private fun ensureStorageInstalled() {
        runCatching { supabase.storage }
            .getOrElse {
                error(
                    "Storage plugin is not installed. Install with createSupabaseClient { install(Storage) }"
                )
            }
    }
}

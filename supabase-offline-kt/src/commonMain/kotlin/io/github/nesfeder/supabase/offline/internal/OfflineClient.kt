// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AutomaticReplay
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
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
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.LiveQueries
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.internal.postgrest.RequestSession
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.installDryRunCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installReadShapeCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installTransportFailureCapture
import io.github.nesfeder.supabase.offline.internal.postgrest.readShapePostgrestOf
import io.github.nesfeder.supabase.offline.internal.postgrest.refreshWakeUps
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
 * Internal implementation of [SupabaseOfflineClient]. Handles wiring of [OfflineStore] and
 * [OfflinePostgrestQueryBuilder].
 */
@OptIn(SupabaseInternal::class)
internal class OfflineClient(
    override val supabase: SupabaseClient,
    private val store: OfflineStore,
    private val baseDirectoryProvider: () -> Path,
    private val engineLogger: EngineLogger = EngineLogger(null, OfflineLogLevel.INFO),
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
    coordinatorScope: CoroutineScope = newEngineScope(engineLogger),
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
    private val clientScope: CoroutineScope = childEngineScope(coordinatorScope, engineLogger)

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
            engineLogger = engineLogger,
        )

    private val mutationQueueReplayer =
        MutationQueueReplayer(
            store,
            supabase,
            engineLogger = engineLogger,
            primaryKeys = primaryKeys,
        )

    /**
     * Runs the replay passes the engine starts on its own, in the client's scope so [close] stops
     * them.
     */
    private val automaticReplay =
        AutomaticReplay(scope = clientScope, engineLogger = engineLogger) {
            mutationQueueReplayer.sync()
        }

    /** Encodes the primary-key [FilterDescriptor] built for realtime records. */
    private val json = EngineJson

    /**
     * Client-scoped coordinator shared by every builder this client creates: single-flight refresh
     * sharing per `(table, cacheKey)` plus the live-signature registry that backs
     * materialize-on-write in [OfflineStore.patchCachedQueries].
     */
    private val liveQueries = LiveQueries(clientScope)

    /**
     * SDK-internal recovery accelerator merging auth-session-restored and connectivity-regained
     * edges. Forwarded to every builder to short-circuit a stalled refresh's backoff.
     */
    private val wakeUps: Flow<Unit> = refreshWakeUps(supabase, networkAvailability, responseTrust)

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
        store.liveCacheKeysOf = liveQueries::liveCacheKeysOf
        store.allLiveCacheKeys = liveQueries::allLiveCacheKeys
        store.onQueriesMarkedStale = { table, cacheKeys ->
            liveQueries.requestRefresh(table, cacheKeys)
        }
        store.engineLogger = engineLogger
        store.primaryKeys = primaryKeys
        // Overlays and counts show the current session's queued writes only.
        store.identityProvider = ::overlayIdentityValue
        store.identityChanges = sessionChanges()
        store.ownerlessMutationsAreAnonymous = authPluginOrNull() == null
        // The cache is one identity's at a time; without Auth there is only one
        store.isCacheOwnedPerIdentity = authPluginOrNull() != null
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
     * [AutomaticReplay].
     */
    private fun replayWhenTheSessionSettles() {
        val auth = authPluginOrNull() ?: return
        clientScope.launch {
            auth.sessionStatus.collect { status ->
                val identity = replayIdentityFor(status) ?: return@collect
                if (store.hasPendingMutationsOf(identity.storedValue)) automaticReplay.requestPass()
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
                    engineLogger.error {
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
     * locks in its order, and live queries fetch again. A crash after the rows are dropped and
     * before the files are deleted leaves those files on disk with no row: no call reaches them,
     * and the next clear or drop deletes them.
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
        engineLogger.info {
            "The session settled into another identity; the cache was emptied for it"
        }
        liveQueries.liveTables().forEach { table ->
            liveQueries.requestRefresh(table)
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
        return OfflineTable(
            delegate = delegate,
            store = store,
            engineLogger = engineLogger,
            requestSession = ::requestSession,
            trustPolicy = responseTrust,
            liveQueries = liveQueries,
            wakeUps = wakeUps,
            queueIdentity = { supabase.currentQueueIdentity() },
            requestSync = automaticReplay::requestPass,
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
            engineLogger = engineLogger,
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

    override fun pendingMutationCountFlow(): Flow<Int> =
        operationGate.callCollecting(store.pendingMutationCountFlow())

    override suspend fun pendingMutationCount(table: String): Int = operationGate.call {
        store.pendingMutationCountForTable(table)
    }

    override fun pendingMutationCountFlow(table: String): Flow<Int> =
        operationGate.callCollecting(store.pendingMutationCountForTableFlow(table))

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>,
    ): Unit = operationGate.call {
        val filterJson = primaryKeyFilterJson(record, primaryKeys)
        if (filterJson == null) {
            engineLogger.warning {
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
            engineLogger.warning {
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
        return encodeFilter(descriptor, json)
    }

    /**
     * Clears once, locks outermost first: the replayer's send lock, after it cancels passes in
     * flight; the file cache's lock; the store's, and innermost the cache-owner record's, around
     * [OfflineStore.clearLocalData]'s one transaction. Signed out, that transaction records the
     * emptied cache as nobody's; a user's first use claims it, so no user id is written back by the
     * clear. The scrub runs after that transaction, and the cache files go last.
     */
    override suspend fun clearAllCache(): Unit = operationGate.call {
        val owner =
            QueueIdentity.Anonymous.storedValue.takeIf {
                authPluginOrNull() != null &&
                    supabase.currentReplayIdentity() == QueueIdentity.Anonymous
            }
        mutationQueueReplayer.clearLocalData {
            fileCache.clear {
                store.clearLocalData(owner)
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
        if (releasedInTime || released.isCompleted) return
        // Each close() that gives up marks the database before it returns; only the first also
        // warns and schedules the release
        onStillClosing { operationGate.inFlight }
        if (!reportedStillClosing.compareAndSet(false, true)) return
        engineLogger.warning {
            "close() waited $closeWait for ${operationGate.inFlight} call(s) still using the database; " +
                "it is released when they finish"
        }
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
        return operationGate.callCollecting(
            store.rejectedList.rejectionsFlow().map { entries ->
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
        store.rejectedList.rejections().map { entry ->
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
        automaticReplay.requestPass()
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

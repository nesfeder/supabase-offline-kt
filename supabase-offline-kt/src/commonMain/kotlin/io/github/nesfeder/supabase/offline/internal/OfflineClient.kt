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
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.OfflineStorage
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AutomaticReplay
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutation as EngineRejectedMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncReport as EngineSyncReport
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentQueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.currentReplayIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.replayIdentityFor
import io.github.nesfeder.supabase.offline.internal.mutationqueue.syncFailureOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
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
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
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
 * The client behind [Offline]: one database's cache, mutation queue and Storage file cache, serving
 * one [SupabaseClient]. It wires the store, the table engines, the replayer and the file cache, and
 * owns their lifecycle through its operation gate.
 */
@OptIn(SupabaseInternal::class, OfflineInternal::class, ExperimentalAtomicApi::class)
internal class OfflineClient(
    override val supabaseClient: SupabaseClient,
    private val store: OfflineStore,
    private val baseDirectoryProvider: () -> Path,
    private val engineLogger: EngineLogger = EngineLogger(null, EngineLogLevel.INFO),
    /**
     * The connectivity signal (`true` = online): the platform's monitor or the app's own, `null`
     * for none. A hint, never a gate: it decides when the client tries by itself, what a waiting
     * refresh reports, and [syncStatus]'s network.
     */
    networkAvailability: Flow<Boolean>? = null,
    /**
     * Scope the client's own scope is made a child of. Defaults to a process-lifetime supervised
     * scope; injectable so tests can supply a controlled scope. [close] cancels only the client's
     * child scope, never this one.
     */
    coordinatorScope: CoroutineScope = newEngineScope(engineLogger),
    /** Size bound of the Storage file cache in bytes, or `null` for none. */
    fileCacheMaxBytes: Long? = Offline.DEFAULT_FILE_CACHE_SIZE_BYTES,
    /**
     * Whether to create the file cache's directories now and exclude them from backups. The
     * platform factories do; tests and the in-memory client, whose data directory is not theirs to
     * write into, do not.
     */
    prepareFileCacheDirectories: Boolean = false,
    /**
     * Rewrites the database without the rows [clearLocalData] deleted, so none can be read back
     * from its files. The factories pass the database's own; the in-memory store has no files.
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
    /** The configuration the client was opened with, as [Offline.config] reports it. */
    override val config: Offline.Config = Offline.Config(),
) : Offline {

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
    private val operationGate = ClientOperationGate(supabaseClient::isTransportClosed)

    /**
     * The app's network switch and the connectivity signal: what may be sent, and what may work.
     */
    private val networkAccess = NetworkAccess(networkAvailability)

    /** What [syncStatus] reports; its network is set at once, its counts as the queue changes. */
    private val syncStatusState =
        MutableStateFlow(
            SyncStatus(
                network = networkAccess.status,
                pendingMutationCount = 0,
                rejectedMutationCount = 0,
                isSyncing = false,
                lastSyncedAt = null,
                lastSyncError = null,
            )
        )

    /** Taken by the first read of [syncStatus], which starts the work that keeps it current. */
    private val statusTracked = AtomicBoolean(false)

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
    private val renderImagePath: String by lazy { renderImagePathOf(supabaseClient) }

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
            supabaseClient,
            engineLogger = engineLogger,
            networkAccess = networkAccess,
            primaryKeys = primaryKeys,
            passEnded = { _, report -> recordPassEnded(report) },
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
    private val wakeUps: Flow<Unit> =
        refreshWakeUps(supabaseClient, networkAccess.reconnections, responseTrust)

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
        supabaseClient.installDryRunCheck()
        supabaseClient.installReadShapeCheck()
        supabaseClient.installTransportFailureCapture()
        networkAccess.watchIn(clientScope, onReconnected = ::replayAsTheNetworkReturns)
        if (config.autoSync) replayWhenTheSessionSettles()
        keepCacheWithTheSession()
    }

    private fun authPluginOrNull(): Auth? = supabaseClient.pluginManager.getPluginOrNull(Auth)

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
        val identity = supabaseClient.currentQueueIdentity()?.storedValue
        overlayIdentity.value = status to identity
        return identity
    }

    /**
     * Starts a pass when the session settles into an identity with mutations queued, in the
     * client's scope so [close] stops it. Every new status counts, as a sign-out and sign-in it
     * never saw arrive as a new session of the same identity. Not started with `autoSync` off; see
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
     * Starts a pass, as the network becomes reachable again (the monitor, the app's signal, or
     * [enableNetwork]), when the settled identity has mutations queued. An identity still settling
     * is left to the session trigger. Does nothing with `autoSync` off; see [AutomaticReplay].
     */
    private suspend fun replayAsTheNetworkReturns() {
        if (!config.autoSync) return
        val identity = supabaseClient.currentReplayIdentity() ?: return
        try {
            if (store.hasPendingMutationsOf(identity.storedValue)) automaticReplay.requestPass()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not tell whether queued writes wait for the network " +
                    "(${failure::class.simpleName}); they go with the next pass"
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
     * cached queries and the Storage file cache, never the mutation queue — under
     * [clearLocalData]'s locks in its order, and live queries fetch again. A crash after the rows
     * are dropped and before the files are deleted leaves those files on disk with no row: no call
     * reaches them, and the next clear or drop deletes them.
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

    override fun from(table: String): OfflinePostgrestQueryBuilder {
        ensureOpen()
        ensurePostgrestInstalled()
        return from(supabaseClient.postgrest.config.defaultSchema, table)
    }

    override fun from(schema: String, table: String): OfflinePostgrestQueryBuilder {
        ensureOpen()
        ensurePostgrestInstalled()
        require(!isReservedTable(table)) { "The table name is reserved by the offline engine" }
        val delegate = supabaseClient.postgrest.from(schema, table)
        val readShape =
            readShapePostgrest.value
                ?: readShapePostgrestOf(supabaseClient.postgrest).let { created ->
                    if (readShapePostgrest.compareAndSet(null, created)) created
                    else readShapePostgrest.value ?: created
                }
        val engine =
            OfflineTable(
                delegate = delegate,
                store = store,
                engineLogger = engineLogger,
                requestSession = ::requestSession,
                trustPolicy = responseTrust,
                liveQueries = liveQueries,
                wakeUps = wakeUps,
                queueIdentity = { supabaseClient.currentQueueIdentity() },
                requestSync = automaticReplay::requestPass,
                headSender = mutationQueueReplayer,
                operationGate = operationGate,
                readShapePostgrest = readShape,
                queueEmptied = ::recordQueueEmptiedBySending,
                networkAccess = networkAccess,
            )
        return OfflinePostgrestQueryBuilder(engine)
    }

    override suspend fun rpc(
        function: String,
        request: RpcRequestBuilder.() -> Unit,
    ): PostgrestResult = operationGate.call {
        ensurePostgrestInstalled()
        networkAccess.ensureEnabled()
        supabaseClient.sendUnlessClosing { supabaseClient.postgrest.rpc(function, request) }
    }

    override suspend fun rpc(
        function: String,
        parameters: JsonObject,
        request: RpcRequestBuilder.() -> Unit,
    ): PostgrestResult = operationGate.call {
        ensurePostgrestInstalled()
        networkAccess.ensureEnabled()
        supabaseClient.sendUnlessClosing {
            supabaseClient.postgrest.rpc(function, parameters, request)
        }
    }

    /** The session a read sent now goes out under; the identity is the mutation queue's own. */
    private fun requestSession(): RequestSession =
        requestSessionFor(authPluginOrNull()?.sessionStatus?.value)

    override val storage: OfflineStorage = OfflineStorage(::bucket)

    /** The bucket [bucketId], caching through the client's file cache. */
    private fun bucket(bucketId: String): OfflineBucketApi {
        ensureOpen()
        ensureStorageInstalled()
        return CachingBucketApi(
            delegate = supabaseClient.storage.from(bucketId),
            cache = fileCache,
            engineLogger = engineLogger,
            backgroundScope = clientScope,
            operationGate = operationGate,
            renderImagePath = renderImagePath,
            networkAccess = networkAccess,
        )
    }

    /* ─────────────────────────── The mutation queue ─────────────────────────── */

    override fun pendingMutations(table: String?): Flow<List<PendingMutation>> =
        operationGate.callCollecting(
            store.shownPendingMutationsFlow(table).map { rows ->
                rows.mapNotNull { row -> row.toPendingMutation(store.json, primaryKeys) }
            }
        )

    override suspend fun pendingMutationCount(table: String?): Int = operationGate.call {
        store.shownPendingMutationCount(table)
    }

    override fun pendingMutationCountFlow(table: String?): Flow<Int> =
        operationGate.callCollecting(
            store.shownPendingMutationsFlow(table).map { rows -> rows.size }.distinctUntilChanged()
        )

    /**
     * Waits for a sender that has the mutation on the wire, then discards it. What it held back is
     * sent by a pass asked for here, with `autoSync` off too: it is a write-path pass.
     */
    override suspend fun discardPendingMutation(id: Long): Unit = operationGate.call {
        val identity = supabaseClient.currentQueueIdentity() ?: return@call
        if (!mutationQueueReplayer.discardPendingMutationOnceUnsent(id, identity)) return@call
        if (store.hasPendingMutationsOf(identity.storedValue)) automaticReplay.requestPass()
    }

    override fun rejectedMutations(): Flow<List<RejectedMutation>> =
        operationGate.callCollecting(
            store.shownRejectionsFlow().map { entries ->
                entries.map { entry -> entry.toRejectedMutation(store.json, primaryKeys).shown() }
            }
        )

    override suspend fun dismissRejectedMutation(id: Long): Unit = operationGate.call {
        val identity = supabaseClient.currentQueueIdentity() ?: return@call
        store.dismissRejectedMutationOf(id, identity.storedValue)
    }

    override suspend fun retryRejectedMutation(id: Long): Unit = operationGate.call {
        val identity = supabaseClient.currentQueueIdentity() ?: return@call
        val entry = store.shownRejection(id, identity.storedValue) ?: return@call
        require(entry.bodyOmittedChars == null) {
            "This rejected mutation cannot be retried: its body was too large to keep " +
                "(RejectedMutation.isRetryable is false)"
        }
        store.retryRejectedMutation(id, identity.storedValue) ?: return@call
        automaticReplay.requestPass()
    }

    override suspend fun syncPendingMutations(table: String?): SyncReport =
        operationGate.cancellableCall(clientScope) {
            ensurePostgrestInstalled()
            mutationQueueReplayer.syncWaitingOutOtherSenders(table).shown()
        }

    /* ─────────────────────────── Sync status and the network ─────────────────────────── */

    /** [syncStatusState], read only. */
    private val syncStatusView: StateFlow<SyncStatus> = syncStatusState.asStateFlow()

    override val syncStatus: StateFlow<SyncStatus>
        get() {
            ensureOpen()
            trackStatusOnce()
            return syncStatusView
        }

    override suspend fun disableNetwork(): Unit = operationGate.call {
        networkAccess.disable()
        syncStatusState.update { status -> status.copy(network = networkAccess.status) }
    }

    override suspend fun enableNetwork(): Unit = operationGate.call {
        val reconnected = networkAccess.enable()
        syncStatusState.update { status -> status.copy(network = networkAccess.status) }
        if (reconnected) replayAsTheNetworkReturns()
    }

    /**
     * Starts, once, the work that keeps [syncStatus] current, in the client's scope so [close]
     * stops it: the network, whether a pass runs, and the two counts as the queue and the rejected
     * list change. A client nobody asks the status of does none of it.
     */
    private fun trackStatusOnce() {
        if (!statusTracked.compareAndSet(expectedValue = false, newValue = true)) return
        clientScope.launch {
            networkAccess.statusChanges.collect {
                syncStatusState.update { status -> status.copy(network = networkAccess.status) }
            }
        }
        clientScope.launch {
            mutationQueueReplayer.passRunning.collect { running ->
                syncStatusState.update { status -> status.copy(isSyncing = running) }
            }
        }
        clientScope.launch {
            // One count per change, in order, so an older count never lands after a newer one
            store.queueChanges().collect {
                val count = statusCountOrNull("pending") { store.shownPendingMutationCount(null) }
                if (count != null)
                    syncStatusState.update { status -> status.copy(pendingMutationCount = count) }
            }
        }
        clientScope.launch {
            store
                .shownRejectionsFlow()
                .catch { failure ->
                    engineLogger.warning {
                        "The sync status stopped following the rejected list (${failure::class.simpleName})"
                    }
                }
                .collect { entries ->
                    syncStatusState.update { status ->
                        status.copy(rejectedMutationCount = entries.size)
                    }
                }
        }
    }

    /** [count], or `null` when it fails; the status keeps its last count then, and says so. */
    private suspend fun statusCountOrNull(name: String, count: suspend () -> Int): Int? =
        try {
            count()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not count the $name mutations for the sync status (${failure::class.simpleName})"
            }
            null
        }

    /**
     * Records what a pass that ended with [report] tells the status: a failure that stopped it as
     * [SyncStatus.lastSyncError], built without the failure's message; a pass that ran to the end
     * clears it, and records [SyncStatus.lastSyncedAt] when it left the session's queue empty.
     */
    private suspend fun recordPassEnded(report: EngineSyncReport) {
        when (val stop = report.stop) {
            null ->
                if (report.remainingCount == 0 && sessionQueueIsEmpty()) {
                    recordQueueEmptiedBySending()
                } else {
                    syncStatusState.update { status -> status.copy(lastSyncError = null) }
                }
            is SyncStop.TransientFailure ->
                syncStatusState.update { status ->
                    status.copy(lastSyncError = syncFailureOf(stop.cause))
                }
            is SyncStop.HeldBehindEarlierMutations ->
                syncStatusState.update { status ->
                    status.copy(lastSyncError = syncFailureOf(stop.cause))
                }
            // The session, a clear, the switch or another sender: nothing failed
            else -> Unit
        }
    }

    /** Whether the settled session has nothing queued; `false` when that cannot be told. */
    private suspend fun sessionQueueIsEmpty(): Boolean {
        val identity = supabaseClient.currentReplayIdentity() ?: return false
        return try {
            !store.hasPendingMutationsOf(identity.storedValue)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            false
        }
    }

    /** Records that sending left the session's queue empty: now, and no error since. */
    private fun recordQueueEmptiedBySending() {
        syncStatusState.update { status ->
            status.copy(lastSyncedAt = Clock.System.now(), lastSyncError = null)
        }
    }

    /** This engine rejection as the app sees it. */
    private fun EngineRejectedMutation.shown(): RejectedMutation =
        RejectedMutation(
            id = id,
            table = table,
            operation = operation,
            createdAt = createdAt,
            rowKeys = rowKeys,
            body = body,
            serverError =
                serverError?.let { error ->
                    ServerError(
                        status = error.status,
                        code = error.code,
                        message = error.message,
                        details = error.details,
                        hint = error.hint,
                    )
                },
            message = message,
            rejectedAt = rejectedAt,
            isRetryable = bodyOmittedChars == null,
        )

    /** This engine report as the app sees it. */
    private fun EngineSyncReport.shown(): SyncReport =
        SyncReport(
            syncedCount = syncedCount,
            rejected = rejected.map { rejection -> rejection.shown() },
            remainingCount = remainingCount,
            alreadyOnServerCount = alreadyOnServerCount,
        )

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = operationGate.call {
        val cachedTable = cachedTableOf(table, schema)
        val keyColumns = primaryKeys ?: this.primaryKeys.of(cachedTable)
        val filterJson = primaryKeyFilterJson(record, keyColumns)
        if (filterJson == null) {
            engineLogger.warning {
                "applyServerRecord($table): dropping record — primary key(s) $keyColumns " +
                    "absent or non-primitive"
            }
            return@call
        }
        store.patchCachedQueries(
            table = cachedTable,
            operation = MutationOperation.UPSERT,
            bodyJson = JsonArray(listOf(record)).toString(),
            filterJson = filterJson,
            onConflict = keyColumns.joinToString(separator = ","),
        )
    }

    override suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = operationGate.call {
        val cachedTable = cachedTableOf(table, schema)
        val keyColumns = primaryKeys ?: this.primaryKeys.of(cachedTable)
        val filterJson = primaryKeyFilterJson(oldRecord, keyColumns)
        if (filterJson == null) {
            engineLogger.warning {
                "applyServerDelete($table): dropping record — primary key(s) $keyColumns " +
                    "absent or non-primitive"
            }
            return@call
        }
        store.patchCachedQueries(
            table = cachedTable,
            operation = MutationOperation.DELETE,
            bodyJson = "{}",
            filterJson = filterJson,
        )
    }

    /**
     * The name [table] of [schema] is cached under: its own in the default schema, `schema.table`
     * in another, as a read of it caches it.
     */
    private fun cachedTableOf(table: String, schema: String?): String {
        val defaultSchema =
            supabaseClient.pluginManager.getPluginOrNull(Postgrest)?.config?.defaultSchema
        return if (schema.isNullOrBlank() || schema == defaultSchema) table else "$schema.$table"
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
    override suspend fun clearLocalData(): Unit = operationGate.call {
        val owner =
            QueueIdentity.Anonymous.storedValue.takeIf {
                authPluginOrNull() != null &&
                    supabaseClient.currentReplayIdentity() == QueueIdentity.Anonymous
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

    /* ──────────────────────── For the engine's own tests ──────────────────────── */

    /**
     * One replay pass and the engine's own report of it, why it stopped included; for tests that
     * look past [syncPendingMutations]. Returns promptly when offline.
     *
     * @param table The table to replay, or `null` for the whole queue.
     */
    internal suspend fun syncPendingMutationsReport(table: String? = null): EngineSyncReport =
        operationGate.cancellableCall(clientScope) { mutationQueueReplayer.sync(table = table) }

    /**
     * Every identity's persisted rejected mutations, oldest first; for tests that look past
     * [rejectedMutations]. Throws if the persisted list cannot be decoded.
     */
    internal suspend fun rejectedMutationList(): List<EngineRejectedMutation> = operationGate.call {
        store.rejectedList.rejections().map { entry ->
            entry.toRejectedMutation(store.json, primaryKeys)
        }
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
        runCatching { supabaseClient.postgrest }
            .getOrElse {
                error(
                    "Postgrest plugin is not installed. Install with createSupabaseClient { install(Postgrest) }"
                )
            }
    }

    private fun ensureStorageInstalled() {
        runCatching { supabaseClient.storage }
            .getOrElse {
                error(
                    "Storage plugin is not installed. Install with createSupabaseClient { install(Storage) }"
                )
            }
    }
}

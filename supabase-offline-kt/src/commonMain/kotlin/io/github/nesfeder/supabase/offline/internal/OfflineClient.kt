// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.request.RpcRequestBuilder
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.jan.supabase.storage.Storage
import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.ConflictResolution
import io.github.nesfeder.supabase.offline.ConflictedMutation
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilder
import io.github.nesfeder.supabase.offline.OfflineStorage
import io.github.nesfeder.supabase.offline.PendingMutation
import io.github.nesfeder.supabase.offline.PendingUpload
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.RejectedMutation
import io.github.nesfeder.supabase.offline.RejectedUpload
import io.github.nesfeder.supabase.offline.ServerError
import io.github.nesfeder.supabase.offline.SyncReport
import io.github.nesfeder.supabase.offline.SyncStatus
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.CacheOwnership
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.LocalData
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.cache.QueryOverlay
import io.github.nesfeder.supabase.offline.internal.cache.buildCacheComponents
import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import io.github.nesfeder.supabase.offline.internal.cache.monotonicTickSource
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.DatabaseScrub
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.AutomaticReplay
import io.github.nesfeder.supabase.offline.internal.mutationqueue.CONFLICT_POLICY_TIMEOUT
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ConflictPolicy
import io.github.nesfeder.supabase.offline.internal.mutationqueue.ConflictRequeue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultConflictPolicy
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.GONE_REASON
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.NoVersionColumns
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.RejectedMutation as EngineRejectedMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredConflict
import io.github.nesfeder.supabase.offline.internal.mutationqueue.StoredRejection
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncReport as EngineSyncReport
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.VersionColumns
import io.github.nesfeder.supabase.offline.internal.mutationqueue.conflictDecisionOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.jsonObjectOrNull
import io.github.nesfeder.supabase.offline.internal.mutationqueue.pinnedRowKeyOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.stopReasonOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toConflictedMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toRejectedMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.versionTextOf
import io.github.nesfeder.supabase.offline.internal.network.NetworkAccess
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.LiveQueries
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.installCommittedDryRunReport
import io.github.nesfeder.supabase.offline.internal.postgrest.installDryRunCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installReadShapeCheck
import io.github.nesfeder.supabase.offline.internal.postgrest.installTransportFailureCapture
import io.github.nesfeder.supabase.offline.internal.postgrest.readShapePostgrestOf
import io.github.nesfeder.supabase.offline.internal.postgrest.refreshWakeUps
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.storage.CachingBucketApi
import io.github.nesfeder.supabase.offline.internal.storage.PinnedFileRecords
import io.github.nesfeder.supabase.offline.internal.storage.QueuedUploadFiles
import io.github.nesfeder.supabase.offline.internal.storage.QueuedUploadWriter
import io.github.nesfeder.supabase.offline.internal.storage.QueuedUploads
import io.github.nesfeder.supabase.offline.internal.storage.StorageFileCache
import io.github.nesfeder.supabase.offline.internal.storage.installDownloadStatusCheck
import io.github.nesfeder.supabase.offline.internal.storage.isQueuedUpload
import io.github.nesfeder.supabase.offline.internal.storage.markFileUsed
import io.github.nesfeder.supabase.offline.internal.storage.renderImagePathOf
import io.github.nesfeder.supabase.offline.internal.storage.syncFileToDisk
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * The client behind [Offline]: one database's cache, mutation queue and Storage file cache, serving
 * one [SupabaseClient]. It builds the cache and queue components over the database's tables, the
 * table engines, the replayer and the file cache, and owns their lifecycle through its gate.
 *
 * Four parts it builds carry what is not one call's own: [SessionIdentity] tells whom the session
 * speaks for; [SessionCoordinator] acts on the session and the cache's owner, and clears, and
 * states the lock order a clear and a drop for a new owner take; [SyncStatusTracker] keeps
 * [syncStatus]; [RealtimeApplier] follows the watched tables and applies server rows.
 */
@OptIn(SupabaseInternal::class, OfflineInternal::class, ExperimentalAtomicApi::class)
internal class OfflineClient(
    override val supabaseClient: SupabaseClient,
    /** The cached queries' table, which also holds the engine's reserved rows. */
    queryCacheDao: QueryCacheDao,
    /** The Storage file cache's table. */
    cachedFileDao: CachedFileDao,
    /** The mutation queue's table. */
    mutationDao: MutationDao,
    /** When the query cache evicts, and how much. */
    queryCacheLimits: QueryCacheLimits,
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
     * from its files, and tries once to empty its write-ahead log: whether it did. The factories
     * pass the database's own; the in-memory tables have no files.
     */
    scrubDatabase: suspend () -> Boolean = { true },
    /** Tries once, waiting for no read, to empty the database's write-ahead log: whether it did. */
    emptyWriteAheadLog: suspend () -> Boolean = { true },
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
    /** Each table's version column, which guards a queued UPDATE's or DELETE's replay; none. */
    versionColumns: VersionColumns = NoVersionColumns,
    /**
     * Settles a guarded write whose row moved on the server: the app's resolver, or the default.
     */
    private val conflictPolicy: ConflictPolicy = DefaultConflictPolicy,
    /** How long [conflictPolicy] may take before its answer reads as Hold; tests shorten it. */
    private val conflictPolicyTimeout: Duration = CONFLICT_POLICY_TIMEOUT,
    /** The engine's dispatcher, where [conflictPolicy] runs whatever dispatcher syncs. */
    private val conflictPolicyDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Which read responses may replace what the cache holds; see [ResponseTrustPolicy]. */
    private val responseTrust: ResponseTrustPolicy = ResponseTrustPolicy.SETTLED_SESSION,
    /** The configuration the client was opened with, as [Offline.config] reports it. */
    override val config: Offline.Config = Offline.Config(),
    /** Where the Storage file cache keeps its files: the device's disk unless a test says not. */
    fileSystem: FileSystem = FileSystem.SYSTEM,
    /** Records a use time on one of [fileSystem]'s files. */
    markFileUsed: (Path, Long) -> Unit = ::markFileUsed,
    /** Writes one of [fileSystem]'s files through to the disk before the cache renames it. */
    syncFile: (Path) -> Unit = ::syncFileToDisk,
    /** The time the client's reads and file cache see; rows are stamped by [rowClock]. */
    private val clock: Clock = Clock.System,
    /** The clock the cached queries, the queue and the cached files' rows are stamped with. */
    rowClock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The one tick source of the watermarks and of request starts; see [CacheFreshness]. */
    monotonicTick: () -> Long = monotonicTickSource(),
    /** Where file work runs. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Where [close] waits out its bound, which is real time unless a test injects otherwise. */
    private val closeWaitDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The tables whose changes on the server the client follows and applies; none by default. */
    watchedTables: List<WatchedTable> = emptyList(),
    /** Where those changes come from; `null` follows nothing. */
    serverChangeFeed: ServerChangeFeed? = null,
    /**
     * Runs before a live reader hears of a payload write; `LiveReadSourceOrderTest` delays it so a
     * reader hears of the write after other changes. `null` for none.
     */
    beforePayloadWriteHeard: (suspend (table: String) -> Unit)? = null,
) : Offline {

    /**
     * Where everything the client starts runs: a child of the injected scope, on its dispatcher.
     * [close] cancels it without touching the caller's scope.
     */
    private val clientScope: CoroutineScope =
        newEngineScope(engineLogger, parent = coordinatorScope)

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

    /**
     * [Offline.Config.autoSync], read once when the client opens, as every other setting is: both
     * triggers it governs follow the value the client was opened with.
     */
    private val autoSync: Boolean = config.autoSync

    /** Taken by the one committed dry run this client reports. */
    private val committedDryRunReported = AtomicBoolean(false)

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

    /**
     * Client-scoped coordinator shared by every builder this client creates: single-flight refresh
     * sharing per `(table, cacheKey)` plus the live-query registry that backs materialize-on-write
     * in [CachePatcher.patchCachedQueries].
     */
    private val liveQueries = LiveQueries(clientScope, engineLogger)

    /** Whom the session speaks for, as each part below asks; see [SessionIdentity]. */
    private val sessionIdentity = SessionIdentity(supabaseClient, config, engineLogger)

    /** The files holding queued uploads' bytes, beside the file cache's. */
    private val queuedUploadFiles =
        QueuedUploadFiles(
            dataDirectoryProvider = baseDirectoryProvider,
            engineLogger = engineLogger,
            fileSystem = fileSystem,
            syncFile = syncFile,
            ioDispatcher = ioDispatcher,
        )

    /** The cache and the mutation queue, built bottom up over the database's tables. */
    private val components =
        buildCacheComponents(
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            queryCacheLimits = queryCacheLimits,
            nowMillis = rowClock,
            monotonicTick = monotonicTick,
            primaryKeys = primaryKeys,
            identityProvider = sessionIdentity::overlayIdentityValue,
            identityChanges = sessionIdentity.sessionChanges(),
            isCacheOwnedPerIdentity = sessionIdentity.identityIsPerUser,
            ownerlessMutationsAreAnonymous = !sessionIdentity.identityIsPerUser,
            engineLogger = engineLogger,
            liveCacheKeysOf = liveQueries::liveCacheKeysOf,
            allLiveCacheKeys = liveQueries::allLiveCacheKeys,
            onQueriesMarkedStale = { table, cacheKeys ->
                liveQueries.requestRefresh(table, cacheKeys)
            },
            onCacheNotServed = { sessionIdentity.cacheOwnerChecks.tryEmit(Unit) },
            versionColumns = versionColumns,
            backgroundScope = clientScope,
            beforePayloadWriteHeard = beforePayloadWriteHeard,
            queuedUploadFiles = queuedUploadFiles,
        )

    /*
     * The parts the client calls. The three internal ones are not private for one test each, which
     * no public call can drive deterministically.
     */

    /**
     * The watermarks and the clear lock. `ClearLocalDataDuringWritesTest` reads the tick a response
     * started at, to race it against a clear.
     */
    internal val freshness: CacheFreshness = components.freshness

    /** Whose the caches are: one identity's at a time with the Auth plugin, for good without. */
    private val cacheOwnership: CacheOwnership = components.cacheOwnership

    /**
     * The mutation queue; the overlay and the counts show the current session's writes only.
     * `ClearLocalDataDuringWritesTest` queues writes on it and reads its rejected list around a
     * clear.
     */
    internal val mutationQueue: MutationQueue = components.mutationQueue

    /**
     * The cached queries. `ClearLocalDataDuringWritesTest` caches a response through it during a
     * clear, and `QueryShapeRecordsTest` reads its query-shape records.
     */
    internal val queryCache: QueryCache = components.queryCache

    /** The reads served with the queued mutations laid over them. */
    private val queryOverlay: QueryOverlay = components.queryOverlay

    /**
     * Patches an online write into the cached queries; a query it marks stale fetches again if
     * live.
     */
    private val cachePatcher: CachePatcher = components.cachePatcher

    /** Clears local data, or drops the caches for a new owner, in the one lock order. */
    private val localData: LocalData = components.localData

    /** What [syncStatus] reports, kept current; see [SyncStatusTracker]. */
    private val statusTracker =
        SyncStatusTracker(
            scope = clientScope,
            networkAccess = networkAccess,
            mutationQueue = mutationQueue,
            lastSyncedAtRecord = components.lastSyncedAt,
            engineLogger = engineLogger,
            clock = clock,
            replayIdentityNow = sessionIdentity::replayIdentityNow,
            cacheOwner = { cacheOwnership.record.owner() },
        )

    /**
     * Keeps what a clear or a cache drop deleted out of the database's files: outside the locks and
     * in the background when a read holds the log, and once more at [close].
     */
    private val databaseScrub =
        DatabaseScrub(
            scope = clientScope,
            engineLogger = engineLogger,
            rewrite = scrubDatabase,
            emptyLog = emptyWriteAheadLog,
        )

    /** The Storage file cache every bucket of this client shares. */
    private val fileCache =
        StorageFileCache(
            cachedFileDao = cachedFileDao,
            pinRecords = PinnedFileRecords(components.queryCacheDao),
            ownership = cacheOwnership,
            fetchedAtMillis = rowClock,
            dataDirectoryProvider = baseDirectoryProvider,
            maxSizeBytes = fileCacheMaxBytes,
            engineLogger = engineLogger,
            fileSystem = fileSystem,
            nowMillis = { clock.now().toEpochMilliseconds() },
            markUsed = markFileUsed,
            syncFile = syncFile,
            ioDispatcher = ioDispatcher,
        )

    /** The queued uploads: how a pass sends one, and the copy `cachedFile` answers meanwhile. */
    private val queuedUploads =
        QueuedUploads(
            files = queuedUploadFiles,
            mutationQueue = mutationQueue,
            fileCache = fileCache,
            engineLogger = engineLogger,
            bucketOf = { bucketId ->
                supabaseClient.pluginManager.getPluginOrNull(Storage)?.from(bucketId)
            },
        )

    private val mutationQueueReplayer =
        MutationQueueReplayer(
            mutationQueue,
            freshness,
            supabaseClient,
            engineLogger = engineLogger,
            networkAccess = networkAccess,
            primaryKeys = primaryKeys,
            passEnded = { _, report -> statusTracker.recordPassEnded(report) },
            cachePatcher = cachePatcher,
            conflictPolicy = conflictPolicy,
            conflictPolicyTimeout = conflictPolicyTimeout,
            conflictPolicyDispatcher = conflictPolicyDispatcher,
            awaitSessionSettled = sessionIdentity::awaitSettledReplaySession,
            currentIdentity = sessionIdentity::replayIdentityNow,
            isSessionSettledNow = sessionIdentity::isReplaySessionSettledNow,
            identityOfToken = sessionIdentity.tokenIdentity?.let { source -> source::identityOf },
            uploadSends = queuedUploads,
        )

    /**
     * Runs the replay passes the engine starts on its own, in the client's scope so [close] stops
     * them.
     */
    private val automaticReplay =
        AutomaticReplay(
            scope = clientScope,
            engineLogger = engineLogger,
            clock = clock,
            networkStatus = { networkAccess.status },
        ) {
            mutationQueueReplayer.sync()
        }

    /**
     * SDK-internal recovery accelerator merging auth-session-restored and connectivity-regained
     * edges. Forwarded to every builder to short-circuit a stalled refresh's backoff.
     */
    private val wakeUps: Flow<Unit> =
        refreshWakeUps(
            supabaseClient,
            networkAccess.reconnections,
            responseTrust,
            tokenIdentityStates = sessionIdentity.tokenIdentity?.state,
        )

    /** Follows the watched tables and applies server rows to the cache; see [RealtimeApplier]. */
    private val realtime =
        RealtimeApplier(
            supabaseClient = supabaseClient,
            scope = clientScope,
            watchedTables = watchedTables,
            serverChangeFeed = serverChangeFeed,
            sessionIdentity = sessionIdentity,
            networkStatuses = networkAccess.statusChanges,
            wakeUps = wakeUps,
            operationGate = operationGate,
            primaryKeys = primaryKeys,
            cachePatcher = cachePatcher,
            freshness = freshness,
            engineLogger = engineLogger,
        )

    override val realtimeStatus: StateFlow<RealtimeStatus> = realtime.status

    override val realtimeTableStatus: StateFlow<Map<RealtimeTable, RealtimeStatus>> =
        realtime.tableStatus

    /** Queues and sends the uploads a bucket makes under `MutationStrategy.QueueIfOffline`. */
    private val queuedUploadWriter =
        QueuedUploadWriter(
            uploads = queuedUploads,
            mutationQueue = mutationQueue,
            freshness = freshness,
            headSender = mutationQueueReplayer,
            queueIdentity = sessionIdentity::writeOwner,
            requestSync = automaticReplay::requestPass,
            queueEmptied = statusTracker::recordQueueEmptiedBySending,
            engineLogger = engineLogger,
        )

    /** Acts on the session and the cache's owner, and clears; see [SessionCoordinator]. */
    private val sessionCoordinator =
        SessionCoordinator(
            scope = clientScope,
            session = sessionIdentity,
            autoSync = autoSync,
            operationGate = operationGate,
            mutationQueue = mutationQueue,
            mutationQueueReplayer = mutationQueueReplayer,
            automaticReplay = automaticReplay,
            cacheOwnership = cacheOwnership,
            fileCache = fileCache,
            localData = localData,
            databaseScrub = databaseScrub,
            liveQueries = liveQueries,
            cacheOwnerChanged = statusTracker::recordCacheOwnerChanged,
            engineLogger = engineLogger,
        )

    init {
        if (prepareFileCacheDirectories) {
            fileCache.prepareDirectories()
            queuedUploadFiles.prepareDirectory()
        }
        // Before the app sends anything through this client, so the hooks are on the pipeline once,
        // and not added while requests may be walking it.
        supabaseClient.installDryRunCheck()
        supabaseClient.installReadShapeCheck()
        supabaseClient.installTransportFailureCapture()
        supabaseClient.installDownloadStatusCheck()
        supabaseClient.installCommittedDryRunReport(::reportCommittedDryRunOnce)
        networkAccess.watchIn(
            clientScope,
            onReconnected = sessionCoordinator::replayAsTheNetworkReturns,
        )
        sessionCoordinator.replayWhenTheSessionSettles()
        sessionCoordinator.keepCacheWithTheSession()
        realtime.start()
        // The token's identity from the start: it settles the cache's owner, and starts a pass for
        // what that user has queued
        sessionIdentity.tokenIdentity?.let { source -> clientScope.launch { source.resolve() } }
    }

    override suspend fun disableRealtime(): Unit = operationGate.call {
        realtime.setEnabled(false)
    }

    override suspend fun enableRealtime(): Unit = operationGate.call {
        realtime.setEnabled(true)
    }

    /**
     * Logs, once per client, that a write asked PostgREST to roll it back and was committed: an
     * app's dry run changed data. It names nothing else.
     */
    private fun reportCommittedDryRunOnce() {
        if (!committedDryRunReported.compareAndSet(expectedValue = false, newValue = true)) return
        engineLogger.warning {
            "A dry run was committed: the server did not apply the rollback it asked for " +
                "(Prefer: tx=rollback)"
        }
    }

    /** The identity a write made now is queued for; see [SessionIdentity.writeOwner]. */
    private suspend fun writeOwner(): QueueIdentity? = sessionIdentity.writeOwner()

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
                queryCache = queryCache,
                queryOverlay = queryOverlay,
                mutationQueue = mutationQueue,
                cachePatcher = cachePatcher,
                freshness = freshness,
                primaryKeys = primaryKeys,
                engineLogger = engineLogger,
                requestSession = sessionIdentity::requestSession,
                trustPolicy = responseTrust,
                liveQueries = liveQueries,
                wakeUps = wakeUps,
                queueIdentity = ::writeOwner,
                requestSync = automaticReplay::requestPass,
                headSender = mutationQueueReplayer,
                operationGate = operationGate,
                readShapePostgrest = readShape,
                clock = clock,
                queueEmptied = statusTracker::recordQueueEmptiedBySending,
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

    override val storage: OfflineStorage =
        OfflineStorage(
            bucketOf = ::bucket,
            clearFiles = ::clearFileCache,
            pendingUploadsOf = ::pendingUploads,
            rejectedUploadsOf = ::rejectedUploads,
            rejectedUploadListOf = ::rejectedUploadList,
        )

    /** The current user's queued uploads, as the queue changes; see `OfflineStorage`. */
    private fun pendingUploads(): Flow<List<PendingUpload>> =
        operationGate.callCollecting(
            mutationQueue.shownPendingUploadsFlow().map { rows ->
                rows.mapNotNull { row -> queuedUploads.pendingUploadOf(row) }
            }
        )

    /** The current user's refused uploads, as the rejected list changes; see `OfflineStorage`. */
    private fun rejectedUploads(): Flow<List<RejectedUpload>> =
        operationGate.callCollecting(
            mutationQueue.shownRejectionsFlow().map { entries -> rejectedUploadsOf(entries) }
        )

    /** [rejectedUploads], read once. */
    private suspend fun rejectedUploadList(): List<RejectedUpload> = operationGate.call {
        rejectedUploadsOf(mutationQueue.shownRejections())
    }

    /** The refused uploads among the rejected list's [entries], as the app sees them. */
    private suspend fun rejectedUploadsOf(entries: List<StoredRejection>): List<RejectedUpload> =
        entries
            .filter { entry -> entry.isQueuedUpload }
            .map { entry -> queuedUploads.rejectedUploadOf(entry) }

    /**
     * Empties the Storage file cache alone, under its own lock as [clearLocalData] does: the queue
     * and the query cache are not touched, so neither of their locks is taken, and nothing a live
     * read shows changes.
     */
    private suspend fun clearFileCache(): Unit = operationGate.call { fileCache.clearFiles() }

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
            ioDispatcher = ioDispatcher,
            networkAccess = networkAccess,
            requestSession = sessionIdentity::requestSession,
            queuedUploads = queuedUploadWriter,
        )
    }

    /* ─────────────────────────── The mutation queue ─────────────────────────── */

    override fun pendingMutations(table: String?): Flow<List<PendingMutation>> =
        operationGate.callCollecting(
            mutationQueue.shownPendingMutationsFlow(table).map { rows ->
                rows.mapNotNull { row -> row.toPendingMutation(EngineJson, primaryKeys) }
            }
        )

    override suspend fun pendingMutationCount(table: String?): Int = operationGate.call {
        mutationQueue.shownPendingMutationCount(table)
    }

    override fun pendingMutationCountFlow(table: String?): Flow<Int> =
        operationGate.callCollecting(
            mutationQueue
                .shownPendingMutationsFlow(table)
                .map { rows -> rows.size }
                .distinctUntilChanged()
        )

    /**
     * Waits for a sender that has the mutation on the wire, then discards it. What it held back is
     * sent by a pass asked for here, with `autoSync` off too: it is a write-path pass.
     */
    override suspend fun discardPendingMutation(id: Long): Unit = operationGate.call {
        val identity = writeOwner() ?: return@call
        // Read first: an upload's file goes once its row has
        val upload = mutationQueue.pendingMutation(id)?.takeIf { row -> row.isQueuedUpload }
        if (!mutationQueueReplayer.discardPendingMutationOnceUnsent(id, identity)) return@call
        upload?.let { row -> queuedUploads.release(row) }
        sessionCoordinator.requestPassUnlessQueueEmpty(identity, onEdge = false)
    }

    override fun rejectedMutations(): Flow<List<RejectedMutation>> =
        operationGate.callCollecting(
            mutationQueue.shownRejectionsFlow().map { entries -> rejectedMutationsOf(entries) }
        )

    override suspend fun rejectedMutationList(): List<RejectedMutation> = operationGate.call {
        rejectedMutationsOf(mutationQueue.shownRejections())
    }

    /** The refused row writes among the rejected list's [entries], as the app sees them. */
    private fun rejectedMutationsOf(entries: List<StoredRejection>): List<RejectedMutation> =
        entries
            .filterNot { entry -> entry.isQueuedUpload }
            .map { entry -> entry.toRejectedMutation(EngineJson, primaryKeys).shown() }

    override suspend fun dismissRejectedMutation(id: Long): Unit = operationGate.call {
        val identity = writeOwner() ?: return@call
        // Read first: a refused upload's file goes once its entry has
        val upload =
            mutationQueue.shownRejection(id, identity.storedValue)?.takeIf { entry ->
                entry.isQueuedUpload
            }
        if (!mutationQueue.dismissRejectedMutationOf(id, identity.storedValue)) return@call
        upload?.let { entry -> queuedUploads.release(entry) }
    }

    override suspend fun retryRejectedMutation(id: Long): Unit = operationGate.call {
        val identity = writeOwner() ?: return@call
        val entry = mutationQueue.shownRejection(id, identity.storedValue) ?: return@call
        require(entry.bodyOmittedChars == null) {
            "This rejected mutation cannot be retried: its body was too large to keep " +
                "(RejectedMutation.isRetryable is false)"
        }
        require(!entry.isQueuedUpload || queuedUploads.isRetryable(entry)) {
            "This rejected upload cannot be retried: its file is no longer on this device " +
                "(RejectedUpload.isRetryable is false)"
        }
        mutationQueue.retryRejectedMutation(id, identity.storedValue) ?: return@call
        automaticReplay.requestPass()
    }

    override fun conflictedMutations(): Flow<List<ConflictedMutation>> =
        operationGate.callCollecting(
            mutationQueue.shownConflictsFlow().map { entries -> conflictedMutationsOf(entries) }
        )

    override suspend fun conflictedMutationList(): List<ConflictedMutation> = operationGate.call {
        conflictedMutationsOf(mutationQueue.shownConflicts())
    }

    /** The conflicted list's [entries] as the app sees them. */
    private fun conflictedMutationsOf(entries: List<StoredConflict>): List<ConflictedMutation> =
        entries.map { entry ->
            entry.toConflictedMutation(EngineJson, primaryKeys)
        }

    override suspend fun resolveConflictedMutation(id: Long, resolution: ConflictResolution): Unit =
        operationGate.call {
            require(resolution != ConflictResolution.Hold) {
                "A conflicted mutation cannot be resolved with Hold: it is held already"
            }
            val identity = writeOwner() ?: return@call
            val entry = mutationQueue.shownConflict(id, identity.storedValue) ?: return@call
            requireResolvable(entry, resolution)
            val startedAtTick = freshness.tickNow()
            val requeue =
                mutationQueue.requeueConflict(
                    id,
                    identity.storedValue,
                    conflictDecisionOf(resolution),
                )
            require(requeue != ConflictRequeue.Refused) {
                "This conflicted mutation cannot be settled that way"
            }
            if (requeue is ConflictRequeue.Requeued) automaticReplay.requestPass()
            if (requeue == ConflictRequeue.Dropped)
                showServerSideOf(entry, identity.storedValue, startedAtTick)
        }

    /**
     * Shows the server's side of the conflicted [entry] the app dropped with
     * [ConflictResolution.KeepServer] in the cache, as the resolver's own `KeepServer` does. A row
     * gone from the server is dropped from the cached queries. A row the replay read is laid in,
     * without its version, only while the cached row still carries the entry's base version: the
     * row as the device saw it before the write, which an entry a build before this one parked left
     * there; a cached row at any other version was fetched, or laid in, since the entry was made,
     * and is left as it is. Either way the queries holding the row are then marked stale, so a live
     * read fetches it again: the row may have moved while the entry waited. Runs after the queue
     * lock is released, since the patch takes the table's lock, which precedes the queue's; a
     * failure is logged, since the entry is gone either way. An entry whose filter pins no row (a
     * guarded replay always pins one) patches nothing.
     *
     * @param identity the stored value of the identity resolving, whose cache the row is read from.
     * @param startedAtTick the tick the resolution started at; a clear since then voids the patch.
     */
    private suspend fun showServerSideOf(
        entry: StoredConflict,
        identity: String,
        startedAtTick: Long,
    ) {
        val table = entry.table
        val keyColumns = primaryKeys.of(table)
        val filter = decodeFilterOrNull(entry.filterJson, EngineJson) ?: return
        val pinned = pinnedRowKeyOf(filter, keyColumns) ?: return
        val keyFilter =
            FilterDescriptor(
                conditions =
                    pinned.map { (column, value) ->
                        FilterDescriptor.Condition(column, "eq", value.jsonPrimitive.content)
                    }
            )
        val versionColumn = mutationQueue.versionColumns.of(table)
        val server = entry.serverRowJson?.let { text -> jsonObjectOrNull(text, EngineJson) }
        try {
            if (entry.serverVersion == null && entry.message == GONE_REASON) {
                cachePatcher.patchCachedQueries(
                    table = table,
                    operation = MutationOperation.DELETE,
                    bodyJson = "{}",
                    filterJson = encodeFilter(keyFilter, EngineJson),
                    startedAtTick = startedAtTick,
                )
            } else if (
                server != null &&
                    versionColumn != null &&
                    entry.baseVersion != null &&
                    cachedVersionOf(table, pinned, keyColumns, versionColumn, identity) ==
                        entry.baseVersion
            ) {
                cachePatcher.patchReturnedRows(
                    table,
                    JsonObject(emptyMap()),
                    JsonArray(listOf(JsonObject(server - versionColumn))).toString(),
                    startedAtTick,
                )
            }
            // As a delete would: the queries that still hold the row, which a dropped row left
            cachePatcher.markQueriesAWriteMayReach(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                narrowing = keyFilter,
                startedAtTick = startedAtTick,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Patching the cache after a conflicted write to $table was dropped failed: " +
                    failure::class.simpleName
            }
        }
    }

    /**
     * The version the newest cached row of [table] with the key [pinned] carries, in [identity]'s
     * cache, as text; `null` when no cached list holds the row with [versionColumn].
     */
    private suspend fun cachedVersionOf(
        table: String,
        pinned: JsonObject,
        keyColumns: List<String>,
        versionColumn: String,
        identity: String,
    ): String? {
        val key = keyOf(pinned, keyColumns) ?: return null
        val row = queryOverlay.cachedRowHolding(table, key, versionColumn, identity) ?: return null
        return versionTextOf(row[versionColumn])
    }

    override suspend fun dismissConflictedMutation(id: Long): Unit = operationGate.call {
        val identity = writeOwner() ?: return@call
        mutationQueue.dismissConflictOf(id, identity.storedValue)
    }

    /**
     * Refuses a [resolution] the conflicted [entry] cannot take, as [resolveConflictedMutation]
     * says.
     */
    private fun requireResolvable(entry: StoredConflict, resolution: ConflictResolution) {
        when (resolution) {
            ConflictResolution.KeepLocal -> {
                require(entry.serverVersion != null) {
                    "This conflicted mutation's row is gone from the server: there is nothing to " +
                        "resend it against"
                }
                require(entry.bodyOmittedChars == null) {
                    "This conflicted mutation cannot be resent: its body was too large to keep"
                }
            }
            is ConflictResolution.Apply ->
                require(
                    entry.operation == MutationOperation.UPDATE.storedName &&
                        entry.serverVersion != null
                ) {
                    "Apply settles only an update of a row the server still held"
                }
            ConflictResolution.KeepServer,
            ConflictResolution.Hold -> Unit
        }
    }

    override suspend fun syncPendingMutations(table: String?): SyncReport =
        operationGate.cancellableCall(clientScope) {
            ensurePostgrestInstalled()
            // The app's own attempt: the library may try once more by itself while offline
            automaticReplay.allowAnOfflineAttempt()
            val report = mutationQueueReplayer.syncWaitingOutOtherSenders(table)
            // A pass that could not tell whose queue it was counted every user's: count the shown
            val remaining =
                when (report.stop) {
                    SyncStop.SessionNotReady,
                    SyncStop.IdentityUnknown,
                    SyncStop.NetworkDisabled -> mutationQueue.shownPendingMutationCount(table)
                    else -> report.remainingCount
                }
            report.copy(remainingCount = remaining).shown()
        }

    /* ─────────────────────────── Sync status and the network ─────────────────────────── */

    /** The same flow whether or not the client is open; after [close] it keeps its last value. */
    override val syncStatus: StateFlow<SyncStatus> = statusTracker.status

    override suspend fun disableNetwork(): Unit = operationGate.call {
        networkAccess.disable()
        statusTracker.publishNetwork()
    }

    override suspend fun enableNetwork(): Unit = operationGate.call {
        val reconnected = networkAccess.enable()
        statusTracker.publishNetwork()
        if (reconnected) sessionCoordinator.replayAsTheNetworkReturns()
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
    private suspend fun EngineSyncReport.shown(): SyncReport =
        SyncReport(
            syncedCount = syncedCount,
            rejected = rejected.map { rejection -> rejection.shown() },
            remainingCount = remainingCount,
            alreadyOnServerCount = alreadyOnServerCount,
            heldBehindOtherTablesCount =
                if (stop is SyncStop.HeldBehindOtherTables) remainingCount else 0,
            conflictsResolvedCount = conflictsResolvedCount,
            conflictsKeptServerCount = conflictsKeptServerCount,
            conflicted =
                conflicted.map { entry -> entry.toConflictedMutation(EngineJson, primaryKeys) },
            stopReason = stopReasonOf(stop),
            rejectedUploads =
                rejectedUploads.map { entry -> queuedUploads.rejectedUploadOf(entry) },
        )

    override suspend fun applyServerRecord(
        table: String,
        record: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = operationGate.call { realtime.applyServerRecord(table, record, primaryKeys, schema) }

    override suspend fun applyServerDelete(
        table: String,
        oldRecord: JsonObject,
        primaryKeys: List<String>?,
        schema: String?,
    ): Unit = operationGate.call {
        realtime.applyServerDelete(table, oldRecord, primaryKeys, schema)
    }

    /**
     * Clears local data for a sign-out, in the lock order [SessionCoordinator] states; the writes
     * an earlier failure held back go with it, so the status's last error does too, and so does the
     * record of when the queue was last emptied.
     */
    override suspend fun clearLocalData(): Unit = operationGate.call {
        sessionCoordinator.clearLocalData()
        // The writes an earlier failure held back are gone with it, and the last-synced record
        statusTracker.recordLocalDataCleared()
    }

    /**
     * The first call shuts the gate, which ends every collection, cancels the client's work and the
     * calls it may stop, and publishes the status's last value. Every call then waits, up to
     * [closeWait], for the release.
     */
    override suspend fun close() {
        if (operationGate.close()) clientScope.coroutineContext.job.cancel()
        statusTracker.publishFinal()
        realtime.stop()
        readShapePostgrest.value = null
        // On a real clock whatever calls it: under a test scheduler the caller's clock skips ahead
        val releasedInTime =
            withContext(NonCancellable + closeWaitDispatcher) {
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
     * Waits until the client's work and every call have ended, then finishes a clear's rewrite and
     * releases the database, once. A failure to close it is logged, never thrown: the claim is
     * released either way, and [close] cannot fail its caller.
     */
    private suspend fun releaseOnceIdle() {
        clientScope.coroutineContext.job.join()
        operationGate.awaitNoCallsInFlight()
        if (!releasing.compareAndSet(expect = false, update = true)) return released.await()
        try {
            // Whatever cancels this wait, the database is still closed after it
            withContext(NonCancellable) { databaseScrub.finishBeforeClose() }
            onClose()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.error {
                "Closing the offline database failed (${failure::class.simpleName}); " +
                    "it was released"
            }
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
    internal suspend fun persistedRejectedMutations(): List<EngineRejectedMutation> =
        operationGate.call {
            mutationQueue.rejectedList.rejections().map { entry ->
                entry.toRejectedMutation(EngineJson, primaryKeys)
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

    /**
     * Deletes, once at open, the queued files no queued or rejected upload names: what a crash
     * between a file and its row, or the rejected list's bound, left. A failure is logged; the next
     * open tries again.
     */
    private fun sweepQueuedUploadFiles() {
        clientScope.launch {
            try {
                operationGate.call { queuedUploads.sweepUnnamedFiles() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                if (operationGate.isClosed) return@launch
                engineLogger.warning {
                    "Could not delete the queued upload files no upload names " +
                        "(${failure::class.simpleName}); the next open tries again"
                }
            }
        }
    }

    // Last, once every part the work below reads is built
    init {
        statusTracker.start(mutationQueueReplayer.passRunning)
        sessionCoordinator.replayWhatAnEarlierRunLeft()
        sweepQueuedUploadFiles()
    }
}

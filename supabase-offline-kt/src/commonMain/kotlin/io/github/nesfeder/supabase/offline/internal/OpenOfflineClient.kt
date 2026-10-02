// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.Offline.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.Offline.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.ResponseTrust
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.scrubDeletedRows
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.logging.engineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DEFAULT_PRIMARY_KEY
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.internal.storage.markFileUsed
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Claims the database and data directory, opens the database and builds the client over it. Its
 * [OfflineClient.close] closes the database and releases the claim. A configured [databasePath]
 * brings along a database kept at [defaultDatabasePath] (see [moveDefaultDatabaseIfUnclaimed]).
 *
 * @param platformNetworkAvailability the platform's connectivity monitor, used unless the
 *   configuration names the app's own signal.
 * @throws IllegalStateException when another open client already uses the database or the
 *   directory.
 */
internal fun openOfflineClient(
    supabase: SupabaseClient,
    config: Offline.Config,
    databasePath: Path,
    dataDirectory: Path,
    openDatabase: (databasePath: Path) -> OfflineDatabase,
    defaultDatabasePath: Path? = null,
    platformNetworkAvailability: Flow<Boolean>? = null,
): OfflineClient {
    val engineLogger = supabase.engineLogger(config.logLevel)
    val openedPath =
        if (defaultDatabasePath == null) databasePath
        else moveDefaultDatabaseIfUnclaimed(databasePath, defaultDatabasePath, engineLogger)
    val registration = OpenDatabaseRegistry.register(openedPath, dataDirectory)
    try {
        openedPath.parent?.let { directory -> FileSystem.SYSTEM.createDirectories(directory) }
        val database = openDatabase(openedPath)
        return config.buildOfflineClient(
            supabase = supabase,
            openStore = { limits ->
                OfflineStore(
                    queryCacheDao = database.queryCacheDao(),
                    cachedFileDao = database.cachedFileDao(),
                    mutationDao = database.mutationDao(),
                    queryCacheLimits = limits,
                )
            },
            dataDirectory = dataDirectory,
            prepareFileCacheDirectories = true,
            engineLogger = engineLogger,
            scrubDatabase = {
                if (!database.scrubDeletedRows()) {
                    engineLogger.warning {
                        "Clearing local data could not empty the database's write-ahead log; the next checkpoint will"
                    }
                }
            },
            onClose = {
                try {
                    database.close()
                } finally {
                    registration.release()
                }
            },
            onStillClosing = registration::markStillClosing,
            platformNetworkAvailability = platformNetworkAvailability,
        )
    } catch (failure: Throwable) {
        registration.release()
        throw failure
    }
}

/**
 * The client this configuration describes, over the store [openStore] makes: every setting of
 * [Offline.Config] reaches the engine here and nowhere else, whether the store is the platform's
 * database or a test's in-memory one.
 *
 * @param openStore makes the store, given the query-cache limits [Offline.Config.cacheSizeBytes]
 *   asks for.
 * @param dataDirectory where the Storage file cache keeps its files.
 * @param prepareFileCacheDirectories whether to create the file cache's directories now and keep
 *   them out of backups: the platform's database does; an in-memory store, whose data directory is
 *   not its own to write into, does not.
 * @param engineLogger where the engine logs; by default supabase-kt's logging at
 *   [Offline.Config.logLevel].
 * @param coordinatorScope the parent of the client's own scope; `null` for the engine's own.
 * @param scrubDatabase rewrites the database without the rows a clear deleted.
 * @param onClose releases what the opener opened, when the client closes.
 * @param onStillClosing told when a close returns before the release.
 * @param fileSystem where the Storage file cache keeps its files: the device's disk unless a test
 *   keeps them in memory.
 * @param markFileUsed records a use time on one of [fileSystem]'s files.
 * @param clock the time the client sees, beside the store's own.
 * @param platformNetworkAvailability the platform's connectivity monitor, used unless
 *   [Offline.Config.networkAvailability] is set; `null` for none.
 */
internal fun Offline.Config.buildOfflineClient(
    supabase: SupabaseClient,
    openStore: (QueryCacheLimits) -> OfflineStore,
    dataDirectory: Path,
    prepareFileCacheDirectories: Boolean,
    engineLogger: EngineLogger = supabase.engineLogger(logLevel),
    coordinatorScope: CoroutineScope? = null,
    scrubDatabase: suspend () -> Unit = {},
    onClose: () -> Unit = {},
    onStillClosing: (callsInFlight: () -> Int) -> Unit = {},
    fileSystem: FileSystem = FileSystem.SYSTEM,
    markFileUsed: (Path, Long) -> Unit = ::markFileUsed,
    clock: Clock = Clock.System,
    platformNetworkAvailability: Flow<Boolean>? = null,
): OfflineClient =
    OfflineClient(
        supabaseClient = supabase,
        store = openStore(toQueryCacheLimits()),
        baseDirectoryProvider = { dataDirectory },
        engineLogger = engineLogger,
        networkAvailability = networkAvailability ?: platformNetworkAvailability,
        coordinatorScope = coordinatorScope ?: newEngineScope(engineLogger),
        fileCacheMaxBytes = fileCacheBoundOrNull(),
        prepareFileCacheDirectories = prepareFileCacheDirectories,
        scrubDatabase = scrubDatabase,
        onClose = onClose,
        onStillClosing = onStillClosing,
        primaryKeys = primaryKeysByTable(),
        responseTrust = responseTrustPolicy(),
        config = this,
        fileSystem = fileSystem,
        markFileUsed = markFileUsed,
        clock = clock,
    )

/** The object-cache eviction settings [Offline.Config.cacheSizeBytes] asks for. */
internal fun Offline.Config.toQueryCacheLimits(): QueryCacheLimits =
    QueryCacheLimits(
        // Unlimited turns size-based eviction off; any other bound is at least the minimum.
        maxPayloadBytes =
            if (cacheSizeBytes == CACHE_SIZE_UNLIMITED) null
            else maxOf(cacheSizeBytes, MIN_CACHE_SIZE_BYTES),
        // A small batch of rows per step of a pass; what each batch frees depends on the payloads.
        evictionBatchSize = 10,
        // A pass drains 50 MB below the bound, or to the bound when it is 50 MB or less, so the
        // next writes do not start another at once.
        evictionHeadroomBytes = 50L * 1024L * 1024L,
    )

/**
 * The Storage file cache bound [Offline.Config.fileCacheSizeBytes] asks for, or `null` for none.
 */
internal fun Offline.Config.fileCacheBoundOrNull(): Long? =
    if (fileCacheSizeBytes == CACHE_SIZE_UNLIMITED) null else fileCacheSizeBytes.coerceAtLeast(0L)

/** Each table's key as [Offline.Config.primaryKeys] gives it; `id` for any other table. */
internal fun Offline.Config.primaryKeysByTable(): PrimaryKeys {
    val keys = primaryKeys.toMap()
    return if (keys.isEmpty()) DefaultPrimaryKeys
    else PrimaryKeys { table -> keys[table] ?: DEFAULT_PRIMARY_KEY }
}

/** The engine's rule for [Offline.Config.responseTrust]. */
internal fun Offline.Config.responseTrustPolicy(): ResponseTrustPolicy =
    when (responseTrust) {
        ResponseTrust.SettledSession -> ResponseTrustPolicy.SETTLED_SESSION
        ResponseTrust.AuthenticatedSessionOnly -> ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY
    }

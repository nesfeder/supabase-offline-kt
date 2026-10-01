// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.Offline.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.Offline.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.OfflineExperimental
import io.github.nesfeder.supabase.offline.ResponseTrust
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.scrubDeletedRows
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DEFAULT_PRIMARY_KEY
import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import kotlinx.coroutines.flow.Flow
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Claims the database and data directory, opens the database and builds the client over it. Its
 * [OfflineClient.close] closes the database and releases the claim. A configured [databasePath]
 * brings along a database kept at [defaultDatabasePath] (see [moveDefaultDatabaseIfUnclaimed]).
 *
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
): OfflineClient {
    val engineLogger = EngineLogger(config.logger, config.minLogLevel)
    val openedPath =
        if (defaultDatabasePath == null) databasePath
        else moveDefaultDatabaseIfUnclaimed(databasePath, defaultDatabasePath, engineLogger)
    val registration = OpenDatabaseRegistry.register(openedPath, dataDirectory)
    try {
        openedPath.parent?.let { directory -> FileSystem.SYSTEM.createDirectories(directory) }
        val database = openDatabase(openedPath)
        val store =
            OfflineStore(
                queryCacheDao = database.queryCacheDao(),
                cachedFileDao = database.cachedFileDao(),
                mutationDao = database.mutationDao(),
                queryCacheLimits = config.toQueryCacheLimits(),
            )
        return OfflineClient(
            supabaseClient = supabase,
            store = store,
            baseDirectoryProvider = { dataDirectory },
            engineLogger = engineLogger,
            networkAvailability = config.networkAvailabilityHint(),
            fileCacheMaxBytes = config.fileCacheBoundOrNull(),
            prepareFileCacheDirectories = true,
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
            primaryKeys = config.primaryKeysByTable(),
            responseTrust = config.responseTrustPolicy(),
            config = config,
        )
    } catch (failure: Throwable) {
        registration.release()
        throw failure
    }
}

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

/** [Offline.Config.networkAvailability]: what the engine takes as its connectivity hint. */
@OptIn(OfflineExperimental::class)
internal fun Offline.Config.networkAvailabilityHint(): Flow<Boolean>? = networkAvailability

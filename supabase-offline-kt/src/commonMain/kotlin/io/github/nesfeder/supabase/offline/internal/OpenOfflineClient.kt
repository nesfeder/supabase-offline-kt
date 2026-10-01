// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.scrubDeletedRows
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Claims the database and data directory, opens the database and builds the client over it. Its
 * [SupabaseOfflineClient.close] closes the database and releases the claim. A configured
 * [databasePath] brings along a database kept at [defaultDatabasePath] (see
 * [moveDefaultDatabaseIfUnclaimed]).
 *
 * @throws IllegalStateException when another open client already uses the database or the
 *   directory.
 */
internal fun openOfflineClient(
    supabase: SupabaseClient,
    config: SupabaseOfflineConfig,
    databasePath: Path,
    dataDirectory: Path,
    openDatabase: (databasePath: Path) -> OfflineDatabase,
    defaultDatabasePath: Path? = null,
): SupabaseOfflineClient {
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
        return SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { dataDirectory },
            engineLogger = engineLogger,
            networkAvailability = config.networkAvailability,
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
        )
    } catch (failure: Throwable) {
        registration.release()
        throw failure
    }
}

/** The object-cache eviction settings [SupabaseOfflineConfig.cacheSizeBytes] asks for. */
internal fun SupabaseOfflineConfig.toQueryCacheLimits(): QueryCacheLimits =
    QueryCacheLimits(
        // Unlimited turns size-based eviction off; any other bound is at least the minimum.
        maxPayloadBytes =
            if (cacheSizeBytes == CACHE_SIZE_UNLIMITED) null
            else maxOf(cacheSizeBytes, MIN_CACHE_SIZE_BYTES),
        // A small batch of rows per step of a pass; what each batch frees depends on the payloads.
        evictionBatchSize = 10,
        // A pass drains 50 MB below the bound, or to the bound when it is 50 MB or less, so the
        // next writes do not start another at once.
        drainHeadroomBytes = 50L * 1024L * 1024L,
    )

/**
 * The Storage file cache bound [SupabaseOfflineConfig.fileCacheSizeBytes] asks for, or `null` for
 * none.
 */
internal fun SupabaseOfflineConfig.fileCacheBoundOrNull(): Long? =
    if (fileCacheSizeBytes == CACHE_SIZE_UNLIMITED) null else fileCacheSizeBytes.coerceAtLeast(0L)

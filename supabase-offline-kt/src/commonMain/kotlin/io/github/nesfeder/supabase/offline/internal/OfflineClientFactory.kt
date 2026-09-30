// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.CACHE_SIZE_UNLIMITED
import io.github.nesfeder.supabase.offline.SupabaseOfflineConfig.Companion.MIN_CACHE_SIZE_BYTES
import io.github.nesfeder.supabase.offline.internal.db.FixedDbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineDatabase
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.resolvedOrNormalized
import io.github.nesfeder.supabase.offline.internal.db.scrubDeletedRows
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Claims the database and data directory, opens the database and builds the client over it. Its
 * [SupabaseOfflineClient.close] closes the database and releases the claim. A configured
 * [databasePath] brings along a database kept at [defaultDatabasePath] (see
 * [adoptDefaultDatabase]).
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
    val sdkLogger = SdkLogger(config.logger, config.minLogLevel)
    val openedPath =
        if (defaultDatabasePath == null) databasePath
        else adoptDefaultDatabase(databasePath, defaultDatabasePath, sdkLogger)
    val registration = OpenDatabaseRegistry.register(openedPath, dataDirectory)
    try {
        openedPath.parent?.let { directory -> FileSystem.SYSTEM.createDirectories(directory) }
        val database = openDatabase(openedPath)
        val store =
            OfflineStore(
                objectDao = database.objectDao(),
                storageDao = database.storageDao(),
                mutationDao = database.mutationDao(),
                dbPathProvider = FixedDbPathProvider(openedPath),
                settings = config.toCacheSettings(),
            )
        return SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { dataDirectory },
            sdkLogger = sdkLogger,
            networkAvailability = config.networkAvailability,
            fileCacheMaxBytes = config.fileCacheBoundOrNull(),
            prepareFileCacheDirectories = true,
            scrubDatabase = {
                if (!database.scrubDeletedRows()) {
                    sdkLogger.warning {
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

/** What sits beside a database file: its write-ahead log, its shared-memory index, its journal. */
private val DATABASE_SIDE_FILE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

/**
 * The database a client with a configured data directory opens. [default] is moved to [configured],
 * with its side files, when only it exists and no client here holds it. Otherwise it is the one
 * that can be opened without abandoning a queue, with a warning whenever the other is left behind.
 */
internal fun adoptDefaultDatabase(
    configured: Path,
    default: Path,
    sdkLogger: SdkLogger,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): Path {
    if (configured.resolvedOrNormalized(fileSystem) == default.resolvedOrNormalized(fileSystem))
        return configured
    val defaultExists = fileSystem.exists(default)
    if (fileSystem.exists(configured)) {
        if (defaultExists) {
            sdkLogger.warning {
                "An offline database is also at the default location; it was left there, with any writes it still queues"
            }
        }
        return configured
    }
    if (!defaultExists) return configured
    // Hold the default database while it moves, so no client in this process opens it meanwhile.
    val claim =
        try {
            OpenDatabaseRegistry.register(default)
        } catch (_: IllegalStateException) {
            sdkLogger.warning {
                "The offline database at the default location is open in another client; keeping it in use"
            }
            return default
        }
    try {
        configured.parent?.let { directory -> fileSystem.createDirectories(directory) }
        val moved = mutableListOf<Pair<Path, Path>>()
        try {
            // The database file moves last: until it has, the configured directory holds no
            // database, and a failed move is tried again on the next start.
            DATABASE_SIDE_FILE_SUFFIXES.forEach { suffix ->
                val from = "$default$suffix".toPath()
                if (fileSystem.exists(from)) {
                    val to = "$configured$suffix".toPath()
                    fileSystem.atomicMove(from, to)
                    moved += from to to
                }
            }
            fileSystem.atomicMove(default, configured)
        } catch (failure: IOException) {
            moved.asReversed().forEach { (from, to) ->
                try {
                    fileSystem.atomicMove(to, from)
                } catch (_: IOException) {
                    // Nothing more can be done here; the warning below says where things stand.
                }
            }
            sdkLogger.warning {
                "Could not move the offline database into the configured directory (${failure::class.simpleName}); " +
                    "keeping it at the default location"
            }
            return default
        }
        sdkLogger.info {
            "Moved the offline database from the default location into the configured directory"
        }
        return configured
    } catch (failure: IOException) {
        sdkLogger.warning {
            "Could not prepare the configured directory for the offline database (${failure::class.simpleName}); " +
                "keeping it at the default location"
        }
        return default
    } finally {
        claim.release()
    }
}

/** The object-cache eviction settings [SupabaseOfflineConfig.cacheSizeBytes] asks for. */
internal fun SupabaseOfflineConfig.toCacheSettings(): OfflineCacheSettings =
    OfflineCacheSettings(
        // Unlimited turns size-based eviction off; any other bound is at least the minimum.
        maxDbSizeBytes =
            if (cacheSizeBytes == CACHE_SIZE_UNLIMITED) null
            else maxOf(cacheSizeBytes, MIN_CACHE_SIZE_BYTES),
        // A small batch of rows per pass; what each batch frees depends on the payloads.
        evictionBatchSize = 10,
        // A pass drains 50 MB below the bound, so the next writes do not start another at once.
        minRetainedBytes = 50L * 1024L * 1024L,
    )

/**
 * The Storage file cache bound [SupabaseOfflineConfig.fileCacheSizeBytes] asks for, or `null` for
 * none.
 */
internal fun SupabaseOfflineConfig.fileCacheBoundOrNull(): Long? =
    if (fileCacheSizeBytes == CACHE_SIZE_UNLIMITED) null else fileCacheSizeBytes.coerceAtLeast(0L)

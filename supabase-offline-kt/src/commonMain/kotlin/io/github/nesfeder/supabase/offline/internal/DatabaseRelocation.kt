// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.resolvedOrNormalized
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.storage.HASHED_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.storage.STORAGE_DIRECTORY_NAME
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/** What sits beside a database file: its write-ahead log, its shared-memory index, its journal. */
private val DATABASE_SIDE_FILE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

/**
 * Where a client keeps its data: the database file, and the directory its file cache lives under.
 *
 * @property databasePath the database file.
 * @property dataDirectory the file cache's directory.
 */
internal data class DataLocation(val databasePath: Path, val dataDirectory: Path)

/**
 * The database a client with a configured data directory opens. [default]'s is moved to
 * [configured]'s, with its side files and then its file cache, when only it exists and no client
 * here holds it. Otherwise it is the one that can be opened without abandoning a queue, with a
 * warning whenever the other is left behind.
 */
internal fun moveDefaultDatabaseIfUnclaimed(
    configured: DataLocation,
    default: DataLocation,
    engineLogger: EngineLogger,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): Path {
    val configuredDatabase = configured.databasePath
    val defaultDatabase = default.databasePath
    if (
        configuredDatabase.resolvedOrNormalized(fileSystem) ==
            defaultDatabase.resolvedOrNormalized(fileSystem)
    )
        return configuredDatabase
    val defaultExists = fileSystem.exists(defaultDatabase)
    if (fileSystem.exists(configuredDatabase)) {
        if (defaultExists) {
            engineLogger.warning {
                "An offline database is also at the default location; it was left there, with any writes it still queues"
            }
        }
        return configuredDatabase
    }
    if (!defaultExists) return configuredDatabase
    // Hold the default database and its files while they move, so no client here opens them.
    val claim =
        try {
            OpenDatabaseRegistry.register(defaultDatabase, default.dataDirectory)
        } catch (_: IllegalStateException) {
            engineLogger.warning {
                "The offline database at the default location is open in another client; keeping it in use"
            }
            return defaultDatabase
        }
    try {
        configuredDatabase.parent?.let { directory -> fileSystem.createDirectories(directory) }
        try {
            moveDatabaseFiles(
                from = defaultDatabase,
                to = configuredDatabase,
                fileSystem = fileSystem,
            )
        } catch (failure: IOException) {
            engineLogger.warning {
                "Could not move the offline database into the configured directory (${failure::class.simpleName}); " +
                    "keeping it at the default location"
            }
            return defaultDatabase
        }
        moveFileCacheDirectories(
            default.dataDirectory,
            configured.dataDirectory,
            engineLogger,
            fileSystem,
        )
        engineLogger.info {
            "Moved the offline database from the default location into the configured directory"
        }
        return configuredDatabase
    } catch (failure: IOException) {
        engineLogger.warning {
            "Could not prepare the configured directory for the offline database (${failure::class.simpleName}); " +
                "keeping it at the default location"
        }
        return defaultDatabase
    } finally {
        claim.release()
    }
}

/**
 * Moves the file cache's two directories from under [from] to under [to], each as
 * [moveFileCacheDirectory] does; none whose tree overlaps its target's, which moving or deleting it
 * would reach.
 */
private fun moveFileCacheDirectories(
    from: Path,
    to: Path,
    engineLogger: EngineLogger,
    fileSystem: FileSystem,
) {
    listOf(STORAGE_DIRECTORY_NAME, HASHED_DIRECTORY_NAME).forEach { name ->
        val source = (from / name).resolvedOrNormalized(fileSystem)
        val target = (to / name).resolvedOrNormalized(fileSystem)
        if (source.isWithin(target) || target.isWithin(source)) return@forEach
        try {
            fileSystem.createDirectories(to)
        } catch (_: IOException) {
            // The move fails then, and the files are deleted instead.
        }
        moveFileCacheDirectory(source, target, engineLogger, fileSystem)
    }
}

/** Whether this path is [other] or lies under it. */
private fun Path.isWithin(other: Path): Boolean =
    root == other.root && segments.take(other.segments.size) == other.segments

/**
 * The default location a client opens: [current], once the data an earlier default kept at
 * [earlier] has moved there, the database with its unsent writes and the file cache alike. It stays
 * [earlier] when that database is open in another client or cannot be moved; nothing moves when
 * [current] already holds a database, which is logged.
 */
internal fun moveEarlierDefaultIfUnclaimed(
    current: DataLocation,
    earlier: DataLocation,
    engineLogger: EngineLogger,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): DataLocation {
    val sameDatabase =
        current.databasePath.resolvedOrNormalized(fileSystem) ==
            earlier.databasePath.resolvedOrNormalized(fileSystem)
    if (sameDatabase || !fileSystem.exists(earlier.databasePath)) return current
    if (fileSystem.exists(current.databasePath)) {
        engineLogger.warning {
            "An offline database is also at an earlier default location; it was left there, with any writes it still queues"
        }
        return current
    }
    // Hold both locations while the data moves, so no client in this process opens either
    // meanwhile.
    val earlierClaim =
        try {
            OpenDatabaseRegistry.register(earlier.databasePath, earlier.dataDirectory)
        } catch (_: IllegalStateException) {
            engineLogger.warning {
                "The offline database at an earlier default location is open in another client; keeping it in use"
            }
            return earlier
        }
    try {
        val currentClaim =
            try {
                OpenDatabaseRegistry.register(current.databasePath, current.dataDirectory)
            } catch (_: IllegalStateException) {
                // Another client holds the current location: opening it fails with that error.
                return current
            }
        try {
            try {
                current.databasePath.parent?.let { directory ->
                    fileSystem.createDirectories(directory)
                }
                moveDatabaseFiles(
                    from = earlier.databasePath,
                    to = current.databasePath,
                    fileSystem = fileSystem,
                )
            } catch (failure: IOException) {
                engineLogger.warning {
                    "Could not move the offline database from an earlier default location (${failure::class.simpleName}); " +
                        "keeping it there"
                }
                return earlier
            }
            moveFileCacheDirectory(
                from = earlier.dataDirectory,
                to = current.dataDirectory,
                engineLogger = engineLogger,
                fileSystem = fileSystem,
            )
        } finally {
            currentClaim.release()
        }
        engineLogger.info { "Moved the offline database from an earlier default location" }
        return current
    } finally {
        earlierClaim.release()
    }
}

/**
 * Moves the file cache under [from] to [to], or deletes it when it cannot. Its files can be
 * downloaded again, and left behind they would outlive a clear. Called once the database has moved.
 */
private fun moveFileCacheDirectory(
    from: Path,
    to: Path,
    engineLogger: EngineLogger,
    fileSystem: FileSystem,
) {
    if (!fileSystem.exists(from)) return
    try {
        if (!fileSystem.exists(to)) {
            fileSystem.atomicMove(from, to)
            return
        }
    } catch (_: IOException) {
        // Deleted below instead.
    }
    try {
        fileSystem.deleteRecursively(from)
    } catch (failure: IOException) {
        engineLogger.warning {
            "Could not remove the file cache from its earlier location (${failure::class.simpleName})"
        }
    }
}

/**
 * Moves the database at [from] to [to] with its side files, the database file last. Until it has
 * moved, [to] holds no database, so a failed move is tried again on the next start.
 *
 * @throws IOException when a file cannot be moved, after moving back the side files already moved.
 */
private fun moveDatabaseFiles(from: Path, to: Path, fileSystem: FileSystem) {
    val moved = mutableListOf<Pair<Path, Path>>()
    try {
        DATABASE_SIDE_FILE_SUFFIXES.forEach { suffix ->
            val sideFile = "$from$suffix".toPath()
            if (fileSystem.exists(sideFile)) {
                val target = "$to$suffix".toPath()
                fileSystem.atomicMove(sideFile, target)
                moved += sideFile to target
            }
        }
        fileSystem.atomicMove(from, to)
    } catch (failure: IOException) {
        moved.asReversed().forEach { (sideFile, target) ->
            try {
                fileSystem.atomicMove(target, sideFile)
            } catch (_: IOException) {
                // Nothing more can be done here; the caller's warning says where things stand.
            }
        }
        throw failure
    }
}

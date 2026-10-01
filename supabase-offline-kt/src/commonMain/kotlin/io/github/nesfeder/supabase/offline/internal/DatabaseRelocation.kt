// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal

import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.resolvedOrNormalized
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/** What sits beside a database file: its write-ahead log, its shared-memory index, its journal. */
private val DATABASE_SIDE_FILE_SUFFIXES: List<String> = listOf("-wal", "-shm", "-journal")

/**
 * The database a client with a configured data directory opens. [default] is moved to [configured],
 * with its side files, when only it exists and no client here holds it. Otherwise it is the one
 * that can be opened without abandoning a queue, with a warning whenever the other is left behind.
 */
internal fun moveDefaultDatabaseIfUnclaimed(
    configured: Path,
    default: Path,
    engineLogger: EngineLogger,
    fileSystem: FileSystem = FileSystem.SYSTEM,
): Path {
    if (configured.resolvedOrNormalized(fileSystem) == default.resolvedOrNormalized(fileSystem))
        return configured
    val defaultExists = fileSystem.exists(default)
    if (fileSystem.exists(configured)) {
        if (defaultExists) {
            engineLogger.warning {
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
            engineLogger.warning {
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
            engineLogger.warning {
                "Could not move the offline database into the configured directory (${failure::class.simpleName}); " +
                    "keeping it at the default location"
            }
            return default
        }
        engineLogger.info {
            "Moved the offline database from the default location into the configured directory"
        }
        return configured
    } catch (failure: IOException) {
        engineLogger.warning {
            "Could not prepare the configured directory for the offline database (${failure::class.simpleName}); " +
                "keeping it at the default location"
        }
        return default
    } finally {
        claim.release()
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM

/**
 * The offline database's file name, on every platform and in every configuration. Installs keep
 * their cache and unsent writes in it; renaming it would orphan them.
 */
internal const val OFFLINE_DATABASE_FILE_NAME: String = "supabase_offline.db"

/**
 * The offline databases and data directories open in this process, one client each. Two clients on
 * one would replay the same queue twice and clear each other's file cache. Paths are compared with
 * every symlink resolved; [Registration.release], from the client's close, frees them.
 */
internal object OpenDatabaseRegistry {

    /** Each claimed key, `database:` or `data:` and a resolved path, to the claim that holds it. */
    private val openPaths = AtomicReference<Map<String, Registration>>(emptyMap())

    /**
     * Claims [databasePath], and [dataDirectory] when given, for one client.
     *
     * @param databasePath the database file.
     * @param dataDirectory the directory the client's file cache lives under.
     * @return the claim; release it when the client closes.
     * @throws IllegalStateException when another client already holds either, open or still
     *   closing.
     */
    fun register(databasePath: Path, dataDirectory: Path? = null): Registration {
        val keys = buildSet {
            add("database:${databasePath.resolvedOrNormalized()}")
            if (dataDirectory != null) add("data:${dataDirectory.resolvedOrNormalized()}")
        }
        val registration = Registration(keys)
        while (true) {
            val current = openPaths.load()
            val holder = keys.firstNotNullOfOrNull { key -> current[key] }
            check(holder == null) { holder?.conflictMessage(databasePath).orEmpty() }
            if (openPaths.compareAndSet(current, current + keys.associateWith { registration }))
                return registration
        }
    }

    /** One client's claim on its database and data directory. */
    internal class Registration(private val keys: Set<String>) {

        /** Set by the first [release], so a second one frees nothing. */
        private val released = AtomicBoolean(false)

        /** Counts the closing client's calls in flight, once [markStillClosing] has run. */
        private val stillClosing = AtomicReference<(() -> Int)?>(null)

        /**
         * Records that the client's close() returned before the release, with [callsInFlight] still
         * holding the database, so a client opened meanwhile is told so.
         */
        fun markStillClosing(callsInFlight: () -> Int) {
            stillClosing.store(callsInFlight)
        }

        /** Why another client cannot have [databasePath] while this claim holds it. */
        fun conflictMessage(databasePath: Path): String {
            val callsInFlight =
                stillClosing.load()
                    ?: return "A SupabaseOfflineClient is already open on $databasePath or its data directory. Reuse that " +
                        "client, or close() it before creating another: two clients on one database would each " +
                        "replay the same queued writes."
            val stillRunning = callsInFlight()
            if (stillRunning == 0) {
                return "The SupabaseOfflineClient on $databasePath or its data directory is still closing: its last " +
                    "call has finished and it is releasing the database now. Call close() again on it to wait for " +
                    "the release, then create the new client."
            }
            return "The SupabaseOfflineClient on $databasePath or its data directory is still closing: " +
                "$stillRunning call(s) it had in flight have not finished. It releases the database as soon as " +
                "they do; call close() again on it to wait for that, then create the new client."
        }

        /** Frees them for another client. Safe to call more than once. */
        fun release() {
            if (!released.compareAndSet(false, true)) return
            while (true) {
                val current = openPaths.load()
                if (openPaths.compareAndSet(current, current - keys)) return
            }
        }
    }
}

/**
 * This path with every symlink on it resolved: the deepest part that exists is canonicalized and
 * the missing rest appended. Falls back to the normalized path when nothing can be resolved.
 *
 * @param fileSystem the file system to resolve against.
 * @return the resolved path.
 */
internal fun Path.resolvedOrNormalized(fileSystem: FileSystem = FileSystem.SYSTEM): Path {
    var existing = normalized()
    val missing = mutableListOf<String>()
    while (fileSystem.metadataOrNull(existing) == null) {
        missing += existing.name
        existing = existing.parent ?: return normalized()
    }
    return try {
        missing.asReversed().fold(fileSystem.canonicalize(existing)) { directory, name ->
            directory / name
        }
    } catch (_: IOException) {
        normalized()
    }
}

/**
 * [DbPathProvider] for a database whose path the factory already resolved.
 *
 * @param path the database file.
 */
internal class FixedDbPathProvider(private val path: Path) : DbPathProvider {
    override fun dbPath(): Path = path
}

@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import okio.FileSystem
import okio.IOException
import okio.Path
import okio.SYSTEM
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The offline database's file name, on every platform and in every configuration. Installs keep
 * their cache and unsent writes in it; renaming it would orphan them.
 */
internal const val OFFLINE_DATABASE_FILE_NAME: String = "supabase_offline.db"

/**
 * The offline databases and data directories open in this process, one client each: two clients on
 * one would replay the same queue twice and clear each other's file cache. Paths are compared with
 * every symlink resolved; [Registration.release], from the client's close, frees them.
 */
internal object OpenDatabaseRegistry {

    private val openPaths = AtomicReference<Set<String>>(emptySet())

    /**
     * Claims [databasePath], and [dataDirectory] when given, for one client.
     *
     * @param databasePath the database file.
     * @param dataDirectory the directory the client's file cache lives under.
     * @return the claim; release it when the client closes.
     * @throws IllegalStateException when another open client already holds either.
     */
    fun register(databasePath: Path, dataDirectory: Path? = null): Registration {
        val keys = buildSet {
            add("database:${databasePath.resolvedOrNormalized()}")
            if (dataDirectory != null) add("data:${dataDirectory.resolvedOrNormalized()}")
        }
        while (true) {
            val current = openPaths.load()
            check(keys.none { it in current }) {
                "A SupabaseOfflineClient is already open on $databasePath or its data directory. Reuse that " +
                    "client, or close() it before creating another: two clients on one database would each " +
                    "replay the same queued writes."
            }
            if (openPaths.compareAndSet(current, current + keys)) return Registration(keys)
        }
    }

    /** One client's claim on its database and data directory. */
    internal class Registration(private val keys: Set<String>) {

        private val released = AtomicBoolean(false)

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
        missing.asReversed().fold(fileSystem.canonicalize(existing)) { directory, name -> directory / name }
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

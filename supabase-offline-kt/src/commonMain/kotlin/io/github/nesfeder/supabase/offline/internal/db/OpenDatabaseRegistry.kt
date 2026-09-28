@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import okio.Path
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The offline database's file name, on every platform and in every configuration. Installs keep
 * their cache and unsent writes in it; renaming it would orphan them.
 */
internal const val OFFLINE_DATABASE_FILE_NAME: String = "supabase_offline.db"

/**
 * The offline databases open in this process, one client each.
 *
 * Two clients on one database file each run their own queue replay, write locks, stale-response
 * watermarks and refresh loops, none of which see the other's: the same queued write can be sent
 * twice, and one client's observers go stale on the other's writes. So opening a second client on a
 * file that is already open fails fast, and [Registration.release] (from the client's `close()`)
 * frees the file for the next one.
 */
internal object OpenDatabaseRegistry {

    private val openPaths = AtomicReference<Set<String>>(emptySet())

    /**
     * Claims [databasePath] for one client.
     *
     * @param databasePath the database file.
     * @return the claim; release it when the client closes.
     * @throws IllegalStateException when another open client already holds the file.
     */
    fun register(databasePath: Path): Registration {
        val key = databasePath.normalized().toString()
        while (true) {
            val current = openPaths.load()
            check(key !in current) {
                "A SupabaseOfflineClient is already open on $key. Reuse that client, or close() it before " +
                    "creating another: two clients on one database would each replay the same queued writes."
            }
            if (openPaths.compareAndSet(current, current + key)) return Registration(key)
        }
    }

    /** One client's claim on a database file. */
    internal class Registration(private val key: String) {

        private val released = AtomicBoolean(false)

        /** Frees the file for another client. Safe to call more than once. */
        fun release() {
            if (!released.compareAndSet(false, true)) return
            while (true) {
                val current = openPaths.load()
                if (openPaths.compareAndSet(current, current - key)) return
            }
        }
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

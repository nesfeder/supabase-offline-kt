package io.github.nesfeder.supabase.internal.db

import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Internal cache configuration used by the offline store.
 *
 * @param maxDbSizeBytes Maximum allowed size of the DB file. `null` means
 * "unlimited" and disables size-based eviction.
 * @param evictionBatchSize Approximate number of rows to evict in a single
 * cleanup pass once the limit is exceeded.
 * @param minRetainedBytes Minimum number of bytes we try to keep even during
 * aggressive cleanup, to avoid wiping all cached data.
 */
internal data class OfflineCacheSettings(
    val maxDbSizeBytes: Long?,
    val evictionBatchSize: Int,
    val minRetainedBytes: Long,
)

/**
 * Platform-agnostic provider for the path of the underlying offline DB file.
 */
internal interface DbPathProvider {
    fun dbPath(): Path
}

/**
 * Best-effort helper for determining the current size of the offline DB.
 * Returns `null` if the size can't be read (for example, if the file doesn't
 * exist yet).
 */
internal fun currentDbSizeBytes(dbPath: Path): Long? = runCatching {
    FileSystem.SYSTEM.metadata(dbPath).size
}.getOrNull()

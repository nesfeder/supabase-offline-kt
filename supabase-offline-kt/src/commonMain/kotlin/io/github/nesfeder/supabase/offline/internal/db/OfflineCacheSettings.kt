package io.github.nesfeder.supabase.offline.internal.db

import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Internal cache configuration used by the offline store.
 *
 * @param maxDbSizeBytes Maximum logical size, in bytes, of the cached SELECT payloads to retain
 * — the summed `LENGTH(payload)` of every cached row, not the on-disk size of the Room database
 * file (SQLite never returns pages freed by `DELETE` to the OS, so file size can't drive
 * eviction). `null` means "unlimited" and disables size-based eviction.
 * @param evictionBatchSize Number of oldest rows to evict per pass once the limit is exceeded.
 * Coerced to at least one row by the eviction loop so a non-positive value can't stall progress.
 * @param minRetainedBytes Hysteresis headroom, in bytes: once eviction triggers it drains down to
 * `maxDbSizeBytes - minRetainedBytes` (coerced to the ceiling when this is non-positive or
 * meets/exceeds `maxDbSizeBytes`) so a burst of writes across the limit doesn't re-trigger a pass
 * on every subsequent write.
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

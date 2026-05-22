package io.github.nesfeder.supabase

/**
 * Metadata for a storage object tracked in the offline download cache (Room `storage_objects`).
 *
 * [localPath] refers to a file that exists on disk: APIs that return this type verify the path
 * first and drop invalid cache rows when the file is missing.
 *
 * Use [lastFetchedEpochMs] with your own rules (cutoff instant, max age, etc.) to decide staleness.
 */
data class StorageCacheMetadata(
    val bucketId: String,
    /** Object key within the bucket (same string passed to download / path helpers). */
    val objectPath: String,
    /** Absolute path to the cached file on device. */
    val localPath: String,
    /** Epoch milliseconds when this cache entry was last written (successful download / track). */
    val lastFetchedEpochMs: Long,
)

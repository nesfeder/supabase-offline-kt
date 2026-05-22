package io.github.nesfeder.supabase

import io.github.nesfeder.supabase.internal.storage.OfflineBucketApi
import io.github.jan.supabase.storage.BucketApi

/**
 * Ensure that [objectPath] is present in local cache.
 * If not cached yet, downloads it (authenticated) and returns the absolute POSIX path as [String].
 */
@Throws(Exception::class)
suspend fun BucketApi.ensureLocalPath(
    objectPath: String,
    isPublic: Boolean = false
): String? {
    val offline = this as? OfflineBucketApi ?: return null

    // already cached?
    offline.localPathIfPresent(objectPath)?.let { return it.toString() }

    // not cached -> download which writes through the offline wrapper to disk
    runCatching {
        if (isPublic) {
            downloadPublic(objectPath) { /* no options */ }
        } else {
            downloadAuthenticated(objectPath) { /* no options */ }
        }
    }.onFailure { return null }

    // resolve after download
    return offline.localPathIfPresent(objectPath)?.toString()
}

/**
 * Return the local absolute path ONLY if already cached (no network).
 */
@Throws(Exception::class)
suspend fun BucketApi.localPathIfCached(objectPath: String): String? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.localPathIfPresent(objectPath)?.toString()
}

/**
 * Convenience: return a file:// URL string for [objectPath],
 * downloading if necessary. Handy for Swift: URL(string: …).
 */
@Throws(Exception::class)
suspend fun BucketApi.ensureLocalFileUrl(objectPath: String, isPublic: Boolean): String? =
    ensureLocalPath(objectPath, isPublic)?.let { "file://$it" }

/**
 * Convenience: return a file:// URL string ONLY if already cached (no network).
 */
@Throws(Exception::class)
suspend fun BucketApi.localFileUrlIfCached(objectPath: String): String? =
    localPathIfCached(objectPath)?.let { "file://$it" }

/**
 * Metadata for the offline storage cache row for [objectPath], or `null` if nothing is tracked.
 * Use [StorageCacheMetadata.lastFetchedEpochMs] for client-driven staleness checks.
 */
@Throws(Exception::class)
suspend fun BucketApi.storageCacheMetadata(objectPath: String): StorageCacheMetadata? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.storageCacheMetadata(objectPath)
}
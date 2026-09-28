package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.storage.OfflineBucketApi
import io.github.jan.supabase.storage.BucketApi

/**
 * Ensure that [objectPath] is present in local cache.
 * If not cached yet, downloads it (authenticated) and returns the absolute POSIX path as [String].
 */
public suspend fun BucketApi.ensureLocalPath(
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
public suspend fun BucketApi.localPathIfCached(objectPath: String): String? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.localPathIfPresent(objectPath)?.toString()
}

/**
 * Convenience: return a file:// URL string for [objectPath],
 * downloading if necessary. Handy for Swift: URL(string: …).
 */
public suspend fun BucketApi.ensureLocalFileUrl(objectPath: String, isPublic: Boolean): String? =
    ensureLocalPath(objectPath, isPublic)?.let { "file://$it" }

/**
 * Convenience: return a file:// URL string ONLY if already cached (no network).
 */
public suspend fun BucketApi.localFileUrlIfCached(objectPath: String): String? =
    localPathIfCached(objectPath)?.let { "file://$it" }
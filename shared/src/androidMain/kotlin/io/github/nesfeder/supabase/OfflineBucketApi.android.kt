package io.github.nesfeder.supabase

import android.net.Uri
import io.github.nesfeder.supabase.internal.storage.OfflineBucketApi
import io.github.jan.supabase.storage.BucketApi
import java.io.File

/**
 * Ensure that [objectPath] is present in local cache.
 * If not cached yet, downloads it (authenticated) and returns the local [File].
 */
suspend fun BucketApi.ensureLocalFile(
    objectPath: String,
    isPublic: Boolean = false
): File? {
    val offline = this as? OfflineBucketApi ?: return null

    // already cached?
    offline.localPathIfPresent(objectPath)?.let { return it.toFile() }

    // not cached -> download which writes through the offline wrapper to disk
    runCatching {
        if (isPublic) {
            downloadPublic(objectPath) { /* no options */ }
        } else {
            downloadAuthenticated(objectPath) { /* no options */ }
        }
    }.onFailure { return null }

    return offline.localPathIfPresent(objectPath)?.toFile()
}

/**
 * Return a [Uri] for the locally cached file for [objectPath],
 * downloading it first if necessary.
 */
suspend fun BucketApi.ensureLocalUri(
    objectPath: String,
    isPublic: Boolean = false
): Uri? = ensureLocalFile(objectPath, isPublic)?.let { Uri.fromFile(it) }

/**
 * Return a [Uri] only if [objectPath] is already cached locally (no network).
 */
suspend fun BucketApi.localUriIfCached(objectPath: String): Uri? {
    val offline = this as? OfflineBucketApi ?: return null
    val p = offline.localPathIfPresent(objectPath) ?: return null
    return Uri.fromFile(p.toFile())
}

/**
 * Metadata for the offline storage cache row for [objectPath], or `null` if nothing is tracked.
 * Use [StorageCacheMetadata.lastFetchedEpochMs] for client-driven staleness checks.
 */
suspend fun BucketApi.storageCacheMetadata(objectPath: String): StorageCacheMetadata? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.storageCacheMetadata(objectPath)
}
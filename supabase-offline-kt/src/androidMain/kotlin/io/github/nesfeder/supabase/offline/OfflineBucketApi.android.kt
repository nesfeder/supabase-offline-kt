package io.github.nesfeder.supabase.offline

import android.net.Uri
import io.github.jan.supabase.storage.BucketApi
import java.io.File

/**
 * The cached copy of [objectPath] as a [File], downloading it first when none is cached. A thin
 * Android convenience over [OfflineBucketApi.ensureCachedFile]; see there for what it throws.
 *
 * @param objectPath the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 * @return the cached file, or `null` only when this bucket did not come from the offline client.
 */
public suspend fun BucketApi.ensureLocalFile(
    objectPath: String,
    isPublic: Boolean = false,
): File? {
    val offline = this as? OfflineBucketApi ?: return null
    return File(offline.ensureCachedFile(objectPath, isPublic).path)
}

/**
 * The cached copy of [objectPath] as a `file:` [Uri], downloading it first when none is cached.
 * See [ensureLocalFile].
 *
 * @param objectPath the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 * @return the file's [Uri], or `null` only when this bucket did not come from the offline client.
 */
public suspend fun BucketApi.ensureLocalUri(
    objectPath: String,
    isPublic: Boolean = false,
): Uri? = ensureLocalFile(objectPath, isPublic)?.let { Uri.fromFile(it) }

/**
 * The cached copy of [objectPath] as a `file:` [Uri] when one is cached; never touches the network.
 * A thin Android convenience over [OfflineBucketApi.cachedFile].
 *
 * @param objectPath the object path within this bucket.
 * @return the file's [Uri], or `null` when nothing is cached or this bucket did not come from the
 *   offline client.
 */
public suspend fun BucketApi.localUriIfCached(objectPath: String): Uri? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.cachedFile(objectPath)?.let { Uri.fromFile(File(it.path)) }
}

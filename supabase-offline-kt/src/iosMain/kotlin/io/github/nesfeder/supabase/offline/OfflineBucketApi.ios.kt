// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi

/**
 * The absolute path of the cached copy of [objectPath], downloading it first when none is cached. A
 * thin convenience over [OfflineBucketApi.ensureCachedFile]; see there for what it throws.
 *
 * @param objectPath the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 * @return the file's absolute path, or `null` only when this bucket did not come from the offline
 *   client.
 */
@Throws(Exception::class)
public suspend fun BucketApi.ensureLocalPath(
    objectPath: String,
    isPublic: Boolean = false,
): String? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.ensureCachedFile(objectPath, isPublic).path
}

/**
 * The absolute path of the cached copy of [objectPath] when one is cached; never touches the
 * network. A thin convenience over [OfflineBucketApi.cachedFile].
 *
 * @param objectPath the object path within this bucket.
 * @return the file's absolute path, or `null` when nothing is cached or this bucket did not come
 *   from the offline client.
 */
@Throws(Exception::class)
public suspend fun BucketApi.localPathIfCached(objectPath: String): String? {
    val offline = this as? OfflineBucketApi ?: return null
    return offline.cachedFile(objectPath)?.path
}

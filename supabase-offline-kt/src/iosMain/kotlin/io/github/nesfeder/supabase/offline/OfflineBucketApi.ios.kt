// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi
import platform.Foundation.NSURL

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

/**
 * [ensureLocalPath] as a percent-encoded `file://` URL string, for Swift's `URL(string:)`. A name
 * with a space, `#`, `?` or `%` still parses back to the file.
 *
 * @param objectPath the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 * @return the file URL, or `null` only when this bucket did not come from the offline client.
 */
@Throws(Exception::class)
public suspend fun BucketApi.ensureLocalFileUrl(objectPath: String, isPublic: Boolean): String? =
    ensureLocalPath(objectPath, isPublic)?.let(::fileUrlOf)

/**
 * [localPathIfCached] as a `file://` URL string, for Swift's `URL(string:)`, percent-encoded like
 * [ensureLocalFileUrl]'s; never touches the network.
 *
 * @param objectPath the object path within this bucket.
 * @return the file URL, or `null` when nothing is cached.
 */
@Throws(Exception::class)
public suspend fun BucketApi.localFileUrlIfCached(objectPath: String): String? =
    localPathIfCached(objectPath)?.let(::fileUrlOf)

/** The `file://` URL of the file at [path], percent-encoded by Foundation. */
private fun fileUrlOf(path: String): String? = NSURL.fileURLWithPath(path).absoluteString

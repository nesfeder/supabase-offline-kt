// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * The absolute path of the cached copy of the object at [path], downloading it into the cache first
 * when none is cached: [OfflineBucketApi.ensureCachedFile]'s path. It throws what that throws.
 *
 * @param path the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.ensureLocalPath(
    path: String,
    isPublic: Boolean = false,
): String = ensureCachedFile(path, isPublic).path

/**
 * The absolute path of the cached copy of the object at [path], or `null` when none is cached:
 * [OfflineBucketApi.cachedFile]'s path. Never touches the network.
 *
 * @param path the object path within this bucket.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.localPathIfCached(path: String): String? =
    cachedFile(path)?.path

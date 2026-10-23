// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import android.net.Uri
import java.io.File

/**
 * The cached copy of the object at [path] as a [File], downloading it into the cache first when
 * none is cached: [OfflineBucketApi.ensureCachedFile] as a [File]. It throws what that throws.
 *
 * @param path the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.ensureLocalFile(path: String, isPublic: Boolean = false): File =
    File(ensureCachedFile(path, isPublic).path)

/**
 * The cached copy of the object at [path] as a `file:` [Uri], downloading it into the cache first
 * when none is cached: [ensureLocalFile] as a [Uri]. It throws what that throws.
 *
 * @param path the object path within this bucket.
 * @param isPublic whether to download through the public URL rather than the authenticated one.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.ensureLocalUri(path: String, isPublic: Boolean = false): Uri =
    Uri.fromFile(ensureLocalFile(path, isPublic))

/**
 * The cached copy of the object at [path] as a [File], or `null` when none is cached:
 * [OfflineBucketApi.cachedFile] as a [File]. Never touches the network.
 *
 * @param path the object path within this bucket.
 * @throws IllegalStateException when the client is closed.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.localFileIfCached(path: String): File? =
    cachedFile(path)?.let { File(it.path) }

/**
 * The cached copy of the object at [path] as a `file:` [Uri], or `null` when none is cached:
 * [OfflineBucketApi.cachedFile] as a [Uri]. Never touches the network.
 *
 * @param path the object path within this bucket.
 * @throws IllegalStateException when the client is closed.
 */
@Throws(Throwable::class)
public suspend fun OfflineBucketApi.localUriIfCached(path: String): Uri? =
    cachedFile(path)?.let { Uri.fromFile(File(it.path)) }

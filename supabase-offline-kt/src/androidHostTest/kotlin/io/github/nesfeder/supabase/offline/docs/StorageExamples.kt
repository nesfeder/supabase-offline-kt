// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import android.net.Uri
import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.ensureLocalFile
import io.github.nesfeder.supabase.offline.ensureLocalUri
import io.github.nesfeder.supabase.offline.localUriIfCached
import io.github.nesfeder.supabase.offline.offline
import java.io.File
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

// The documentation site's Storage page includes the sections of this file between the `--8<--`
// markers, so every Kotlin sample there compiles. Nothing here runs.

/** The bucket the Storage examples read from. */
internal fun photoBucket(supabase: SupabaseClient): OfflineBucketApi {
    // --8<-- [start:bucket]
    val photos = supabase.offline.storage.from("task-photos")
    // --8<-- [end:bucket]
    return photos
}

/** A download, served from the file cache when the network fails. */
internal suspend fun downloadPhoto(photos: OfflineBucketApi, path: String): ByteArray {
    // --8<-- [start:download]
    // The server's bytes, kept on disk; the kept copy when the network fails
    val bytes = photos.downloadAuthenticated(path)
    // --8<-- [end:download]
    return bytes
}

/** The cached copy as a file, downloaded first when there is none. */
internal suspend fun photoFile(photos: OfflineBucketApi, path: String): CachedFile? {
    // --8<-- [start:cached-file]
    // On the device already? Never touches the network
    val cached: CachedFile? = photos.cachedFile(path)

    // On the device, downloading it into the cache first when it is not; a copy is returned
    // however old, so compare fetchedAt with your own rule
    val file: CachedFile = photos.ensureCachedFile(path)
    val isOld = Clock.System.now() - file.fetchedAt > 7.days
    // --8<-- [end:cached-file]
    return if (isOld) cached else file
}

/** The Android helpers: the cached copy as a `file:` [Uri] or a [File], for an image loader. */
internal suspend fun photoUris(photos: OfflineBucketApi, path: String): List<Any?> {
    // --8<-- [start:android-helpers]
    val shownNow: Uri? = photos.localUriIfCached(path) // null when nothing is cached; no request
    val shownSoon: Uri = photos.ensureLocalUri(path) // downloads it into the cache first if needed
    val file: File = photos.ensureLocalFile(path) // the same copy, as a File
    // --8<-- [end:android-helpers]
    return listOf(shownNow, shownSoon, file)
}

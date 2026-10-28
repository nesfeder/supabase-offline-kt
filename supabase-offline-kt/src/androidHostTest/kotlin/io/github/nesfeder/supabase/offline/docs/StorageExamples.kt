// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi

// The documentation site's Storage page includes the sections of this file between the `--8<--`
// markers, so every Kotlin listing there compiles. The sample apps show the rest of the bucket;
// they read photos through the file cache and never hold a download's bytes. Nothing here runs.

/** A download, served from the file cache when the network fails. */
internal suspend fun downloadPhoto(photos: OfflineBucketApi, path: String): ByteArray {
    // --8<-- [start:download]
    // The server's bytes, kept on disk; the kept copy when the network fails
    val bytes = photos.downloadAuthenticated(path)
    // --8<-- [end:download]
    return bytes
}

/** A thumbnail's cached file for an image loader, and the calls that free the device's storage. */
internal suspend fun freeSpace(offline: Offline, photos: OfflineBucketApi, path: String): String? {
    // --8<-- [start:evict-and-clear]
    // The cached thumbnail a download with this transform kept, or null; never a request
    val thumbnail = photos.cachedFile(path) { size(256, 256) }

    // One photo's copies, the full image and every thumbnail
    photos.evictCachedFile(path)

    // Every downloaded file, in every bucket; queued writes and cached lists stay
    offline.storage.clearFileCache()
    // --8<-- [end:evict-and-clear]
    return thumbnail?.path
}

/** A "keep offline" switch: a pinned document stays on the device whatever the cache evicts. */
internal suspend fun keepOffline(documents: OfflineBucketApi, path: String, keep: Boolean) {
    // --8<-- [start:pin]
    if (keep) {
        // Never evicted from now on; downloaded now, so it is there offline
        documents.pinCachedFile(path)
        documents.ensureCachedFile(path)
    } else {
        // Evicted again when the cache needs the room
        documents.unpinCachedFile(path)
    }
    // --8<-- [end:pin]
}

/** A photo that is not on the device yet, with a progress bar while it downloads. */
internal suspend fun photoWithProgress(
    photos: OfflineBucketApi,
    path: String,
    showFraction: (Float?) -> Unit,
): String {
    // --8<-- [start:progress]
    // Runs on this coroutine's dispatcher; totalBytes is null when the server gave no size
    val photo =
        photos.ensureCachedFile(path) { progress ->
            val total = progress.totalBytes
            showFraction(total?.let { progress.bytesReceived.toFloat() / it })
        }
    // --8<-- [end:progress]
    return photo.path
}

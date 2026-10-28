// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.UploadOutcome
import io.ktor.http.ContentType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray

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

/** A photo and the row that names it, made offline: the file is sent first, then the row. */
internal suspend fun addPhoto(
    offline: Offline,
    path: String,
    jpeg: ByteArray,
    row: JsonArray,
): Pair<UploadOutcome, CachedFile?> {
    // --8<-- [start:queued-upload]
    // Kept on the device and queued when it cannot be sent now
    val upload =
        offline.storage.from("photos").upload(path, jpeg, MutationStrategy.QueueIfOffline) {
            contentType = ContentType.Image.JPEG
        }
    // The row that names the file is sent after it
    offline.from("task_photos").insert(row)

    // The bytes on the device while it waits; the cached copy once Storage has it
    val shown = offline.storage.from("photos").cachedFile(path)
    // --8<-- [end:queued-upload]
    return upload to shown
}

/** What waits, and what Storage refused: listed, retried and dismissed. */
internal suspend fun queuedUploads(offline: Offline) {
    // --8<-- [start:queued-upload-lists]
    // The current user's uploads still to send, in the order they will go
    val waiting = offline.storage.pendingUploads().first()

    // Refused ones keep their bytes: retry once the cause is fixed, or dismiss
    offline.storage.rejectedUploadList().forEach { refused ->
        if (refused.isRetryable) offline.retryRejectedMutation(refused.id)
        else offline.dismissRejectedMutation(refused.id)
    }

    // Drop one that has not gone yet, with its bytes
    waiting.firstOrNull()?.let { upload -> offline.discardPendingMutation(upload.id) }
    // --8<-- [end:queued-upload-lists]
}

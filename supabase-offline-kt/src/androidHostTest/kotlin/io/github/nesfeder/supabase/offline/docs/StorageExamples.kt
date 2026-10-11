// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.docs

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

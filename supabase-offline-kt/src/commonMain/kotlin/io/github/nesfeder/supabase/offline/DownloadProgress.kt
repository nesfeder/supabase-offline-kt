// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * How far a download into the file cache has come, as [OfflineBucketApi.ensureCachedFile] with a
 * progress callback reports it.
 *
 * @property bytesReceived the bytes of the object received so far.
 * @property totalBytes the object's size in bytes, as the server's answer gave it, or `null` when
 *   it gave none.
 */
@ConsistentCopyVisibility
public data class DownloadProgress
internal constructor(
    public val bytesReceived: Long,
    public val totalBytes: Long?,
)

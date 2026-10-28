// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant

/**
 * One Storage upload waiting in the mutation queue to be sent ([OfflineStorage.pendingUploads]),
 * its bytes kept on the device meanwhile.
 *
 * It is a write of the one queue: it is sent in the order it was made among the user's row writes,
 * [Offline.discardPendingMutation] takes its id, and [SyncStatus.pendingMutationCount] counts it.
 *
 * @property id the upload's id in the queue, from the sequence [PendingMutation.id] comes from. It
 *   survives a restart, and an upload Storage later refuses keeps it ([RejectedUpload.id]).
 * @property bucketId the bucket the file goes to.
 * @property path the object path within the bucket.
 * @property createdAt when the upload was queued, by the device's clock. Writes are sent in the
 *   order of their ids, not of this.
 * @property sizeBytes the file's size, in bytes.
 * @property contentType the content type it is sent with, or `null` when the upload set none, so
 *   supabase-kt infers it from the path as it sends it.
 * @property upsert whether the upload overwrites an object already at the path.
 */
@ConsistentCopyVisibility
public data class PendingUpload
internal constructor(
    public val id: Long,
    public val bucketId: String,
    public val path: String,
    public val createdAt: Instant,
    public val sizeBytes: Long,
    public val contentType: String?,
    public val upsert: Boolean,
)

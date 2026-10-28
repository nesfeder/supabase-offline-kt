// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import kotlin.time.Instant

/**
 * A queued Storage upload that Storage refused for good when it was replayed, kept on the rejected
 * list with its bytes until the app dismisses or retries it ([OfflineStorage.rejectedUploads]).
 *
 * It is an entry of the one rejected list, so [Offline.dismissRejectedMutation] and
 * [Offline.retryRejectedMutation] take its id, and [SyncStatus.rejectedMutationCount] counts it.
 * Only an upload that waited in the mutation queue can end up here: one sent at once that Storage
 * refuses throws Storage's error to its caller instead.
 *
 * @property id the upload's id in the queue, the [PendingUpload.id] it had while it waited.
 * @property bucketId the bucket the file was going to; empty for an entry whose stored record this
 *   version of the library cannot read, which can only be dismissed.
 * @property path the object path within the bucket; empty as the bucket is.
 * @property createdAt when the upload was queued, by the device's clock.
 * @property sizeBytes the file's size, in bytes.
 * @property contentType the content type it was sent with, or `null` when the upload set none.
 * @property upsert whether the upload overwrites an object already at the path.
 * @property serverError what Storage answered: its HTTP status, its error name as
 *   [ServerError.code] when it sent one (such as `Duplicate`), and its message. `null` when the
 *   library refused the upload itself, as when its file is no longer on the device.
 * @property message why it was refused, in words: Storage's message, or the library's reason.
 * @property rejectedAt when it was refused, by the device's clock.
 * @property isRetryable whether [Offline.retryRejectedMutation] can queue it again: whether its
 *   file is still on the device, whole.
 */
@ConsistentCopyVisibility
public data class RejectedUpload
internal constructor(
    public val id: Long,
    public val bucketId: String,
    public val path: String,
    public val createdAt: Instant,
    public val sizeBytes: Long,
    public val contentType: String?,
    public val upsert: Boolean,
    public val serverError: ServerError?,
    public val message: String,
    public val rejectedAt: Instant,
    public val isRetryable: Boolean,
)

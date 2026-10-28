// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * What became of an upload made with a strategy ([BridgeStorage.upload] with `mutationStrategy`):
 * Storage stored it, or it waits in the mutation queue with its bytes kept on the device.
 *
 * @property isQueued whether the upload waits in the mutation queue.
 * @property applied Storage's response when it stored the file, `{"id", "path", "key"}`; `null`
 *   when queued.
 * @property queued the queued upload when it waits; `null` when applied.
 */
public class BridgeUploadResult
internal constructor(
    public val isQueued: Boolean,
    public val applied: String?,
    public val queued: BridgePendingUpload?,
)

/**
 * One upload waiting in the mutation queue, a write of the queue whose id the queue's id-based
 * calls take ([BridgeClient.discardPendingMutation]).
 *
 * @property id the upload's id in the queue, which a later rejection keeps.
 * @property bucketId the bucket the file goes to.
 * @property path the object path within the bucket.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property sizeBytes the file's size.
 * @property contentType the content type it is sent with; `null` when the upload set none.
 * @property upsert whether it overwrites an object already at the path.
 */
public class BridgePendingUpload
internal constructor(
    public val id: Long,
    public val bucketId: String,
    public val path: String,
    public val createdAtEpochMillis: Long,
    public val sizeBytes: Long,
    public val contentType: String?,
    public val upsert: Boolean,
)

/**
 * A queued upload Storage refused for good on replay, kept with its bytes until it is dismissed or
 * retried ([BridgeClient.dismissRejectedMutation], [BridgeClient.retryRejectedMutation]).
 *
 * @property id the upload's id, as it had in the queue.
 * @property bucketId the bucket the file was going to; empty for an entry the library can no longer
 *   read.
 * @property path the object path within the bucket; empty as the bucket is.
 * @property createdAtEpochMillis when it was queued, in milliseconds since 1970.
 * @property sizeBytes the file's size.
 * @property contentType the content type it was sent with; `null` when the upload set none.
 * @property upsert whether it overwrites an object already at the path.
 * @property serverError what Storage answered; `null` when the library refused it itself.
 * @property message why it was refused, in words.
 * @property rejectedAtEpochMillis when it was refused, in milliseconds since 1970.
 * @property isRetryable whether [BridgeClient.retryRejectedMutation] can queue it again: whether
 *   its file is still on the device.
 */
public class BridgeRejectedUpload
internal constructor(
    public val id: Long,
    public val bucketId: String,
    public val path: String,
    public val createdAtEpochMillis: Long,
    public val sizeBytes: Long,
    public val contentType: String?,
    public val upsert: Boolean,
    public val serverError: BridgeServerError?,
    public val message: String,
    public val rejectedAtEpochMillis: Long,
    public val isRetryable: Boolean,
)

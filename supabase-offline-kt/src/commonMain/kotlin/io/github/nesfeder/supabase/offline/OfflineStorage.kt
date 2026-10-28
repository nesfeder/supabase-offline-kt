// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.Storage
import kotlinx.coroutines.flow.Flow

/**
 * Storage buckets seen through the [Offline] plugin: `offline.storage.from("avatars")`, as
 * supabase-kt's [Storage] is reached. Each bucket keeps its downloads on the device, and can queue
 * an upload with the row writes around it; see [OfflineBucketApi]. Bucket management (creating,
 * listing, emptying buckets) is the server's business and stays on supabase-kt's [Storage].
 */
public class OfflineStorage
internal constructor(
    private val bucketOf: (bucketId: String) -> OfflineBucketApi,
    private val clearFiles: suspend () -> Unit,
    private val pendingUploadsOf: () -> Flow<List<PendingUpload>>,
    private val rejectedUploadsOf: () -> Flow<List<RejectedUpload>>,
    private val rejectedUploadListOf: suspend () -> List<RejectedUpload>,
) {

    /**
     * The bucket [bucketId].
     *
     * @param bucketId the bucket's id.
     * @return the bucket, with downloads kept on the device.
     * @throws OfflineClientClosedException when the plugin is closed.
     * @throws IllegalStateException when [Storage] is not installed.
     */
    public fun from(bucketId: String): OfflineBucketApi = bucketOf(bucketId)

    /**
     * The bucket [bucketId]; `offline.storage["avatars"]` is `offline.storage.from("avatars")`.
     *
     * @param bucketId the bucket's id.
     * @return the bucket, with downloads kept on the device.
     * @throws OfflineClientClosedException when the plugin is closed.
     * @throws IllegalStateException when [Storage] is not installed.
     */
    public operator fun get(bucketId: String): OfflineBucketApi = from(bucketId)

    /**
     * Deletes every downloaded Storage file the library keeps on this device, in every bucket, with
     * its record. The mutation queue, the cached query results and the rejected and conflicted
     * lists stay, and nothing is sent. Call it to free the device's storage without dropping writes
     * waiting to sync; the next download of each object fetches it again. At sign-out, call
     * [Offline.clearLocalData] instead: it deletes these files too, and rewrites the database
     * without what it deleted, which this does not.
     *
     * A Storage download in flight may hand its bytes to its caller, or fail, but is not written
     * back, as with [Offline.clearLocalData]: an [OfflineBucketApi.ensureCachedFile] running
     * meanwhile throws [IllegalStateException].
     *
     * @throws IllegalStateException when the plugin is closed.
     */
    @Throws(Throwable::class) public suspend fun clearFileCache(): Unit = clearFiles()

    /**
     * The current user's uploads waiting in the mutation queue ([OfflineBucketApi.upload] with a
     * [MutationStrategy]), in every bucket, in the order they will be sent, as the queue changes.
     * Each is a write of the queue: [Offline.discardPendingMutation] drops one, with its bytes, and
     * [SyncStatus.pendingMutationCount] counts them. [Offline.pendingMutations] lists the row
     * writes around them.
     *
     * Whose they are, and how the flow ends, is as for [Offline.pendingMutations]: the uploads made
     * under the current session, and those queued with no user recorded that it may take over;
     * empty while the stored session cannot be read. A collection live at [Offline.close]
     * completes; collected after it, the flow fails with [IllegalStateException].
     *
     * @return the flow of lists.
     */
    public fun pendingUploads(): Flow<List<PendingUpload>> = pendingUploadsOf()

    /**
     * The current user's queued uploads Storage refused for good when they were replayed, oldest
     * first, as the list changes: what an app shows so its user can retry or drop a file that will
     * never go through on its own. Each keeps its bytes on the device until it is dismissed
     * ([Offline.dismissRejectedMutation]) or retried ([Offline.retryRejectedMutation], which queues
     * it again at the end of the user's queue).
     *
     * They are entries of the one rejected list: [SyncStatus.rejectedMutationCount] counts them,
     * they share its bound of 100 entries, and [Offline.rejectedMutations] lists the refused row
     * writes beside them. Whose they are, and how the flow ends, is as for
     * [Offline.rejectedMutations].
     *
     * @return the flow of lists.
     */
    public fun rejectedUploads(): Flow<List<RejectedUpload>> = rejectedUploadsOf()

    /**
     * The current user's refused uploads on the rejected list, oldest first, read once: the list
     * [rejectedUploads] emits first.
     *
     * @return the list.
     * @throws IllegalStateException when the plugin is closed.
     */
    @Throws(Throwable::class)
    public suspend fun rejectedUploadList(): List<RejectedUpload> = rejectedUploadListOf()
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.Storage

/**
 * Storage buckets seen through the [Offline] plugin: `offline.storage.from("avatars")`, as
 * supabase-kt's [Storage] is reached. Each bucket keeps its downloads on the device; see
 * [OfflineBucketApi]. Bucket management (creating, listing, emptying buckets) is the server's
 * business and stays on supabase-kt's [Storage].
 */
public class OfflineStorage
internal constructor(
    private val bucketOf: (bucketId: String) -> OfflineBucketApi,
    private val clearFiles: suspend () -> Unit,
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
     * its record, and every pin ([OfflineBucketApi.pinCachedFile]). The mutation queue, the cached
     * query results and the rejected and conflicted lists stay, and nothing is sent. Call it to
     * free the device's storage without dropping writes waiting to sync; the next download of each
     * object fetches it again. At sign-out, call [Offline.clearLocalData] instead: it deletes these
     * files too, and rewrites the database without what it deleted, which this does not.
     *
     * A Storage download in flight may hand its bytes to its caller, or fail, but is not written
     * back, as with [Offline.clearLocalData]: an [OfflineBucketApi.ensureCachedFile] running
     * meanwhile throws [IllegalStateException].
     *
     * @throws IllegalStateException when the plugin is closed.
     */
    @Throws(Throwable::class) public suspend fun clearFileCache(): Unit = clearFiles()
}

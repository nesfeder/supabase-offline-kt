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
internal constructor(private val bucketOf: (bucketId: String) -> OfflineBucketApi) {

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
}

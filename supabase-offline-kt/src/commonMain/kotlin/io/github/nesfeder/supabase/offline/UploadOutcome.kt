// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.FileUploadResponse

/**
 * What became of an upload made with a [MutationStrategy] through [OfflineBucketApi.upload]:
 * Storage stored the file, or the upload waits in the mutation queue. It is [MutationOutcome] for a
 * Storage upload.
 *
 * The set is closed: these two cases are every outcome such an upload can have, so a `when` over
 * them stays exhaustive.
 */
public sealed interface UploadOutcome {

    /**
     * Storage stored the file.
     *
     * @property response Storage's response, as supabase-kt's `upload` returns it.
     */
    @ConsistentCopyVisibility
    public data class Applied internal constructor(public val response: FileUploadResponse) :
        UploadOutcome

    /**
     * The upload waits in the mutation queue, its bytes kept on the device, and is sent by a later
     * pass in the order it was made among the user's writes. It has no response from Storage yet.
     *
     * @property pendingUpload the queued upload, whose [PendingUpload.id] identifies it from now
     *   on.
     */
    @ConsistentCopyVisibility
    public data class Queued internal constructor(public val pendingUpload: PendingUpload) :
        UploadOutcome

    /** Whether the upload waits in the mutation queue. */
    public val isQueued: Boolean
        get() = this is Queued

    /**
     * Storage's response when the upload was [Applied], or `null` when it was [Queued].
     *
     * @return the [Applied.response], or `null`.
     */
    public fun responseOrNull(): FileUploadResponse? = (this as? Applied)?.response
}

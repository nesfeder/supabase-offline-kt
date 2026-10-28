// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

/** Where a photo's upload stands while Storage does not have its file. */
enum class PendingPhotoState {
    /** Kept on the device, in the library's queue: it uploads once the network allows. */
    Waiting,

    /**
     * Refused by Storage on its way out, or failed when it was sent at once: kept on the device
     * until it is retried or removed.
     */
    Failed,
}

/**
 * What a photo shown before Storage has its file carries: where its upload stands. The screens draw
 * a waiting one from the library's copy on the device, as they draw every photo, and a refused one
 * from the file its rejected entry names.
 *
 * @property failure why Storage refused it, in Storage's words; null unless [state] is
 *   [PendingPhotoState.Failed].
 * @property retryable false once its file is no longer on the device, so a retry cannot send it.
 * @property keptFile the path of a failed upload's file on the device, which the screens draw it
 *   from: the one the library's rejected list keeps, or the app's own for an upload that failed
 *   when it was sent at once; null unless [state] is [PendingPhotoState.Failed] and it is whole.
 */
data class PendingUpload(
    val state: PendingPhotoState,
    val failure: String? = null,
    val retryable: Boolean = true,
    val keptFile: String? = null,
)

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

/** Where a photo's upload stands while Storage does not have its file. */
enum class PendingPhotoState {
    /** Kept on the device, in the library's queue: it uploads once the network allows. */
    Waiting,

    /** Refused by Storage on its way out: kept on the device until it is retried or removed. */
    Failed,
}

/**
 * What a photo shown before Storage has its file carries: where its upload stands. The screens draw
 * it from the library's copy on the device, as they draw every photo.
 *
 * @property failure why Storage refused it, in Storage's words; null unless [state] is
 *   [PendingPhotoState.Failed].
 * @property retryable false once its file is no longer on the device, so a retry cannot send it.
 */
data class PendingUpload(
    val state: PendingPhotoState,
    val failure: String? = null,
    val retryable: Boolean = true,
)

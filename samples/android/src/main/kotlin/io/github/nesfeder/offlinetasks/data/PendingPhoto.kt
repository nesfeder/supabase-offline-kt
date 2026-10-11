// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The content type of every photo the app uploads: the encoder makes a JPEG of each. */
const val PHOTO_CONTENT_TYPE: String = "image/jpeg"

// --8<-- [start:pending-photo-record]
/**
 * A photo picked on this device that is not in the bucket yet, as its JSON record beside its JPEG
 * in [PendingPhotoDirectory]. The device makes the ids, so the photo's Storage path is known before
 * anything is sent.
 *
 * @property objectPath where the file goes in the bucket: `<user id>/<task id>/<photo id>.jpg`.
 * @property failureMessage why the last try failed, as the app says it; null unless [state] is
 *   [PendingPhotoState.Failed].
 * @property attempts how many uploads of it were started.
 * @property canRetry false once the server refused it in a way another try cannot change.
 */
@Serializable
data class PendingPhoto(
    @SerialName("photo_id") val photoId: String,
    @SerialName("task_id") val taskId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("object_path") val objectPath: String,
    @SerialName("picked_at") val pickedAt: Instant,
    val state: PendingPhotoState = PendingPhotoState.Waiting,
    @SerialName("failure_message") val failureMessage: String? = null,
    val attempts: Int = 0,
    @SerialName("can_retry") val canRetry: Boolean = true,
    @SerialName("content_type") val contentType: String = PHOTO_CONTENT_TYPE,
    val width: Int,
    val height: Int,
)

/** Where a pending photo stands; stored in its record by these names. */
@Serializable
enum class PendingPhotoState {
    @SerialName("waiting") Waiting,
    @SerialName("uploading") Uploading,
    @SerialName("failed") Failed,
}

// --8<-- [end:pending-photo-record]

/**
 * What a photo shown before its upload has finished carries: where it stands, and the file on this
 * device the screens draw it from.
 *
 * @property progress the fraction of the file sent so far while it uploads, when known.
 */
data class PendingUpload(
    val state: PendingPhotoState,
    val failureMessage: String?,
    val attempts: Int,
    val canRetry: Boolean,
    val localPath: String,
    val progress: Float? = null,
)

/** This pending photo as the screens show photos, drawn from [localPath] on this device. */
fun PendingPhoto.toTaskPhoto(localPath: String, progress: Float?): TaskPhoto =
    TaskPhoto(
        id = photoId,
        userId = userId,
        taskId = taskId,
        objectPath = objectPath,
        width = width,
        height = height,
        createdAt = pickedAt,
        pendingUpload =
            PendingUpload(state, failureMessage, attempts, canRetry, localPath, progress),
    )

// --8<-- [start:pending-photo-merge]
/**
 * These rows and the [pending] photos as one list in [order], each photo once. A pending photo
 * stands for its row until the upload has finished: until the library's cache holds its file, the
 * device's own copy is the one that shows offline.
 */
fun PhotoListSnapshot.plusPendingPhotos(
    pending: List<TaskPhoto>,
    order: Comparator<TaskPhoto>,
): PhotoListSnapshot {
    if (pending.isEmpty()) return this
    val pendingIds = pending.mapTo(HashSet()) { it.id }
    val stored = value.orEmpty().filterNot { it.id in pendingIds }
    return copy(value = (stored + pending).sortedWith(order))
}

// --8<-- [end:pending-photo-merge]

/** Photos oldest first, as a task's detail lists them. */
val OLDEST_FIRST: Comparator<TaskPhoto> = compareBy { it.createdAt }

/** Photos newest first, as the Photos tab lists them. */
val NEWEST_FIRST: Comparator<TaskPhoto> = compareByDescending { it.createdAt }

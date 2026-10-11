// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

/** The private bucket every photo lives in, as the samples' schema creates it. */
const val PHOTOS_BUCKET: String = "task-photos"

/** The largest file the `task-photos` bucket accepts: 5 MB. */
const val PHOTO_MAX_BYTES: Int = 5 * 1024 * 1024

/** A photo already re-encoded on the device as a JPEG under [PHOTO_MAX_BYTES]. */
class EncodedPhoto(val jpeg: ByteArray, val width: Int, val height: Int)

/** Turns a picked image into an [EncodedPhoto]; the platform decides how. */
fun interface PhotoEncoder {
    /**
     * Reads the image at [contentUri] (a content URI, as text) and re-encodes it.
     *
     * @throws PhotoUnreadableException when the image cannot be read or decoded.
     */
    suspend fun encode(contentUri: String): EncodedPhoto
}

/** A photo's file on this device, where the app can read it without the network. */
data class PhotoFile(val path: String, val fetchedAt: Instant, val sizeBytes: Long)

/**
 * Everything the app can say about one photo's file: Storage's own record of it, its links, and the
 * copy on this device.
 *
 * @property existsInBucket whether Storage has the file now.
 * @property sizeBytes the file's size in the bucket.
 * @property publicUrl the file's public link; the bucket is private, so it answers with an error.
 * @property authenticatedUrl the link a request with the user's token may read.
 * @property cachedFile the copy on this device, or null when there is none.
 */
data class PhotoDetails(
    val existsInBucket: Boolean,
    val sizeBytes: Long,
    val contentType: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val etag: String?,
    val publicUrl: String,
    val authenticatedUrl: String,
    val cachedFile: PhotoFile?,
)

/** One file in the bucket, as Storage lists it. */
data class BucketFile(
    val name: String,
    val sizeBytes: Long?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val lastAccessedAt: Instant?,
)

/** One task's folder in the bucket, `<user id>/<task id>/`, with its files, newest first. */
data class BucketFolder(val taskId: String, val files: List<BucketFile>)

/**
 * One page of the user's task folders in the bucket; [hasMore] when another page follows, from
 * [nextOffset].
 */
data class BucketListing(
    val folders: List<BucketFolder>,
    val hasMore: Boolean,
    val nextOffset: Int,
)

/**
 * The progress of attaching one photo: the upload, then the row. A failure is not a value: the flow
 * fails with it, as an upload does.
 */
sealed interface PhotoUpload {
    /** [fraction] of the file has reached the server, from 0 to 1. */
    data class Uploading(val fraction: Float) : PhotoUpload

    /** The file is on the server and its row is written (or queued). */
    data class Attached(val photo: TaskPhoto) : PhotoUpload
}

/** The request needs the server, and the network is off or unavailable. */
open class NetworkUnavailableException(cause: Throwable? = null) :
    Exception("The network is unavailable", cause)

/** The network is up but the server does not answer, or answers with an error. */
class ServerUnreachableException(cause: Throwable? = null) :
    Exception("The server could not be reached", cause)

/** A photo's file is in neither the device's cache nor the bucket. */
class PhotoNotFoundException(objectPath: String) : Exception("No file at $objectPath")

/** Storage refused a request: an upload, a download or a delete. [status] is its HTTP status. */
class PhotoStorageException(val status: Int, message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** A picked image could not be read or decoded, so there is nothing to upload. */
class PhotoUnreadableException(cause: Throwable) : Exception("The image could not be read", cause)

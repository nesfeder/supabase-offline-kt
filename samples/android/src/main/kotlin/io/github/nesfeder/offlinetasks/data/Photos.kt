// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data

import kotlin.time.Instant

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
 * The progress of attaching one photo: the upload, then the row. A failure is not a value: the flow
 * fails with it, as an upload does.
 */
sealed interface PhotoUpload {
    /** [fraction] of the file has reached the server, from 0 to 1. */
    data class Uploading(val fraction: Float) : PhotoUpload

    /** The file is on the server and its row is written (or queued). */
    data class Attached(val photo: TaskPhoto) : PhotoUpload
}

/** Photo files kept on this device, by file name. It knows nothing about Android. */
interface PhotoFileStore {
    /** Saves [bytes] as [fileName], replacing any file of that name. */
    suspend fun write(fileName: String, bytes: ByteArray): PhotoFile

    /** The file called [fileName], or null when it is not on this device. */
    suspend fun find(fileName: String): PhotoFile?

    /** Deletes [fileName] if it exists. */
    suspend fun delete(fileName: String)

    /** Deletes every file. */
    suspend fun deleteAll()
}

/** The request needs the server, and the network is off or unavailable. */
class NetworkUnavailableException : Exception("The network is unavailable")

/** The network is up but the server does not answer. */
class ServerUnreachableException : Exception("The server could not be reached")

/** A photo's file is in neither the device's cache nor the bucket. */
class PhotoNotFoundException(objectPath: String) : Exception("No file at $objectPath")

/** Storage refused a request: an upload, a download or a delete. [status] is its HTTP status. */
class PhotoStorageException(val status: Int, message: String) : Exception(message)

/** A picked image could not be read or decoded, so there is nothing to upload. */
class PhotoUnreadableException(cause: Throwable) : Exception("The image could not be read", cause)

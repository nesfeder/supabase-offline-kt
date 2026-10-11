// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.storage.FileObject
import io.github.jan.supabase.storage.SortOrder
import io.github.jan.supabase.storage.UploadStatus
import io.github.jan.supabase.storage.uploadAsFlow
import io.github.nesfeder.offlinetasks.data.BucketFile
import io.github.nesfeder.offlinetasks.data.BucketFolder
import io.github.nesfeder.offlinetasks.data.BucketListing
import io.github.nesfeder.offlinetasks.data.PHOTOS_BUCKET
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.PhotoFile
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.ensureLocalFile
import io.github.nesfeder.supabase.offline.ensureLocalUri
import io.github.nesfeder.supabase.offline.localUriIfCached
import io.ktor.http.ContentType
import java.io.File
import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** How many objects one Storage delete request names at most. */
private const val STORAGE_DELETE_BATCH = 100

/** How long a shared link works. */
private val SHARE_LINK_LIFETIME = 1.hours

/** The width of the copy a shared link serves, in pixels. */
private const val SHARE_LINK_WIDTH = 512

/** The JPEG quality of that copy, from 20 to 100. */
private const val SHARE_LINK_QUALITY = 80

/** How many task folders one page of the bucket's listing shows. */
private const val FOLDERS_PER_PAGE = 10

/** The most files listed in one task folder. */
private const val FILES_PER_FOLDER = 100

/**
 * The photos' files in the private `task-photos` bucket, through the library's offline bucket: every
 * Storage operation supabase-kt has, with downloads kept on the device. Failures are the library's
 * and supabase-kt's own; the repository puts them in the app's terms.
 */
internal class TaskPhotoFiles(offline: Offline) {

    // --8<-- [start:bucket]
    /**
     * The offline bucket. An app adds a header here when its gateway wants one on every Storage
     * request, such as a proxy's key; this one names the app, which Storage ignores. The bucket
     * `setHeader` returns still keeps downloads on the device.
     */
    private val bucket: OfflineBucketApi =
        offline.storage.from(PHOTOS_BUCKET).setHeader("X-Client-Name", "offline-tasks-android")
    // --8<-- [end:bucket]

    // --8<-- [start:upload]
    /** Uploads a JPEG to [path], handing [onProgress] the fraction sent so far. */
    suspend fun upload(path: String, jpeg: ByteArray, onProgress: (Float) -> Unit) {
        bucket
            .uploadAsFlow(path, jpeg) {
                upsert = false // a path is never reused, so an existing file is an error
                contentType = ContentType.Image.JPEG // the only type the bucket takes
            }
            .collect { status ->
                if (status is UploadStatus.Progress && status.contentLength > 0) {
                    onProgress(status.totalBytesSend.toFloat() / status.contentLength)
                }
            }
    }
    // --8<-- [end:upload]

    /** Deletes the files at [paths], in batches; a missing file is no error. */
    suspend fun delete(paths: List<String>) {
        for (batch in paths.chunked(STORAGE_DELETE_BATCH)) bucket.delete(batch)
    }

    // --8<-- [start:cached-file]
    /** The copy on this device, or null; never touches the network. */
    suspend fun cachedFile(path: String): PhotoFile? = bucket.cachedFile(path)?.toPhotoFile()

    /**
     * The copy on this device, downloaded into the cache first when there is none. A copy is
     * returned however old it is; the photos never change in place but through Replace photo,
     * which drops the copy.
     */
    suspend fun ensureCachedFile(path: String): PhotoFile =
        bucket.ensureCachedFile(path).toPhotoFile() // isPublic = false: a private bucket
    // --8<-- [end:cached-file]

    // --8<-- [start:android-helpers]
    /** A thumbnail's copy as a `file:` URI when it is on this device; never touches the network. */
    suspend fun uriIfCached(path: String): String? = bucket.localUriIfCached(path)?.toString()

    /** The copy as a `file:` URI for the image loader, downloaded into the cache first if needed. */
    suspend fun uri(path: String): String = bucket.ensureLocalUri(path).toString()

    /** The copy as a [File], downloaded first if needed: Share copies it out of the cache. */
    suspend fun file(path: String): File = bucket.ensureLocalFile(path)
    // --8<-- [end:android-helpers]

    // --8<-- [start:photo-details]
    /** What Storage and this device know about the file at [path]. */
    suspend fun details(path: String): PhotoDetails {
        val exists = bucket.exists(path) // always asks the server
        val info = if (exists) bucket.info(path) else null // size, type, times, etag
        return PhotoDetails(
            existsInBucket = exists,
            sizeBytes = info?.size ?: 0,
            contentType = info?.rawContentType,
            createdAt = info?.createdAt,
            updatedAt = info?.updatedAt,
            etag = info?.etag,
            publicUrl = bucket.publicUrl(path), // a private bucket refuses it
            authenticatedUrl = bucket.authenticatedUrl(path), // needs the user's token
            cachedFile = cachedFile(path), // the device's copy: when fetched, how large
        )
    }
    // --8<-- [end:photo-details]

    // --8<-- [start:share-link]
    /** A link anyone can open for an hour, to a copy 512 pixels wide that Storage renders. */
    suspend fun shareLink(path: String): String =
        bucket.createSignedUrl(path, SHARE_LINK_LIFETIME) {
            transform {
                width = SHARE_LINK_WIDTH // the height follows the photo's shape
                quality = SHARE_LINK_QUALITY
            }
        }
    // --8<-- [end:share-link]

    // --8<-- [start:share-all-links]
    /** Links anyone can open for an hour, one per file, in one request. */
    suspend fun shareLinks(paths: List<String>): List<String> =
        bucket.createSignedUrls(SHARE_LINK_LIFETIME, paths).map { it.signedURL }
    // --8<-- [end:share-all-links]

    // --8<-- [start:replace-photo]
    /** Replaces the file at [path] in place; the cached copy goes, so the next read downloads. */
    suspend fun replace(path: String, jpeg: ByteArray) {
        bucket.update(path, jpeg) {
            upsert = false // replace only: there must be a file there already
            contentType = ContentType.Image.JPEG
        }
    }
    // --8<-- [end:replace-photo]

    /** Moves the file at [from] to [to]; the cached copy at [from] goes. */
    suspend fun move(from: String, to: String) {
        bucket.move(from, to)
    }

    /** Copies the file at [from] to [to], in the same bucket. */
    suspend fun copy(from: String, to: String) {
        bucket.copy(from, to)
    }

    // --8<-- [start:list-bucket]
    /** A page of [userId]'s task folders, from the [offset]th, with each folder's files. */
    suspend fun list(userId: String, offset: Int): BucketListing {
        val folders =
            bucket.list(userId) {
                limit = FOLDERS_PER_PAGE + 1 // one more, to tell whether a page follows
                this.offset = offset
                sortBy("name", SortOrder.ASC) // a folder is named for its task's id
            }
        val page = folders.take(FOLDERS_PER_PAGE)
        return BucketListing(
            folders = page.map { folder -> BucketFolder(folder.name, filesIn("$userId/${folder.name}")) },
            hasMore = folders.size > FOLDERS_PER_PAGE,
            nextOffset = offset + page.size,
        )
    }

    /** The files in [folder], newest first. */
    private suspend fun filesIn(folder: String): List<BucketFile> =
        bucket
            .list(folder) {
                limit = FILES_PER_FOLDER
                sortBy("created_at", SortOrder.DESC)
                search = "" // the start of a name to match; empty matches every file
            }
            .map { it.toBucketFile() }
    // --8<-- [end:list-bucket]

    // --8<-- [start:signed-upload]
    /**
     * Uploads a JPEG to [path] through a signed upload URL: the URL's token, not the session,
     * lets it in, so an app can hand such an upload to code that holds no session.
     */
    suspend fun uploadThroughSignedUrl(path: String, jpeg: ByteArray) {
        val signed = bucket.createSignedUploadUrl(path) // the server checks the policies here
        bucket.uploadToSignedUrl(signed.path, signed.token, jpeg) {
            contentType = ContentType.Image.JPEG
        }
    }
    // --8<-- [end:signed-upload]
}

/** A listed file in the app's terms; its size is in Storage's metadata. */
private fun FileObject.toBucketFile(): BucketFile =
    BucketFile(
        name = name,
        sizeBytes = metadata?.get("size")?.jsonPrimitive?.longOrNull,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessedAt = lastAccessedAt,
    )

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.bridge.internal.OperationRunner
import io.github.nesfeder.supabase.offline.bridge.internal.encodeFileObject
import io.github.nesfeder.supabase.offline.bridge.internal.encodeFileObjects
import io.github.nesfeder.supabase.offline.bridge.internal.encodeSignedUrls
import io.github.nesfeder.supabase.offline.bridge.internal.encodeUploadResponse
import io.github.nesfeder.supabase.offline.bridge.internal.encodeUploadSignedUrl
import io.github.nesfeder.supabase.offline.bridge.internal.imageTransformOf
import io.github.nesfeder.supabase.offline.bridge.internal.listOptionsOf
import io.github.nesfeder.supabase.offline.bridge.internal.toBridge
import io.github.nesfeder.supabase.offline.bridge.internal.toByteArray
import io.github.nesfeder.supabase.offline.bridge.internal.toNSData
import io.github.nesfeder.supabase.offline.bridge.internal.uploadOptionsOf
import kotlin.time.Duration.Companion.seconds
import platform.Foundation.NSData

/**
 * Storage buckets through the offline client: every object operation of the offline bucket, and
 * its file cache. Downloads are kept on the device and served from there offline; uploads,
 * updates, deletes, moves and copies are online only and drop the cached copies they make stale.
 *
 * Every call names its bucket. Options and transforms cross as JSON text, results that are not
 * bytes or files as JSON text too (the formats are in the contract note beside this module); bytes
 * cross as [NSData].
 */
public class BridgeStorage
internal constructor(
    private val offline: () -> Offline,
    private val runner: OperationRunner,
) {
    /**
     * Uploads [data] to [path].
     *
     * @param options `{"contentType", "upsert", "metadata"}`, each optional, or `null`.
     * @param completion the upload response, `{"id", "path", "key"}`, or the failure.
     */
    public fun upload(
        bucket: String,
        path: String,
        data: NSData,
        options: String?,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("upload", completion) {
            val upload = uploadOptionsOf(options)
            val response = bucketOf(bucket).upload(path, data.toByteArray()) { upload.applyTo(this) }
            encodeUploadResponse(response)
        }

    /** Replaces the object at [path] with [data]; [options] and the answer as for [upload]. */
    public fun update(
        bucket: String,
        path: String,
        data: NSData,
        options: String?,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("update", completion) {
            val upload = uploadOptionsOf(options)
            val response = bucketOf(bucket).update(path, data.toByteArray()) { upload.applyTo(this) }
            encodeUploadResponse(response)
        }

    /**
     * Uploads [data] to [path] through a signed upload URL's [token]; [options] and the answer as
     * for [upload].
     */
    public fun uploadToSignedUrl(
        bucket: String,
        path: String,
        token: String,
        data: NSData,
        options: String?,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("uploadToSignedUrl", completion) {
            val upload = uploadOptionsOf(options)
            val response =
                bucketOf(bucket).uploadToSignedUrl(path, token, data.toByteArray()) {
                    upload.applyTo(this)
                }
            encodeUploadResponse(response)
        }

    /** Deletes the objects at [paths]. */
    public fun delete(
        bucket: String,
        paths: List<String>,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("delete", completion) { bucketOf(bucket).delete(paths) }

    /** Moves the object at [from] to [to], in this bucket or [destinationBucket]. */
    public fun moveObject(
        bucket: String,
        from: String,
        to: String,
        destinationBucket: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("moveObject", completion) {
            bucketOf(bucket).move(from, to, destinationBucket)
        }

    /** Copies the object at [from] to [to], in this bucket or [destinationBucket]. */
    public fun copyObject(
        bucket: String,
        from: String,
        to: String,
        destinationBucket: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("copyObject", completion) {
            bucketOf(bucket).copy(from, to, destinationBucket)
        }

    /**
     * A URL that uploads to [path] without a session.
     *
     * @param completion `{"url", "path", "token"}`, or the failure.
     */
    public fun createSignedUploadUrl(
        bucket: String,
        path: String,
        upsert: Boolean,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("createSignedUploadUrl", completion) {
            encodeUploadSignedUrl(bucketOf(bucket).createSignedUploadUrl(path, upsert))
        }

    /**
     * A URL that downloads [path] for [expiresInSeconds].
     *
     * @param imageTransform an image transform, `{"width", "height", "resize", "quality",
     *   "format"}`, or `null`.
     */
    public fun createSignedUrl(
        bucket: String,
        path: String,
        expiresInSeconds: Long,
        imageTransform: String?,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("createSignedUrl", completion) {
            val transformation = imageTransform?.let(::imageTransformOf)
            bucketOf(bucket).createSignedUrl(path, expiresInSeconds.seconds) {
                transformation?.let { transform { it.applyTo(this) } }
            }
        }

    /**
     * URLs that download [paths] for [expiresInSeconds].
     *
     * @param completion a JSON array of `{"error", "signedURL", "path"}`, or the failure.
     */
    public fun createSignedUrls(
        bucket: String,
        paths: List<String>,
        expiresInSeconds: Long,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("createSignedUrls", completion) {
            encodeSignedUrls(bucketOf(bucket).createSignedUrls(expiresInSeconds.seconds, paths))
        }

    /**
     * Downloads the object at [path]: from the network first, cached on success, and from the
     * cache when the network fails.
     *
     * @param isPublic whether to download through the public URL rather than the authenticated one.
     * @param imageTransform an image transform as for [createSignedUrl], or `null`; a
     *   transformed download is cached apart from the original.
     */
    public fun download(
        bucket: String,
        path: String,
        isPublic: Boolean,
        imageTransform: String?,
        completion: (NSData?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("download", completion) {
            val transformation = imageTransform?.let(::imageTransformOf)
            val api = bucketOf(bucket)
            val bytes =
                if (isPublic) {
                    api.downloadPublic(path) {
                        transformation?.let { transform { it.applyTo(this) } }
                    }
                } else {
                    api.downloadAuthenticated(path) {
                        transformation?.let { transform { it.applyTo(this) } }
                    }
                }
            bytes.toNSData()
        }

    /**
     * The objects under [prefix].
     *
     * @param options `{"limit", "offset", "search", "sortBy": {"column", "order"}}`, each
     *   optional, or `null`.
     * @param completion a JSON array of supabase-kt's file objects, or the failure.
     */
    public fun list(
        bucket: String,
        prefix: String,
        options: String?,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("list", completion) {
            val listing = listOptionsOf(options)
            encodeFileObjects(bucketOf(bucket).list(prefix) { listing.applyTo(this) })
        }

    /** The object at [path]'s details, as supabase-kt's file object in JSON. */
    public fun info(
        bucket: String,
        path: String,
        completion: (String?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("info", completion) { encodeFileObject(bucketOf(bucket).info(path)) }

    /** Whether an object exists at [path]. */
    public fun exists(
        bucket: String,
        path: String,
        completion: (Boolean?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("exists", completion) { bucketOf(bucket).exists(path) }

    /** The public URL of [path]; builds a URL and sends nothing. */
    public fun publicUrl(bucket: String, path: String): BridgeStringResult =
        runner.answerNow(
            "publicUrl",
            onFailure = { error -> BridgeStringResult(null, error) },
        ) {
            BridgeStringResult(bucketOf(bucket).publicUrl(path), null)
        }

    /** The authenticated URL of [path]; builds a URL and sends nothing. */
    public fun authenticatedUrl(bucket: String, path: String): BridgeStringResult =
        runner.answerNow(
            "authenticatedUrl",
            onFailure = { error -> BridgeStringResult(null, error) },
        ) {
            BridgeStringResult(bucketOf(bucket).authenticatedUrl(path), null)
        }

    /**
     * The cached copy of [path], or `null` with no error when none is cached; never touches the
     * network.
     */
    public fun cachedFile(
        bucket: String,
        path: String,
        completion: (BridgeCachedFile?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("cachedFile", completion) { bucketOf(bucket).cachedFile(path)?.toBridge() }

    /** The cached copy of [path], downloading it into the cache first when none is cached. */
    public fun ensureCachedFile(
        bucket: String,
        path: String,
        isPublic: Boolean,
        completion: (BridgeCachedFile?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("ensureCachedFile", completion) {
            bucketOf(bucket).ensureCachedFile(path, isPublic).toBridge()
        }

    /** The offline bucket [bucket]. */
    private fun bucketOf(bucket: String): OfflineBucketApi = offline().storage.from(bucket)
}

/**
 * A Storage object held in the offline file cache: read it or open it, but never move or write it.
 *
 * @property path the cached copy's absolute path on this device.
 * @property fetchedAtEpochMillis when it was downloaded, in milliseconds since 1970.
 * @property sizeBytes its size on disk.
 */
public class BridgeCachedFile
internal constructor(
    public val path: String,
    public val fetchedAtEpochMillis: Long,
    public val sizeBytes: Long,
)

/** A synchronous answer: a value or an error, never both. */
public class BridgeStringResult
internal constructor(public val value: String?, public val error: BridgeError?)

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.bridge.internal.OperationRunner
import io.github.nesfeder.supabase.offline.bridge.internal.UnsupportedDescriptionException
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import platform.Foundation.NSData

/**
 * Storage buckets through the offline client: every object operation of the offline bucket, and its
 * file cache. Downloads are kept on the device and served from there offline; uploads, updates,
 * deletes, moves and copies are online only and drop the cached copies they make stale, but for the
 * upload that takes a strategy, which can wait in the mutation queue with the row writes.
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
            val response =
                bucketOf(bucket).upload(path, data.toByteArray()) { upload.applyTo(this) }
            encodeUploadResponse(response)
        }

    /**
     * Uploads [data] to [path] under [mutationStrategy], as a write is made: `queueIfOffline` lets
     * it wait in the mutation queue, its bytes kept on the device, and be sent in the order it was
     * made among the user's writes; `onlineOnly` sends it now and keeps nothing.
     *
     * @param mutationStrategy `queueIfOffline` or `onlineOnly`; another name fails with
     *   [BridgeErrorKind.UNSUPPORTED].
     * @param options as for the [upload] without a strategy.
     * @param completion the outcome (applied, with Storage's response, or queued), or the failure.
     */
    public fun upload(
        bucket: String,
        path: String,
        data: NSData,
        mutationStrategy: String,
        options: String?,
        completion: (BridgeUploadResult?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("queueableUpload", completion) {
            val strategy = mutationStrategyNamed(mutationStrategy)
            val upload = uploadOptionsOf(options)
            bucketOf(bucket)
                .upload(path, data.toByteArray(), strategy) { upload.applyTo(this) }
                .toBridge()
        }

    /** The current user's queued uploads, in every bucket, in the order they will be sent. */
    public fun pendingUploads(
        completion: (List<BridgePendingUpload>?, BridgeError?) -> Unit
    ): BridgeCancellable =
        runner.runOnce("pendingUploads", completion) {
            offline().storage.pendingUploads().first().map { it.toBridge() }
        }

    /** [pendingUploads] as the queue changes; [onCompletion] fires once, when it ends. */
    public fun observePendingUploads(
        onUploads: (List<BridgePendingUpload>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observePendingUploads", onUploads, onCompletion) {
            offline().storage.pendingUploads().map { uploads -> uploads.map { it.toBridge() } }
        }

    /** The current user's queued uploads Storage refused for good on replay, oldest first. */
    public fun rejectedUploads(
        completion: (List<BridgeRejectedUpload>?, BridgeError?) -> Unit
    ): BridgeCancellable =
        runner.runOnce("rejectedUploads", completion) {
            offline().storage.rejectedUploadList().map { it.toBridge() }
        }

    /** [rejectedUploads] as the list changes; [onCompletion] fires once, when it ends. */
    public fun observeRejectedUploads(
        onUploads: (List<BridgeRejectedUpload>) -> Unit,
        onCompletion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.subscribe("observeRejectedUploads", onUploads, onCompletion) {
            offline().storage.rejectedUploads().map { uploads -> uploads.map { it.toBridge() } }
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
            val response =
                bucketOf(bucket).update(path, data.toByteArray()) { upload.applyTo(this) }
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
     * Downloads the object at [path]: from the network first, cached on success, and from the cache
     * when the network fails.
     *
     * @param isPublic whether to download through the public URL rather than the authenticated one.
     * @param imageTransform an image transform as for [createSignedUrl], or `null`; a transformed
     *   download is cached apart from the original.
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
     * @param options `{"limit", "offset", "search", "sortBy": {"column", "order"}}`, each optional,
     *   or `null`.
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
    ): BridgeCancellable = runner.runOnce("exists", completion) { bucketOf(bucket).exists(path) }

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
     *
     * @param imageTransform an image transform as for [createSignedUrl], whose cached copy a
     *   download with it keeps, or `null` for the untransformed object's.
     */
    public fun cachedFile(
        bucket: String,
        path: String,
        imageTransform: String?,
        completion: (BridgeCachedFile?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("cachedFile", completion) {
            val transformation = imageTransform?.let(::imageTransformOf)
            val api = bucketOf(bucket)
            val file =
                if (transformation == null) api.cachedFile(path)
                else api.cachedFile(path) { transformation.applyTo(this) }
            file?.toBridge()
        }

    /**
     * Deletes cached copies of [path] from the device; sends nothing.
     *
     * @param imageTransform an image transform as for [createSignedUrl], whose cached copy alone
     *   goes, or `null` for every copy of the object, untransformed and transformed.
     */
    public fun evictCachedFile(
        bucket: String,
        path: String,
        imageTransform: String?,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("evictCachedFile", completion) {
            val transformation = imageTransform?.let(::imageTransformOf)
            val api = bucketOf(bucket)
            if (transformation == null) api.evictCachedFile(path)
            else api.evictCachedFile(path) { transformation.applyTo(this) }
        }

    /**
     * Deletes every downloaded file the library keeps on this device; the mutation queue and the
     * cached rows stay.
     */
    public fun clearFileCache(completion: (BridgeError?) -> Unit): BridgeCancellable =
        runner.runToCompletion("clearFileCache", completion) { offline().storage.clearFileCache() }

    /** Pins [path] on the device, so the cache never evicts its copies; downloads nothing. */
    public fun pinCachedFile(
        bucket: String,
        path: String,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("pinCachedFile", completion) { bucketOf(bucket).pinCachedFile(path) }

    /** Removes the pin on [path], if it has one; its cached copies stay. */
    public fun unpinCachedFile(
        bucket: String,
        path: String,
        completion: (BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runToCompletion("unpinCachedFile", completion) {
            bucketOf(bucket).unpinCachedFile(path)
        }

    /** Whether [path] is pinned on the device; never touches the network. */
    public fun isCachedFilePinned(
        bucket: String,
        path: String,
        completion: (Boolean?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("isCachedFilePinned", completion) {
            bucketOf(bucket).isCachedFilePinned(path)
        }

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

    /**
     * [ensureCachedFile], handing [onProgress] the bytes received and the object's size, `null`
     * when the server gave none, while the download runs.
     */
    public fun ensureCachedFileWithProgress(
        bucket: String,
        path: String,
        isPublic: Boolean,
        onProgress: (Long, Long?) -> Unit,
        completion: (BridgeCachedFile?, BridgeError?) -> Unit,
    ): BridgeCancellable =
        runner.runOnce("ensureCachedFileWithProgress", completion) {
            bucketOf(bucket)
                .ensureCachedFile(path, isPublic) { progress ->
                    try {
                        onProgress(progress.bytesReceived, progress.totalBytes)
                    } catch (failure: Throwable) {
                        runner.report(failure, "A progress handler of ensureCachedFile")
                    }
                }
                .toBridge()
        }

    /** The offline bucket [bucket]. */
    private fun bucketOf(bucket: String): OfflineBucketApi = offline().storage.from(bucket)
}

/** The strategy [name] names: `queueIfOffline` or `onlineOnly`, as a write description's. */
private fun mutationStrategyNamed(name: String): MutationStrategy =
    when (name) {
        "queueIfOffline" -> MutationStrategy.QueueIfOffline
        "onlineOnly" -> MutationStrategy.OnlineOnly
        else ->
            throw UnsupportedDescriptionException(
                "mutationStrategy: \"$name\" is not a strategy this build offers"
            )
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

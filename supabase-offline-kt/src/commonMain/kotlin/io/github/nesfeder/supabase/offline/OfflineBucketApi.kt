// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.ImageTransformation
import io.github.jan.supabase.storage.UploadOptionBuilder

/**
 * One Storage bucket seen through the offline client: every [BucketApi] operation, plus a file
 * cache that keeps downloaded objects on the device.
 *
 * Get one with `offline.storage.from(bucketId)` ([OfflineStorage.from]).
 *
 * Everything it inherits from [BucketApi] keeps supabase-kt's declaration, is documented in
 * [supabase-kt's reference](https://supabase-community.github.io/supabase-kt/), and, except
 * `purgeCache` (supabase-kt 3.8), works on this bucket with the behavior below: uploads, updates
 * and uploads to a signed URL, resumable uploads through [resumable], downloads, deletes, moves,
 * copies, signed, public, authenticated and render URLs, `list`, `info`, `exists`,
 * `changePublicStatusTo`, and supabase-kt's `…AsFlow` extensions, which call them.
 *
 * **Downloads** go to the network first. A successful download is written to the cache (a failure
 * to write the copy is logged and never fails the download); when the network fails, the cached
 * copy is served instead, and when the server says the object no longer exists, the cached copy is
 * dropped and the error is rethrown. A download with an image transform is cached separately from
 * the original, per transform. Channel downloads stream through a file on disk and are never held
 * in memory whole. A download canceled while it creates, commits or opens that file returns once
 * the file operation is done and the file is deleted: behind a clear or an eviction holding the
 * cache, that can take as long as they do. No partial file is left behind.
 *
 * **Writes** — uploads, updates, deletes, moves and copies — are online-only and never queued, but
 * for the [upload] that takes a [MutationStrategy], which can wait in the mutation queue with the
 * row writes around it. They are tried whatever the connectivity monitor says, and offline fail
 * with supabase-kt's own connectivity error; while network access is off ([Offline.disableNetwork])
 * they throw [NetworkDisabledException] without being sent. A write that reaches the server drops
 * any cached copy it made stale, so a later read never serves the old bytes: a download of the
 * object still on the wire when the write lands is handed to its caller but never becomes the
 * cached copy. A resumable upload started or continued through [resumable] drops them once it
 * finishes.
 *
 * **Object paths that cannot be cached** — a leading `/`, an empty or `.`/`..` segment, a `\`, a
 * control character, and with supabase-kt below 3.8 a `%`, `?` or `#`, which it puts in the URL
 * unencoded — still download and write normally; they are never mapped onto a file. A `%` there
 * that does not begin a percent-escape throws [IllegalArgumentException] before anything is sent.
 *
 * The cache is bounded by [Offline.Config.fileCacheSizeBytes], evicting the least recently used
 * files first; [evictCachedFile] deletes one object's copies, and [OfflineStorage.clearFileCache]
 * and clearing local data empty it.
 *
 * The members inherited from [BucketApi] keep supabase-kt's declarations, so they declare no
 * `@Throws`: in a framework exported to Swift, one that throws ends the app unless the caller's own
 * bridge catches around it. [cachedFile], [ensureCachedFile] and [evictCachedFile] declare it.
 *
 * Only the library implements this interface; [OfflineInternal] marks it so.
 */
@SubclassOptInRequired(OfflineInternal::class)
public interface OfflineBucketApi : BucketApi {

    /**
     * This bucket, sending the header [name] with [value] on every request, and still caching.
     *
     * @param name the header name.
     * @param value the header value.
     * @return the offline bucket with the header set.
     */
    override fun setHeader(name: String, value: String): OfflineBucketApi

    /**
     * Uploads [data] to [path] as supabase-kt's `upload` does, under [mutationStrategy], as a row
     * write is made: [MutationStrategy.QueueIfOffline] lets it wait in the mutation queue, in the
     * order it was made among the current user's writes, so a row written after it that names the
     * file is sent after the file.
     *
     * Under [MutationStrategy.QueueIfOffline] the bytes are first kept on the device, beside the
     * file cache, and the upload queued; it is sent at once when nothing of the user is pending
     * ahead of it, and the result is [UploadOutcome.Applied] with Storage's response. Otherwise it
     * waits and the result is [UploadOutcome.Queued]: no connection or no answer, an answer that
     * says the request did not finish (a `408`, `429`, `502`, `503`, `504`, `520`, `522` or `524`),
     * a token Storage refused, earlier writes still pending, network access turned off, or a
     * session still restoring. A later pass sends it, and a refusal is kept on the rejected list
     * ([OfflineStorage.rejectedUploads]) with its bytes, instead of being thrown. Sent at once and
     * refused, or failing with another `5xx`, it is thrown and does not stay queued. While it waits
     * it is listed by [OfflineStorage.pendingUploads], [cachedFile] and [ensureCachedFile] return
     * its bytes on the device, and [Offline.discardPendingMutation] drops it. Once Storage holds
     * the file, its bytes become the object's cached copy.
     *
     * Some uploads cannot be queued faithfully and are sent at once instead, as with
     * [MutationStrategy.OnlineOnly]: one whose [options] add an `httpOverride`, one on a bucket
     * with a header set by [setHeader], one whose user cannot be read from the stored session, one
     * whose bytes cannot be written to the device, and one that was on its way into the queue while
     * local data was cleared. When one of them cannot be sent for want of a connection, it throws
     * [MutationNotQueueableException] with that failure as its cause. A queued upload keeps its
     * options' `upsert`, `contentType` and `userMetadata`.
     *
     * [MutationStrategy.OnlineOnly] is supabase-kt's `upload`, its response wrapped in
     * [UploadOutcome.Applied]: sent now, thrown when it cannot be sent, and nothing kept.
     *
     * @param path the object path within this bucket.
     * @param data the file's bytes; not empty.
     * @param mutationStrategy whether the upload may wait in the mutation queue.
     * @param options `upsert`, `contentType` and `userMetadata`, as for supabase-kt's `upload`.
     * @return [UploadOutcome.Applied] with Storage's response, or [UploadOutcome.Queued].
     * @throws IllegalArgumentException when [data] is empty, or [path] holds a `%` that does not
     *   begin a percent-escape (see [OfflineBucketApi]); nothing is sent or kept.
     * @throws MutationNotQueueableException when an upload the queue cannot hold cannot be sent for
     *   want of a connection.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun upload(
        path: String,
        data: ByteArray,
        mutationStrategy: MutationStrategy,
        options: UploadOptionBuilder.() -> Unit = {},
    ): UploadOutcome

    /**
     * The cached copy of the object at [path], or `null` when none is cached. Never touches the
     * network. Only an untransformed download, [ensureCachedFile] or a queued [upload] Storage has
     * stored caches the copy this returns; a transformed download's copy is found with a transform.
     *
     * While the current user has an upload of the object waiting in the mutation queue, it returns
     * that upload's bytes on the device instead, the newest when there are several: its
     * [CachedFile.fetchedAt] is when the upload was queued. The file is deleted once the upload is
     * sent, discarded or cleared. A refused upload's bytes are never answered here, since Storage
     * may hold other bytes at the path: [RejectedUpload.localPath] names them.
     *
     * @param path the object path within this bucket.
     * @return the cached file, or `null`.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun cachedFile(path: String): CachedFile?

    /**
     * The cached copy of one image transform of the object at [path], or `null` when none is
     * cached: the copy a download with the same `transform { … }` caches, and is answered from
     * offline. Never touches the network. Each transform is a copy of its own, so a thumbnail can
     * be handed to an image loader as a file. A transform that sets nothing is the untransformed
     * object, as it is for a download.
     *
     * @param path the object path within this bucket.
     * @param transform the image transform, set as in a download's `transform { … }`.
     * @return the cached file, or `null`.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun cachedFile(
        path: String,
        transform: ImageTransformation.() -> Unit,
    ): CachedFile?

    /**
     * The cached copy of the object at [path], downloading it into the cache first when none is
     * cached. The download streams to disk, and concurrent calls for one object share it. A cached
     * copy is returned as it is, however old; check [CachedFile.fetchedAt] against your own
     * freshness rule. An upload of the object still queued for the current user is returned as
     * [cachedFile] returns it, with no request; a refused one is not, and the object is downloaded
     * as when nothing is cached. Canceled mid-download, it returns once its partial file is deleted
     * (see [OfflineBucketApi] on how long that can take), unless another call is still waiting for
     * the download, which then goes on for that call.
     *
     * @param path the object path within this bucket.
     * @param isPublic whether to download through the public URL rather than the authenticated one.
     * @return the cached file.
     * @throws IllegalArgumentException when [path] cannot be cached (see [OfflineBucketApi]).
     * @throws IllegalStateException when local data or the file cache was cleared while the file
     *   was downloading; when the object was written through this client, or its copies evicted,
     *   while it was downloading, twice in a row (the first time, it is downloaded again); or when
     *   a header set on this bucket made the request fetch only part of the object, which cannot be
     *   the cached copy.
     * @throws Exception the download's own failure when nothing is cached: supabase-kt's
     *   connectivity error offline, or its REST error from the server.
     */
    @Throws(Throwable::class)
    public suspend fun ensureCachedFile(path: String, isPublic: Boolean = false): CachedFile

    /**
     * Deletes every cached copy of the object at [path] from this device: the untransformed copy
     * and each image transform. Nothing is sent, and the object stays on the server; the next
     * download fetches it again. Other objects' copies stay.
     *
     * A download of the object still running is handed to its caller but not cached, as when the
     * object is written through this bucket: an [ensureCachedFile] running meanwhile downloads it
     * once more. A path that cannot be cached (see [OfflineBucketApi]) has no copy to delete.
     *
     * @param path the object path within this bucket.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun evictCachedFile(path: String)

    /**
     * Deletes the cached copy of one image transform of the object at [path] from this device: the
     * copy [cachedFile] with the same [transform] returns. The untransformed copy and the other
     * transforms stay; a transform that sets nothing is the untransformed copy, which goes alone.
     * Nothing is sent. A download of that transform still running is handed to its caller but not
     * cached.
     *
     * @param path the object path within this bucket.
     * @param transform the image transform, set as in a download's `transform { … }`.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun evictCachedFile(path: String, transform: ImageTransformation.() -> Unit)
}

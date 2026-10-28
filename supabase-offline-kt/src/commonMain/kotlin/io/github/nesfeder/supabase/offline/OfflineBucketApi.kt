// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.ImageTransformation

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
 * dropped and the error is rethrown. A copy is kept with the `ETag` the server sent it under, and
 * the next download of the object asks whether it changed (`If-None-Match`): a "not modified"
 * answers the download from the copy and moves its [CachedFile.fetchedAt] to now, unless the copy
 * was written over, evicted or lost since the download started, when the object is fetched whole. A
 * download with an image transform is cached separately from the original, per transform. Channel
 * downloads stream through a file on disk and are never held in memory whole. A download canceled
 * while it creates, commits or opens that file returns once the file operation is done and the file
 * is deleted: behind a clear or an eviction holding the cache, that can take as long as they do. No
 * partial file is left behind.
 *
 * **Writes** — uploads, updates, deletes, moves and copies — are online-only and never queued. They
 * are tried whatever the connectivity monitor says, and offline fail with supabase-kt's own
 * connectivity error; while network access is off ([Offline.disableNetwork]) they throw
 * [NetworkDisabledException] without being sent. A write that reaches the server drops any cached
 * copy it made stale, so a later read never serves the old bytes: a download of the object still on
 * the wire when the write lands is handed to its caller but never becomes the cached copy. A
 * resumable upload started or continued through [resumable] drops them once it finishes.
 *
 * **Object paths that cannot be cached** — a leading `/`, an empty or `.`/`..` segment, a `\`, a
 * control character, and with supabase-kt below 3.8 a `%`, `?` or `#`, which it puts in the URL
 * unencoded — still download and write normally; they are never mapped onto a file. A `%` there
 * that does not begin a percent-escape throws [IllegalArgumentException] before anything is sent.
 *
 * The cache is bounded by [Offline.Config.fileCacheSizeBytes], evicting the least recently used
 * files first and never those of an object pinned with [pinCachedFile]; [evictCachedFile] deletes
 * one object's copies, and [OfflineStorage.clearFileCache] and clearing local data empty it.
 *
 * The members inherited from [BucketApi] keep supabase-kt's declarations, so they declare no
 * `@Throws`: in a framework exported to Swift, one that throws ends the app unless the caller's own
 * bridge catches around it. [cachedFile], [ensureCachedFile], [evictCachedFile], [pinCachedFile],
 * [unpinCachedFile] and [isCachedFilePinned] declare it.
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
     * The cached copy of the object at [path], or `null` when none is cached. Never touches the
     * network. Only an untransformed download or [ensureCachedFile] caches the copy this returns; a
     * transformed download's copy is found with a transform.
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
     * copy is returned as it is, however old, without asking the server; check
     * [CachedFile.fetchedAt] against your own freshness rule, and download the object to refresh
     * it. Canceled mid-download, it returns once its partial file is deleted (see
     * [OfflineBucketApi] on how long that can take), unless another call is still waiting for the
     * download, which then goes on for that call.
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
     * download fetches it again. Other objects' copies stay. A pin on the object ([pinCachedFile])
     * goes too.
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
     * Nothing is sent, and a pin on the object stays. A download of that transform still running is
     * handed to its caller but not cached.
     *
     * @param path the object path within this bucket.
     * @param transform the image transform, set as in a download's `transform { … }`.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class)
    public suspend fun evictCachedFile(path: String, transform: ImageTransformation.() -> Unit)

    /**
     * Pins the object at [path] on this device: the cache never evicts its copies, the
     * untransformed one and each image transform's, cached now or later, however long ago they were
     * used. Nothing is sent and nothing is downloaded; call [ensureCachedFile] to have a copy now.
     * Pinning a pinned object changes nothing.
     *
     * The pin outlives a restart. It stays until [unpinCachedFile], [evictCachedFile] for the
     * object, a delete of the object through this bucket, [OfflineStorage.clearFileCache], or
     * clearing local data; the file cache emptied for another user takes it too. A write to the
     * object drops its copies, as for any object, and keeps the pin, and a move through this bucket
     * takes the pin to the new path, or drops it when the new path cannot be cached.
     *
     * Pinned copies count toward [Offline.Config.fileCacheSizeBytes]. When they alone fill it, the
     * cache stays over its bound, and every other file is evicted once it is no longer in use.
     * While the cache still belongs to the user before, until a sign-in settles, the pin is not
     * kept, as a download then is not.
     *
     * @param path the object path within this bucket.
     * @throws IllegalArgumentException when [path] cannot be cached (see [OfflineBucketApi]).
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun pinCachedFile(path: String)

    /**
     * Removes the pin [pinCachedFile] put on the object at [path], if it has one. Its cached copies
     * stay, and the cache evicts them as it does any other file's once it is over its bound.
     * Nothing is sent.
     *
     * @param path the object path within this bucket.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun unpinCachedFile(path: String)

    /**
     * Whether the object at [path] is pinned on this device ([pinCachedFile]), whether or not a
     * copy of it is cached now. Never touches the network.
     *
     * @param path the object path within this bucket.
     * @return `true` while the object is pinned.
     * @throws IllegalStateException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun isCachedFilePinned(path: String): Boolean
}

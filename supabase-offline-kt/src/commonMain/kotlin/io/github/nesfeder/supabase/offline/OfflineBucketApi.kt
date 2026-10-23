// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi

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
 * files first; clearing local data empties it.
 *
 * The members inherited from [BucketApi] keep supabase-kt's declarations, so they declare no
 * `@Throws`: in a framework exported to Swift, one that throws ends the app unless the caller's own
 * bridge catches around it. [cachedFile] and [ensureCachedFile] declare it.
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
     * network. Only an untransformed download or [ensureCachedFile] caches the copy this returns.
     *
     * @param path the object path within this bucket.
     * @return the cached file, or `null`.
     * @throws OfflineClientClosedException when the client is closed.
     */
    @Throws(Throwable::class) public suspend fun cachedFile(path: String): CachedFile?

    /**
     * The cached copy of the object at [path], downloading it into the cache first when none is
     * cached. The download streams to disk. A cached copy is returned as it is, however old; check
     * [CachedFile.fetchedAt] against your own freshness rule. Canceled mid-download, it returns
     * once its partial file is deleted (see [OfflineBucketApi] on how long that can take).
     *
     * @param path the object path within this bucket.
     * @param isPublic whether to download through the public URL rather than the authenticated one.
     * @return the cached file.
     * @throws IllegalArgumentException when [path] cannot be cached (see [OfflineBucketApi]).
     * @throws IllegalStateException when local data was cleared while the file was downloading;
     *   when the object was written through this client while it was downloading, twice in a row
     *   (the first time, it is downloaded again); or when a header set on this bucket made the
     *   request fetch only part of the object, which cannot be the cached copy.
     * @throws Exception the download's own failure when nothing is cached: supabase-kt's
     *   connectivity error offline, or its REST error from the server.
     */
    @Throws(Throwable::class)
    public suspend fun ensureCachedFile(path: String, isPublic: Boolean = false): CachedFile
}

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.storage.BucketApi

/**
 * One Storage bucket seen through the offline client: every [BucketApi] operation, plus a file cache
 * that keeps downloaded objects on the device.
 *
 * Get one with [SupabaseOfflineClient.storage].
 *
 * **Downloads** go to the network first. A successful download is written to the cache (a failure
 * to write the copy is logged and never fails the download); when the network fails, the cached
 * copy is served instead, and when the server says the object no longer exists, the cached copy is
 * dropped and the error is rethrown. A download with an image transform is cached separately from
 * the original, per transform. Channel downloads stream through a file on disk and are never held
 * in memory whole.
 *
 * **Writes** — uploads, updates, deletes, moves and copies — are online-only: offline they fail
 * with supabase-kt's own connectivity error, and nothing is queued. A write that reaches the server
 * drops any cached copy it made stale, so a later read never serves the old bytes.
 *
 * **Object paths that cannot be cached** — a leading `/`, an empty or `.`/`..` segment, a `\`, or a
 * control character — still download and write normally; they are never mapped onto a file.
 *
 * The cache is bounded by `SupabaseOfflineConfig.fileCacheSizeBytes`, evicting the least recently
 * used files first; clearing local data empties it.
 */
public interface OfflineBucketApi : BucketApi {

    /**
     * The cached copy of the object at [path], or `null` when none is cached. Never touches the
     * network. Only an untransformed download or [ensureCachedFile] caches the copy this returns.
     *
     * @param path the object path within this bucket.
     * @return the cached file, or `null`.
     */
    @Throws(Exception::class)
    public suspend fun cachedFile(path: String): CachedFile?

    /**
     * The cached copy of the object at [path], downloading it into the cache first when none is
     * cached. The download streams to disk. A cached copy is returned as it is, however old; check
     * [CachedFile.fetchedAt] against your own freshness rule.
     *
     * @param path the object path within this bucket.
     * @param isPublic whether to download through the public URL rather than the authenticated one.
     * @return the cached file.
     * @throws IllegalArgumentException when [path] cannot be cached (see [OfflineBucketApi]).
     * @throws IllegalStateException when local data was cleared while the file was downloading.
     * @throws Exception the download's own failure when nothing is cached: supabase-kt's
     *   connectivity error offline, or its REST error from the server.
     */
    @Throws(Exception::class)
    public suspend fun ensureCachedFile(path: String, isPublic: Boolean = false): CachedFile
}

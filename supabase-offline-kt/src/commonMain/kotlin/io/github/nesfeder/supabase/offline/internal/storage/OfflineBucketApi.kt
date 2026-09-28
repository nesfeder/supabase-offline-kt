@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.util.httpStatusOrNull
import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.ExperimentalTime

/** HTTP status returned by the server for an object that no longer exists. */
private const val HTTP_NOT_FOUND = 404

/**
 * Offline-aware [BucketApi] that:
 *  - Delegates to the real BucketApi for all operations
 *  - Caches successful downloads as files on disk (Okio) under <base>/storage/<bucket>/<path>
 *  - Tracks cached files in Room (StorageDao)
 *
 * All four download overrides funnel through [downloadWithCache], which:
 *  - always re-throws [kotlin.coroutines.cancellation.CancellationException] instead of serving
 *    cache (structured concurrency), and
 *  - evicts the disk cache on a server "object missing" response rather than serving a file that
 *    has since been deleted server-side.
 *
 * Uploads/resumables DO NOT auto-populate the read cache; first successful download fills it.
 */
internal class OfflineBucketApi(
    private val delegate: BucketApi,
    private val offlineStore: OfflineStore,
    private val sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    private val baseDirectoryProvider: () -> Path
) : BucketApi by delegate {

    private val baseDirectory: Path by lazy { baseDirectoryProvider() }

    // ------------------------- DOWNLOADS (cache) -------------------------

    override suspend fun downloadAuthenticated(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray =
        downloadWithCache(path) { delegate.downloadAuthenticated(path, options) }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        channel.writeFully(downloadWithCache(path) { delegate.downloadAuthenticated(path, options) })
    }

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray =
        downloadWithCache(path) { delegate.downloadPublic(path, options) }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        channel.writeFully(downloadWithCache(path) { delegate.downloadPublic(path, options) })
    }

    /**
     * Shared download-with-disk-cache path for all four [BucketApi] download overrides.
     *
     * On success the fetched bytes are written to the disk cache (and tracked in Room) and
     * returned. On failure:
     *  - **Cancellation** is always re-thrown first (via [ensureActive]) so a cancelled coroutine
     *    is never served cache bytes — structured concurrency is preserved.
     *  - A server **"object missing"** failure (see [indicatesServerObjectMissing]) evicts the
     *    now-stale cache (disk file + Room row) and re-throws, so an object deleted server-side
     *    stops being served from the local cache forever.
     *  - Any **other** failure (transport / connectivity / server error) falls back to the disk
     *    cache when present, otherwise re-throws — preserving the pre-existing offline behavior.
     *
     * @param path The object path within [bucketId].
     * @param fetch The specific suspending `delegate.download*` network call to attempt.
     * @return The freshly-fetched bytes, or cached bytes on a tolerated transport failure.
     */
    private suspend fun downloadWithCache(
        path: String,
        fetch: suspend () -> ByteArray
    ): ByteArray {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        return try {
            val bytes = fetch()
            cacheBytes(path, bytes)
            bytes
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive() // rethrows if the coroutine was cancelled
            if (error.indicatesServerObjectMissing()) {
                evictCache(path)
                sdkLogger.warning {
                    "Storage download $bucketId/$path not found on server, evicted stale cache"
                }
                throw error
            }
            val cached = readBytesOrNull(localPath(path))
            if (cached != null) {
                sdkLogger.warning { "Storage download $bucketId/$path failed, serving from disk cache" }
                cached
            } else {
                sdkLogger.error { "Storage download $bucketId/$path failed and no cache available" }
                throw error
            }
        }
    }

    /**
     * True when this throwable (or any cause in its chain) indicates the object no longer exists
     * on the server, so its disk cache should be evicted rather than served.
     *
     * Supabase Storage reports a missing object as a [NotFoundRestException] — frequently carried
     * on an HTTP 400 whose JSON body reports a 404, so its [RestException.statusCode] can read 400
     * rather than 404. We therefore match the exception **type** directly, plus any [RestException]
     * (e.g. `UnknownRestException` for a literal HTTP 404) or ktor response whose HTTP status is a
     * literal 404 (via [httpStatusOrNull], which covers non-storage callers).
     */
    private fun Throwable.indicatesServerObjectMissing(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is NotFoundRestException) return true
            if (current is RestException && current.statusCode == HTTP_NOT_FOUND) return true
            current = current.cause
        }
        return httpStatusOrNull() == HTTP_NOT_FOUND
    }

    /** Evicts a cached object: removes the on-disk file and its Room metadata row. */
    private suspend fun evictCache(path: String) {
        withContext(Dispatchers.IO) { deleteIfExists(localPath(path)) }
        offlineStore.deleteStorageByKey(bucketId, path)
    }

    // --------------------------- WRITE OPS (pass-through + local consistency) ---------------------------

    override suspend fun delete(paths: Collection<String>) {
        delegate.delete(paths)
        // Remove cached files/rows
        paths.forEach { path ->
            val lp = localPath(path)
            withContext(Dispatchers.IO) { deleteIfExists(lp) }
            offlineStore.deleteStorageByKey(bucketId, path)
        }
    }

    override suspend fun move(from: String, to: String, destinationBucket: String?) {
        delegate.move(from, to, destinationBucket)
        val dstBucket = destinationBucket ?: bucketId
        val src = localPath(from)
        val dst = resolveLocalPath(baseDirectory, dstBucket, to)
        withContext(Dispatchers.IO) {
            if (exists(src)) {
                ensureParentDirs(dst)
                // atomic move if possible
                runCatching {
                    FileSystem.SYSTEM.atomicMove(src, dst)
                }.onFailure {
                    // fall back to copy+delete
                    FileSystem.SYSTEM.copy(src, dst)
                    deleteIfExists(src)
                }
                offlineStore.deleteStorageByKey(bucketId, from)
                trackRow(
                    to,
                    dst,
                    size = fileSizeOrNull(dst),
                    eTag = null,
                    bucketOverride = dstBucket
                )
            }
        }
    }

    override suspend fun copy(from: String, to: String, destinationBucket: String?) {
        delegate.copy(from, to, destinationBucket)
        // We do not auto-populate cache for the copy (no bytes yet).
        // The next download of "to" will populate the cache.
    }

    // ---------------------------- Helpers ----------------------------

    private fun localPath(path: String): Path =
        resolveLocalPath(baseDirectory, bucketId, path)

    private suspend fun cacheBytes(path: String, bytes: ByteArray) {
        val file = localPath(path)
        withContext(Dispatchers.IO) {
            writeBytes(file, bytes, overwrite = true)
        }
        trackRow(path, file, size = bytes.size.toLong(), eTag = null)
    }

    private fun fileSizeOrNull(p: Path): Long? =
        runCatching { FileSystem.SYSTEM.metadata(p).size }.getOrNull()

    /**
     * Records/updates the Room metadata row for a cached object.
     *
     * [eTag] is reserved for a future conditional-revalidation feature and is currently always
     * `null` — eTag-based revalidation is intentionally out of scope, so nothing reads it back yet.
     */
    private suspend fun trackRow(
        path: String,
        file: Path,
        size: Long?,
        eTag: String?,
        bucketOverride: String? = null
    ) {
        val bucketId = bucketOverride ?: this@OfflineBucketApi.bucketId
        offlineStore.addStorage(
            bucketId = bucketId,
            path = path,
            file = file,
            size = size,
            eTag = eTag
        )
    }

    // ----------------- Local lookup helpers (optional) -----------------

    /**
     * Returns the absolute local [Path] if it exists on disk AND is tracked in DB.
     */
    suspend fun localPathIfPresent(path: String): Path? {
        val row = offlineStore.getStorageByKey(bucketId, path) ?: return null
        val asPath = row.localPath.toPath()
        return if (exists(asPath)) asPath else null
    }
}
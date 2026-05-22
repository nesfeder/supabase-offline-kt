@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.internal.storage

import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.SdkLogger
import io.github.nesfeder.supabase.StorageCacheMetadata
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.jan.supabase.storage.BucketApi
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.time.ExperimentalTime

/**
 * Offline-aware [BucketApi] that:
 *  - Delegates to the real BucketApi for all operations
 *  - Caches successful downloads as files on disk (Okio) under <base>/storage/<bucket>/<path>
 *  - Tracks cached files in Room (StorageDao)
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
    ): ByteArray {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        return try {
            val bytes = delegate.downloadAuthenticated(path, options)
            cacheBytes(path, bytes)
            bytes
        } catch (e: Exception) {
            val cached = readBytesOrNull(localPath(path))
            if (cached != null) {
                sdkLogger.warning { "Storage download $bucketId/$path failed, serving from disk cache" }
                cached
            } else {
                sdkLogger.error { "Storage download $bucketId/$path failed and no cache available" }
                throw e
            }
        }
    }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        try {
            val bytes = delegate.downloadAuthenticated(path, options)
            cacheBytes(path, bytes)
            channel.writeFully(bytes)
        } catch (e: Exception) {
            val cached = readBytesOrNull(localPath(path))
            if (cached != null) {
                sdkLogger.warning { "Storage download $bucketId/$path failed, serving from disk cache" }
                channel.writeFully(cached)
            } else {
                sdkLogger.error { "Storage download $bucketId/$path failed and no cache available" }
                throw e
            }
        }
    }

    override suspend fun downloadPublic(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        return try {
            val bytes = delegate.downloadPublic(path, options)
            cacheBytes(path, bytes)
            bytes
        } catch (e: Exception) {
            val cached = readBytesOrNull(localPath(path))
            if (cached != null) {
                sdkLogger.warning { "Storage download $bucketId/$path failed, serving from disk cache" }
                cached
            } else {
                sdkLogger.error { "Storage download $bucketId/$path failed and no cache available" }
                throw e
            }
        }
    }

    override suspend fun downloadPublic(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        sdkLogger.debug { "Storage download $bucketId/$path attempting network" }
        try {
            val bytes = delegate.downloadPublic(path, options)
            cacheBytes(path, bytes)
            channel.writeFully(bytes)
        } catch (e: Exception) {
            val cached = readBytesOrNull(localPath(path))
            if (cached != null) {
                sdkLogger.warning { "Storage download $bucketId/$path failed, serving from disk cache" }
                channel.writeFully(cached)
            } else {
                sdkLogger.error { "Storage download $bucketId/$path failed and no cache available" }
                throw e
            }
        }
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
     * Returns cache metadata for [objectPath] when a row exists in the offline store.
     * `null` if nothing is tracked for this bucket/path.
     */
    suspend fun storageCacheMetadata(objectPath: String): StorageCacheMetadata? {
        val row = offlineStore.getStorageByKey(bucketId, objectPath) ?: return null
        val asPath = row.localPath.toPath()
        if (!exists(asPath)) {
            return null
        }
        return StorageCacheMetadata(
            bucketId = row.bucketId,
            objectPath = row.path,
            localPath = row.localPath,
            lastFetchedEpochMs = row.lastFetchedEpochMs,
        )
    }

    /**
     * Returns the absolute local [Path] if it exists on disk AND is tracked in DB.
     */
    suspend fun localPathIfPresent(path: String): Path? {
        val row = offlineStore.getStorageByKey(bucketId, path) ?: return null
        val asPath = row.localPath.toPath()
        return if (exists(asPath)) asPath else null
    }
}
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.StorageRow
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import okio.buffer
import okio.use
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Instant

/** Size of the buffer streamed downloads and cached-file reads move bytes through. */
private const val STREAM_BUFFER_BYTES: Int = 64 * 1024

/** Every temporary download file starts with this, so a sweep can find the ones a crash left. */
private const val TEMPORARY_FILE_PREFIX: String = ".supabase-offline-download-"

/** And ends with this. */
private const val TEMPORARY_FILE_SUFFIX: String = ".tmp"

/**
 * One entry of the offline file cache: an object, or one image-transform variant of it.
 *
 * @property bucketId the bucket.
 * @property objectPath the object path, as the caller wrote it.
 * @property transform the transform's canonical query (`"height=64&width=64"`), or `null` for the
 *   untransformed object.
 * @property file where the entry's bytes live.
 */
internal class StorageCacheEntry(
    val bucketId: String,
    val objectPath: String,
    val transform: String?,
    val file: Path,
) {
    /**
     * The cache row's `path`: the object path for the original, which keeps the stored key format
     * `"$bucket::$path"` every existing install uses; the object path, the variant separator and
     * the transform for a variant.
     */
    val rowPath: String = if (transform == null) objectPath else "$objectPath$VARIANT_SEPARATOR$transform"

    /** The cache row's key. */
    val key: String = "$bucketId::$rowPath"
}

/**
 * A download written to a temporary file beside its entry's file, not yet in the cache.
 *
 * @property entry the entry it is for.
 * @property path the temporary file.
 * @property sink an open sink to the temporary file; [StorageFileCache.commit] closes it.
 */
internal class TemporaryDownload(
    val entry: StorageCacheEntry,
    val path: Path,
    val sink: BufferedSink,
)

/** What [StorageFileCache.commit] did with a finished download. */
internal enum class CommitOutcome {
    /** The file replaced the entry atomically and is tracked. */
    COMMITTED,

    /**
     * Local data was cleared while the file downloaded; it belongs to the outgoing session, so it
     * was not kept. The temporary file is left for the caller to read and then [discard].
     */
    CLEARED,

    /** The file system refused the move; the temporary file is left for the caller. */
    FAILED,
}

/**
 * The offline client's file cache for Storage objects, shared by every bucket the client hands out.
 *
 * It owns everything that touches the cache directory, and holds three rules:
 *
 * - **Containment.** A file path comes only from [objectFilePathOrNull] or [variantFilePath], and a
 *   path read back from a cache row is followed only when it lies inside the data directory.
 * - **Atomicity.** A download is written to a temporary file in the directory of the file it will
 *   replace, then renamed over it. A reader sees the old bytes or the new ones, never a part, and a
 *   cancelled or failed download leaves nothing behind. Temporary files a crash left are swept on
 *   first use.
 * - **Generations.** [clear] bumps a generation; a download started in an earlier one is not
 *   committed, so a response that lands after sign-out cannot repopulate the cache.
 *
 * Commits, evictions, invalidations and clears run under one lock, so none of them interleaves
 * with another. Reads run outside it, protected by a pin: a pinned entry is never evicted.
 *
 * The size bound ([maxSizeBytes]) is enforced after every commit by deleting the least recently
 * used entries — ranked by file modification time, which [markFileUsed] moves forward on every use
 * — skipping pinned ones and the one just committed.
 *
 * @param store the offline store holding the cache rows.
 * @param dataDirectoryProvider the offline data directory; resolved on first use.
 * @param maxSizeBytes the size bound, or `null` for none.
 * @param sdkLogger the SDK logger. Nothing logged at INFO or above names an object path.
 * @param fileSystem the file system; injectable so tests can make it fail.
 * @param nowMillis clock for use times.
 * @param markUsed records a use time on a file.
 */
internal class StorageFileCache(
    private val store: OfflineStore,
    private val dataDirectoryProvider: () -> Path,
    private val maxSizeBytes: Long?,
    private val sdkLogger: SdkLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val markUsed: (Path, Long) -> Unit = ::markFileUsed,
    private val excludeFromBackup: (Path) -> Boolean = ::excludeFromBackup,
) {

    private val dataDirectory: Path by lazy { dataDirectoryProvider() }
    private val storageDirectory: Path get() = dataDirectory / STORAGE_DIRECTORY_NAME
    private val transformedDirectory: Path get() = dataDirectory / TRANSFORMED_DIRECTORY_NAME

    private val lock = Mutex()
    private val generation = AtomicLong(0L)

    /** Entry key → number of reads in progress. Guarded by [lock]. */
    private val pins = mutableMapOf<String, Int>()

    /** Whether the one sweep for crash-orphaned temporary files has run. Guarded by [lock]. */
    private var temporaryFilesSwept = false

    /** Whether [prepareDirectories] has run, so [clear] re-creates what it deletes. */
    private var directoriesPrepared = false

    /**
     * Creates both cache directories now and asks the platform to leave them out of device
     * backups, before anything is written into them. Best effort: a failure is logged at DEBUG and
     * the directories are created on first write instead. [clear] repeats it once it has run.
     */
    fun prepareDirectories() {
        directoriesPrepared = true
        cacheDirectories().forEach { directory ->
            try {
                fileSystem.createDirectories(directory)
                excludeFromBackup(directory)
            } catch (failure: IOException) {
                sdkLogger.debug { "Storage cache could not prepare its directories: $failure" }
            }
        }
    }

    /**
     * The cache entry of the untransformed object at [objectPath].
     *
     * @return the entry, or `null` when the object cannot be cached.
     */
    fun entryFor(bucketId: String, objectPath: String): StorageCacheEntry? {
        val file = objectFilePathOrNull(dataDirectory, bucketId, objectPath) ?: return null
        return StorageCacheEntry(bucketId, objectPath, transform = null, file = file)
    }

    /**
     * The cache entry of one image-transform variant of the object at [objectPath].
     *
     * @param transform the transform's canonical query.
     * @return the entry, or `null` when the object cannot be cached.
     */
    fun variantEntryFor(bucketId: String, objectPath: String, transform: String): StorageCacheEntry? {
        if (objectFilePathOrNull(dataDirectory, bucketId, objectPath) == null) return null
        val key = "$bucketId::$objectPath$VARIANT_SEPARATOR$transform"
        return StorageCacheEntry(bucketId, objectPath, transform, variantFilePath(dataDirectory, key))
    }

    /** The current generation; capture it before a download starts and pass it to [commit]. */
    fun currentGeneration(): Long = generation.load()

    /**
     * The cached copy of [entry], marked as used now, or `null` when it is not cached.
     *
     * @param entry the entry.
     * @return the cached file.
     */
    suspend fun lookup(entry: StorageCacheEntry): CachedFile? = withContext(Dispatchers.IO) {
        val row = store.getStorageByKey(entry.bucketId, entry.rowPath) ?: return@withContext null
        val file = row.containedFileOrNull() ?: return@withContext null
        val size = fileSystem.metadataOrNull(file)?.size ?: return@withContext null
        markUsed(file, nowMillis())
        CachedFile(
            path = file.toString(),
            fetchedAt = Instant.fromEpochMilliseconds(row.lastFetchedEpochMs),
            sizeBytes = row.sizeBytes ?: size,
        )
    }

    /**
     * Runs [block] with [entry] pinned, so no eviction removes its file while [block] reads it.
     *
     * @param entry the entry to pin.
     * @param block the read.
     * @return what [block] returns.
     */
    suspend fun <T> withPinned(entry: StorageCacheEntry, block: suspend () -> T): T {
        lock.withLock { pins[entry.key] = (pins[entry.key] ?: 0) + 1 }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    val remaining = (pins[entry.key] ?: 1) - 1
                    if (remaining <= 0) pins.remove(entry.key) else pins[entry.key] = remaining
                }
            }
        }
    }

    /**
     * The cached bytes of [entry], or `null` when it is not cached or cannot be read.
     *
     * @param entry the entry.
     * @return the bytes.
     */
    suspend fun readBytes(entry: StorageCacheEntry): ByteArray? = withPinned(entry) {
        val cached = lookup(entry) ?: return@withPinned null
        withContext(Dispatchers.IO) {
            try {
                fileSystem.read(cached.path.toPath()) { readByteArray() }
            } catch (failure: IOException) {
                sdkLogger.warning { "Storage cache could not read a cached file (${failure::class.simpleName})" }
                null
            }
        }
    }

    /**
     * Streams the cached copy of [entry] into [channel], without closing it.
     *
     * @param entry the entry.
     * @param channel where the bytes go.
     * @return `false` when nothing was cached, so nothing was written.
     */
    suspend fun copyTo(entry: StorageCacheEntry, channel: ByteWriteChannel): Boolean = withPinned(entry) {
        val cached = lookup(entry) ?: return@withPinned false
        copyFile(cached.path.toPath(), channel)
        true
    }

    /**
     * Streams [file] into [channel], without closing it.
     *
     * @param file the file.
     * @param channel where the bytes go.
     */
    suspend fun copyFile(file: Path, channel: ByteWriteChannel) {
        val source = withContext(Dispatchers.IO) { fileSystem.source(file).buffer() }
        source.use { reader ->
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                val read = withContext(Dispatchers.IO) { reader.read(buffer, 0, buffer.size) }
                if (read == -1) break
                channel.writeFully(buffer, 0, read)
            }
        }
        channel.flush()
    }

    /**
     * Opens a temporary file for a download of [entry], in the directory of the entry's file.
     *
     * Runs under the lock, so a [clear] never finds a file appearing in a directory it is deleting.
     *
     * @param entry the entry.
     * @return the temporary download, or `null` when the file system refuses (logged).
     */
    suspend fun openTemporaryDownload(entry: StorageCacheEntry): TemporaryDownload? = withContext(Dispatchers.IO) {
        lock.withLock {
            sweepTemporaryFilesOnce()
            val directory = entry.file.parent ?: return@withLock null
            val temporary = directory / "$TEMPORARY_FILE_PREFIX${Random.nextLong().toULong().toString(16)}$TEMPORARY_FILE_SUFFIX"
            try {
                fileSystem.createDirectories(directory)
                TemporaryDownload(entry, temporary, fileSystem.sink(temporary, mustCreate = true).buffer())
            } catch (failure: IOException) {
                deleteQuietly(temporary)
                sdkLogger.warning { "Storage cache could not create a file (${failure::class.simpleName}); not caching" }
                null
            }
        }
    }

    /**
     * Writes [bytes] to the cache as [entry]'s copy: a temporary file, then an atomic rename.
     *
     * Never throws for a file-system failure — the download already succeeded and its bytes are
     * the caller's — it logs instead. Cancellation propagates, leaving nothing behind.
     *
     * @param entry the entry.
     * @param bytes the downloaded bytes.
     * @param startedInGeneration [currentGeneration] as read before the download started.
     */
    suspend fun store(entry: StorageCacheEntry, bytes: ByteArray, startedInGeneration: Long) {
        val temporary = openTemporaryDownload(entry) ?: return
        try {
            withContext(Dispatchers.IO) { temporary.sink.write(bytes) }
        } catch (failure: Throwable) {
            discard(temporary)
            if (failure !is IOException) throw failure
            sdkLogger.warning { "Storage cache could not write a download (${failure::class.simpleName}); not caching" }
            return
        }
        if (commit(temporary, startedInGeneration) != CommitOutcome.COMMITTED) discard(temporary)
    }

    /**
     * Closes [temporary] and renames it over its entry's file, then records the entry and enforces
     * the size bound.
     *
     * @param temporary the finished download.
     * @param startedInGeneration [currentGeneration] as read before the download started.
     * @return what happened. On anything but [CommitOutcome.COMMITTED] the temporary file is left
     *   in place for the caller to read, then [discard].
     */
    suspend fun commit(temporary: TemporaryDownload, startedInGeneration: Long): CommitOutcome =
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                val entry = temporary.entry
                try {
                    temporary.sink.close()
                } catch (failure: IOException) {
                    sdkLogger.warning { "Storage cache could not finish a file (${failure::class.simpleName}); not caching" }
                    return@withLock CommitOutcome.FAILED
                }
                if (generation.load() != startedInGeneration) {
                    sdkLogger.info { "Storage download from ${entry.bucketId} finished after local data was cleared; not caching" }
                    return@withLock CommitOutcome.CLEARED
                }
                try {
                    fileSystem.atomicMove(temporary.path, entry.file)
                } catch (failure: IOException) {
                    sdkLogger.warning { "Storage cache could not store a file (${failure::class.simpleName}); not caching" }
                    return@withLock CommitOutcome.FAILED
                }
                val size = fileSystem.metadataOrNull(entry.file)?.size
                markUsed(entry.file, nowMillis())
                store.addStorage(
                    bucketId = entry.bucketId,
                    path = entry.rowPath,
                    file = entry.file,
                    size = size,
                    eTag = null,
                )
                evictOverLimit(protectedKey = entry.key)
                CommitOutcome.COMMITTED
            }
        }

    /**
     * Deletes a temporary download that was not committed. Safe to call more than once.
     *
     * @param temporary the download.
     */
    suspend fun discard(temporary: TemporaryDownload) {
        withContext(Dispatchers.IO + NonCancellable) {
            try {
                temporary.sink.close()
            } catch (_: IOException) {
                // The file is being thrown away; a failed flush changes nothing.
            }
            deleteQuietly(temporary.path)
        }
    }

    /**
     * Drops every cached copy of the object at [objectPath] — the original and each transformed
     * variant — because the server's copy changed or is gone.
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     */
    suspend fun invalidateObject(bucketId: String, objectPath: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock { removeObjectLocked(bucketId, objectPath) }
        }
    }

    /**
     * Mirrors a server-side move in the cache: the destination's stale copies are dropped, and the
     * source's copy, if cached, becomes the destination's. Transformed variants of the source are
     * dropped; they are cached again when next downloaded.
     */
    suspend fun moveObject(fromBucket: String, from: String, toBucket: String, to: String) {
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                removeObjectLocked(toBucket, to)
                val source = entryFor(fromBucket, from)
                val destination = entryFor(toBucket, to)
                val sourceRow = store.getStorageByKey(fromBucket, from)
                val sourceFile = sourceRow?.containedFileOrNull()
                if (source != null && destination != null && sourceRow != null && sourceFile != null &&
                    fileSystem.exists(sourceFile)
                ) {
                    try {
                        destination.file.parent?.let { fileSystem.createDirectories(it) }
                        fileSystem.atomicMove(sourceFile, destination.file)
                        store.addStorage(
                            bucketId = toBucket,
                            path = destination.rowPath,
                            file = destination.file,
                            size = fileSystem.metadataOrNull(destination.file)?.size,
                            eTag = null,
                        )
                    } catch (failure: IOException) {
                        sdkLogger.warning { "Storage cache could not move a cached file (${failure::class.simpleName})" }
                    }
                }
                removeObjectLocked(fromBucket, from)
            }
        }
    }

    /**
     * Empties the cache for an identity change. Runs [clearDatabase] — the store's own wipe, which
     * removes every storage row — and deletes both cache directories, all under the lock and after
     * bumping the generation, so no download that started before can commit into the emptied cache.
     *
     * @param clearDatabase the store's wipe.
     */
    suspend fun clear(clearDatabase: suspend () -> Unit) {
        withContext(Dispatchers.IO + NonCancellable) {
            lock.withLock {
                generation.addAndFetch(1L)
                clearDatabase()
                fileSystem.deleteRecursively(storageDirectory, mustExist = false)
                fileSystem.deleteRecursively(transformedDirectory, mustExist = false)
                if (directoriesPrepared) prepareDirectories()
            }
        }
    }

    /**
     * The two directories the cache writes into: `<data>/storage` and `<data>/storage-transformed`.
     *
     * @return the directories.
     */
    fun cacheDirectories(): List<Path> = listOf(storageDirectory, transformedDirectory)

    // ───────────────────────────── Under the lock ─────────────────────────────

    private suspend fun removeObjectLocked(bucketId: String, objectPath: String) {
        store.storageRowsForObject(bucketId, objectPath, "$objectPath$VARIANT_SEPARATOR").forEach { row ->
            row.containedFileOrNull()?.let(::deleteQuietly)
            store.deleteStorageRow(row.key)
        }
    }

    private suspend fun evictOverLimit(protectedKey: String) {
        val limit = maxSizeBytes ?: return
        var total = store.totalStorageSizeBytes()
        if (total <= limit) return
        val candidates = store.allStorageRows()
            .filter { row -> row.key != protectedKey && row.key !in pins }
            .map { row -> row to (row.containedFileOrNull()?.let { fileSystem.metadataOrNull(it)?.lastModifiedAtMillis } ?: Long.MIN_VALUE) }
            .sortedBy { (_, lastUsed) -> lastUsed }
        var evicted = 0
        for ((row, _) in candidates) {
            if (total <= limit) break
            row.containedFileOrNull()?.let(::deleteQuietly)
            store.deleteStorageRow(row.key)
            total -= row.sizeBytes ?: 0L
            evicted++
        }
        sdkLogger.debug { "Storage cache evicted $evicted file(s) to stay within $limit bytes" }
    }

    private fun sweepTemporaryFilesOnce() {
        if (temporaryFilesSwept) return
        temporaryFilesSwept = true
        cacheDirectories().filter { fileSystem.exists(it) }.forEach { directory ->
            try {
                fileSystem.listRecursively(directory)
                    .filter { it.name.startsWith(TEMPORARY_FILE_PREFIX) && it.name.endsWith(TEMPORARY_FILE_SUFFIX) }
                    .toList()
                    .forEach(::deleteQuietly)
            } catch (failure: IOException) {
                sdkLogger.debug { "Storage cache could not sweep temporary files: $failure" }
            }
        }
    }

    private fun StorageRow.containedFileOrNull(): Path? {
        val file = localPath.toPath()
        return file.takeIf { it.isStrictlyInside(storageDirectory) || it.isStrictlyInside(transformedDirectory) }
    }

    private fun deleteQuietly(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (failure: IOException) {
            sdkLogger.debug { "Storage cache could not delete a file: $failure" }
        }
    }
}

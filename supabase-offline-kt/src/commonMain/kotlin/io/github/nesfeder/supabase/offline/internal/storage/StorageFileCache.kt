// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.internal.cache.CacheOwnership
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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

/** Size of the buffer streamed downloads and cached-file reads move bytes through. */
private const val STREAM_BUFFER_BYTES: Int = 64 * 1024

/** Every temporary download file starts with this, so a sweep can find the ones a crash left. */
private const val TEMPORARY_FILE_PREFIX: String = ".supabase-offline-download-"

/** Every temporary download file ends with this. */
private const val TEMPORARY_FILE_SUFFIX: String = ".tmp"

/**
 * Age past which a temporary download file is taken for a crash's leftover. A younger one may
 * belong to a download still running — in a client that was closed and re-created on the same
 * directory, whose downloads outlive `close()` because they run in their callers' coroutines.
 */
private val STALE_TEMPORARY_FILE_AGE: Duration = 24.hours

/**
 * A file used this recently is never evicted. The download that just committed it has yet to
 * deliver it, or a caller was just handed its path and has yet to open it.
 */
internal val RECENT_USE_GRACE: Duration = 30.seconds

/**
 * Once over its bound, the cache evicts a further 1/this of the bound below it, so the next
 * downloads fit without another scan.
 */
private const val LOW_WATER_FRACTION: Long = 10L

/**
 * One entry of the offline file cache: an object, or one image-transform variant of it.
 *
 * @property bucketId the bucket.
 * @property objectPath the object path, as the caller wrote it.
 * @property transform the transform's canonical query (`"height=64&width=64"`), or `null` for the
 *   untransformed object.
 * @property file where the entry's bytes live.
 */
internal class FileCacheEntry(
    val bucketId: String,
    val objectPath: String,
    val transform: String?,
    val file: Path,
) {
    /**
     * The cache row's `path`. For the original it is the object path, so the key keeps the
     * persisted `"$bucket::$path"` format; for a variant, the object path, [VARIANT_SEPARATOR] and
     * the transform.
     */
    val rowPath: String =
        if (transform == null) objectPath else "$objectPath$VARIANT_SEPARATOR$transform"

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
    val entry: FileCacheEntry,
    val path: Path,
    val sink: BufferedSink,
)

/** What [StorageFileCache.commit] did with a finished download. */
internal enum class CommitOutcome {
    /** The file replaced the entry atomically and is tracked. */
    COMMITTED,

    /**
     * Local data was cleared while the file downloaded, or the cache is another identity's; the
     * file belongs to another session, so it was not kept. The temporary file is left for the
     * caller to read and then [discard].
     */
    CLEARED,

    /** The file system refused the move; the temporary file is left for the caller. */
    FAILED,
}

/** The key of the cached file row of [path] in [bucketId]: the persisted `"$bucket::$path"`. */
internal fun cachedFileKeyOf(bucketId: String, path: String): String = "$bucketId::$path"

/**
 * The cached file row recording [file] as the copy of [path] in [bucketId], fetched at
 * [fetchedAtMillis].
 */
internal fun cachedFileRowOf(
    bucketId: String,
    path: String,
    file: Path,
    size: Long?,
    eTag: String?,
    fetchedAtMillis: Long,
): CachedFileRow =
    CachedFileRow(
        key = cachedFileKeyOf(bucketId, path),
        bucketId = bucketId,
        path = path,
        localPath = file.toString(),
        sizeBytes = size,
        eTag = eTag,
        lastFetchedEpochMs = fetchedAtMillis,
    )

/**
 * The client's file cache for Storage objects, shared by every bucket it hands out. An entry's file
 * is derived from its bucket and path, never read back from its row, and every file operation stays
 * inside the data directory; commits, evictions, invalidations and clears run under one lock.
 *
 * @param cachedFileDao the cached files' rows.
 * @param ownership whether the cache serves, and takes files for, the current identity.
 * @param fetchedAtMillis the clock a row's fetch time is read from.
 * @param maxSizeBytes the size bound, or `null` for none.
 * @param nowMillis the clock use times and file ages are read from.
 * @param markUsed records a use time on a file.
 */
internal class StorageFileCache(
    private val cachedFileDao: CachedFileDao,
    private val ownership: CacheOwnership,
    private val fetchedAtMillis: () -> Long,
    private val dataDirectoryProvider: () -> Path,
    private val maxSizeBytes: Long?,
    private val engineLogger: EngineLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val markUsed: (Path, Long) -> Unit = ::markFileUsed,
    private val excludeFromBackup: (Path) -> Boolean = ::excludeFromBackup,
) {

    /** Over the store facade's file rows, identity and clock; retired with the facade in C3b. */
    constructor(
        store: OfflineStore,
        dataDirectoryProvider: () -> Path,
        maxSizeBytes: Long?,
        engineLogger: EngineLogger,
        fileSystem: FileSystem = FileSystem.SYSTEM,
        nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
        markUsed: (Path, Long) -> Unit = ::markFileUsed,
        excludeFromBackup: (Path) -> Boolean = ::excludeFromBackup,
    ) : this(
        cachedFileDao = store.cachedFileDao,
        ownership = store.cacheOwnership,
        fetchedAtMillis = store.nowMillis,
        dataDirectoryProvider = dataDirectoryProvider,
        maxSizeBytes = maxSizeBytes,
        engineLogger = engineLogger,
        fileSystem = fileSystem,
        nowMillis = nowMillis,
        markUsed = markUsed,
        excludeFromBackup = excludeFromBackup,
    )

    /** The row of [path] in [bucketId], or `null` when none is cached. */
    private suspend fun rowOf(bucketId: String, path: String): CachedFileRow? =
        cachedFileDao.getByKey(cachedFileKeyOf(bucketId, path))

    /** Records [file] as the cached copy of [path] in [bucketId], fetched now. */
    private suspend fun recordRow(bucketId: String, path: String, file: Path, size: Long?) {
        cachedFileDao.upsert(
            cachedFileRowOf(bucketId, path, file, size, eTag = null, fetchedAtMillis())
        )
    }

    private val dataDirectory: Path by lazy { dataDirectoryProvider().normalized() }
    private val storageDirectory: Path
        get() = dataDirectory / STORAGE_DIRECTORY_NAME

    private val hashedDirectory: Path
        get() = dataDirectory / HASHED_DIRECTORY_NAME

    /** The lock every commit, eviction, move, invalidation and clear runs under. */
    private val lock = Mutex()

    /** Bumped by [clear]; a download that started in an earlier generation is never committed. */
    private val generation = AtomicLong(0L)

    /** Entry key → number of reads in progress. Guarded by [lock]. */
    private val pins = mutableMapOf<String, Int>()

    /** Whether the one sweep for crash-orphaned temporary files has run. Guarded by [lock]. */
    private var temporaryFilesSwept = false

    /**
     * Whether [prepareDirectories] has run, so [clear] re-creates what it deletes. Written outside
     * [lock] only while the client is being built, before any [clear] can run.
     */
    private var directoriesPrepared = false

    /**
     * The recorded size of every cached file, once summed; `null` until the size bound first needs
     * it, and after [clear]. Kept current by every change made under [lock].
     */
    private var trackedBytes: Long? = null

    /**
     * Until when every file an eviction could take is too recently used for a scan to be worth it.
     * Guarded by [lock].
     */
    private var evictionIdleUntilMillis: Long = Long.MIN_VALUE

    /**
     * The data directory with every symlink resolved, once it exists. Cached without [lock]: racing
     * callers resolve the same path, and one that misses the cache resolves it again.
     */
    private var canonicalDataDirectory: Path? = null

    /**
     * Creates both cache directories and asks the platform to leave them out of device backups,
     * before anything is written into them. Best effort: a failure is logged at DEBUG and the
     * directories are created on first write instead. [clear] repeats it once it has run.
     */
    fun prepareDirectories() {
        directoriesPrepared = true
        cacheDirectories().forEach { directory ->
            try {
                fileSystem.createDirectories(directory)
                excludeFromBackup(directory)
            } catch (failure: IOException) {
                engineLogger.debug { "Storage cache could not prepare its directories: $failure" }
            }
        }
    }

    /**
     * The cache entry of the untransformed object at [objectPath].
     *
     * @return the entry, or `null` when the object cannot be cached.
     */
    fun entryFor(bucketId: String, objectPath: String): FileCacheEntry? {
        val file = objectFilePathOrNull(dataDirectory, bucketId, objectPath) ?: return null
        return FileCacheEntry(bucketId, objectPath, transform = null, file = file)
    }

    /**
     * The cache entry of one image-transform variant of the object at [objectPath].
     *
     * @param transform the transform's canonical query.
     * @return the entry, or `null` when the object cannot be cached.
     */
    fun variantEntryFor(
        bucketId: String,
        objectPath: String,
        transform: String,
    ): FileCacheEntry? {
        if (!isCacheableBucketId(bucketId) || !isCacheableObjectPath(objectPath)) return null
        val key = "$bucketId::$objectPath$VARIANT_SEPARATOR$transform"
        return FileCacheEntry(
            bucketId,
            objectPath,
            transform,
            hashedFilePath(dataDirectory, key),
        )
    }

    /** The current generation; capture it before a download starts and pass it to [commit]. */
    fun currentGeneration(): Long = generation.load()

    /**
     * The cached copy of [entry], marked as used, or `null` when it is not cached.
     *
     * The file is [entry]'s own, whatever its row recorded. A row that recorded another path
     * mapping's file is dropped and counts as not cached, as does every entry while the cache is
     * not the current identity's.
     *
     * @param entry the entry.
     * @return the cached file, reporting the size of the file on disk.
     */
    suspend fun useCachedCopy(entry: FileCacheEntry): CachedFile? =
        withContext(Dispatchers.IO) {
            if (!ownership.cacheServesCurrentIdentity()) return@withContext null
            val row = rowOf(entry.bucketId, entry.rowPath) ?: return@withContext null
            if (row.recordsOutdatedPath(entry)) {
                withContext(NonCancellable) { lock.withLock { dropOutdatedRowLocked(entry) } }
                return@withContext null
            }
            if (!resolvesInsideDataDirectory(entry.file)) return@withContext null
            val size =
                fileSystem.metadataOrNull(entry.file)?.takeIf { it.isRegularFile }?.size
                    ?: return@withContext null
            markUsed(entry.file, nowMillis())
            CachedFile(
                path = entry.file.toString(),
                fetchedAt = Instant.fromEpochMilliseconds(row.lastFetchedEpochMs),
                sizeBytes = size,
            )
        }

    /**
     * Runs [block] with [entry] pinned, so no eviction removes its file while [block] reads it.
     *
     * @param entry the entry to pin.
     * @param block the read.
     * @return what [block] returns.
     */
    suspend fun <T> withPinned(entry: FileCacheEntry, block: suspend () -> T): T {
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
    suspend fun readBytes(entry: FileCacheEntry): ByteArray? =
        withPinned(entry) {
            val cached = useCachedCopy(entry) ?: return@withPinned null
            withContext(Dispatchers.IO) {
                try {
                    fileSystem.read(cached.path.toPath()) { readByteArray() }
                } catch (failure: IOException) {
                    engineLogger.warning {
                        "Storage cache could not read a cached file (${failure::class.simpleName})"
                    }
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
    suspend fun copyTo(entry: FileCacheEntry, channel: ByteWriteChannel): Boolean =
        withPinned(entry) {
            val cached = useCachedCopy(entry) ?: return@withPinned false
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
        currentCoroutineContext().ensureActive()
        val source = onIoHandingBackItsResult { fileSystem.source(file).buffer() }
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
     * A caller cancelled while the file is being created still gets it back, to [discard].
     *
     * @param entry the entry.
     * @return the temporary download, or `null` when the file system refuses or the directory
     *   resolves outside the cache (logged).
     */
    suspend fun openTemporaryDownload(entry: FileCacheEntry): TemporaryDownload? {
        currentCoroutineContext().ensureActive()
        return onIoHandingBackItsResult { createTemporaryDownload(entry) }
    }

    /** [openTemporaryDownload]'s body: creates the file under the lock. */
    private suspend fun createTemporaryDownload(entry: FileCacheEntry): TemporaryDownload? =
        lock.withLock {
            sweepTemporaryFilesOnce()
            val directory = entry.file.parent ?: return@withLock null
            val temporary =
                directory /
                    "$TEMPORARY_FILE_PREFIX${Random.nextLong().toULong().toString(16)}$TEMPORARY_FILE_SUFFIX"
            try {
                fileSystem.createDirectories(dataDirectory)
                if (!resolvesInsideDataDirectory(directory)) {
                    engineLogger.warning {
                        "Storage cache refused a directory that resolves outside the cache; not caching"
                    }
                    return@withLock null
                }
                fileSystem.createDirectories(directory)
                TemporaryDownload(
                    entry,
                    temporary,
                    fileSystem.sink(temporary, mustCreate = true).buffer(),
                )
            } catch (failure: IOException) {
                deleteQuietly(temporary)
                engineLogger.warning {
                    "Storage cache could not create a file (${failure::class.simpleName}); not caching"
                }
                null
            }
        }

    /**
     * Writes [bytes] as [entry]'s cached copy. A file-system or database failure is logged, never
     * thrown: the download's bytes are already the caller's.
     *
     * @param startedInGeneration [currentGeneration] as read before the download started.
     */
    suspend fun write(entry: FileCacheEntry, bytes: ByteArray, startedInGeneration: Long) {
        val temporary = openTemporaryDownload(entry) ?: return
        try {
            withContext(Dispatchers.IO) { temporary.sink.write(bytes) }
        } catch (failure: Throwable) {
            discard(temporary)
            if (failure !is IOException) throw failure
            engineLogger.warning {
                "Storage cache could not write a download (${failure::class.simpleName}); not caching"
            }
            return
        }
        if (commit(temporary, startedInGeneration) != CommitOutcome.COMMITTED) discard(temporary)
    }

    /**
     * Records [temporary]'s entry, renames the file over the entry's own, then enforces the size
     * bound. The row goes first, so a database that refuses it leaves the previous copy in place.
     *
     * @param startedInGeneration [currentGeneration] as read before the download started.
     * @return what happened, even to a caller cancelled meanwhile; on anything but
     *   [CommitOutcome.COMMITTED] the caller reads, then [discard]s, the temporary file.
     */
    suspend fun commit(temporary: TemporaryDownload, startedInGeneration: Long): CommitOutcome =
        onIoHandingBackItsResult {
            lock.withLock {
                val entry = temporary.entry
                try {
                    temporary.sink.close()
                } catch (failure: IOException) {
                    engineLogger.warning {
                        "Storage cache could not finish a file (${failure::class.simpleName}); not caching"
                    }
                    return@withLock CommitOutcome.FAILED
                }
                if (
                    generation.load() != startedInGeneration ||
                        !ownership.cacheTakesWritesOfCurrentIdentity()
                ) {
                    engineLogger.info {
                        "Storage download from ${entry.bucketId} finished after local data was cleared; not caching"
                    }
                    return@withLock CommitOutcome.CLEARED
                }
                val directory = entry.file.parent
                if (directory == null || !resolvesInsideDataDirectory(directory)) {
                    engineLogger.warning {
                        "Storage cache refused a directory that resolves outside the cache; not caching"
                    }
                    return@withLock CommitOutcome.FAILED
                }
                val size = fileSystem.metadataOrNull(temporary.path)?.size
                val previous =
                    try {
                        rowOf(entry.bucketId, entry.rowPath).also {
                            recordRow(
                                bucketId = entry.bucketId,
                                path = entry.rowPath,
                                file = entry.file,
                                size = size,
                            )
                        }
                    } catch (failure: Exception) {
                        engineLogger.warning {
                            "Storage cache could not record a download (${failure::class.simpleName}); not caching"
                        }
                        return@withLock CommitOutcome.FAILED
                    }
                trackedBytes = trackedBytes?.plus((size ?: 0L) - (previous?.sizeBytes ?: 0L))
                try {
                    fileSystem.atomicMove(temporary.path, entry.file)
                } catch (failure: IOException) {
                    engineLogger.warning {
                        "Storage cache could not store a file (${failure::class.simpleName}); not caching"
                    }
                    // The row describes bytes that never arrived: put back the one it replaced.
                    restoreRowLocked(entry, replaced = previous, recordedSize = size)
                    return@withLock CommitOutcome.FAILED
                }
                markUsed(entry.file, nowMillis())
                try {
                    evictOverLimit(protectedKey = entry.key)
                } catch (failure: Exception) {
                    engineLogger.warning {
                        "Storage cache could not enforce its size bound (${failure::class.simpleName})"
                    }
                }
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
                val sourceRow = rowOf(fromBucket, from)
                if (
                    source != null &&
                        destination != null &&
                        sourceRow != null &&
                        !sourceRow.recordsOutdatedPath(source)
                ) {
                    moveFileLocked(source, destination)
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
                trackedBytes = null
                evictionIdleUntilMillis = Long.MIN_VALUE
                clearDatabase()
                fileSystem.deleteRecursively(storageDirectory, mustExist = false)
                fileSystem.deleteRecursively(hashedDirectory, mustExist = false)
                if (directoriesPrepared) prepareDirectories()
            }
        }
    }

    /**
     * The two directories the cache writes into: `<data>/storage` and `<data>/storage-hashed`.
     *
     * @return the directories.
     */
    fun cacheDirectories(): List<Path> = listOf(storageDirectory, hashedDirectory)

    /**
     * The entry [row] describes, derived from its bucket and path — never from the file it
     * recorded.
     */
    private fun entryForRow(row: CachedFileRow): FileCacheEntry? {
        val separator = row.path.indexOf(VARIANT_SEPARATOR)
        if (separator < 0) return entryFor(row.bucketId, row.path)
        return variantEntryFor(
            row.bucketId,
            row.path.substring(0, separator),
            row.path.substring(separator + 1),
        )
    }

    /**
     * Whether this row recorded a file inside the data directory other than [entry]'s own, under
     * another path mapping. A recorded file outside the data directory only means the directory
     * moved.
     */
    private fun CachedFileRow.recordsOutdatedPath(entry: FileCacheEntry): Boolean {
        val recorded = localPath.toPath().normalized()
        return recorded != entry.file && recorded.isStrictlyInside(dataDirectory)
    }

    // A function whose name ends in Locked runs under [lock].

    /** Drops every row and file of the object at [objectPath], its variants included. */
    private suspend fun removeObjectLocked(bucketId: String, objectPath: String) {
        cachedFileDao.forObject(bucketId, objectPath, "$objectPath$VARIANT_SEPARATOR").forEach { row
            ->
            removeRowLocked(row)
            val entry = entryForRow(row) ?: return@forEach
            deleteContained(entry.file)
            if (row.recordsOutdatedPath(entry))
                deleteOrphanedOutdatedFileLocked(row.localPath.toPath().normalized())
        }
        // The object's own file even without a row: an outdated row may have recorded another path.
        entryFor(bucketId, objectPath)?.let { entry -> deleteContained(entry.file) }
    }

    /** Deletes [row] and takes its recorded size off [trackedBytes]. */
    private suspend fun removeRowLocked(row: CachedFileRow) {
        cachedFileDao.delete(row.key)
        trackedBytes = trackedBytes?.minus(row.sizeBytes ?: 0L)
    }

    /**
     * Undoes the row a commit or a move wrote for [entry] when its file could not be renamed into
     * place. It puts back the row it [replaced], whose file is still there, or drops it when there
     * was none.
     */
    private suspend fun restoreRowLocked(
        entry: FileCacheEntry,
        replaced: CachedFileRow?,
        recordedSize: Long?,
    ) {
        try {
            if (replaced != null) cachedFileDao.upsert(replaced)
            else cachedFileDao.delete(entry.key)
            trackedBytes = trackedBytes?.plus((replaced?.sizeBytes ?: 0L) - (recordedSize ?: 0L))
        } catch (failure: Exception) {
            engineLogger.warning {
                "Storage cache could not undo a failed download's row (${failure::class.simpleName})"
            }
        }
    }

    /**
     * Drops [entry]'s row when it still records an outdated path, and that file when nothing owns
     * it.
     */
    private suspend fun dropOutdatedRowLocked(entry: FileCacheEntry) {
        val row = rowOf(entry.bucketId, entry.rowPath) ?: return
        if (!row.recordsOutdatedPath(entry)) return
        removeRowLocked(row)
        deleteOrphanedOutdatedFileLocked(row.localPath.toPath().normalized())
    }

    /**
     * Deletes [recorded], a file an outdated row pointed at, unless the current mapping gives it to
     * another cached object. (`x.txt` owns the file `media/x.txt` was once mapped to.)
     */
    private suspend fun deleteOrphanedOutdatedFileLocked(recorded: Path) {
        if (!recorded.isStrictlyInside(storageDirectory)) return
        val segments = recorded.relativeTo(storageDirectory).segments
        if (segments.size >= 2) {
            val bucketId = segments.first()
            val objectPath = segments.drop(1).joinToString("/")
            val owner = entryFor(bucketId, objectPath)
            if (owner != null && owner.file == recorded && rowOf(bucketId, objectPath) != null)
                return
        }
        deleteContained(recorded)
    }

    /** Moves [source]'s cached file to [destination]'s, recording the destination first. */
    private suspend fun moveFileLocked(source: FileCacheEntry, destination: FileCacheEntry) {
        val directory = destination.file.parent ?: return
        if (!resolvesInsideDataDirectory(source.file) || !resolvesInsideDataDirectory(directory))
            return
        val size =
            fileSystem.metadataOrNull(source.file)?.takeIf { it.isRegularFile }?.size ?: return
        try {
            fileSystem.createDirectories(directory)
            recordRow(
                bucketId = destination.bucketId,
                path = destination.rowPath,
                file = destination.file,
                size = size,
            )
            trackedBytes = trackedBytes?.plus(size)
        } catch (failure: Exception) {
            engineLogger.warning {
                "Storage cache could not move a cached file (${failure::class.simpleName})"
            }
            return
        }
        try {
            fileSystem.atomicMove(source.file, destination.file)
        } catch (failure: IOException) {
            engineLogger.warning {
                "Storage cache could not move a cached file (${failure::class.simpleName})"
            }
            restoreRowLocked(destination, replaced = null, recordedSize = size)
        }
    }

    /**
     * Once the cache is over its bound, evicts the least recently used files until it is a tenth
     * below it. It spares [protectedKey], pinned entries and files used within [RECENT_USE_GRACE],
     * and skips the scan until the oldest file it had to spare ages out. Runs under [lock].
     */
    private suspend fun evictOverLimit(protectedKey: String) {
        val limit = maxSizeBytes ?: return
        val total = trackedBytes ?: cachedFileDao.totalSizeBytes().also { trackedBytes = it }
        if (total <= limit) return
        val now = nowMillis()
        if (now < evictionIdleUntilMillis) return
        val lowWater = limit - limit / LOW_WATER_FRACTION
        val usedBefore = now - RECENT_USE_GRACE.inWholeMilliseconds
        val (evictable, recent) =
            cachedFileDao
                .all()
                .filter { row -> row.key != protectedKey && row.key !in pins }
                .map { row -> row to lastUsedMillis(row) }
                .partition { (_, lastUsed) -> lastUsed < usedBefore }
        var remaining = total
        var evicted = 0
        for ((row, _) in evictable.sortedBy { (_, lastUsed) -> lastUsed }) {
            if (remaining <= lowWater) break
            entryForRow(row)?.let { entry -> deleteContained(entry.file) }
            cachedFileDao.delete(row.key)
            remaining -= row.sizeBytes ?: 0L
            evicted++
        }
        trackedBytes = remaining
        // Still over: the rest is too recent to take, so scan again once the oldest of it ages out.
        val oldestRecentUse = recent.minOfOrNull { (_, lastUsed) -> lastUsed }
        evictionIdleUntilMillis =
            if (remaining > lowWater && oldestRecentUse != null)
                oldestRecentUse + RECENT_USE_GRACE.inWholeMilliseconds
            else Long.MIN_VALUE
        engineLogger.debug { "Storage cache evicted $evicted file(s) to stay within $limit bytes" }
    }

    /** When [row]'s file was last used, or the earliest time there is when it has no file. */
    private fun lastUsedMillis(row: CachedFileRow): Long {
        val file = entryForRow(row)?.file ?: return Long.MIN_VALUE
        return fileSystem.metadataOrNull(file)?.lastModifiedAtMillis ?: Long.MIN_VALUE
    }

    /**
     * Runs [block] on the IO dispatcher to the end, and hands its result back even to a caller
     * cancelled meanwhile. A bare `withContext` would drop it, and with it a file only that caller
     * can clean up.
     */
    private suspend fun <T> onIoHandingBackItsResult(block: suspend () -> T): T =
        withContext(NonCancellable) { withContext(Dispatchers.IO) { block() } }

    /**
     * Deletes the temporary files a crash left behind, once per cache, under [lock]. It takes those
     * last modified more than [STALE_TEMPORARY_FILE_AGE] ago that no cache row names, since an
     * object can be named like one.
     */
    private suspend fun sweepTemporaryFilesOnce() {
        if (temporaryFilesSwept) return
        temporaryFilesSwept = true
        val staleBefore = nowMillis() - STALE_TEMPORARY_FILE_AGE.inWholeMilliseconds
        cacheDirectories()
            .filter { fileSystem.exists(it) }
            .forEach { directory ->
                try {
                    fileSystem
                        .listRecursively(directory)
                        .filter {
                            it.name.startsWith(TEMPORARY_FILE_PREFIX) &&
                                it.name.endsWith(TEMPORARY_FILE_SUFFIX)
                        }
                        .toList()
                        .filter { file ->
                            (fileSystem.metadataOrNull(file)?.lastModifiedAtMillis
                                ?: Long.MAX_VALUE) < staleBefore
                        }
                        .filterNot { file -> belongsToACachedEntry(file) }
                        .forEach(::deleteQuietly)
                } catch (failure: IOException) {
                    engineLogger.debug { "Storage cache could not sweep temporary files: $failure" }
                }
            }
    }

    /** Whether [file] is where a cached entry lives, so the sweep must leave it. */
    private suspend fun belongsToACachedEntry(file: Path): Boolean {
        val normalized = file.normalized()
        if (!normalized.isStrictlyInside(storageDirectory)) return false
        val segments = normalized.relativeTo(storageDirectory).segments
        if (segments.size < 2) return false
        val objectPath = segments.drop(1).joinToString("/")
        return entryFor(segments.first(), objectPath)?.file == normalized &&
            rowOf(segments.first(), objectPath) != null
    }

    /**
     * Whether [path] stays inside the data directory once every symlink on it is resolved. The
     * deepest part of [path] that exists — [path] itself when it does — is canonicalized, and the
     * missing rest appended. A dangling symlink, or anything that cannot be resolved, is refused.
     */
    private fun resolvesInsideDataDirectory(path: Path): Boolean {
        val root =
            canonicalDataDirectory
                ?: try {
                    fileSystem.canonicalize(dataDirectory).also { canonicalDataDirectory = it }
                } catch (_: IOException) {
                    return false
                }
        var existing = path
        val missing = mutableListOf<String>()
        while (fileSystem.metadataOrNull(existing) == null) {
            missing += existing.name
            existing = existing.parent ?: return false
        }
        val resolved =
            try {
                missing.asReversed().fold(fileSystem.canonicalize(existing)) { directory, name ->
                    directory / name
                }
            } catch (_: IOException) {
                return false
            }
        return resolved.isStrictlyInside(root)
    }

    /**
     * Deletes [file] when its directory resolves inside the data directory. A symlink at [file]
     * itself is removed as a link; its target is never touched.
     */
    private fun deleteContained(file: Path) {
        val directory = file.parent ?: return
        if (!resolvesInsideDataDirectory(directory)) {
            engineLogger.debug {
                "Storage cache left a file whose directory resolves outside the cache"
            }
            return
        }
        deleteQuietly(file)
    }

    private fun deleteQuietly(path: Path) {
        try {
            fileSystem.delete(path, mustExist = false)
        } catch (failure: IOException) {
            engineLogger.debug { "Storage cache could not delete a file: $failure" }
        }
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.internal.cache.CacheOwnership
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.FileMetadata
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
 * A use of a file is written to it, as its modification time, only once this much time has passed
 * since the last one written: a grid re-binding the same thumbnails writes no metadata. The uses
 * left off the file are kept in memory for the eviction pass instead.
 */
internal val USE_MARK_INTERVAL: Duration = 1.minutes

/** Size past which the uses kept in memory are pruned of those too old to spare a file. */
private const val RECENT_USES_PRUNE_SIZE: Int = 256

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

/**
 * Where the cache stood when a download started, from [StorageFileCache.beginDownload]: the clear
 * generation and the invalidation sequence. [StorageFileCache.commit] refuses a download whose
 * object was cleared or written since; [StorageFileCache.endDownload] takes it back.
 *
 * @property generation the clear generation.
 * @property sequence the invalidation sequence.
 */
internal class DownloadStart(val generation: Long, val sequence: Long)

/** What [StorageFileCache.commit] did with a finished download. */
internal enum class CommitOutcome {
    /** The file replaced the entry atomically and is tracked. */
    COMMITTED,

    /**
     * Local data or the file cache was cleared while the file downloaded, or the cache is another
     * identity's; the file belongs to a cache that is gone, so it was not kept. The temporary file
     * is left for the caller to read and then [discard].
     */
    CLEARED,

    /**
     * The object was written, deleted, moved or copied over through this client while the file
     * downloaded, so the file may hold the old bytes, or its copies were evicted; it was not kept.
     * The temporary file is left for the caller to read and then [discard].
     */
    SUPERSEDED,

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
 * inside the data directory; commits, evictions, invalidations, pins and clears run under one lock,
 * and reads hold their files under a lock of their own, which an eviction holds only to unlink a
 * file, so no read waits behind those. An object the app pinned keeps its copies through every
 * eviction pass, until it is unpinned, evicted, deleted or the cache cleared.
 *
 * @param cachedFileDao the cached files' rows.
 * @param pinRecords the pinned objects' records.
 * @param ownership whether the cache serves, and takes files for, the current identity.
 * @param fetchedAtMillis the clock a row's fetch time is read from.
 * @param maxSizeBytes the size bound, or `null` for none.
 * @param nowMillis the clock use times and file ages are read from.
 * @param markUsed records a use time on a file; called at most once per [USE_MARK_INTERVAL] per
 *   file.
 * @param syncFile asks the platform to write a file through to the disk, before it is renamed into
 *   place; what it throws is logged, and the size check on reads catches a file a power loss cut
 *   short.
 * @param ioDispatcher where file work runs.
 */
internal class StorageFileCache(
    private val cachedFileDao: CachedFileDao,
    private val pinRecords: PinnedFileRecords,
    private val ownership: CacheOwnership,
    private val fetchedAtMillis: () -> Long,
    private val dataDirectoryProvider: () -> Path,
    private val maxSizeBytes: Long?,
    private val engineLogger: EngineLogger,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val markUsed: (Path, Long) -> Unit = ::markFileUsed,
    private val syncFile: (Path) -> Unit = ::syncFileToDisk,
    private val excludeFromBackup: (Path) -> Boolean = ::excludeFromBackup,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** The row of [path] in [bucketId], or `null` when none is cached. */
    private suspend fun rowOf(bucketId: String, path: String): CachedFileRow? =
        cachedFileDao.getByKey(cachedFileKeyOf(bucketId, path))

    /**
     * Records [file] as the cached copy of [path] in [bucketId], fetched at [fetchedAt], with the
     * [eTag] the server sent it under.
     */
    private suspend fun recordRow(
        bucketId: String,
        path: String,
        file: Path,
        size: Long?,
        eTag: String?,
        fetchedAt: Long = fetchedAtMillis(),
    ) {
        cachedFileDao.upsert(cachedFileRowOf(bucketId, path, file, size, eTag, fetchedAt))
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

    /**
     * The lock of the running downloads' bookkeeping: [invalidationSequence], [invalidatedAt],
     * [entryInvalidatedAt] and [runningDownloads]. The operations that bump or check the sequence
     * take it under [lock]; [beginDownload] and [endDownload] take it alone, so a download's start
     * — an offline read's too — waits for no commit, eviction pass or clear.
     */
    private val downloadsLock = Mutex()

    /**
     * Bumped each time an object's copies, or one entry's, are dropped ([removeObjectLocked],
     * [removeEntryLocked]), so a download that started before the drop is never committed over it.
     * Guarded by [downloadsLock].
     */
    private var invalidationSequence = 0L

    /**
     * Object key → [invalidationSequence] when its copies were last dropped, kept while a download
     * that started before the drop is still running. Guarded by [downloadsLock].
     */
    private val invalidatedAt = mutableMapOf<String, Long>()

    /**
     * Entry key → [invalidationSequence] when that entry alone was last dropped, kept as
     * [invalidatedAt] is. Apart from it, since an untransformed entry's key is its object's.
     * Guarded by [downloadsLock].
     */
    private val entryInvalidatedAt = mutableMapOf<String, Long>()

    /**
     * The downloads in progress, between [beginDownload] and [endDownload]. Guarded by
     * [downloadsLock].
     */
    private val runningDownloads = mutableListOf<DownloadStart>()

    /**
     * The temporary files of downloads in progress, which [clear] leaves on disk for their callers
     * to read and [discard]. Guarded by [lock].
     */
    private val openTemporaryFiles = mutableSetOf<Path>()

    /**
     * The read side's lock: [readHolds] and [recentUses]. It is never held over a database call or
     * while waiting for [lock]. A read takes it alone, so no read waits behind a commit, an
     * eviction pass or a clear; an eviction takes it under [lock], per file it removes, for no more
     * than the hold check, the use check and the unlink.
     */
    private val readLock = Mutex()

    /** Entry key → number of reads in progress. Guarded by [readLock]. */
    private val readHolds = mutableMapOf<String, Int>()

    /**
     * Entry key → the latest use [useCachedCopy] left off the file, within [USE_MARK_INTERVAL] of
     * the one written to it. The eviction pass counts these uses too, so a file handed out keeps
     * its [RECENT_USE_GRACE] whether or not its use was written. Guarded by [readLock].
     */
    private val recentUses = mutableMapOf<String, Long>()

    /** The `ensureCachedFile` downloads in flight, which every caller for one entry shares. */
    val downloadsInFlight = InFlightDownloads()

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

    /**
     * Marks a download as started, before its request is sent, and says where the cache stood: pass
     * the result to [commit] or [write], and to [endDownload] once the download is over, however it
     * ends. Waits for no commit, eviction pass or clear; one begun during a clear is refused by
     * [commit] or not as the clear's bump of the generation came after or before.
     *
     * @return the download's start.
     */
    suspend fun beginDownload(): DownloadStart = downloadsLock.withLock {
        DownloadStart(generation.load(), invalidationSequence).also { runningDownloads += it }
    }

    /**
     * Marks the download that began at [start] as over, committed or not, so the invalidations it
     * had to see can be forgotten. Runs even for a caller canceled meanwhile.
     *
     * @param start the download's start.
     */
    suspend fun endDownload(start: DownloadStart) {
        withContext(NonCancellable) {
            downloadsLock.withLock {
                runningDownloads.remove(start)
                forgetStaleInvalidations()
            }
        }
    }

    /**
     * The cached copy of [entry], marked as used, or `null` when it is not cached.
     *
     * The file is [entry]'s own, whatever its row recorded. A row that recorded another path
     * mapping's file is dropped and counts as not cached, as does every entry while the cache is
     * not the current identity's. A file whose size is not the one its row recorded, as a power
     * loss between the rename and the disk can leave, is dropped with its row and counts as not
     * cached. Takes [lock] only to drop such a row.
     *
     * @param entry the entry.
     * @return the cached file, reporting the size of the file on disk.
     */
    suspend fun useCachedCopy(entry: FileCacheEntry): CachedFile? =
        withContext(ioDispatcher) {
            if (!ownership.cacheServesCurrentIdentity()) return@withContext null
            val row = rowOf(entry.bucketId, entry.rowPath) ?: return@withContext null
            if (row.recordsOutdatedPath(entry)) {
                withContext(NonCancellable) { lock.withLock { dropOutdatedRowLocked(entry) } }
                return@withContext null
            }
            if (!resolvesInsideDataDirectory(entry.file)) return@withContext null
            val metadata =
                fileSystem.metadataOrNull(entry.file)?.takeIf { it.isRegularFile }
                    ?: return@withContext null
            val size = metadata.size ?: return@withContext null
            if (row.sizeBytes != null && row.sizeBytes != size) {
                val dropped =
                    withContext(NonCancellable) { lock.withLock { dropTruncatedFileLocked(entry) } }
                // Not dropped: a commit replaced the row and the file between the two reads
                return@withContext if (dropped) null else useCachedCopy(entry)
            }
            noteUse(entry, markedAtMillis = metadata.lastModifiedAtMillis)
            CachedFile(
                path = entry.file.toString(),
                fetchedAt = Instant.fromEpochMilliseconds(row.lastFetchedEpochMs),
                sizeBytes = size,
            )
        }

    /**
     * The ETag of each cached copy of the object at [objectPath] that has one, the original's and
     * each image transform's, keyed by its entry's key: what a download of the object asks the
     * server about. Empty while the cache does not serve the current identity, and when the rows
     * cannot be read (logged): the download then asks about nothing.
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     * @return entry key to ETag.
     */
    suspend fun eTagsOf(bucketId: String, objectPath: String): Map<String, String> =
        withContext(ioDispatcher) {
            if (!ownership.cacheServesCurrentIdentity()) return@withContext emptyMap()
            try {
                cachedFileDao
                    .forObject(
                        bucketId,
                        objectPath,
                        variantPrefix = "$objectPath$VARIANT_SEPARATOR",
                        variantBound = "$objectPath$VARIANT_BOUND",
                    )
                    .mapNotNull { row -> row.eTag?.let { eTag -> row.key to eTag } }
                    .toMap()
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                engineLogger.debug { "Storage cache could not read the copies' ETags: $failure" }
                emptyMap()
            }
        }

    /**
     * Records that the server confirmed [entry]'s cached copy is current: it answered HTTP 304 to a
     * download that began at [start] and asked about the copy whose ETag is [eTag]. The copy's
     * fetch time becomes now; a failure to record it is logged, and the copy is still current.
     *
     * Refused, changing nothing, when that copy is no longer the one cached: local data or the file
     * cache was cleared, the cache is another identity's, the object or [entry] was dropped since
     * [start] — a write or an eviction the answer may predate — or the row records another ETag,
     * another file, or a size the file on disk does not have.
     *
     * @param entry the entry the download asked about.
     * @param eTag the ETag the download's `If-None-Match` carried.
     * @param start the download's [beginDownload].
     * @return whether the copy was confirmed, and can be served.
     */
    suspend fun confirmCopy(entry: FileCacheEntry, eTag: String, start: DownloadStart): Boolean =
        withContext(ioDispatcher) {
            lock.withLock {
                if (
                    generation.load() != start.generation ||
                        !ownership.cacheTakesWritesOfCurrentIdentity() ||
                        downloadsLock.withLock { invalidatedSince(entry, start) }
                )
                    return@withLock false
                val row = rowOf(entry.bucketId, entry.rowPath) ?: return@withLock false
                if (row.eTag != eTag || row.recordsOutdatedPath(entry)) return@withLock false
                if (!resolvesInsideDataDirectory(entry.file)) return@withLock false
                val size =
                    fileSystem.metadataOrNull(entry.file)?.takeIf { it.isRegularFile }?.size
                        ?: return@withLock false
                if (row.sizeBytes != null && row.sizeBytes != size) return@withLock false
                try {
                    cachedFileDao.upsert(row.copy(lastFetchedEpochMs = fetchedAtMillis()))
                } catch (failure: Exception) {
                    currentCoroutineContext().ensureActive()
                    engineLogger.warning {
                        "Storage cache could not record a revalidated file (${failure::class.simpleName})"
                    }
                }
                true
            }
        }

    /**
     * Records a use of [entry]'s file, whose use last written to it is [markedAtMillis]: on the
     * file once [USE_MARK_INTERVAL] has passed since that one (or the clock went back past it), in
     * [recentUses] before then, where the eviction pass finds it all the same.
     */
    private suspend fun noteUse(entry: FileCacheEntry, markedAtMillis: Long?) {
        val now = nowMillis()
        val sinceMarked = if (markedAtMillis == null) Long.MAX_VALUE else now - markedAtMillis
        if (sinceMarked < 0L || sinceMarked >= USE_MARK_INTERVAL.inWholeMilliseconds) {
            markUsed(entry.file, now)
            return
        }
        // Without a bound nothing evicts, so no one would read the use
        if (maxSizeBytes == null) return
        readLock.withLock {
            recentUses[entry.key] = now
            if (recentUses.size > RECENT_USES_PRUNE_SIZE) {
                val sparedAfter = now - RECENT_USE_GRACE.inWholeMilliseconds
                recentUses.values.removeAll { usedAt -> usedAt < sparedAfter }
            }
        }
    }

    /**
     * Runs [block] holding [entry] for a read, so no eviction removes its file while [block] reads
     * it. Holds live under [readLock], so the read waits for no commit, eviction pass or clear.
     *
     * @param entry the entry to hold.
     * @param block the read.
     * @return what [block] returns.
     */
    suspend fun <T> holdingForRead(entry: FileCacheEntry, block: suspend () -> T): T {
        readLock.withLock { readHolds[entry.key] = (readHolds[entry.key] ?: 0) + 1 }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                readLock.withLock {
                    val remaining = (readHolds[entry.key] ?: 1) - 1
                    if (remaining <= 0) readHolds.remove(entry.key)
                    else readHolds[entry.key] = remaining
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
        holdingForRead(entry) {
            val cached = useCachedCopy(entry) ?: return@holdingForRead null
            withContext(ioDispatcher) {
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
        holdingForRead(entry) {
            val cached = useCachedCopy(entry) ?: return@holdingForRead false
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
                val read = withContext(ioDispatcher) { reader.read(buffer, 0, buffer.size) }
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
     * A caller canceled while the file is being created still gets it back, to [discard].
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
                    .also { openTemporaryFiles += temporary }
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
     * @param start the download's [beginDownload].
     * @param eTag the ETag the server sent the bytes under, or `null` for none.
     */
    suspend fun write(
        entry: FileCacheEntry,
        bytes: ByteArray,
        start: DownloadStart,
        eTag: String? = null,
    ) {
        val temporary = openTemporaryDownload(entry) ?: return
        try {
            withContext(ioDispatcher) { temporary.sink.write(bytes) }
        } catch (failure: Throwable) {
            discard(temporary)
            if (failure !is IOException) throw failure
            engineLogger.warning {
                "Storage cache could not write a download (${failure::class.simpleName}); not caching"
            }
            return
        }
        if (commit(temporary, start, eTag) != CommitOutcome.COMMITTED) discard(temporary)
    }

    /**
     * Writes the file through to the disk, records [temporary]'s entry, renames the file over the
     * entry's own, then enforces the size bound. The row goes first, so a database that refuses it
     * leaves the previous copy in place.
     *
     * @param start the download's [beginDownload].
     * @param eTag the ETag the server sent the file under, recorded with it; `null` for none.
     * @return what happened, even to a caller canceled meanwhile; on anything but
     *   [CommitOutcome.COMMITTED] the caller reads, then [discard]s, the temporary file.
     */
    suspend fun commit(
        temporary: TemporaryDownload,
        start: DownloadStart,
        eTag: String?,
    ): CommitOutcome = onIoHandingBackItsResult {
        val entry = temporary.entry
        // Closed and synced outside the lock: the file is this download's alone, and a sync
        // takes as long as the drive does
        try {
            temporary.sink.close()
        } catch (failure: IOException) {
            engineLogger.warning {
                "Storage cache could not finish a file (${failure::class.simpleName}); not caching"
            }
            return@onIoHandingBackItsResult CommitOutcome.FAILED
        }
        try {
            syncFile(temporary.path)
        } catch (failure: IOException) {
            // The read side's size check catches what a power loss leaves of the file
            engineLogger.debug { "Storage cache could not sync a file to disk: $failure" }
        }
        val outcome = commitLocked(temporary, start, eTag)
        if (outcome == CommitOutcome.COMMITTED) {
            try {
                enforceSizeBound(protectedKey = entry.key)
            } catch (failure: Exception) {
                engineLogger.warning {
                    "Storage cache could not enforce its size bound (${failure::class.simpleName})"
                }
            }
        }
        outcome
    }

    /** [commit]'s part under [lock]: the row, then the rename. */
    private suspend fun commitLocked(
        temporary: TemporaryDownload,
        start: DownloadStart,
        eTag: String?,
    ): CommitOutcome {
        val entry = temporary.entry
        return lock.withLock {
            if (
                generation.load() != start.generation ||
                    !ownership.cacheTakesWritesOfCurrentIdentity()
            ) {
                engineLogger.info {
                    "Storage download from ${entry.bucketId} finished after local data was cleared; not caching"
                }
                return@withLock CommitOutcome.CLEARED
            }
            if (downloadsLock.withLock { invalidatedSince(entry, start) }) {
                engineLogger.info {
                    "Storage download from ${entry.bucketId} finished after the object was written; not caching"
                }
                return@withLock CommitOutcome.SUPERSEDED
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
                            eTag = eTag,
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
            openTemporaryFiles -= temporary.path
            markUsed(entry.file, nowMillis())
            CommitOutcome.COMMITTED
        }
    }

    /**
     * Deletes a temporary download that was not committed. Safe to call more than once.
     *
     * @param temporary the download.
     */
    suspend fun discard(temporary: TemporaryDownload) {
        withContext(ioDispatcher + NonCancellable) {
            try {
                temporary.sink.close()
            } catch (_: IOException) {
                // The file is being thrown away; a failed flush changes nothing.
            }
            deleteQuietly(temporary.path)
            lock.withLock { openTemporaryFiles -= temporary.path }
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
        withContext(ioDispatcher + NonCancellable) {
            lock.withLock { removeObjectLocked(bucketId, objectPath) }
        }
    }

    /**
     * Drops the cached copy of [entry] alone: one transformed variant, or the original without its
     * variants. A download of that entry still running is never committed over the drop, as
     * [invalidateObject] has it for a whole object; downloads of the object's other entries are
     * left to commit.
     *
     * @param entry the entry.
     */
    suspend fun invalidateEntry(entry: FileCacheEntry) {
        withContext(ioDispatcher + NonCancellable) { lock.withLock { removeEntryLocked(entry) } }
    }

    /**
     * Drops every cached copy of the object at [objectPath] and its pin, because the app no longer
     * wants the object on the device: it evicted the object, or deleted it through this client.
     * Downloads of it still running are never committed, as with [invalidateObject].
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     */
    suspend fun forgetObject(bucketId: String, objectPath: String) {
        withContext(ioDispatcher + NonCancellable) {
            lock.withLock {
                removeObjectLocked(bucketId, objectPath)
                pinRecords.unpin(bucketId, objectPath)
            }
        }
    }

    /**
     * Pins the object at [objectPath]: no eviction pass removes its copies, the original's and each
     * image transform's, cached now or later, until [unpinObject], [forgetObject] or a clear. A
     * write to the object drops its copies and keeps the pin; a move takes the pin along. Nothing
     * is downloaded. While the cache is not the current identity's, the pin is not recorded, as a
     * download then is not kept (logged).
     *
     * Runs under [lock], which an eviction pass holds while it removes files: once this returns, no
     * pass takes the object's copies.
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     * @return whether the pin was recorded.
     */
    suspend fun pinObject(bucketId: String, objectPath: String): Boolean =
        withContext(ioDispatcher) {
            lock.withLock {
                if (!ownership.cacheTakesWritesOfCurrentIdentity()) {
                    engineLogger.info {
                        "Storage pin in $bucketId not recorded: the file cache is another user's"
                    }
                    return@withLock false
                }
                pinRecords.pin(bucketId, objectPath)
                true
            }
        }

    /**
     * Removes the pin of the object at [objectPath], if it has one. Its copies stay, for the next
     * eviction pass to weigh as it does any other file's.
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     */
    suspend fun unpinObject(bucketId: String, objectPath: String) {
        withContext(ioDispatcher) { lock.withLock { pinRecords.unpin(bucketId, objectPath) } }
    }

    /**
     * Whether the object at [objectPath] is pinned; never while the cache is not the current
     * identity's. Takes no lock: a pin and an unpin are one row each.
     *
     * @param bucketId the bucket.
     * @param objectPath the object path.
     * @return whether it is pinned.
     */
    suspend fun isObjectPinned(bucketId: String, objectPath: String): Boolean =
        withContext(ioDispatcher) {
            ownership.cacheServesCurrentIdentity() && pinRecords.isPinned(bucketId, objectPath)
        }

    /**
     * Mirrors a server-side move in the cache: the destination's stale copies are dropped, and the
     * source's copy, if cached, becomes the destination's. Transformed variants of the source are
     * dropped; they are cached again when next downloaded. The source's pin moves to the
     * destination, which keeps its own.
     */
    suspend fun moveObject(fromBucket: String, from: String, toBucket: String, to: String) {
        withContext(ioDispatcher + NonCancellable) {
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
                    moveFileLocked(source, destination, sourceRow)
                }
                removeObjectLocked(fromBucket, from)
                movePinLocked(fromBucket, from, toBucket, to, keepSource = false)
            }
        }
    }

    /**
     * Drops the cached copies at both ends of a move that may or may not have reached the server,
     * as [invalidateObject] does for each. The source's pin stays, and the destination gets it too:
     * the object is at one of the two.
     */
    suspend fun invalidateMove(fromBucket: String, from: String, toBucket: String, to: String) {
        withContext(ioDispatcher + NonCancellable) {
            lock.withLock {
                removeObjectLocked(fromBucket, from)
                removeObjectLocked(toBucket, to)
                movePinLocked(fromBucket, from, toBucket, to, keepSource = true)
            }
        }
    }

    /**
     * Empties the cache, for an identity change or [clearFiles]. Runs [clearDatabase] — the store's
     * own wipe, or the cache's rows alone, removing every storage row and every pin either way —
     * and deletes both cache directories, all under the lock and after bumping the generation, so
     * no download that started before can commit into the emptied cache. The temporary file of a
     * download still running stays, for its caller to read and [discard].
     *
     * @param clearDatabase the wipe of the rows and the pins.
     */
    suspend fun clear(clearDatabase: suspend () -> Unit) {
        withContext(ioDispatcher + NonCancellable) {
            lock.withLock {
                generation.addAndFetch(1L)
                // Their commits are refused now; a caller arriving after the clear starts afresh
                downloadsInFlight.forgetAll()
                trackedBytes = null
                evictionIdleUntilMillis = Long.MIN_VALUE
                readLock.withLock { recentUses.clear() }
                clearDatabase()
                cacheDirectories().forEach { directory ->
                    deleteSparingOpenTemporaryFilesLocked(directory)
                }
                if (directoriesPrepared) prepareDirectories()
            }
        }
    }

    /**
     * Empties the cache and nothing else, as [clear] does: every row, file and pin goes, and no
     * download that started before commits. The rest of the database is left as it is.
     */
    suspend fun clearFiles() {
        clear {
            pinRecords.runInTransaction {
                cachedFileDao.deleteAll()
                pinRecords.unpinAll()
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

    /**
     * Drops every row and file of the object at [objectPath], its variants included, and records
     * the drop for the downloads of it still running, which may hold the bytes it replaced.
     */
    private suspend fun removeObjectLocked(bucketId: String, objectPath: String) {
        val objectKey = cachedFileKeyOf(bucketId, objectPath)
        downloadsLock.withLock {
            invalidationSequence += 1
            if (runningDownloads.isNotEmpty()) invalidatedAt[objectKey] = invalidationSequence
        }
        // Its commit is refused now; a caller arriving after the write starts afresh
        downloadsInFlight.forget(objectKey)
        val rows =
            cachedFileDao.forObject(
                bucketId,
                objectPath,
                variantPrefix = "$objectPath$VARIANT_SEPARATOR",
                variantBound = "$objectPath$VARIANT_BOUND",
            )
        rows.forEach { row ->
            removeRowLocked(row)
            val entry = entryForRow(row) ?: return@forEach
            deleteContained(entry.file)
            if (row.recordsOutdatedPath(entry))
                deleteOrphanedOutdatedFileLocked(row.localPath.toPath().normalized())
        }
        // The object's own file even without a row: an outdated row may have recorded another path.
        entryFor(bucketId, objectPath)?.let { entry -> deleteContained(entry.file) }
    }

    /**
     * Drops [entry]'s row and file alone, and records the drop for the downloads of that entry
     * still running, as [removeObjectLocked] does for a whole object.
     */
    private suspend fun removeEntryLocked(entry: FileCacheEntry) {
        downloadsLock.withLock {
            invalidationSequence += 1
            if (runningDownloads.isNotEmpty()) entryInvalidatedAt[entry.key] = invalidationSequence
        }
        // Its commit is refused now; a caller arriving after the drop starts afresh
        downloadsInFlight.forget(entry.key)
        rowOf(entry.bucketId, entry.rowPath)?.let { row ->
            removeRowLocked(row)
            if (row.recordsOutdatedPath(entry))
                deleteOrphanedOutdatedFileLocked(row.localPath.toPath().normalized())
        }
        // Even without a row: an outdated row may have recorded another path
        deleteContained(entry.file)
    }

    /** Deletes [row] and takes its recorded size off [trackedBytes]. */
    private suspend fun removeRowLocked(row: CachedFileRow) {
        cachedFileDao.delete(row.key)
        trackedBytes = trackedBytes?.minus(row.sizeBytes ?: 0L)
    }

    /**
     * Whether [entry]'s object, or [entry] alone, had its copies dropped after the download that
     * began at [start]. Runs under [downloadsLock].
     */
    private fun invalidatedSince(entry: FileCacheEntry, start: DownloadStart): Boolean {
        val objectDropped =
            invalidatedAt[cachedFileKeyOf(entry.bucketId, entry.objectPath)] ?: Long.MIN_VALUE
        val entryDropped = entryInvalidatedAt[entry.key] ?: Long.MIN_VALUE
        return maxOf(objectDropped, entryDropped) > start.sequence
    }

    /**
     * Forgets the recorded drops no running download can be refused for: those at or before the
     * oldest running download's start, or all of them once none is running. Runs under
     * [downloadsLock].
     */
    private fun forgetStaleInvalidations() {
        val oldestStart = runningDownloads.minOfOrNull { it.sequence } ?: invalidationSequence
        invalidatedAt.values.removeAll { sequence -> sequence <= oldestStart }
        entryInvalidatedAt.values.removeAll { sequence -> sequence <= oldestStart }
    }

    /**
     * Drops [entry]'s row and file once a read found the file's size is not the one the row
     * recorded, unless a commit replaced both meanwhile.
     *
     * @return `true` when they were dropped.
     */
    private suspend fun dropTruncatedFileLocked(entry: FileCacheEntry): Boolean {
        val row = rowOf(entry.bucketId, entry.rowPath) ?: return true
        val size = fileSystem.metadataOrNull(entry.file)?.takeIf { it.isRegularFile }?.size
        if (row.sizeBytes == null || size == null || row.sizeBytes == size) return false
        engineLogger.warning {
            "Storage cache dropped a file from ${entry.bucketId} whose size is not the one recorded"
        }
        removeRowLocked(row)
        deleteContained(entry.file)
        return true
    }

    /**
     * Deletes [directory] and everything under it, except the temporary files of downloads still
     * running and the directories that hold them.
     */
    private fun deleteSparingOpenTemporaryFilesLocked(directory: Path) {
        val spared = openTemporaryFiles.filter { file -> file.isStrictlyInside(directory) }
        if (spared.isEmpty()) {
            fileSystem.deleteRecursively(directory, mustExist = false)
            return
        }
        if (!fileSystem.exists(directory)) return
        val keptDirectories =
            spared.flatMapTo(mutableSetOf()) { file -> generateSequence(file.parent) { it.parent } }
        fileSystem.listRecursively(directory).toList().asReversed().forEach { path ->
            if (path !in spared && path !in keptDirectories) deleteQuietly(path)
        }
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

    /**
     * Moves [source]'s cached file to [destination]'s, recording the destination first, with the
     * fetch time and the ETag of [sourceRow]: the bytes are the ones fetched then. A server that
     * gives the moved object another ETag answers the next download whole.
     */
    private suspend fun moveFileLocked(
        source: FileCacheEntry,
        destination: FileCacheEntry,
        sourceRow: CachedFileRow,
    ) {
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
                eTag = sourceRow.eTag,
                fetchedAt = sourceRow.lastFetchedEpochMs,
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
     * Gives the object at [to] the pin of the one at [from], when [from] has one and [to] can be
     * cached; [from] loses it unless [keepSource]. [to]'s own pin stays either way.
     */
    private suspend fun movePinLocked(
        fromBucket: String,
        from: String,
        toBucket: String,
        to: String,
        keepSource: Boolean,
    ) {
        if (!pinRecords.isPinned(fromBucket, from)) return
        pinRecords.runInTransaction {
            if (entryFor(toBucket, to) != null) pinRecords.pin(toBucket, to)
            if (!keepSource) pinRecords.unpin(fromBucket, from)
        }
    }

    /**
     * The key of the object [row] holds a copy of: the row's own key for the original, the
     * original's for an image transform.
     */
    private fun objectKeyOf(row: CachedFileRow): String =
        cachedFileKeyOf(row.bucketId, row.path.substringBefore(VARIANT_SEPARATOR))

    /**
     * Once the cache is over its bound, evicts the least recently used files until it is a tenth
     * below it. The rows are read, and their files' use times looked up, outside [lock], so no
     * commit, invalidation or clear queues behind a look at every file; each file chosen is looked
     * at again, and removed, under [lock] and [readLock]. Spared are [protectedKey], the copies of
     * pinned objects, entries a read holds and files used within [RECENT_USE_GRACE], and the scan
     * is skipped until the oldest file it had to spare ages out. Pinned copies count toward the
     * bound: when they fill it, the cache stays over it, and every other file goes once its grace
     * has passed.
     */
    private suspend fun enforceSizeBound(protectedKey: String) {
        val limit = maxSizeBytes ?: return
        val lowWater = limit - limit / LOW_WATER_FRACTION
        val grace = RECENT_USE_GRACE.inWholeMilliseconds
        val scanDue = lock.withLock {
            val total = trackedBytes ?: cachedFileDao.totalSizeBytes().also { trackedBytes = it }
            total > limit && nowMillis() >= evictionIdleUntilMillis
        }
        if (!scanDue) return
        val scannedAt = nowMillis()
        val usesAtScan = readLock.withLock { recentUses.toMap() }
        val pinnedAtScan = pinRecords.pinnedObjectKeys()
        val candidates =
            cachedFileDao
                .all()
                .filter { row -> row.key != protectedKey && objectKeyOf(row) !in pinnedAtScan }
                .map { row -> row to lastUsedMillis(row, usesAtScan) }
                .sortedBy { (_, lastUsed) -> lastUsed }
        lock.withLock {
            // Cleared since the scan: nothing is left to evict
            var remaining = trackedBytes ?: return
            // Read again: an object pinned since the scan was pinned under this lock
            val pinned = pinRecords.pinnedObjectKeys()
            var evicted = 0
            var oldestSpared: Long? = null
            try {
                for ((scanned, usedAtScan) in candidates) {
                    if (remaining <= lowWater) break
                    if (objectKeyOf(scanned) in pinned) continue
                    if (usedAtScan >= scannedAt - grace) {
                        oldestSpared = minOf(oldestSpared ?: usedAtScan, usedAtScan)
                        continue
                    }
                    // Looked at again, under this lock alone: used, replaced or removed since the
                    // scan
                    val row = cachedFileDao.getByKey(scanned.key) ?: continue
                    val file = entryForRow(row)?.file
                    val metadata = file?.let { fileSystem.metadataOrNull(it) }
                    val contained = file != null && directoryContains(file)
                    // The read side's lock only for the hold, the use in memory and the unlink: a
                    // read that holds the file after this finds it gone, never half gone
                    val taken = readLock.withLock {
                        if (row.key in readHolds) return@withLock false
                        val lastUsed = lastUsedMillis(metadata, recentUses[row.key])
                        if (lastUsed >= nowMillis() - grace) {
                            oldestSpared = minOf(oldestSpared ?: lastUsed, lastUsed)
                            return@withLock false
                        }
                        if (file != null) deleteContained(file, contained)
                        true
                    }
                    if (!taken) continue
                    cachedFileDao.delete(row.key)
                    remaining -= row.sizeBytes ?: 0L
                    evicted++
                }
            } finally {
                trackedBytes = remaining
            }
            // Still over: the rest is too recent to take, so scan again once the oldest of it
            // ages out.
            val spared = oldestSpared
            evictionIdleUntilMillis =
                if (remaining > lowWater && spared != null) spared + grace else Long.MIN_VALUE
            engineLogger.debug {
                "Storage cache evicted $evicted file(s) to stay within $limit bytes"
            }
        }
    }

    /**
     * When [row]'s file was last used: the later of the time on the file and the one [uses] holds,
     * or the earliest time there is when it has no file.
     */
    private fun lastUsedMillis(row: CachedFileRow, uses: Map<String, Long>): Long {
        val file = entryForRow(row)?.file ?: return Long.MIN_VALUE
        return lastUsedMillis(fileSystem.metadataOrNull(file), uses[row.key])
    }

    /**
     * When a file with [metadata] was last used: the later of the time on it, when the platform
     * reports one (okio on the JVM reports none for an epoch of zero), and [useInMemory]; the
     * earliest time there is when the file is missing.
     */
    private fun lastUsedMillis(metadata: FileMetadata?, useInMemory: Long?): Long =
        if (metadata == null) Long.MIN_VALUE
        else maxOf(metadata.lastModifiedAtMillis ?: Long.MIN_VALUE, useInMemory ?: Long.MIN_VALUE)

    /**
     * Runs [block] on the IO dispatcher to the end, and hands its result back even to a caller
     * canceled meanwhile. A bare `withContext` would drop it, and with it a file only that caller
     * can clean up.
     */
    private suspend fun <T> onIoHandingBackItsResult(block: suspend () -> T): T =
        withContext(NonCancellable) { withContext(ioDispatcher) { block() } }

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

    /** Whether [file]'s directory resolves inside the data directory. */
    private fun directoryContains(file: Path): Boolean {
        val directory = file.parent ?: return false
        return resolvesInsideDataDirectory(directory)
    }

    /**
     * Deletes [file] when its directory resolves inside the data directory ([contained], looked up
     * now or earlier). A symlink at [file] itself is removed as a link; its target is never
     * touched.
     */
    private fun deleteContained(file: Path, contained: Boolean = directoryContains(file)) {
        if (!contained) {
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

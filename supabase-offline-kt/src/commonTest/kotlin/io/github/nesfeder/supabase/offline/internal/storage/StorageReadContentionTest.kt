// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.RecordingCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * An offline read of a cached file — a download answered from the cache, or the cache's own
 * `readBytes` — waits for no commit, eviction pass or clear: a read pins its file apart from the
 * cache's lock, and an eviction pass looks at the files outside it, and at the ones it chose again
 * without the read side's lock. A commit held writing its row, and an eviction pass held looking at
 * a file in either of its phases, each leave a concurrent read of another file to finish in bounded
 * time.
 */
class StorageReadContentionTest {

    private lateinit var dataDirectory: Path
    private lateinit var dao: RecordingCachedFileDao
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi
    private val fileSystem = FakeFailingFileSystem()
    private var now = 0L

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-read-contention-${Random.nextLong().toULong()}"
        dao = RecordingCachedFileDao()
        store = newTestStore(cachedFileDao = dao)
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private class StorageTestBucket(val api: CachingBucketApi, val cache: StorageFileCache)

    private fun bucket(maxSizeBytes: Long?): StorageTestBucket {
        val cache =
            newTestFileCache(
                baseDirectory = dataDirectory,
                store = store,
                fileSystem = fileSystem,
                maxSizeBytes = maxSizeBytes,
                nowMillis = { now },
            )
        return StorageTestBucket(
            newTestBucketApi(
                delegate = delegate,
                baseDirectory = dataDirectory,
                store = store,
                cache = cache,
            ),
            cache,
        )
    }

    /** Downloads [path] with [bytes] through [bucket] at time [at]. */
    private suspend fun download(
        bucket: StorageTestBucket,
        path: String,
        bytes: ByteArray,
        at: Long,
    ) {
        now = at
        delegate.onDownload = { bytes }
        bucket.api.downloadAuthenticated(path) {}
    }

    /**
     * Reads [path] through [bucket] with the network down, within [READ_BOUND] of real time.
     *
     * @return the cached bytes, or `null` when the read took longer than that.
     */
    private suspend fun boundedOfflineRead(bucket: StorageTestBucket, path: String): ByteArray? {
        delegate.onDownload = { throw IOException("network unreachable") }
        return withContext(Dispatchers.Default) {
            withTimeoutOrNull(READ_BOUND) { bucket.api.downloadAuthenticated(path) {} }
        }
    }

    /** Waits, on real time, for [deferred]. */
    private suspend fun <T> awaitSoon(deferred: Deferred<T>): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { deferred.await() } }

    /**
     * Holds the calling thread until [released] completes, as a slow disk holds a look at a file.
     */
    private fun holdUntil(released: CompletableDeferred<Unit>) {
        val started = TimeSource.Monotonic.markNow()
        while (!released.isCompleted) {
            check(started.elapsedNow() < HOLD_LIMIT) { "The eviction pass was never released" }
        }
    }

    @Test
    fun an_offline_read_does_not_wait_for_a_commit_held_in_the_database() = runTest {
        // Given — a cached, and a download of b whose commit is held writing its row, under the
        // cache's lock
        val bucket = bucket(maxSizeBytes = null)
        download(bucket, "a.bin", A, at = 1 * MINUTE)
        val gate = CompletableDeferred<Unit>()
        dao.upsertGate = gate
        dao.upsertHeld = CompletableDeferred()
        now = 2 * MINUTE
        delegate.onDownload = { B }
        val commit = async(Dispatchers.Default) { bucket.api.downloadAuthenticated("b.bin") {} }
        awaitSoon(dao.upsertHeld)

        // When — a is read offline, and straight from the cache, while the commit holds the lock
        val read = boundedOfflineRead(bucket, "a.bin")
        val entry = assertNotNull(bucket.cache.entryFor(BUCKET, "a.bin"))
        val readBytes =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(READ_BOUND) { bucket.cache.readBytes(entry) }
            }

        // Then — neither read waited behind the commit
        assertContentEquals(A, assertNotNull(read, "the offline read waited behind the commit"))
        assertContentEquals(A, assertNotNull(readBytes, "readBytes waited behind the commit"))
        gate.complete(Unit)
        assertContentEquals(B, awaitSoon(commit))
        assertNotNull(store.getStorageByKey(BUCKET, "b.bin"))
    }

    @Test
    fun an_offline_read_does_not_wait_for_an_eviction_pass_held_at_a_file() = runTest {
        // Given — a and b in a bounded cache, and a download of c that takes it past the bound,
        // whose eviction pass is held looking at b's file
        val bucket = bucket(maxSizeBytes = 20)
        download(bucket, "a.bin", A, at = 1 * MINUTE)
        download(bucket, "b.bin", B, at = 2 * MINUTE)
        val passHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fileSystem.onMetadata = { path ->
            if (path.name == "b.bin") {
                passHeld.complete(Unit)
                holdUntil(release)
            }
        }
        now = 3 * MINUTE
        delegate.onDownload = { C }
        val download = async(Dispatchers.Default) { bucket.api.downloadAuthenticated("c.bin") {} }
        awaitSoon(passHeld)

        // When — a is read offline while the pass is held
        val read = boundedOfflineRead(bucket, "a.bin")

        // Then — the read did not wait behind the pass; once released, the pass brought the cache
        // within its bound, sparing a, which the read had just used, and taking b
        assertContentEquals(A, assertNotNull(read, "the offline read waited behind the pass"))
        release.complete(Unit)
        assertContentEquals(C, awaitSoon(download))
        assertTrue(store.cachedFileDao.totalSizeBytes() <= 20)
        assertNotNull(store.getStorageByKey(BUCKET, "a.bin"))
        assertNull(store.getStorageByKey(BUCKET, "b.bin"))
        assertNotNull(store.getStorageByKey(BUCKET, "c.bin"))
    }

    @Test
    fun an_offline_read_does_not_wait_for_an_eviction_pass_held_at_the_file_it_chose() = runTest {
        // Given — a and b in a bounded cache, and a download of c that takes it past the bound,
        // whose eviction pass is held looking again, under the cache's lock, at a: the file it
        // chose to evict
        val bucket = bucket(maxSizeBytes = 20)
        download(bucket, "a.bin", A, at = 1 * MINUTE)
        download(bucket, "b.bin", B, at = 2 * MINUTE)
        val looksAtA = AtomicInt(0)
        val passHeld = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fileSystem.onMetadata = { path ->
            // The pass's first look at a ranks it; its second, once chosen, is the one held
            if (path.name == "a.bin" && looksAtA.addAndFetch(1) == 2) {
                passHeld.complete(Unit)
                holdUntil(release)
            }
        }
        now = 3 * MINUTE
        delegate.onDownload = { C }
        val download = async(Dispatchers.Default) { bucket.api.downloadAuthenticated("c.bin") {} }
        awaitSoon(passHeld)

        // When — b is read offline while the pass holds the cache's lock
        val read = boundedOfflineRead(bucket, "b.bin")

        // Then — the read did not wait behind the pass; once released, the pass took a
        assertContentEquals(B, assertNotNull(read, "the offline read waited behind the pass"))
        release.complete(Unit)
        assertContentEquals(C, awaitSoon(download))
        assertTrue(store.cachedFileDao.totalSizeBytes() <= 20)
        assertNull(store.getStorageByKey(BUCKET, "a.bin"))
        assertNotNull(store.getStorageByKey(BUCKET, "b.bin"))
        assertNotNull(store.getStorageByKey(BUCKET, "c.bin"))
    }

    private companion object {
        const val BUCKET = "media"
        const val MINUTE = 60_000L
        val READ_BOUND: Duration = 5.seconds
        val HOLD_LIMIT: Duration = 15.seconds
        val A = ByteArray(6) { 1 }
        val B = ByteArray(6) { 2 }
        val C = ByteArray(10) { 3 }
    }
}

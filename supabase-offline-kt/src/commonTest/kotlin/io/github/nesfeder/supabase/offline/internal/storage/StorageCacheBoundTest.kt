// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.REJECTED_MUTATIONS_SIGNATURE
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.UNCONFIRMED_SENDS_SIGNATURE
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The file cache stays within its size bound by evicting the least recently used files — never one
 * a read is using, and never one used in the last minute: the one just downloaded, or a
 * `CachedFile` handed to a caller who has yet to open it. Once over the bound it evicts down to a
 * low-water mark below it, so a full cache does not rescan itself on every download.
 *
 * Uses are spaced minutes apart on the test clock, past that grace window.
 */
class StorageCacheBoundTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private var now = 0L

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-bound-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun cache(maxSizeBytes: Long?) =
        newTestFileCache(
            baseDirectory = dataDirectory,
            store = store,
            maxSizeBytes = maxSizeBytes,
            nowMillis = { now },
        )

    /** Downloads [path] (of [size] bytes) through [bucket] at time [at]. */
    private suspend fun download(bucket: StorageTestBucket, path: String, size: Int, at: Long) {
        now = at
        delegate.onDownload = { ByteArray(size) { 7 } }
        bucket.api.downloadAuthenticated(path) {}
    }

    private class StorageTestBucket(val api: CachingBucketApi, val cache: StorageFileCache)

    private fun bucket(maxSizeBytes: Long?): StorageTestBucket {
        val cache = cache(maxSizeBytes)
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

    @Test
    fun the_least_recently_used_file_is_evicted_to_stay_within_the_bound() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)

        // When — a second file takes the cache past the bound
        download(bucket, "b.bin", size = 6, at = 2 * MINUTE)

        // Then
        assertNull(bucket.api.cachedFile("a.bin"))
        assertNotNull(bucket.api.cachedFile("b.bin"))
        assertEquals(6, store.totalStorageSizeBytes())
    }

    @Test
    fun a_file_that_was_used_outlives_one_that_was_not() = runTest {
        // Given — a is older than b, but used after it
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 4, at = 1 * MINUTE)
        download(bucket, "b.bin", size = 4, at = 2 * MINUTE)
        now = 3 * MINUTE
        assertNotNull(bucket.api.cachedFile("a.bin"))

        // When
        download(bucket, "c.bin", size = 4, at = 4 * MINUTE)

        // Then — b is the least recently used
        assertNotNull(bucket.api.cachedFile("a.bin"))
        assertNull(bucket.api.cachedFile("b.bin"))
        assertNotNull(bucket.api.cachedFile("c.bin"))
    }

    @Test
    fun a_file_being_read_is_never_evicted() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)
        val entry = assertNotNull(bucket.cache.entryFor(BUCKET, "a.bin"))

        // When — while a read holds a, a download takes the cache past the bound
        bucket.cache.withPinned(entry) {
            download(bucket, "b.bin", size = 6, at = 2 * MINUTE)
            // Then — a stays for as long as it is being read
            assertNotNull(bucket.api.cachedFile("a.bin"))
        }

        // And once the read is done, the next download evicts it
        download(bucket, "c.bin", size = 2, at = 3 * MINUTE)
        assertNull(bucket.api.cachedFile("a.bin"))
        assertNotNull(bucket.api.cachedFile("b.bin"))
        assertNotNull(bucket.api.cachedFile("c.bin"))
    }

    @Test
    fun an_object_larger_than_the_bound_stays_until_something_newer_arrives() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 5)

        // When
        download(bucket, "big.bin", size = 8, at = 1 * MINUTE)

        // Then — the download that was just made is never its own victim
        assertNotNull(bucket.api.cachedFile("big.bin"))

        // And the next download evicts it
        download(bucket, "small.bin", size = 2, at = 2 * MINUTE)
        assertNull(bucket.api.cachedFile("big.bin"))
        assertNotNull(bucket.api.cachedFile("small.bin"))
    }

    @Test
    fun without_a_bound_nothing_is_evicted() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = null)

        // When
        repeat(5) { index -> download(bucket, "file-$index.bin", size = 100, at = index * MINUTE) }

        // Then
        assertEquals(500, store.totalStorageSizeBytes())
        repeat(5) { index -> assertNotNull(bucket.api.cachedFile("file-$index.bin")) }
    }

    @Test
    fun a_cached_file_handed_out_is_not_evicted_before_it_is_opened() = runTest {
        // Given — a caller was just handed a's file
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)
        now = 5 * MINUTE
        val handedOut = assertNotNull(bucket.api.cachedFile("a.bin"))

        // When — the next download takes the cache past the bound moments later
        download(bucket, "b.bin", size = 6, at = 5 * MINUTE + 1_000)

        // Then — the caller can still open it
        assertTrue(
            FileSystem.SYSTEM.exists(handedOut.path.toPath()),
            "a CachedFile handed out moments ago was evicted",
        )
    }

    @Test
    fun a_download_just_committed_is_not_evicted_before_it_is_delivered() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)

        // When — b commits, and c commits before b's caller has read it
        download(bucket, "b.bin", size = 6, at = 5 * MINUTE)
        download(bucket, "c.bin", size = 6, at = 5 * MINUTE + 500)

        // Then — only the old file was evicted; both fresh ones stay until they have been used
        assertNull(bucket.api.cachedFile("a.bin"))
        assertNotNull(bucket.api.cachedFile("b.bin"))
        assertNotNull(bucket.api.cachedFile("c.bin"))
    }

    @Test
    fun a_full_cache_does_not_rescan_every_row_on_every_download() = runTest {
        // Given — 100 files of 10 bytes, a bound of exactly 1,000
        val dao = RecordingStorageDao()
        store = newTestOfflineStore(storageDao = dao)
        val bucket = bucket(maxSizeBytes = 1_000)
        repeat(100) { index -> download(bucket, "fill-$index.bin", size = 10, at = index * MINUTE) }
        dao.resetCounts()

        // When — 50 more downloads, each taking the cache past the bound by one file
        repeat(50) { index ->
            download(bucket, "new-$index.bin", size = 10, at = (100 + index) * MINUTE)
        }

        // Then — it evicted down to a low-water mark, so only about one download in ten rescans,
        // and the running total was never summed again
        assertTrue(
            dao.scans <= 6,
            "the cache rescanned its rows ${dao.scans} times over 50 downloads",
        )
        assertEquals(0, dao.totalSizeQueries)
        assertTrue(store.totalStorageSizeBytes() <= 1_000)
    }

    @Test
    fun a_cache_whose_files_were_all_used_moments_ago_is_not_rescanned_on_every_download() =
        runTest {
            // Given — a full cache whose every file was used within the last minute
            val dao = RecordingStorageDao()
            store = newTestOfflineStore(storageDao = dao)
            val bucket = bucket(maxSizeBytes = 100)
            repeat(10) { index ->
                download(bucket, "fill-$index.bin", size = 10, at = 10 * MINUTE + index)
            }
            dao.resetCounts()

            // When — ten more downloads within the same few seconds, each over the bound
            repeat(10) { index ->
                download(bucket, "new-$index.bin", size = 10, at = 10 * MINUTE + 100 + index)
            }

            // Then — nothing was old enough to evict, and the cache looked once, not ten times
            assertTrue(
                dao.scans <= 1,
                "the cache rescanned ${dao.scans} times while nothing could be evicted",
            )

            // And once those uses are old enough, the next download brings the cache back under its
            // bound
            download(bucket, "later.bin", size = 10, at = 12 * MINUTE)
            assertTrue(store.totalStorageSizeBytes() <= 100)
        }

    @Test
    fun eviction_leaves_the_mutation_queues_reserved_rows_alone() = runTest {
        // Given — the mutation queue's reserved rows, and a file cache at its bound
        val objectDao = FakeObjectDao()
        val reservedSignatures = listOf(REJECTED_MUTATIONS_SIGNATURE, UNCONFIRMED_SENDS_SIGNATURE)
        reservedSignatures.forEach { signature ->
            objectDao.seedPayload(RESERVED_TABLE_NAME, signature, "{}")
        }
        store = newTestOfflineStore(objectDao = objectDao)
        val bucket = bucket(maxSizeBytes = 10)

        // When — downloads push the cache over its bound, again and again
        repeat(5) { index -> download(bucket, "file-$index.bin", size = 6, at = index * MINUTE) }

        // Then — the size accounting and eviction worked on files only
        assertTrue(store.totalStorageSizeBytes() <= 10)
        reservedSignatures.forEach { signature ->
            assertNotNull(objectDao.getRow(RESERVED_TABLE_NAME, signature), signature)
        }
        assertEquals(0, objectDao.evictedRows)
    }

    private companion object {
        const val BUCKET = "media"
        const val MINUTE = 60_000L
    }
}

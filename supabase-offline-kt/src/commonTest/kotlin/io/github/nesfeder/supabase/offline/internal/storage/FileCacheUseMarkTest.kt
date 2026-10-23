// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
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
import okio.SYSTEM

/**
 * A lookup of a cached file records its use, for least-recently-used eviction, but writes it to the
 * file at most once a minute: a grid re-binding the same thumbnails writes no metadata. A use left
 * off the file still spares it from eviction for the 30-second grace, as a written one does.
 */
class FileCacheUseMarkTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi
    private var now = 0L

    /** The time of every use written to a file named [name], in order. */
    private val marks = mutableListOf<Pair<String, Long>>()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-use-mark-${Random.nextLong().toULong()}"
        store = newTestStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket(maxSizeBytes: Long?): CachingBucketApi =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    maxSizeBytes = maxSizeBytes,
                    nowMillis = { now },
                    markUsed = { file, at ->
                        marks += file.name to at
                        markFileUsed(file, at)
                    },
                ),
        )

    /** The times the uses of the file named [name] were written, in order. */
    private fun marksOf(name: String): List<Long> =
        marks.filter { (file, _) -> file == name }.map { (_, at) -> at }

    /** Downloads [path] (of [size] bytes) through [bucket] at time [at]. */
    private suspend fun download(bucket: CachingBucketApi, path: String, size: Int, at: Long) {
        now = at
        delegate.onDownload = { ByteArray(size) { 7 } }
        bucket.downloadAuthenticated(path) {}
    }

    @Test
    fun sixty_lookups_within_a_minute_write_the_files_use_once() = runTest {
        // Given — a cached file, its use last written when it was downloaded
        val bucket = bucket(maxSizeBytes = 1_000)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)
        assertEquals(listOf(1 * MINUTE), marksOf("a.bin"))

        // When — a grid binds it sixty times over the next minute
        repeat(60) { index ->
            now = 5 * MINUTE + index * 1_000L
            assertNotNull(bucket.cachedFile("a.bin"))
        }

        // Then — the first lookup wrote the use, and the rest left the file alone
        assertEquals(listOf(1 * MINUTE, 5 * MINUTE), marksOf("a.bin"))

        // And a minute on from the written use, the next lookup writes it again
        now = 6 * MINUTE
        assertNotNull(bucket.cachedFile("a.bin"))
        assertEquals(listOf(1 * MINUTE, 5 * MINUTE, 6 * MINUTE), marksOf("a.bin"))
    }

    @Test
    fun a_lookup_after_the_clock_went_back_writes_the_use() = runTest {
        // Given — a file whose written use lies in the future, as a clock set back leaves it
        val bucket = bucket(maxSizeBytes = 1_000)
        download(bucket, "a.bin", size = 6, at = 5 * MINUTE)

        // When
        now = 4 * MINUTE
        assertNotNull(bucket.cachedFile("a.bin"))

        // Then — written, so the file's use follows the clock
        assertEquals(listOf(5 * MINUTE, 4 * MINUTE), marksOf("a.bin"))
    }

    @Test
    fun a_use_left_off_the_file_still_spares_it_for_the_grace() = runTest {
        // Given — a, used at 5:00 (written to the file) and at 5:50 (left off it)
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1 * MINUTE)
        now = 5 * MINUTE
        assertNotNull(bucket.cachedFile("a.bin"))
        now = 5 * MINUTE + 50_000L
        assertNotNull(bucket.cachedFile("a.bin"))
        assertEquals(listOf(1 * MINUTE, 5 * MINUTE), marksOf("a.bin"))

        // When — twenty seconds later a download takes the cache past its bound
        download(bucket, "b.bin", size = 6, at = 6 * MINUTE + 10_000L)

        // Then — a was used within the grace, whatever its file says, so it stays
        assertNotNull(
            store.getStorageByKey(BUCKET, "a.bin"),
            "a file used twenty seconds ago was evicted",
        )
        assertNotNull(store.getStorageByKey(BUCKET, "b.bin"))

        // And once that use is older than the grace, a is the least recently used
        download(bucket, "c.bin", size = 2, at = 6 * MINUTE + 21_000L)
        assertNull(store.getStorageByKey(BUCKET, "a.bin"))
        assertNotNull(store.getStorageByKey(BUCKET, "b.bin"))
        assertNotNull(store.getStorageByKey(BUCKET, "c.bin"))
    }

    @Test
    fun hundreds_of_files_looked_up_within_a_minute_are_evicted_in_the_order_they_were_used() =
        runTest {
            // Given — more files than the uses kept in memory go unpruned, all written at 1:00,
            // then
            // looked up one after another from 1:01, which writes none of those uses to a file;
            // the first is looked up again at 1:45
            val bucket = bucket(maxSizeBytes = 265)
            repeat(260) { index -> download(bucket, "f-$index.bin", size = 1, at = 1 * MINUTE) }
            repeat(260) { index ->
                now = 1 * MINUTE + 1_000L + index * 100L
                assertNotNull(bucket.cachedFile("f-$index.bin"))
            }
            now = 1 * MINUTE + 45_000L
            assertNotNull(bucket.cachedFile("f-0.bin"))
            assertEquals(260, marks.size)

            // When — at 2:00 a download takes the cache past its bound, to a low-water mark 31
            // bytes below the total
            download(bucket, "big.bin", size = 10, at = 2 * MINUTE)

            // Then — the file used within the grace stays; of the rest, the 31 looked up first
            // went, in the order of their lookups, and the later ones stay
            assertNotNull(store.getStorageByKey(BUCKET, "f-0.bin"))
            (1..31).forEach { index -> assertNull(store.getStorageByKey(BUCKET, "f-$index.bin")) }
            (32 until 260).forEach { index ->
                assertNotNull(store.getStorageByKey(BUCKET, "f-$index.bin"), "f-$index.bin")
            }
            assertNotNull(store.getStorageByKey(BUCKET, "big.bin"))
            assertTrue(store.cachedFileDao.totalSizeBytes() <= 265 - 26)
        }

    private companion object {
        const val BUCKET = "media"
        const val MINUTE = 60_000L
    }
}

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The file cache stays within its size bound by evicting the least recently used files — never
 * one a read is using, and never the one just downloaded.
 */
class StorageCacheBoundTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private var now = 0L

    @BeforeTest
    fun setUp() {
        dataDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-bound-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun cache(maxSizeBytes: Long?) = newTestFileCache(
        baseDirectory = dataDirectory,
        store = store,
        maxSizeBytes = maxSizeBytes,
        nowMillis = { now },
    )

    /** Downloads [path] (of [size] bytes) through [bucket] at time [at]. */
    private suspend fun download(bucket: StorageTestBucket, path: String, size: Int, at: Long) {
        now = at
        delegate.onDownload = { ByteArray(size) { 7 } }
        bucket.api.downloadAuthenticated(path) { }
    }

    private class StorageTestBucket(val api: OfflineBucketApiImpl, val cache: StorageFileCache)

    private fun bucket(maxSizeBytes: Long?): StorageTestBucket {
        val cache = cache(maxSizeBytes)
        return StorageTestBucket(newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store, cache = cache), cache)
    }

    @Test
    fun the_least_recently_used_file_is_evicted_to_stay_within_the_bound() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1_000)

        // When — a second file takes the cache past the bound
        download(bucket, "b.bin", size = 6, at = 2_000)

        // Then
        assertNull(bucket.api.cachedFile("a.bin"))
        assertNotNull(bucket.api.cachedFile("b.bin"))
        assertEquals(6, store.totalStorageSizeBytes())
    }

    @Test
    fun a_file_that_was_used_outlives_one_that_was_not() = runTest {
        // Given — a is older than b, but used after it
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 4, at = 1_000)
        download(bucket, "b.bin", size = 4, at = 2_000)
        now = 3_000
        assertNotNull(bucket.api.cachedFile("a.bin"))

        // When
        download(bucket, "c.bin", size = 4, at = 4_000)

        // Then — b is the least recently used
        assertNotNull(bucket.api.cachedFile("a.bin"))
        assertNull(bucket.api.cachedFile("b.bin"))
        assertNotNull(bucket.api.cachedFile("c.bin"))
    }

    @Test
    fun a_file_being_read_is_never_evicted() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 10)
        download(bucket, "a.bin", size = 6, at = 1_000)
        val entry = assertNotNull(bucket.cache.entryFor(BUCKET, "a.bin"))

        // When — while a read holds a, a download takes the cache past the bound
        bucket.cache.withPinned(entry) {
            download(bucket, "b.bin", size = 6, at = 2_000)
            // Then — a stays for as long as it is being read
            assertNotNull(bucket.api.cachedFile("a.bin"))
        }

        // And once the read is done, the next download evicts it
        download(bucket, "c.bin", size = 2, at = 3_000)
        assertNull(bucket.api.cachedFile("a.bin"))
        assertNotNull(bucket.api.cachedFile("b.bin"))
        assertNotNull(bucket.api.cachedFile("c.bin"))
    }

    @Test
    fun an_object_larger_than_the_bound_stays_until_something_newer_arrives() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = 5)

        // When
        download(bucket, "big.bin", size = 8, at = 1_000)

        // Then — the download that was just made is never its own victim
        assertNotNull(bucket.api.cachedFile("big.bin"))

        // And the next download evicts it
        download(bucket, "small.bin", size = 2, at = 2_000)
        assertNull(bucket.api.cachedFile("big.bin"))
        assertNotNull(bucket.api.cachedFile("small.bin"))
    }

    @Test
    fun without_a_bound_nothing_is_evicted() = runTest {
        // Given
        val bucket = bucket(maxSizeBytes = null)

        // When
        repeat(5) { index -> download(bucket, "file-$index.bin", size = 100, at = index * 1_000L) }

        // Then
        assertEquals(500, store.totalStorageSizeBytes())
        repeat(5) { index -> assertNotNull(bucket.api.cachedFile("file-$index.bin")) }
    }

    private companion object {
        const val BUCKET = "media"
    }
}

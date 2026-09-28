package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.notFoundRestException
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * An object path can never reach a file outside the storage cache.
 *
 * Object paths often come from rows other users wrote, so a path is untrusted input to every file
 * operation the offline bucket performs: the eviction after a not-found download, `delete`, `move`,
 * `copy` and the cache write itself. Each case runs against the real file system with a sentinel
 * file beside the data directory, and asserts the sentinel survives untouched and nothing is
 * created outside the data directory.
 */
class StoragePathContainmentTest {

    private lateinit var root: Path
    private lateinit var dataDirectory: Path
    private lateinit var sentinel: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi

    @BeforeTest
    fun setUp() {
        root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-containment-${Random.nextLong().toULong()}"
        dataDirectory = root / "data"
        sentinel = root / SENTINEL_NAME
        FileSystem.SYSTEM.createDirectories(dataDirectory)
        FileSystem.SYSTEM.write(sentinel) { writeUtf8(SENTINEL_CONTENT) }
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }

    private fun bucket() = newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store)

    private fun assertNothingEscaped() {
        assertTrue(FileSystem.SYSTEM.exists(sentinel), "the file outside the cache was deleted")
        assertEquals(SENTINEL_CONTENT, FileSystem.SYSTEM.read(sentinel) { readUtf8() }, "the file outside the cache was overwritten")
        assertEquals(
            setOf("data", SENTINEL_NAME),
            FileSystem.SYSTEM.list(root).map { it.name }.toSet(),
            "a file was created outside the data directory",
        )
    }

    private suspend fun primeCache(path: String) {
        delegate.onDownload = { "cached".encodeToByteArray() }
        bucket().downloadAuthenticated(path) { }
    }

    @Test
    fun a_not_found_download_never_evicts_outside_the_cache() = runTest {
        // Given — the bucket's cache directory exists (so the climbing path resolves on disk), and
        // the server reports the climbing path missing, which evicts its cache entry
        primeCache("legit.txt")
        delegate.onDownload = { throw notFoundRestException() }

        // When
        assertFailsWith<Exception> { bucket().downloadAuthenticated(CLIMBING_PATH) { } }

        // Then
        assertNothingEscaped()
    }

    @Test
    fun delete_never_deletes_outside_the_cache() = runTest {
        // Given — the bucket's cache directory exists, so the climbing path resolves on disk
        primeCache("legit.txt")

        // When
        bucket().delete(listOf(CLIMBING_PATH))

        // Then — the server still received the delete
        assertEquals(listOf<Collection<String>>(listOf(CLIMBING_PATH)), delegate.deletedPaths)
        assertNothingEscaped()
    }

    @Test
    fun move_never_overwrites_outside_the_cache() = runTest {
        // Given
        primeCache("legit.txt")

        // When
        bucket().move(from = "legit.txt", to = CLIMBING_PATH, destinationBucket = null)

        // Then
        assertNothingEscaped()
    }

    @Test
    fun move_never_takes_a_file_from_outside_the_cache() = runTest {
        // Given — the bucket's cache directory exists, so the climbing path resolves on disk
        primeCache("legit.txt")

        // When
        bucket().move(from = CLIMBING_PATH, to = "moved.txt", destinationBucket = null)

        // Then
        assertNothingEscaped()
    }

    @Test
    fun move_to_a_climbing_destination_bucket_never_writes_outside_the_cache() = runTest {
        // Given
        primeCache("legit.txt")

        // When — the bucket name is a path segment too
        bucket().move(from = "legit.txt", to = SENTINEL_NAME, destinationBucket = "../..")

        // Then
        assertNothingEscaped()
    }

    @Test
    fun copy_never_touches_outside_the_cache() = runTest {
        // Given
        primeCache("legit.txt")

        // When
        bucket().copy(from = "legit.txt", to = CLIMBING_PATH, destinationBucket = null)

        // Then
        assertNothingEscaped()
    }

    @Test
    fun a_download_never_writes_outside_the_cache() = runTest {
        // Given — the bucket's cache directory exists, so the climbing path resolves on disk
        primeCache("legit.txt")
        delegate.onDownload = { "escaped".encodeToByteArray() }

        // When
        val bytes = bucket().downloadAuthenticated("../../../escaped.txt") { }

        // Then — the caller still gets the bytes; they are just not cached
        assertContentEquals("escaped".encodeToByteArray(), bytes)
        assertNothingEscaped()
    }

    @Test
    fun an_absolute_object_path_is_never_used_as_a_file_path() = runTest {
        // Given — "//" followed by an absolute path to a file beside the sentinel
        delegate.onDownload = { "escaped".encodeToByteArray() }
        val absolute = "/" + (root / "absolute.txt").toString()

        // When
        val bytes = bucket().downloadAuthenticated(absolute) { }

        // Then
        assertContentEquals("escaped".encodeToByteArray(), bytes)
        assertNothingEscaped()
    }

    @Test
    fun paths_the_cache_cannot_hold_are_downloaded_without_touching_the_disk() = runTest {
        // Given — a leading slash, an empty segment, a trailing slash, dot segments, and NUL and
        // other control characters
        delegate.onDownload = { "bytes".encodeToByteArray() }
        val uncacheable = listOf(
            "/leading.txt",
            "a//b.txt",
            "folder/",
            "./here.txt",
            "a/./b.txt",
            "a/../b.txt",
            "nul\u0000.txt",
            "line\nbreak.txt",
            "tab\t.txt",
            "delete\u007F.txt",
        )

        // When
        val results = uncacheable.map { path -> bucket().downloadAuthenticated(path) { } }

        // Then — every download returned its bytes and nothing was written anywhere
        results.forEach { bytes -> assertContentEquals("bytes".encodeToByteArray(), bytes) }
        assertEquals(
            emptyList(),
            FileSystem.SYSTEM.listRecursively(dataDirectory).filter { FileSystem.SYSTEM.metadata(it).isRegularFile }.toList(),
        )
        assertNothingEscaped()
    }

    @Test
    fun a_cache_row_that_points_outside_the_cache_is_never_followed() = runTest {
        // Given — a row whose recorded local path is the sentinel (a tampered or foreign database)
        store.addStorage(bucketId = BUCKET, path = "tampered.txt", file = sentinel, size = 7, eTag = null)
        delegate.onDownload = { throw notFoundRestException() }

        // When — the not-found download evicts the row's file
        assertFailsWith<Exception> { bucket().downloadAuthenticated("tampered.txt") { } }

        // Then
        assertNothingEscaped()
    }

    private companion object {
        const val BUCKET = "avatars"
        const val SENTINEL_NAME = "sentinel.txt"
        const val SENTINEL_CONTENT = "keep me"

        /** From `<data>/storage/<bucket>/`, three levels up is the directory holding the sentinel. */
        const val CLIMBING_PATH = "../../../$SENTINEL_NAME"
    }
}

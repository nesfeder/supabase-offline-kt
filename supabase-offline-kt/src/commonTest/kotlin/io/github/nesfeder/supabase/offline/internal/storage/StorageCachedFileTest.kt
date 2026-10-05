// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.notFoundRestException
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The cached-file API: [OfflineBucketApi.cachedFile] reads the cache and never the network;
 * [OfflineBucketApi.ensureCachedFile] downloads on a miss, streaming to disk, and throws — never
 * returns `null` — when it cannot produce the file.
 */
class StorageCachedFileTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var delegate: FakeBucketApi

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-cached-file-${Random.nextLong().toULong()}"
        store = newTestStore(nowMillis = { FETCHED_AT_MILLIS })
        delegate = FakeBucketApi(bucketId = BUCKET)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(delegate = delegate, baseDirectory = dataDirectory, store = store)

    @Test
    fun cachedFile_is_null_before_anything_is_cached_and_never_downloads() = runTest {
        // Given

        // When
        val cached = bucket().cachedFile(PATH)

        // Then
        assertNull(cached)
        assertEquals(0, delegate.downloadInvocations)
    }

    @Test
    fun cachedFile_describes_a_downloaded_object() = runTest {
        // Given
        delegate.onDownload = { BYTES }
        bucket().downloadAuthenticated(PATH) {}

        // When
        val cached = assertNotNull(bucket().cachedFile(PATH))

        // Then — the path, when it was fetched, and its size
        assertEquals(
            (dataDirectory / "storage" / BUCKET / "u-1" / "me.png").toString(),
            cached.path,
        )
        assertEquals(Instant.fromEpochMilliseconds(FETCHED_AT_MILLIS), cached.fetchedAt)
        assertEquals(BYTES.size.toLong(), cached.sizeBytes)
        assertContentEquals(BYTES, FileSystem.SYSTEM.read(cached.path.toPath()) { readByteArray() })
        assertEquals(1, delegate.downloadInvocations)
    }

    @Test
    fun ensureCachedFile_streams_a_missing_object_to_disk_once() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When
        val first = bucket().ensureCachedFile(PATH)
        val second = bucket().ensureCachedFile(PATH)

        // Then — downloaded through the streaming overload, then served from the cache
        assertEquals(listOf(PATH), delegate.channelDownloadPaths)
        assertEquals(1, delegate.downloadInvocations)
        assertEquals(first.path, second.path)
        assertContentEquals(BYTES, FileSystem.SYSTEM.read(first.path.toPath()) { readByteArray() })
        assertEquals(BYTES.size.toLong(), first.sizeBytes)
    }

    @Test
    fun ensureCachedFile_downloads_public_objects_through_the_public_url() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When
        bucket().ensureCachedFile(PATH, isPublic = true)

        // Then
        assertEquals(listOf(PATH), delegate.publicDownloadPaths)
        assertEquals(emptyList(), delegate.authenticatedDownloadPaths)
    }

    @Test
    fun ensureCachedFile_throws_the_download_failure_when_nothing_is_cached() = runTest {
        // Given — offline is not "no such file"
        val failure = IOException("network unreachable")
        delegate.onDownload = { throw failure }

        // When
        val thrown = assertFailsWith<IOException> { bucket().ensureCachedFile(PATH) }

        // Then — the download's own failure (the coroutine machinery may hand over a copy of it)
        assertEquals(failure.message, thrown.message)
        assertNull(bucket().cachedFile(PATH))
    }

    @Test
    fun ensureCachedFile_rethrows_not_found_and_caches_nothing() = runTest {
        // Given
        delegate.onDownload = { throw notFoundRestException() }

        // When / Then
        assertFailsWith<NotFoundRestException> { bucket().ensureCachedFile(PATH) }
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun ensureCachedFile_propagates_cancellation_and_leaves_no_file() = runTest {
        // Given — a download that has written part of the object to disk when its caller is
        // canceled
        delegate.onDownload = { BYTES }
        delegate.channelChunkSize = 3
        val midway = CompletableDeferred<Unit>()
        delegate.onChannelChunk = { written ->
            if (written >= 3) {
                midway.complete(Unit)
                awaitCancellation()
            }
        }
        var returned = false
        var caught: Throwable? = null
        val job = launch {
            try {
                bucket().ensureCachedFile(PATH)
                returned = true
            } catch (failure: Throwable) {
                caught = failure
            }
        }
        midway.await()
        awaitPartialFile(dataDirectory)

        // When
        job.cancelAndJoin()

        // Then — canceled, and the partial file is gone
        assertFalse(returned)
        assertIs<CancellationException>(caught)
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        assertNull(store.getStorageByKey(BUCKET, PATH))
    }

    @Test
    fun ensureCachedFile_refuses_a_path_it_cannot_cache_without_downloading() = runTest {
        // Given

        // When / Then
        assertFailsWith<IllegalArgumentException> { bucket().ensureCachedFile("../escape.png") }
        assertEquals(0, delegate.downloadInvocations)
    }

    @Test
    fun cachedFile_is_null_for_a_path_it_cannot_cache() = runTest {
        // Given / When / Then
        assertNull(bucket().cachedFile("../escape.png"))
        assertNull(bucket().cachedFile("/leading.png"))
    }

    @Test
    fun a_bucket_with_a_header_set_still_caches() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When — setHeader, called through the public interface, returns the offline bucket
        val offline: OfflineBucketApi = bucket()
        val withHeader: OfflineBucketApi = offline.setHeader("x-trace", "1")
        withHeader.downloadAuthenticated(PATH) {}

        // Then — its cache API is reachable without a cast
        assertEquals("1", delegate.headers["x-trace"])
        assertNotNull(withHeader.cachedFile(PATH))
    }

    /** Waits, in real time, until a download has put a file under [directory]. */
    private suspend fun awaitPartialFile(directory: Path) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (regularFilesUnder(directory).isEmpty()) delay(5)
            }
        }
    }

    private fun regularFilesUnder(directory: Path): List<Path> =
        if (!FileSystem.SYSTEM.exists(directory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(directory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    @Test
    fun cachedFile_reports_the_size_of_the_file_on_disk() = runTest {
        // Given — the file was replaced and the process died before the row caught up
        delegate.onDownload = { BYTES }
        val bucket = bucket()
        val cached = bucket.ensureCachedFile(PATH)
        FileSystem.SYSTEM.write(cached.path.toPath()) { writeUtf8("a longer replacement avatar") }

        // When
        val reported = assertNotNull(bucket.cachedFile(PATH))

        // Then
        assertEquals("a longer replacement avatar".length.toLong(), reported.sizeBytes)
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val FETCHED_AT_MILLIS = 1_727_000_000_000L
        val BYTES = "a-cached-avatar".encodeToByteArray()
    }
}

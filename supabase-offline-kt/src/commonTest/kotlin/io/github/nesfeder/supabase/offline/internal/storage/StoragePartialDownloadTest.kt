// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download whose request the caller's own overrides, or a header set on the bucket, turned into
 * part of the object (a `Range` or `If-Range` header) or none of it (a `HEAD`) is the caller's,
 * never the cached copy: the cache holds whole objects only, so nothing of it is stored, it is
 * fetched once, and offline it is not answered from the whole copy either.
 *
 * These run the real supabase-kt bucket against [RecordingStorageServer], which answers a range
 * with HTTP 206 and a `HEAD` with no body, as Storage does, with the download status check the
 * client installs, which marks a partial request or answer on the download.
 */
class StoragePartialDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-partial-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket() =
        newTestBucketApi(
            delegate =
                server
                    .supabaseClient()
                    .also { it.installDownloadStatusCheck() }
                    .storage
                    .from(BUCKET),
            baseDirectory = dataDirectory,
            store = store,
        )

    private fun cachedFiles(): List<Path> =
        if (!FileSystem.SYSTEM.exists(dataDirectory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(dataDirectory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    /** Downloads [PATH] whole with the network down: what the offline bucket threw. */
    private suspend fun offlineWholeDownloadFailure(): Throwable {
        server.online = false
        return try {
            assertFailsWith<Exception> { bucket().downloadAuthenticated(PATH) {} }
        } finally {
            server.online = true
        }
    }

    @Test
    fun a_ranged_download_is_the_callers_and_never_the_cached_copy() = runTest {
        // Given / When — the first four bytes of the object
        val bytes =
            bucket().downloadAuthenticated(PATH) {
                httpOverride { header(HttpHeaders.Range, "bytes=0-3") }
            }

        // Then — the fragment was served; nothing was cached, so a whole download offline has
        // nothing to fall back on
        assertEquals(CONTENT.take(4), bytes.decodeToString())
        assertEquals("GET", server.requests.single().method)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
        val failure = offlineWholeDownloadFailure()
        assertTrue(
            failure.isNetworkOrConnectivityFailure(),
            "expected the connectivity failure, got $failure",
        )
    }

    @Test
    fun a_head_download_is_never_the_cached_copy() = runTest {
        // Given / When — the object's headers only
        val bytes =
            bucket().downloadAuthenticated(PATH) { httpOverride { method = HttpMethod.Head } }

        // Then — an empty body, which is not the object, and nothing cached
        assertEquals(0, bytes.size)
        assertEquals("HEAD", server.requests.single().method)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
        val failure = offlineWholeDownloadFailure()
        assertTrue(
            failure.isNetworkOrConnectivityFailure(),
            "expected the connectivity failure, got $failure",
        )
    }

    @Test
    fun a_ranged_channel_download_reaches_the_caller_and_is_never_the_cached_copy() = runTest {
        // Given
        val channel = ByteChannel()
        val received = async { channel.readRemaining().readByteArray() }

        // When — four bytes from the middle of the object, into a channel
        bucket().downloadAuthenticated(PATH, channel) {
            httpOverride { header(HttpHeaders.Range, "bytes=4-7") }
        }

        // Then — fetched once
        assertEquals(CONTENT.substring(4, 8), received.await().decodeToString())
        assertEquals(1, server.requests.size)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_head_channel_download_closes_the_channel_empty_and_is_never_the_cached_copy() = runTest {
        // Given
        val channel = ByteChannel()
        val received = async { channel.readRemaining().readByteArray() }

        // When
        bucket().downloadAuthenticated(PATH, channel) { httpOverride { method = HttpMethod.Head } }

        // Then — one request, no bytes, nothing cached
        assertEquals(0, received.await().size)
        assertEquals(listOf("HEAD"), server.requests.map { it.method })
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun an_if_range_download_is_never_the_cached_copy() = runTest {
        // Given / When — the server has no range to compare, so it answers the whole object
        val bytes =
            bucket().downloadAuthenticated(PATH) {
                httpOverride { header(HttpHeaders.IfRange, "\"etag\"") }
            }

        // Then — the whole object this time, but a request that can get part of it is never cached
        assertEquals(CONTENT, bytes.decodeToString())
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_range_header_set_on_the_bucket_is_never_cached_by_a_channel_download() = runTest {
        // Given — a bucket that asks for the first four bytes of everything, which supabase-kt adds
        // after the download's own overrides
        val ranged = bucket().setHeader(HttpHeaders.Range, "bytes=0-3")
        val channel = ByteChannel()
        val received = async { channel.readRemaining().readByteArray() }

        // When
        ranged.downloadAuthenticated(PATH, channel) {}

        // Then — the fragment reached the caller from one request, and nothing was cached
        assertEquals(CONTENT.take(4), received.await().decodeToString())
        assertEquals(1, server.requests.size)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_range_header_set_on_the_bucket_is_never_cached_by_a_bytes_download() = runTest {
        // Given
        val ranged = bucket().setHeader(HttpHeaders.Range, "bytes=0-3")

        // When
        val bytes = ranged.downloadAuthenticated(PATH) {}

        // Then
        assertEquals(CONTENT.take(4), bytes.decodeToString())
        assertEquals(1, server.requests.size)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun ensureCachedFile_refuses_a_fragment_a_range_header_set_on_the_bucket_fetched() = runTest {
        // Given
        val ranged = bucket().setHeader(HttpHeaders.Range, "bytes=0-3")

        // When
        val failure = assertFailsWith<IllegalStateException> { ranged.ensureCachedFile(PATH) }

        // Then — said so, from one request, with nothing cached
        assertIs<IllegalStateException>(failure)
        assertEquals(1, server.requests.size)
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertNull(ranged.cachedFile(PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_ranged_download_offline_is_not_answered_from_the_whole_cached_copy() = runTest {
        // Given — the whole object is cached
        assertEquals(CONTENT, bucket().downloadAuthenticated(PATH) {}.decodeToString())

        // When — a range of it is asked for with the network down
        server.online = false
        val failure =
            assertFailsWith<Exception> {
                bucket().downloadAuthenticated(PATH) {
                    httpOverride { header(HttpHeaders.Range, "bytes=0-3") }
                }
            }

        // Then — the failure, not the whole object in a fragment's place; the whole copy stays
        assertTrue(
            failure.isNetworkOrConnectivityFailure(),
            "expected the connectivity failure, got $failure",
        )
        server.online = true
        assertNotNull(bucket().cachedFile(PATH))
    }

    private companion object {
        const val BUCKET = "media"
        const val PATH = "u-1/clip.mp4"
        const val CONTENT = "0123456789abcdef"
    }
}

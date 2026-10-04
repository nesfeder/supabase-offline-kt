// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A channel download Storage answers with an error is that error, as the bytes download is: through
 * supabase-kt's own Storage plugin, whose channel download copies any answer's body into the
 * channel, the offline bucket checks the status before anything reaches the file cache, and throws
 * what the bytes download throws.
 */
class ChannelDownloadStatusTest {

    private val dataDirectory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-download-status-${Random.nextLong().toULong()}"
    private val server = RecordingStorageServer()
    private val client: OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            baseDirectoryProvider = { dataDirectory },
        )
    private val bucket: OfflineBucketApi
        get() = client.storage.from(BUCKET)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    @Test
    fun ensuring_a_missing_object_throws_what_the_bytes_download_throws_and_caches_nothing() =
        runTest {
            // Given — no object at the path
            val bytesFailure = failureOf { bucket.downloadAuthenticated(PATH) {} }

            // When — the app asks for the cached file
            val failure = failureOf { bucket.ensureCachedFile(PATH) }

            // Then — the same not-found, and nothing cached
            assertIs<NotFoundRestException>(bytesFailure)
            assertSameFailure(bytesFailure, failure)
            assertNull(bucket.cachedFile(PATH))
        }

    @Test
    fun ensuring_an_object_storage_refuses_throws_what_the_bytes_download_throws() = runTest {
        for (status in listOf(HttpStatusCode.Forbidden, HttpStatusCode.InternalServerError)) {
            // Given — a stored object Storage refuses to serve
            server.objects["$BUCKET/$PATH"] = CONTENT
            server.downloadRefusal = status
            val bytesFailure = failureOf { bucket.downloadAuthenticated(PATH) {} }

            // When — the app asks for the cached file
            val failure = failureOf { bucket.ensureCachedFile(PATH) }

            // Then — the bytes download's failure, and nothing cached
            assertSameFailure(bytesFailure, failure)
            assertEquals(status.value, (failure as RestException).statusCode)
            assertNull(bucket.cachedFile(PATH), "cached after a $status")
        }
    }

    @Test
    fun a_channel_download_of_a_missing_object_throws_and_caches_nothing() = runTest {
        // Given — no object at the path
        val bytesFailure = failureOf { bucket.downloadAuthenticated(PATH) {} }
        val channel = ByteChannel(autoFlush = true)

        // When — the app downloads it into a channel
        val failure = failureOf { bucket.downloadAuthenticated(PATH, channel) {} }

        // Then — the same not-found; the caller is handed no bytes, and nothing is cached
        assertSameFailure(bytesFailure, failure)
        assertEquals(0, bytesLeftIn(channel).size)
        assertNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_channel_download_of_an_object_deleted_on_the_server_drops_the_cached_copy() = runTest {
        // Given — an object cached on the device, then deleted on the server
        server.objects["$BUCKET/$PATH"] = CONTENT
        assertNotNull(bucket.ensureCachedFile(PATH))
        server.objects.remove("$BUCKET/$PATH")

        // When — the app downloads it into a channel
        val failure = failureOf { bucket.downloadAuthenticated(PATH, ByteChannel()) {} }

        // Then — the server's not-found, and the cached copy is gone
        assertIs<NotFoundRestException>(failure)
        assertNull(bucket.cachedFile(PATH))
    }

    @Test
    fun a_refused_channel_download_serves_the_cached_copy_as_the_bytes_download_does() = runTest {
        // Given — an object cached on the device, then refused by a policy
        server.objects["$BUCKET/$PATH"] = CONTENT
        assertNotNull(bucket.ensureCachedFile(PATH))
        server.downloadRefusal = HttpStatusCode.Forbidden
        val bytes = bucket.downloadAuthenticated(PATH) {}
        val channel = ByteChannel(autoFlush = true)

        // When — the app downloads it into a channel
        bucket.downloadAuthenticated(PATH, channel) {}

        // Then — both serve the cached copy, which stays as it was
        assertContentEquals(CONTENT, bytes)
        assertContentEquals(CONTENT, receivedBytes(channel))
        val cached = assertNotNull(bucket.cachedFile(PATH))
        assertContentEquals(CONTENT, FileSystem.SYSTEM.read(cached.path.toPath()) { readByteArray() })
    }

    @Test
    fun a_refused_channel_download_with_nothing_cached_throws_and_caches_nothing() = runTest {
        // Given — a stored object Storage refuses to serve, nothing cached
        server.objects["$BUCKET/$PATH"] = CONTENT
        server.downloadRefusal = HttpStatusCode.InternalServerError
        val bytesFailure = failureOf { bucket.downloadAuthenticated(PATH) {} }
        val channel = ByteChannel(autoFlush = true)

        // When — the app downloads it into a channel
        val failure = failureOf { bucket.downloadAuthenticated(PATH, channel) {} }

        // Then — the bytes download's failure; no bytes, nothing cached
        assertSameFailure(bytesFailure, failure)
        assertEquals(0, bytesLeftIn(channel).size)
        assertNull(bucket.cachedFile(PATH))
    }

    private suspend fun failureOf(call: suspend () -> Unit): Throwable =
        assertNotNull(runCatching { call() }.exceptionOrNull(), "the call returned")

    private fun assertSameFailure(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class, "thrown: $actual")
        assertEquals((expected as RestException).statusCode, (actual as RestException).statusCode)
        assertEquals(expected.error, actual.error)
    }

    private suspend fun receivedBytes(channel: ByteChannel): ByteArray =
        channel.readRemaining().readByteArray()

    /** What a failed download left in [channel], which it may not have closed. */
    private suspend fun bytesLeftIn(channel: ByteChannel): ByteArray {
        runCatching { channel.flushAndClose() }
        return receivedBytes(channel)
    }

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "u/photo.jpg"
        val CONTENT = "the photo's bytes".encodeToByteArray()
    }
}

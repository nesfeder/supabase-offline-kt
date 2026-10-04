// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.exceptions.NotFoundRestException
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.downloadAuthenticatedTo
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath

/**
 * supabase-kt's download-to-a-file helpers stream through the bucket's channel download, so through
 * the offline bucket a file Storage refuses is the refusal, as the bytes download's is, and none of
 * its error body reaches the file cache.
 */
class DownloadToFileStatusAndroidTest {

    private val directory: File = createTempDirectory("offline-download-to-file").toFile()
    private val server = RecordingStorageServer()
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            baseDirectoryProvider = { directory.toOkioPath() / "data" },
        )
    private val bucket = client.storage.from(BUCKET)

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun downloading_a_missing_object_to_a_file_throws_and_caches_nothing() = runTest {
        // Given — no object at the path
        val target = File(directory, "photo.jpg")

        // When / Then — the bytes download's not-found
        assertFailsWith<NotFoundRestException> { bucket.downloadAuthenticatedTo(PATH, target) }
        assertNull(bucket.cachedFile(PATH))
    }

    @Test
    fun downloading_an_object_storage_refuses_to_a_file_throws_and_caches_nothing() = runTest {
        // Given — a stored object a fault keeps Storage from serving
        server.objects["$BUCKET/$PATH"] = CONTENT
        server.downloadRefusal = HttpStatusCode.InternalServerError
        val target = File(directory, "photo.jpg")

        // When
        val failure =
            assertFailsWith<RestException> { bucket.downloadAuthenticatedTo(PATH, target) }

        // Then
        assertEquals(HttpStatusCode.InternalServerError.value, failure.statusCode)
        assertNull(bucket.cachedFile(PATH))
    }

    @Test
    fun downloading_a_stored_object_to_a_file_writes_it_and_caches_it() = runTest {
        // Given
        server.objects["$BUCKET/$PATH"] = CONTENT
        val target = File(directory, "photo.jpg")

        // When
        bucket.downloadAuthenticatedTo(PATH, target)

        // Then
        assertContentEquals(CONTENT, target.readBytes())
        assertContentEquals(CONTENT, File(checkNotNull(bucket.cachedFile(PATH)).path).readBytes())
    }

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "u/photo.jpg"
        val CONTENT = "the photo's bytes".encodeToByteArray()
    }
}

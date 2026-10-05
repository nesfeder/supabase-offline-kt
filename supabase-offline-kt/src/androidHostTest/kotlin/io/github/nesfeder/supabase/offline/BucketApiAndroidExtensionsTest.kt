// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.forbiddenRestException
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.Path.Companion.toPath
import org.junit.runner.RunWith

/**
 * The Android storage helpers (`ensureLocalFile`, `ensureLocalUri`, `localUriIfCached`) on the
 * offline bucket: download-through-cache, cache reuse without the network, a failure thrown rather
 * than turned into "not available", and `null` only from `localUriIfCached` with nothing cached.
 */
@RunWith(AndroidJUnit4::class)
class BucketApiAndroidExtensionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val baseDir: File = context.cacheDir.resolve("offline_storage")
    private val fakeBucket = FakeBucketApi(bucketId = "test-bucket")
    private val offlineApi =
        newTestBucketApi(delegate = fakeBucket, baseDirectory = baseDir.absolutePath.toPath())

    @AfterTest
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `ensureLocalFile returns file when public download succeeds`() = runTest {
        // Given
        fakeBucket.onDownload = { "content".toByteArray() }

        // When
        val file: File = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then
        assertTrue(file.exists())
        assertEquals("content", file.readText())
        assertEquals(listOf("test.txt"), fakeBucket.publicDownloadPaths)
    }

    @Test
    fun `ensureLocalFile returns cached file on network failure`() = runTest {
        // Given — a first download populates the cache, then the network fails
        fakeBucket.onDownload = { "cached content".toByteArray() }
        offlineApi.downloadPublic("test.txt") {}
        fakeBucket.onDownload = { throw IOException("Network error") }

        // When
        val file: File = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then — served from the cache without another network attempt
        assertTrue(file.exists())
        assertEquals("cached content", file.readText())
        assertEquals(1, fakeBucket.downloadInvocations)
    }

    @Test
    fun `ensureLocalUri returns uri for cached file`() = runTest {
        // Given
        fakeBucket.onDownload = { "uri content".toByteArray() }

        // When
        val uri: Uri = offlineApi.ensureLocalUri("image.png", isPublic = true)

        // Then
        assertEquals("file", uri.scheme)
        assertTrue(uri.path.orEmpty().endsWith("image.png"))
    }

    @Test
    fun `localUriIfCached returns null if not cached`() = runTest {
        // Given — nothing cached

        // When
        val uri = offlineApi.localUriIfCached("missing.txt")

        // Then
        assertNull(uri)
        assertEquals(0, fakeBucket.downloadInvocations)
    }

    @Test
    fun `localUriIfCached returns uri if cached`() = runTest {
        // Given
        fakeBucket.onDownload = { ByteArray(0) }
        offlineApi.downloadPublic("cached.txt") {}

        // When
        val uri = offlineApi.localUriIfCached("cached.txt")

        // Then
        assertNotNull(uri)
        assertTrue(uri.path.orEmpty().contains("cached.txt"))
    }

    @Test
    fun `ensureLocalFile returns file when authenticated download succeeds`() = runTest {
        // Given
        fakeBucket.onDownload = { "auth content".toByteArray() }

        // When
        val file: File = offlineApi.ensureLocalFile("auth.txt", isPublic = false)

        // Then
        assertTrue(file.exists())
        assertEquals("auth content", file.readText())
        assertEquals(listOf("auth.txt"), fakeBucket.authenticatedDownloadPaths)
    }

    @Test
    fun `ensureLocalFile throws the download failure when nothing is cached`() = runTest {
        // Given — an offline failure is not "no such file", and an auth failure even less so
        val failure = IOException("Network error")
        fakeBucket.onDownload = { throw failure }

        // When
        val thrown =
            assertFailsWith<IOException> {
                offlineApi.ensureLocalFile("missing.txt", isPublic = true)
            }

        // Then — the download's own failure (the coroutine machinery may hand over a copy of it)
        assertEquals(failure.message, thrown.message)
    }

    @Test
    fun `ensureLocalUri throws the download failure when nothing is cached`() = runTest {
        // Given
        fakeBucket.onDownload = { throw forbiddenRestException() }

        // When / Then
        assertFailsWith<UnauthorizedRestException> {
            offlineApi.ensureLocalUri("missing_uri.txt", isPublic = true)
        }
    }

    @Test
    fun `ensureLocalFile propagates cancellation`() = runTest {
        // Given — a download that is in flight when its caller is cancelled
        fakeBucket.onDownload = { awaitCancellation() }
        var returned = false
        var caught: Throwable? = null
        val job =
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    offlineApi.ensureLocalFile("slow.txt", isPublic = true)
                    returned = true
                } catch (failure: Throwable) {
                    caught = failure
                }
            }

        // When
        job.cancelAndJoin()

        // Then — cancelled, not "not available"
        assertFalse(returned, "ensureLocalFile returned after its caller was cancelled")
        assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
    }
}

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.UnauthorizedRestException
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.forbiddenRestException
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import platform.Foundation.NSURL
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS storage helpers (`ensureLocalPath`, `localPathIfCached`, `ensureLocalFileUrl`) over the
 * offline bucket: they hand back the cached file, as a file URL Swift can parse whatever the name,
 * and they no longer turn a cancellation or a failure into "not available".
 */
class BucketApiIosExtensionsTest {

    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-ios-helpers-${Random.nextLong().toULong()}"
    private val fakeBucket = FakeBucketApi(bucketId = "test-bucket")
    private val offlineApi = newTestBucketApi(delegate = fakeBucket, baseDirectory = directory)

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun ensureLocalPath_returns_the_cached_file() = runTest {
        // Given
        fakeBucket.onDownload = { "content".encodeToByteArray() }

        // When
        val path = offlineApi.ensureLocalPath("a b/test.txt", isPublic = true)

        // Then
        assertEquals((directory / "storage" / "test-bucket" / "a b" / "test.txt").toString(), path)
        assertEquals(NSURL.fileURLWithPath(assertNotNull(path)).absoluteString, offlineApi.localFileUrlIfCached("a b/test.txt"))
    }

    @Test
    fun a_file_url_leads_back_to_the_file_for_any_name() = runTest {
        // Given — names with characters a URL must escape
        fakeBucket.onDownload = { "content".encodeToByteArray() }
        listOf("a b/test.txt", "100%.txt", "what?.txt", "c#.txt").forEach { name ->
            // When
            val path = offlineApi.ensureLocalPath(name)
            val url = assertNotNull(offlineApi.ensureLocalFileUrl(name, isPublic = false))

            // Then — Swift's URL(string:) accepts it, and it names the cached file
            assertEquals(path, NSURL.URLWithString(url)?.path, "the URL for $name does not lead back to its file: $url")
        }
    }

    @Test
    fun localPathIfCached_is_null_before_anything_is_cached() = runTest {
        // Given / When / Then
        assertNull(offlineApi.localPathIfCached("missing.txt"))
        assertEquals(0, fakeBucket.downloadInvocations)
    }

    @Test
    fun ensureLocalPath_throws_the_download_failure_when_nothing_is_cached() = runTest {
        // Given
        fakeBucket.onDownload = { throw IOException("network unreachable") }

        // When / Then
        assertFailsWith<IOException> { offlineApi.ensureLocalPath("missing.txt") }
    }

    @Test
    fun ensureLocalFileUrl_throws_an_auth_failure() = runTest {
        // Given
        fakeBucket.onDownload = { throw forbiddenRestException() }

        // When / Then
        assertFailsWith<UnauthorizedRestException> { offlineApi.ensureLocalFileUrl("missing.txt", isPublic = false) }
    }

    @Test
    fun ensureLocalPath_propagates_cancellation() = runTest {
        // Given
        fakeBucket.onDownload = { awaitCancellation() }
        var returned = false
        var caught: Throwable? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                offlineApi.ensureLocalPath("slow.txt")
                returned = true
            } catch (failure: Throwable) {
                caught = failure
            }
        }

        // When
        job.cancelAndJoin()

        // Then
        assertFalse(returned)
        assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
    }
}

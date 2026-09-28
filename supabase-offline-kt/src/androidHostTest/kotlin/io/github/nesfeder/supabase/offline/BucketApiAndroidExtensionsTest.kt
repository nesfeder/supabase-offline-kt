package io.github.nesfeder.supabase.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.internal.storage.OfflineBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android storage helpers (`ensureLocalFile`, `ensureLocalUri`, `localUriIfCached`) over the
 * offline bucket wrapper: download-through-cache, cache reuse without the network, and `null` when
 * nothing can be served.
 */
@RunWith(AndroidJUnit4::class)
class BucketApiAndroidExtensionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val baseDir: File = context.cacheDir.resolve("offline_storage")
    private val fakeBucket = FakeBucketApi(bucketId = "test-bucket")
    private val offlineApi = OfflineBucketApi(
        delegate = fakeBucket,
        offlineStore = newTestOfflineStore(),
        baseDirectoryProvider = { baseDir.absolutePath.toPath() },
    )

    @AfterTest
    fun tearDown() {
        baseDir.deleteRecursively()
    }

    @Test
    fun `ensureLocalFile returns file when public download succeeds`() = runTest {
        // Given
        fakeBucket.onDownload = { "content".toByteArray() }

        // When
        val file = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("content", file.readText())
        assertEquals(listOf("test.txt"), fakeBucket.publicDownloadPaths)
    }

    @Test
    fun `ensureLocalFile returns cached file on network failure`() = runTest {
        // Given — a first download populates the cache, then the network fails
        fakeBucket.onDownload = { "cached content".toByteArray() }
        offlineApi.downloadPublic("test.txt") { }
        fakeBucket.onDownload = { throw RuntimeException("Network error") }

        // When
        val file = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then — served from the cache without another network attempt
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("cached content", file.readText())
        assertEquals(1, fakeBucket.downloadInvocations)
    }

    @Test
    fun `ensureLocalUri returns uri for cached file`() = runTest {
        // Given
        fakeBucket.onDownload = { "uri content".toByteArray() }

        // When
        val uri = offlineApi.ensureLocalUri("image.png", isPublic = true)

        // Then
        assertNotNull(uri)
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
        val file = offlineApi.ensureLocalFile("auth.txt", isPublic = false)

        // Then
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("auth content", file.readText())
        assertEquals(listOf("auth.txt"), fakeBucket.authenticatedDownloadPaths)
    }

    @Test
    fun `ensureLocalFile returns null when download fails and not cached`() = runTest {
        // Given
        fakeBucket.onDownload = { throw RuntimeException("Network error") }

        // When
        val file = offlineApi.ensureLocalFile("missing.txt", isPublic = true)

        // Then
        assertNull(file)
    }

    @Test
    fun `ensureLocalUri returns null when download fails and not cached`() = runTest {
        // Given
        fakeBucket.onDownload = { throw RuntimeException("Network error") }

        // When
        val uri = offlineApi.ensureLocalUri("missing_uri.txt", isPublic = true)

        // Then
        assertNull(uri)
    }
}

package io.github.nesfeder.supabase

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.internal.db.newTestOfflineStore
import io.github.nesfeder.supabase.internal.storage.FakeBucketApi
import io.github.nesfeder.supabase.internal.storage.OfflineBucketApi
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class BucketApiAndroidExtensionsTest {

    @Test
    fun `ensureLocalFile returns file when public download succeeds`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val expectedBytes = "content".toByteArray()
        fakeBucket.enqueuePublicDownload(
            "test.txt",
            FakeBucketApi.DownloadScenario(bytes = expectedBytes)
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val file = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("content", file.readText())
    }

    @Test
    fun `ensureLocalFile returns cached file on network failure`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val expectedBytes = "cached content".toByteArray()
        // First download succeeds to populate cache
        fakeBucket.enqueuePublicDownload(
            "test.txt",
            FakeBucketApi.DownloadScenario(bytes = expectedBytes)
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_fail").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // Populate cache
        offlineApi.downloadPublic("test.txt") { }

        // Now network fails
        fakeBucket.enqueuePublicDownload(
            "test.txt",
            FakeBucketApi.DownloadScenario(error = RuntimeException("Network error"))
        )

        // When
        val file = offlineApi.ensureLocalFile("test.txt", isPublic = true)

        // Then
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("cached content", file.readText())
    }

    @Test
    fun `ensureLocalUri returns uri for cached file`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val expectedBytes = "uri content".toByteArray()
        fakeBucket.enqueuePublicDownload(
            "image.png",
            FakeBucketApi.DownloadScenario(bytes = expectedBytes)
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_uri").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val uri = offlineApi.ensureLocalUri("image.png", isPublic = true)

        // Then
        assertNotNull(uri)
        assertEquals("file", uri.scheme)
        assertTrue(uri.path!!.endsWith("image.png"))
    }

    @Test
    fun `localUriIfCached returns null if not cached`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_null").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val uri = offlineApi.localUriIfCached("missing.txt")

        // Then
        assertEquals(null, uri)
    }

    @Test
    fun `localUriIfCached returns uri if cached`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        fakeBucket.enqueuePublicDownload(
            "cached.txt",
            FakeBucketApi.DownloadScenario(bytes = ByteArray(0))
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_cached").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        offlineApi.downloadPublic("cached.txt") {}

        // When
        val uri = offlineApi.localUriIfCached("cached.txt")

        // Then
        assertNotNull(uri)
        assertTrue(uri.path!!.contains("cached.txt"))
    }

    @Test
    fun `storageCacheMetadata returns metadata after download`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val expectedBytes = "meta".toByteArray()
        fakeBucket.enqueuePublicDownload(
            "meta.txt",
            FakeBucketApi.DownloadScenario(bytes = expectedBytes)
        )
        val fixedNow = 55_555_555_555L
        val store = newTestOfflineStore(nowMillis = { fixedNow })
        val baseDir = context.cacheDir.resolve("offline_storage_meta").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        offlineApi.downloadPublic("meta.txt") { }

        val meta = offlineApi.storageCacheMetadata("meta.txt")
        assertNotNull(meta)
        assertEquals("test-bucket", meta.bucketId)
        assertEquals("meta.txt", meta.objectPath)
        assertEquals(fixedNow, meta.lastFetchedEpochMs)
    }

    @Test
    fun `ensureLocalFile returns file when authenticated download succeeds`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")
        val expectedBytes = "auth content".toByteArray()
        fakeBucket.enqueueAuthenticatedDownload(
            "auth.txt",
            FakeBucketApi.DownloadScenario(bytes = expectedBytes)
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_auth").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val file = offlineApi.ensureLocalFile("auth.txt", isPublic = false)

        // Then
        assertNotNull(file)
        assertTrue(file.exists())
        assertEquals("auth content", file.readText())
    }

    @Test
    fun `ensureLocalFile returns null when download fails and not cached`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")

        // Network fails
        fakeBucket.enqueuePublicDownload(
            "missing.txt",
            FakeBucketApi.DownloadScenario(error = RuntimeException("Network error"))
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_fail_missing").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val file = offlineApi.ensureLocalFile("missing.txt", isPublic = true)

        // Then
        assertEquals(null, file)
    }

    @Test
    fun `ensureLocalUri returns null when download fails and not cached`() = runTest {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeBucket = FakeBucketApi("test-bucket")

        // Network fails
        fakeBucket.enqueuePublicDownload(
            "missing_uri.txt",
            FakeBucketApi.DownloadScenario(error = RuntimeException("Network error"))
        )

        val store = newTestOfflineStore()
        val baseDir = context.cacheDir.resolve("offline_storage_fail_uri").also { it.mkdirs() }

        val offlineApi = OfflineBucketApi(
            delegate = fakeBucket,
            offlineStore = store,
            baseDirectoryProvider = { baseDir.absolutePath.toPath() }
        )

        // When
        val uri = offlineApi.ensureLocalUri("missing_uri.txt", isPublic = true)

        // Then
        assertEquals(null, uri)
    }
}

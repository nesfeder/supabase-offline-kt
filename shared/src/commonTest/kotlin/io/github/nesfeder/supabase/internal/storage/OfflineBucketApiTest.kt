package io.github.nesfeder.supabase.internal.storage

import io.github.nesfeder.supabase.OfflineLogLevel
import io.github.nesfeder.supabase.internal.CapturingLogger
import io.github.nesfeder.supabase.internal.db.FakeMutationDao
import io.github.nesfeder.supabase.internal.db.FakeObjectDao
import io.github.nesfeder.supabase.internal.db.FakeStorageDao
import io.github.nesfeder.supabase.internal.db.OfflineStore
import io.github.nesfeder.supabase.internal.db.newTestOfflineStore
import io.github.jan.supabase.storage.DownloadOptionBuilder
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflineBucketApiTest {

    private fun newOfflineStore(): OfflineStore =
        newTestOfflineStore(
            objectDao = FakeObjectDao(),
            storageDao = FakeStorageDao(),
            mutationDao = FakeMutationDao(),
        )

    // ---------------------------------------------------------------------
    // downloadPublic (ByteArray)
    // ---------------------------------------------------------------------

    @Test
    fun downloadPublic_success_cachesBytesAndReturnsThem() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_public_success.jpg"
        val bytes = "hello-public".encodeToByteArray()

        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()

        val api = OfflineBucketApi(
            delegate = delegate,
            offlineStore = store,
            baseDirectoryProvider = { baseDir }
        )

        // When
        val result = api.downloadPublic(path) { }

        // Then
        assertContentEquals(bytes, result)
        val row = store.getStorageByKey("test-bucket", path)
        assertNotNull(row)
        assertTrue(FileSystem.SYSTEM.exists(row.localPath.toPath()))
    }

    @Test
    fun downloadPublic_fallsBackToCacheOnFailure() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_public_fallback.jpg"
        val bytes = "cached-public".encodeToByteArray()

        // First call succeeds to fill cache
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache
        val first = api.downloadPublic(path) { }
        assertContentEquals(bytes, first)

        // Subsequent call throws from delegate, should fall back to cached bytes
        delegate.enqueuePublicDownload(
            path,
            FakeBucketApi.DownloadScenario(error = RuntimeException("network"))
        )

        // When
        val second = api.downloadPublic(path) { }

        // Then
        assertContentEquals(bytes, second)
    }

    @Test
    fun downloadPublic_throwsWhenNoCacheAndDelegateFails() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_public_nocache.jpg"

        // Only error; no bytes and never primed the cache.
        delegate.enqueuePublicDownload(
            path,
            FakeBucketApi.DownloadScenario(error = IllegalStateException("server error"))
        )

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // When / Then
        assertFailsWith<IllegalStateException> {
            api.downloadPublic(path) { }
        }
    }

    // ---------------------------------------------------------------------
    // downloadPublic (Channel)
    // ---------------------------------------------------------------------

    @Test
    fun downloadPublicToChannel_writesBytes() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_public_channel.jpg"
        val bytes = "hello-channel".encodeToByteArray()

        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        val channel: ByteWriteChannel = ByteChannel()

        // When
        api.downloadPublic(path, channel) { }
        val byteChannel = channel as ByteChannel
        byteChannel.close()

        // Then
        val readBuffer = ByteArray(bytes.size)
        val readCount = byteChannel.readAvailable(readBuffer, 0, readBuffer.size)
        assertEquals(bytes.size, readCount)
        assertContentEquals(bytes, readBuffer)
    }

    @Test
    fun downloadPublicToChannel_fallsBackToCacheOnFailure() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_public_channel_fallback.jpg"
        val bytes = "cached-channel".encodeToByteArray()

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache via a successful ByteArray download
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))
        val first = api.downloadPublic(path) { }
        assertContentEquals(bytes, first)

        // Now configure delegate to fail; OfflineBucketApi should use cached bytes.
        delegate.enqueuePublicDownload(
            path,
            FakeBucketApi.DownloadScenario(error = RuntimeException("network"))
        )

        val channel: ByteWriteChannel = ByteChannel()

        // When
        api.downloadPublic(path, channel) { }
        val byteChannel = channel as ByteChannel
        byteChannel.close()

        // Then
        val readBuffer = ByteArray(bytes.size)
        val readCount = byteChannel.readAvailable(readBuffer, 0, readBuffer.size)
        assertEquals(bytes.size, readCount)
        assertContentEquals(bytes, readBuffer)
    }

    // ---------------------------------------------------------------------
    // downloadAuthenticated (ByteArray)
    // ---------------------------------------------------------------------

    @Test
    fun downloadAuthenticated_success_cachesBytesAndReturnsThem() = runTest {
        // Given
        val delegate = FakeBucketApiWithAuth(bucketId = "test-bucket")
        val path = "foo_auth_success.jpg"
        val bytes = "hello-auth".encodeToByteArray()

        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(bytes = bytes)
        )

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // When
        val result = api.downloadAuthenticated(path) { }

        // Then
        assertContentEquals(bytes, result)
        val row = store.getStorageByKey("test-bucket", path)
        assertNotNull(row)
        assertTrue(FileSystem.SYSTEM.exists(row.localPath.toPath()))
    }

    @Test
    fun downloadAuthenticated_fallsBackToCacheOnFailure() = runTest {
        // Given
        val delegate = FakeBucketApiWithAuth(bucketId = "test-bucket")
        val path = "foo_auth_fallback.jpg"
        val bytes = "cached-auth".encodeToByteArray()

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache
        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(bytes = bytes)
        )
        val first = api.downloadAuthenticated(path) { }
        assertContentEquals(bytes, first)

        // Now fail, should fall back to cached bytes.
        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(error = RuntimeException("network"))
        )

        // When
        val second = api.downloadAuthenticated(path) { }

        // Then
        assertContentEquals(bytes, second)
    }

    @Test
    fun downloadAuthenticated_throwsWhenNoCacheAndDelegateFails() = runTest {
        // Given
        val delegate = FakeBucketApiWithAuth(bucketId = "test-bucket")
        val path = "foo_auth_nocache.jpg"

        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(error = IllegalStateException("server error"))
        )

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // When / Then
        assertFailsWith<IllegalStateException> {
            api.downloadAuthenticated(path) { }
        }
    }

    // ---------------------------------------------------------------------
    // downloadAuthenticated (Channel)
    // ---------------------------------------------------------------------

    @Test
    fun downloadAuthenticatedToChannel_writesBytes() = runTest {
        // Given
        val delegate = FakeBucketApiWithAuth(bucketId = "test-bucket")
        val path = "foo_auth_channel.jpg"
        val bytes = "auth-channel".encodeToByteArray()

        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(bytes = bytes)
        )

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        val channel: ByteWriteChannel = ByteChannel()

        // When
        api.downloadAuthenticated(path, channel) { }
        val byteChannel = channel as ByteChannel
        byteChannel.close()

        // Then
        val readBuffer = ByteArray(bytes.size)
        val readCount = byteChannel.readAvailable(readBuffer, 0, readBuffer.size)
        assertEquals(bytes.size, readCount)
        assertContentEquals(bytes, readBuffer)
    }

    @Test
    fun downloadAuthenticatedToChannel_fallsBackToCacheOnFailure() = runTest {
        // Given
        val delegate = FakeBucketApiWithAuth(bucketId = "test-bucket")
        val path = "foo_auth_channel_fallback.jpg"
        val bytes = "cached-auth-channel".encodeToByteArray()

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache
        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(bytes = bytes)
        )
        val first = api.downloadAuthenticated(path) { }
        assertContentEquals(bytes, first)

        // Now fail
        delegate.enqueueAuthenticatedDownload(
            path,
            FakeBucketApiWithAuth.AuthScenario(error = RuntimeException("network"))
        )

        val channel: ByteWriteChannel = ByteChannel()

        // When
        api.downloadAuthenticated(path, channel) { }
        val byteChannel = channel as ByteChannel
        byteChannel.close()

        // Then
        val readBuffer = ByteArray(bytes.size)
        val readCount = byteChannel.readAvailable(readBuffer, 0, readBuffer.size)
        assertEquals(bytes.size, readCount)
        assertContentEquals(bytes, readBuffer)
    }

    // ---------------------------------------------------------------------
    // delete / move / copy / localPathIfPresent
    // ---------------------------------------------------------------------

    @Test
    fun delete_removesCachedFileAndStorageRow() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_delete.jpg"
        val bytes = "cached-delete".encodeToByteArray()

        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache
        api.downloadPublic(path) { }
        val rowBefore = store.getStorageByKey("test-bucket", path)
        assertNotNull(rowBefore)
        val localPathBefore = rowBefore.localPath.toPath()
        assertTrue(FileSystem.SYSTEM.exists(localPathBefore))

        // When
        api.delete(listOf(path))

        // Then
        assertNull(store.getStorageByKey("test-bucket", path))
        // File should also be gone
        assertTrue(!FileSystem.SYSTEM.exists(localPathBefore))
    }

    @Test
    fun move_movesFileAndUpdatesStorageRows() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val from = "foo_move_src.jpg"
        val to = "foo_move_dst.jpg"
        val bytes = "move-me".encodeToByteArray()

        delegate.enqueuePublicDownload(from, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache to create source file + row.
        api.downloadPublic(from) { }

        val srcRow = store.getStorageByKey("test-bucket", from)
        assertNotNull(srcRow)
        val srcPath = srcRow.localPath.toPath()
        assertTrue(FileSystem.SYSTEM.exists(srcPath))

        // When
        api.move(from = from, to = to, destinationBucket = null)

        // Then
        // Old row removed
        assertNull(store.getStorageByKey("test-bucket", from))

        // New row created
        val dstRow = store.getStorageByKey("test-bucket", to)
        assertNotNull(dstRow)
        val dstPath = dstRow.localPath.toPath()

        assertTrue(FileSystem.SYSTEM.exists(dstPath))
        // Source should no longer exist
        assertTrue(!FileSystem.SYSTEM.exists(srcPath))
    }

    @Test
    fun move_whenSourceFileMissing_doesNothing() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val from = "foo_move_missing_src.jpg"
        val to = "foo_move_missing_dst.jpg"

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // No cache primed, so source file does not exist.
        assertNull(store.getStorageByKey("test-bucket", from))

        // When
        api.move(from = from, to = to, destinationBucket = null)

        // Then - still no rows
        assertNull(store.getStorageByKey("test-bucket", from))
        assertNull(store.getStorageByKey("test-bucket", to))
    }

    @Test
    fun copy_doesNotPopulateCache() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val from = "foo_copy_src.jpg"
        val to = "foo_copy_dst.jpg"

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // When - just call copy; OfflineBucketApi deliberately does not change cache.
        api.copy(from = from, to = to, destinationBucket = null)

        // Then - still no cached rows.
        assertNull(store.getStorageByKey("test-bucket", from))
        assertNull(store.getStorageByKey("test-bucket", to))
    }

    @Test
    fun localPathIfPresent_checksStoreAndDisk() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "foo_localPath.jpg"
        val bytes = "cached-local-path".encodeToByteArray()

        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // Prime cache
        api.downloadPublic(path) { }

        // When
        val p = api.localPathIfPresent(path)

        // Then
        assertNotNull(p)
        assertTrue(FileSystem.SYSTEM.exists(p))
    }

    @Test
    fun localPathIfPresent_returnsNullWhenNotTracked() = runTest {
        // Given
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { baseDir }

        // No cache primed at all.

        // When
        val p = api.localPathIfPresent("non-existent.jpg")

        // Then
        assertNull(p)
    }

    @Test
    fun storageCacheMetadata_afterDownload_returnsFields() = runTest {
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val path = "meta_after_download.jpg"
        val bytes = "meta-bytes".encodeToByteArray()
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val baseDir: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        val fixedNow = 9_876_543_210L
        val store = newTestOfflineStore(nowMillis = { fixedNow })
        val api = OfflineBucketApi(delegate, store) { baseDir }

        api.downloadPublic(path) { }

        val meta = api.storageCacheMetadata(path)
        assertNotNull(meta)
        assertEquals("test-bucket", meta.bucketId)
        assertEquals(path, meta.objectPath)
        assertEquals(fixedNow, meta.lastFetchedEpochMs)
        assertTrue(FileSystem.SYSTEM.exists(meta.localPath.toPath()))
    }

    @Test
    fun storageCacheMetadata_unknownPath_returnsNull() = runTest {
        val delegate = FakeBucketApi(bucketId = "test-bucket")
        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store) { FileSystem.SYSTEM_TEMPORARY_DIRECTORY }

        assertNull(api.storageCacheMetadata("unknown-object.jpg"))
    }

    // ─────────────────────────────────────────────────────────────────────
    // Logging assertion tests
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun downloadPublic_success_logsDebug() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApi(bucketId = "images")
        val path = "log_pub_success.jpg"
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = "ok".encodeToByteArray()))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // When
        api.downloadPublic(path) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(true, debugEntries.any { it.message.contains("Storage download images/$path attempting network") })
    }

    @Test
    fun downloadPublic_fallback_logsWarning() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApi(bucketId = "images")
        val path = "log_pub_fallback.jpg"
        val bytes = "cached".encodeToByteArray()

        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(bytes = bytes))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // Prime cache
        api.downloadPublic(path) {}
        capturing.entries.clear()

        // Now make network fail
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(error = IOException("offline")))

        // When
        api.downloadPublic(path) {}

        // Then
        val warnings = capturing.entriesAt(OfflineLogLevel.WARNING)
        assertEquals(1, warnings.size)
        assertEquals(true, warnings[0].message.contains("Storage download images/$path failed, serving from disk cache"))
    }

    @Test
    fun downloadPublic_noCache_logsError() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApi(bucketId = "images")
        val path = "log_pub_nocache.jpg"
        delegate.enqueuePublicDownload(path, FakeBucketApi.DownloadScenario(error = IOException("offline")))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // When / Then
        assertFailsWith<IOException> { api.downloadPublic(path) {} }

        val errors = capturing.entriesAt(OfflineLogLevel.ERROR)
        assertEquals(1, errors.size)
        assertEquals(true, errors[0].message.contains("Storage download images/$path failed and no cache available"))
    }

    @Test
    fun downloadAuthenticated_success_logsDebug() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApiWithAuth(bucketId = "images")
        val path = "log_auth_success.jpg"
        delegate.enqueueAuthenticatedDownload(path, FakeBucketApiWithAuth.AuthScenario(bytes = "ok".encodeToByteArray()))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // When
        api.downloadAuthenticated(path) {}

        // Then
        val debugEntries = capturing.entriesAt(OfflineLogLevel.DEBUG)
        assertEquals(true, debugEntries.any { it.message.contains("Storage download images/$path attempting network") })
    }

    @Test
    fun downloadAuthenticated_fallback_logsWarning() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApiWithAuth(bucketId = "images")
        val path = "log_auth_fallback.jpg"
        val bytes = "cached".encodeToByteArray()
        delegate.enqueueAuthenticatedDownload(path, FakeBucketApiWithAuth.AuthScenario(bytes = bytes))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // Prime cache
        api.downloadAuthenticated(path) {}
        capturing.entries.clear()

        // Now make network fail
        delegate.enqueueAuthenticatedDownload(path, FakeBucketApiWithAuth.AuthScenario(error = IOException("offline")))

        // When
        api.downloadAuthenticated(path) {}

        // Then
        val warnings = capturing.entriesAt(OfflineLogLevel.WARNING)
        assertEquals(1, warnings.size)
        assertEquals(true, warnings[0].message.contains("Storage download images/$path failed, serving from disk cache"))
    }

    @Test
    fun downloadAuthenticated_noCache_logsError() = runTest {
        // Given
        val capturing = CapturingLogger()
        val delegate = FakeBucketApiWithAuth(bucketId = "images")
        val path = "log_auth_nocache.jpg"
        delegate.enqueueAuthenticatedDownload(path, FakeBucketApiWithAuth.AuthScenario(error = IOException("offline")))

        val store = newOfflineStore()
        val api = OfflineBucketApi(delegate, store, sdkLogger = capturing.asSdkLogger()) {
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY
        }

        // When / Then
        assertFailsWith<IOException> { api.downloadAuthenticated(path) {} }

        val errors = capturing.entriesAt(OfflineLogLevel.ERROR)
        assertEquals(1, errors.size)
        assertEquals(true, errors[0].message.contains("Storage download images/$path failed and no cache available"))
    }
}

/**
 * Small test-only extension of [FakeBucketApi] that supports authenticated
 * downloads, mirroring the public-download behaviour already present.
 */
internal class FakeBucketApiWithAuth(
    bucketId: String,
) : FakeBucketApi(bucketId) {

    data class AuthScenario(
        val bytes: ByteArray? = null,
        val error: Throwable? = null,
    )

    private val authDownloads = mutableMapOf<String, AuthScenario>()

    fun enqueueAuthenticatedDownload(path: String, scenario: AuthScenario) {
        authDownloads[path] = scenario
    }

    override suspend fun downloadAuthenticated(
        path: String,
        options: DownloadOptionBuilder.() -> Unit
    ): ByteArray {
        val scenario = authDownloads[path]
            ?: error("No FakeBucketApiWithAuth scenario configured for path=$path")

        scenario.error?.let { throw it }
        return scenario.bytes
            ?: error("FakeBucketApiWithAuth scenario for path=$path has no bytes")
    }

    override suspend fun downloadAuthenticated(
        path: String,
        channel: ByteWriteChannel,
        options: DownloadOptionBuilder.() -> Unit
    ) {
        val bytes = downloadAuthenticated(path, options)
        channel.writeFully(bytes)
    }
}
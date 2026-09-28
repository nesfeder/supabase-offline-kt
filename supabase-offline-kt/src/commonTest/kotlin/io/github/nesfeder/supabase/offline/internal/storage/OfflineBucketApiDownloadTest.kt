package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.readByteArray
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

/**
 * The download-through-cache matrix, ported from the previous standalone engine for the variants
 * the rest of the suite does not cover: the authenticated downloads, the byte-channel downloads,
 * and a failed download with nothing cached.
 */
class OfflineBucketApiDownloadTest {

    private lateinit var baseDir: Path
    private lateinit var delegate: FakeBucketApi
    private lateinit var api: OfflineBucketApi

    @BeforeTest
    fun setUp() {
        baseDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-bucket-download-${Random.nextLong()}"
        delegate = FakeBucketApi(bucketId = BUCKET)
        api = OfflineBucketApi(
            delegate = delegate,
            offlineStore = newTestOfflineStore(),
            baseDirectoryProvider = { baseDir },
        )
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(baseDir, mustExist = false)
    }

    @Test
    fun downloadPublic_throwsWhenNoCacheAndDelegateFails() = runTest {
        // Given — nothing cached, and the network fails
        delegate.onDownload = { throw IOException("network unreachable") }

        // When / Then
        assertFailsWith<IOException> { api.downloadPublic(PATH) { } }
    }

    @Test
    fun downloadAuthenticated_success_cachesBytesAndReturnsThem() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When
        val downloaded = api.downloadAuthenticated(PATH) { }

        // Then — returned, and on disk
        assertContentEquals(BYTES, downloaded)
        assertContentEquals(BYTES, readBytesOrNull(resolveLocalPath(baseDir, BUCKET, PATH)))
        assertEquals(listOf(PATH), delegate.authenticatedDownloadPaths)
    }

    @Test
    fun downloadAuthenticated_fallsBackToCacheOnFailure() = runTest {
        // Given — cached by a first download, then the network fails
        delegate.onDownload = { BYTES }
        api.downloadAuthenticated(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }

        // When
        val downloaded = api.downloadAuthenticated(PATH) { }

        // Then
        assertContentEquals(BYTES, downloaded)
    }

    @Test
    fun downloadAuthenticated_throwsWhenNoCacheAndDelegateFails() = runTest {
        // Given
        delegate.onDownload = { throw IOException("network unreachable") }

        // When / Then
        assertFailsWith<IOException> { api.downloadAuthenticated(PATH) { } }
    }

    @Test
    fun downloadPublicToChannel_writesBytes() = runTest {
        // Given
        delegate.onDownload = { BYTES }
        val channel = ByteChannel()

        // When
        api.downloadPublic(PATH, channel) { }
        channel.flushAndClose()

        // Then
        assertContentEquals(BYTES, channel.readRemaining().readByteArray())
    }

    @Test
    fun downloadPublicToChannel_fallsBackToCacheOnFailure() = runTest {
        // Given — cached by a first download, then the network fails
        delegate.onDownload = { BYTES }
        api.downloadPublic(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }
        val channel = ByteChannel()

        // When
        api.downloadPublic(PATH, channel) { }
        channel.flushAndClose()

        // Then
        assertContentEquals(BYTES, channel.readRemaining().readByteArray())
    }

    @Test
    fun downloadAuthenticatedToChannel_writesBytes() = runTest {
        // Given
        delegate.onDownload = { BYTES }
        val channel = ByteChannel()

        // When
        api.downloadAuthenticated(PATH, channel) { }
        channel.flushAndClose()

        // Then
        assertContentEquals(BYTES, channel.readRemaining().readByteArray())
        assertEquals(listOf(PATH), delegate.authenticatedDownloadPaths)
    }

    private companion object {
        const val BUCKET = "test-bucket"
        const val PATH = "folder/file.bin"
        val BYTES = "cached-bytes".encodeToByteArray()
    }
}

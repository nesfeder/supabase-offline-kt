package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.notFoundRestException
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The download-through-cache matrix, ported from the previous standalone engine for the variants
 * the rest of the suite does not cover: the authenticated downloads, the byte-channel downloads,
 * a failed download with nothing cached, and what each outcome logs.
 */
class OfflineBucketApiDownloadTest {

    private lateinit var baseDir: Path
    private lateinit var delegate: FakeBucketApi
    private lateinit var api: CachingBucketApi
    private val logger = RecordingOfflineLogger()

    @BeforeTest
    fun setUp() {
        baseDir = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-bucket-download-${Random.nextLong()}"
        delegate = FakeBucketApi(bucketId = BUCKET)
        api = newTestBucketApi(
            delegate = delegate,
            baseDirectory = baseDir,
            store = newTestOfflineStore(),
            sdkLogger = logger.asSdkLogger(),
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
        assertContentEquals(BYTES, FileSystem.SYSTEM.read(assertNotNull(objectFilePathOrNull(baseDir, BUCKET, PATH))) { readByteArray() })
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

    @Test
    fun downloadAuthenticatedToChannel_fallsBackToCacheOnFailure() = runTest {
        // Given — cached by a first download, then the network fails
        delegate.onDownload = { BYTES }
        api.downloadAuthenticated(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }
        val channel = ByteChannel()

        // When
        api.downloadAuthenticated(PATH, channel) { }
        channel.flushAndClose()

        // Then
        assertContentEquals(BYTES, channel.readRemaining().readByteArray())
    }

    // ─────────────────────────── Logging ───────────────────────────

    @Test
    fun downloadPublic_success_logsDebug() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When
        api.downloadPublic(PATH) { }

        // Then
        assertEquals(listOf("Storage download $BUCKET/$PATH attempting network"), messagesAt(OfflineLogLevel.DEBUG))
        assertEquals(emptyList(), messagesAt(OfflineLogLevel.WARNING) + messagesAt(OfflineLogLevel.ERROR))
    }

    @Test
    fun downloadPublic_fallback_logsWarning() = runTest {
        // Given — cached by a first download, then the network fails
        delegate.onDownload = { BYTES }
        api.downloadPublic(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }

        // When
        api.downloadPublic(PATH) { }

        // Then
        assertEquals(
            listOf("Storage download from $BUCKET failed; serving the cached copy"),
            messagesAt(OfflineLogLevel.WARNING),
        )
    }

    @Test
    fun downloadPublic_noCache_logsError() = runTest {
        // Given — nothing cached, and the network fails
        delegate.onDownload = { throw IOException("network unreachable") }

        // When
        assertFailsWith<IOException> { api.downloadPublic(PATH) { } }

        // Then
        assertEquals(
            listOf("Storage download from $BUCKET failed and nothing is cached"),
            messagesAt(OfflineLogLevel.ERROR),
        )
    }

    @Test
    fun downloadAuthenticated_success_logsDebug() = runTest {
        // Given
        delegate.onDownload = { BYTES }

        // When
        api.downloadAuthenticated(PATH) { }

        // Then
        assertEquals(listOf("Storage download $BUCKET/$PATH attempting network"), messagesAt(OfflineLogLevel.DEBUG))
        assertEquals(emptyList(), messagesAt(OfflineLogLevel.WARNING) + messagesAt(OfflineLogLevel.ERROR))
    }

    @Test
    fun downloadAuthenticated_fallback_logsWarning() = runTest {
        // Given — cached by a first download, then the network fails
        delegate.onDownload = { BYTES }
        api.downloadAuthenticated(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }

        // When
        api.downloadAuthenticated(PATH) { }

        // Then
        assertEquals(
            listOf("Storage download from $BUCKET failed; serving the cached copy"),
            messagesAt(OfflineLogLevel.WARNING),
        )
    }

    @Test
    fun downloadAuthenticated_noCache_logsError() = runTest {
        // Given — nothing cached, and the network fails
        delegate.onDownload = { throw IOException("network unreachable") }

        // When
        assertFailsWith<IOException> { api.downloadAuthenticated(PATH) { } }

        // Then
        assertEquals(
            listOf("Storage download from $BUCKET failed and nothing is cached"),
            messagesAt(OfflineLogLevel.ERROR),
        )
    }

    @Test
    fun no_message_at_info_or_above_names_the_object_path() = runTest {
        // Given — every outcome a download can log: success, fallback, nothing cached, not found
        delegate.onDownload = { BYTES }
        api.downloadPublic(PATH) { }
        delegate.onDownload = { throw IOException("network unreachable") }
        api.downloadPublic(PATH) { }
        assertFailsWith<IOException> { api.downloadPublic("folder/never-cached.bin") { } }
        delegate.onDownload = { throw notFoundRestException() }
        assertFailsWith<Exception> { api.downloadPublic(PATH) { } }

        // When
        val visible = logger.entries.filter { it.level != OfflineLogLevel.DEBUG }.map { it.message }

        // Then — object paths can name people ("jane-doe/passport.jpg"); they stay out of every log
        // an app is likely to forward to a crash reporter
        assertTrue(visible.isNotEmpty(), "expected the fallback and failures to be logged")
        assertTrue(visible.none { "folder" in it || "file.bin" in it || "never-cached" in it }, "paths leaked: $visible")
    }

    private fun messagesAt(level: OfflineLogLevel): List<String> = logger.entriesAt(level).map { it.message }

    private companion object {
        const val BUCKET = "test-bucket"
        const val PATH = "folder/file.bin"
        val BYTES = "cached-bytes".encodeToByteArray()
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * Channel downloads stream through a file on disk instead of being held in memory whole, and a
 * download that is cancelled or fails part-way leaves nothing behind.
 */
class StorageStreamingDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var delegate: FakeBucketApi
    private val logger = RecordingOfflineLogger()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-streaming-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        delegate = FakeBucketApi(bucketId = BUCKET)
        delegate.channelChunkSize = 4
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun bucket(fileSystem: FileSystem = FileSystem.SYSTEM) =
        newTestBucketApi(
            delegate = delegate,
            baseDirectory = dataDirectory,
            store = store,
            engineLogger = logger.asEngineLogger(),
            cache =
                newTestFileCache(
                    baseDirectory = dataDirectory,
                    store = store,
                    engineLogger = logger.asEngineLogger(),
                    fileSystem = fileSystem,
                ),
        )

    @Test
    fun a_channel_download_streams_from_the_network_through_the_cache_to_the_caller() = runTest {
        // Given
        delegate.onDownload = { LARGE }
        val channel = ByteChannel()

        // When
        val received = async { channel.readRemaining().readByteArray() }
        bucket().downloadAuthenticated(PATH, channel) {}

        // Then — the network was read as a stream (never as one byte array), the caller got every
        // byte and its channel was closed as supabase-kt closes it, and the object is cached
        assertEquals(listOf(PATH), delegate.channelDownloadPaths)
        assertContentEquals(LARGE, received.await())
        assertTrue(channel.isClosedForWrite)
        assertContentEquals(LARGE, FileSystem.SYSTEM.read(cachedFile()) { readByteArray() })
    }

    @Test
    fun a_cancelled_channel_download_leaves_no_partial_file() = runTest {
        // Given — cancelled when part of the object is on disk
        delegate.onDownload = { LARGE }
        val midway = CompletableDeferred<Unit>()
        delegate.onChannelChunk = { written ->
            if (written >= 8) {
                midway.complete(Unit)
                awaitCancellation()
            }
        }
        var caught: Throwable? = null
        val job = launch {
            try {
                bucket().downloadAuthenticated(PATH, ByteChannel()) {}
            } catch (failure: Throwable) {
                caught = failure
            }
        }
        midway.await()
        awaitAFileUnder(dataDirectory)

        // When
        job.cancelAndJoin()

        // Then
        assertIs<CancellationException>(caught)
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
    }

    @Test
    fun a_download_that_fails_part_way_serves_the_cached_copy_and_keeps_it_whole() = runTest {
        // Given — an old copy is cached; the new download breaks after a few bytes
        delegate.onDownload = { OLD }
        bucket().downloadAuthenticated(PATH) {}
        delegate.onDownload = { LARGE }
        delegate.onChannelChunk = { written ->
            if (written >= 8) throw IOException("connection reset")
        }
        val channel = ByteChannel()

        // When
        val received = async { channel.readRemaining().readByteArray() }
        bucket().downloadAuthenticated(PATH, channel) {}

        // Then — the caller got the old copy, never a mix; the cache still holds it, nothing else
        assertContentEquals(OLD, received.await())
        assertContentEquals(OLD, FileSystem.SYSTEM.read(cachedFile()) { readByteArray() })
        assertEquals(listOf(cachedFile()), regularFilesUnder(dataDirectory))
    }

    @Test
    fun a_channel_download_whose_disk_fills_up_still_delivers_every_byte() = runTest {
        // Given — the disk refuses the cache copy part-way through
        val fileSystem = FakeFailingFileSystem()
        fileSystem.failWritesAfterBytes = 5
        delegate.onDownload = { LARGE }
        val channel = ByteChannel()

        // When
        val received = async { channel.readRemaining().readByteArray() }
        bucket(fileSystem).downloadAuthenticated(PATH, channel) {}

        // Then — the caller got the whole object; nothing partial was kept; the refusal was logged
        assertContentEquals(LARGE, received.await())
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        assertTrue(logger.entriesAt(OfflineLogLevel.WARNING).isNotEmpty())
    }

    private fun cachedFile(): Path =
        objectFilePathOrNull(dataDirectory, BUCKET, PATH) ?: error("uncacheable fixture path")

    private suspend fun awaitAFileUnder(directory: Path) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) {
                while (regularFilesUnder(directory).isEmpty()) delay(5)
            }
        }
    }

    private fun regularFilesUnder(directory: Path): List<Path> =
        if (!FileSystem.SYSTEM.exists(directory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(directory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    private companion object {
        const val BUCKET = "videos"
        const val PATH = "u-1/clip.mp4"
        val OLD = "old-clip".encodeToByteArray()
        val LARGE = ByteArray(300) { index -> (index % 251).toByte() }
    }
}

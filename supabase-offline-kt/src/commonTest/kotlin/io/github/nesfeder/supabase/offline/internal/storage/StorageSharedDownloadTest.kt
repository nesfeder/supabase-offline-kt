// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * Concurrent requests for one cold object download it once. `ensureCachedFile` calls for the same
 * path, through any bucket of the client, wait for the download the first of them started; a bytes
 * or channel download of the same entry waits for it too; and the download outlives any one caller,
 * canceled only once every caller has canceled.
 *
 * A download is shared only while it can still be the cached copy: once a write of the object lands
 * or local data is cleared, a caller arriving later downloads afresh, and the callers already
 * waiting get what the old download's commit says. A caller that joined a download made with
 * another caller's request — its headers, URL and session — makes its own once when that one fails,
 * and a bytes or channel caller fetches for itself when the download it waited for did not commit.
 *
 * These run the real supabase-kt bucket against [RecordingStorageServer], whose download gate holds
 * a download on the wire with its bytes read, so a write can land under it.
 */
class StorageSharedDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer
    private lateinit var cache: StorageFileCache

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-shared-download-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = OLD.encodeToByteArray()
        cache = newTestFileCache(baseDirectory = dataDirectory, store = store)
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    /**
     * A bucket of its own over the one cache, as each `storage.from(bucket)` of a client is, with
     * the download status check the client installs.
     */
    private fun bucket(): OfflineBucketApi =
        newTestBucketApi(
            delegate =
                server
                    .supabaseClient()
                    .also { it.installDownloadStatusCheck() }
                    .storage
                    .from(BUCKET),
            baseDirectory = dataDirectory,
            store = store,
            cache = cache,
        )

    private val entryKey: String
        get() = assertNotNull(cache.entryFor(BUCKET, PATH)).key

    /** Holds every download at the server, its bytes read, until the returned gate completes. */
    private fun holdDownloads(): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        server.downloadGate = gate
        server.downloadHeld = CompletableDeferred()
        return gate
    }

    /** Waits, on real time, for [deferred]. */
    private suspend fun <T> awaitSoon(deferred: Deferred<T>): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { deferred.await() } }

    /** Waits, on real time, until [condition] holds. */
    private suspend fun awaitOnRealTime(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (!condition()) delay(5) }
        }
    }

    /** Waits, on real time, until [count] callers are waiting for the download of [PATH]. */
    private suspend fun awaitWaiters(count: Int) {
        awaitOnRealTime { cache.downloadsInFlight.waitersOf(entryKey) == count }
    }

    private fun contentOf(file: CachedFile): String =
        FileSystem.SYSTEM.read(file.path.toPath()) { readUtf8() }

    private fun downloads(): Int = server.requests.count { it.method == "GET" }

    private fun regularFilesUnder(directory: Path): List<Path> =
        if (!FileSystem.SYSTEM.exists(directory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(directory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    @Test
    fun two_concurrent_ensureCachedFile_calls_download_the_object_once() = runTest {
        // Given — a download of the object held at the server, and a second call for it, through
        // another bucket
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitWaiters(2)

        // When — the bytes arrive
        gate.complete(Unit)
        val files = listOf(awaitSoon(first), awaitSoon(second))

        // Then — the one request served both callers the same file
        assertEquals(1, downloads())
        assertEquals(1, files.map { it.path }.distinct().size)
        files.forEach { file -> assertEquals(OLD, contentOf(file)) }
        assertEquals(0, cache.downloadsInFlight.waitersOf(entryKey))
    }

    @Test
    fun a_caller_that_cancels_leaves_the_other_to_finish_the_shared_download() = runTest {
        // Given — two callers waiting for one download
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitWaiters(2)

        // When — the first caller cancels, then the bytes arrive
        first.cancelAndJoin()
        assertEquals(1, cache.downloadsInFlight.waitersOf(entryKey))
        gate.complete(Unit)
        val file = awaitSoon(second)

        // Then — the download went on for the other caller, and was made once
        assertTrue(first.isCancelled)
        assertEquals(OLD, contentOf(file))
        assertEquals(1, downloads())
    }

    @Test
    fun the_shared_download_stops_once_every_caller_has_canceled() = runTest {
        // Given — a download parked part-way through its bytes, with two callers waiting for it
        val fake = FakeBucketApi(bucketId = BUCKET)
        fake.onDownload = { OLD.encodeToByteArray() }
        fake.channelChunkSize = 3
        val midway = CompletableDeferred<Unit>()
        fake.onChannelChunk = { written ->
            if (written >= 3) {
                midway.complete(Unit)
                awaitCancellation()
            }
        }
        val bucket =
            newTestBucketApi(
                delegate = fake,
                baseDirectory = dataDirectory,
                store = store,
                cache = cache,
            )
        val outcomes = mutableListOf<Throwable?>()
        val callers =
            List(2) {
                launch {
                    outcomes += runCatching { bucket.ensureCachedFile(PATH) }.exceptionOrNull()
                }
            }
        awaitSoon(midway)
        awaitWaiters(2)
        awaitOnRealTime { regularFilesUnder(dataDirectory).isNotEmpty() }

        // When — one caller cancels, then the other
        callers[0].cancelAndJoin()
        assertEquals(1, cache.downloadsInFlight.waitersOf(entryKey))
        assertTrue(
            regularFilesUnder(dataDirectory).isNotEmpty(),
            "the download was stopped while a caller still waited for it",
        )
        callers[1].cancelAndJoin()

        // Then — both ended canceled; the download stopped with the last of them and left no
        // file, and the next call downloads afresh
        assertEquals(2, outcomes.size)
        outcomes.forEach { outcome -> assertIs<CancellationException>(outcome) }
        assertFalse(cache.downloadsInFlight.isInFlight(entryKey))
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        assertEquals(1, fake.downloadInvocations)
        fake.onChannelChunk = {}
        assertEquals(OLD, contentOf(bucket.ensureCachedFile(PATH)))
        assertEquals(2, fake.downloadInvocations)
    }

    @Test
    fun a_caller_arriving_after_an_update_downloads_afresh_and_both_get_the_new_bytes() = runTest {
        // Given — a download of the old object held at the server
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)

        // When — the object is replaced, then a second caller arrives: the old download can no
        // longer be the cached copy, so it downloads the new object, held at the server too
        bucket().update(PATH, NEW.encodeToByteArray())
        assertFalse(cache.downloadsInFlight.isInFlight(entryKey))
        server.downloadHeld = CompletableDeferred()
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)
        assertEquals(1, cache.downloadsInFlight.waitersOf(entryKey))
        server.downloadGate = null
        gate.complete(Unit)
        val files = listOf(awaitSoon(first), awaitSoon(second))

        // Then — the old bytes were kept for neither; both callers got the new object, which was
        // downloaded once: the first caller's one retry waited for the second's download
        files.forEach { file -> assertEquals(NEW, contentOf(file)) }
        assertEquals(2, downloads())
    }

    @Test
    fun a_caller_arriving_after_a_clear_downloads_afresh_and_gets_the_file() = runTest {
        // Given — a download held at the server
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.downloadHeld)

        // When — local data is cleared, as a sign-out does, and the next user asks for the object
        cache.clear { store.cachedFileDao.deleteAll() }
        assertFalse(cache.downloadsInFlight.isInFlight(entryKey))
        server.downloadHeld = CompletableDeferred()
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)
        server.downloadGate = null
        gate.complete(Unit)

        // Then — the outgoing user's download was voided for its caller; the new one is the next
        // user's file
        assertIs<IllegalStateException>(awaitSoon(first).exceptionOrNull())
        assertEquals(OLD, contentOf(awaitSoon(second)))
        assertNotNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(2, downloads())
    }

    @Test
    fun a_clear_during_a_shared_download_voids_it_for_every_caller_waiting() = runTest {
        // Given — two callers waiting for a download held at the server
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.downloadHeld)
        val second = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitWaiters(2)

        // When — local data is cleared, then the bytes arrive
        cache.clear { store.cachedFileDao.deleteAll() }
        gate.complete(Unit)
        val outcomes = listOf(awaitSoon(first), awaitSoon(second))

        // Then — neither caller got the outgoing user's file, and nothing was put back
        outcomes.forEach { outcome -> assertIs<IllegalStateException>(outcome.exceptionOrNull()) }
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        assertEquals(1, downloads())
    }

    @Test
    fun a_caller_that_joined_a_download_another_request_failed_makes_its_own() = runTest {
        // Given — a download held at the server, started through a bucket whose header asks for
        // part of the object, and a call through a plain bucket waiting for it
        val gate = holdDownloads()
        val ranged = bucket().setHeader(HttpHeaders.Range, "bytes=0-3")
        val first = async(Dispatchers.Default) { runCatching { ranged.ensureCachedFile(PATH) } }
        awaitSoon(server.downloadHeld)
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitWaiters(2)

        // When — the fragment arrives
        server.downloadGate = null
        gate.complete(Unit)

        // Then — the ranged call fails as it always did; the plain one made its own request, once,
        // and got the whole object
        assertIs<IllegalStateException>(awaitSoon(first).exceptionOrNull())
        assertEquals(OLD, contentOf(awaitSoon(second)))
        assertEquals(2, downloads())
    }

    @Test
    fun a_bytes_download_waits_for_the_download_in_flight_of_its_entry() = runTest {
        // Given — an ensureCachedFile download held at the server
        val gate = holdDownloads()
        val ensure = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)

        // When — a bytes download of the same object arrives, then the bytes
        val download = async(Dispatchers.Default) { bucket().downloadAuthenticated(PATH) {} }
        awaitWaiters(2)
        gate.complete(Unit)
        val bytes = awaitSoon(download)

        // Then — it was answered from the one download's file, with no request of its own
        assertEquals(OLD, bytes.decodeToString())
        assertEquals(OLD, contentOf(awaitSoon(ensure)))
        assertEquals(1, downloads())
    }

    @Test
    fun a_channel_download_waits_for_the_download_in_flight_of_its_entry() = runTest {
        // Given
        val gate = holdDownloads()
        val ensure = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)

        // When — a channel download of the same object arrives, then the bytes
        val channel = ByteChannel()
        val download =
            async(Dispatchers.Default) { bucket().downloadAuthenticated(PATH, channel) {} }
        val received = async(Dispatchers.Default) { channel.readRemaining().readByteArray() }
        awaitWaiters(2)
        gate.complete(Unit)
        awaitSoon(download)
        channel.flushAndClose()

        // Then — every byte reached the channel from the one download's file
        assertEquals(OLD, awaitSoon(received).decodeToString())
        assertEquals(OLD, contentOf(awaitSoon(ensure)))
        assertEquals(1, downloads())
    }

    @Test
    fun a_bytes_download_that_joined_one_the_disk_refused_still_returns_its_bytes() = runTest {
        // Given — a cache over a disk that will refuse the download's file, and an ensureCachedFile
        // download held at the server with a bytes download waiting for it
        val fileSystem = FakeFailingFileSystem()
        cache =
            newTestFileCache(baseDirectory = dataDirectory, store = store, fileSystem = fileSystem)
        val gate = holdDownloads()
        val ensure = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.downloadHeld)
        val download = async(Dispatchers.Default) { bucket().downloadAuthenticated(PATH) {} }
        awaitWaiters(2)

        // When — the disk fills up, then the bytes arrive
        fileSystem.failWritesAfterBytes = 0
        server.downloadGate = null
        gate.complete(Unit)

        // Then — ensureCachedFile could not produce its file, but the bytes download, which a copy
        // the disk refused never fails, fetched the object for itself
        assertIs<okio.IOException>(awaitSoon(ensure).exceptionOrNull())
        assertEquals(OLD, awaitSoon(download).decodeToString())
        assertEquals(2, downloads())
    }

    @Test
    fun a_channel_download_that_joined_one_an_update_outran_streams_the_new_object() = runTest {
        // Given — an ensureCachedFile download of the old object held at the server, and a channel
        // download waiting for it
        val gate = holdDownloads()
        val ensure = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)
        val channel = ByteChannel()
        val download =
            async(Dispatchers.Default) { bucket().downloadAuthenticated(PATH, channel) {} }
        val received = async(Dispatchers.Default) { channel.readRemaining().readByteArray() }
        awaitWaiters(2)

        // When — the object is replaced, then the old bytes arrive
        bucket().update(PATH, NEW.encodeToByteArray())
        server.downloadGate = null
        gate.complete(Unit)
        awaitSoon(download)
        channel.flushAndClose()

        // Then — the download it waited for was not kept, so the channel download fetched the new
        // object itself, and nothing of the old one reached it
        assertEquals(NEW, awaitSoon(received).decodeToString())
        assertEquals(NEW, contentOf(awaitSoon(ensure)))
        assertTrue(downloads() >= 2)
    }

    @Test
    fun a_download_that_fails_fails_every_caller_once_each_has_tried_its_own_request() = runTest {
        // Given — a download held before the server answers, with two callers waiting for it
        val held = CompletableDeferred<Unit>()
        server.gate = held
        val first = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.requestArrived)
        val second = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitWaiters(2)

        // When — the network goes away under the request
        server.online = false
        server.gate = null
        held.complete(Unit)
        val outcomes = listOf(awaitSoon(first), awaitSoon(second))

        // Then — each caller got the connectivity failure; the one that joined tried its own
        // request once, and nothing is cached
        outcomes.forEach { outcome ->
            val failure = assertNotNull(outcome.exceptionOrNull())
            assertTrue(
                failure.isNetworkOrConnectivityFailure(),
                "expected the connectivity failure, got $failure",
            )
        }
        assertEquals(2, server.requests.size)
        assertNull(bucket().cachedFile(PATH))
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val OLD = "old-avatar"
        const val NEW = "new-avatar"
    }
}

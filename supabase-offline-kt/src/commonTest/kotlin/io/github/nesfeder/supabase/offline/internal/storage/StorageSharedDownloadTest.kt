// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.jan.supabase.storage.storage
import io.github.nesfeder.supabase.offline.CachedFile
import io.github.nesfeder.supabase.offline.OfflineBucketApi
import io.github.nesfeder.supabase.offline.isNetworkOrConnectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.FakeBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestBucketApi
import io.github.nesfeder.supabase.offline.testdoubles.newTestFileCache
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
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
 * canceled only once every caller has canceled. A write landing while the shared download is on the
 * wire still keeps its bytes out of the cache, and every waiter then gets the new object,
 * downloaded once more between them; a clear voids the download for every waiter.
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

    /** A bucket of its own over the one cache, as each `storage.from(bucket)` of a client is. */
    private fun bucket(): OfflineBucketApi =
        newTestBucketApi(
            delegate = server.supabaseClient().storage.from(BUCKET),
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
        midway.await()
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
    fun a_caller_joining_a_download_an_update_outruns_gets_the_new_bytes() = runTest {
        // Given — a download of the old object held at the server
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitSoon(server.downloadHeld)

        // When — the object is replaced, a second caller joins the download of the old bytes, and
        // then they arrive
        bucket().update(PATH, NEW.encodeToByteArray())
        val second = async(Dispatchers.Default) { bucket().ensureCachedFile(PATH) }
        awaitWaiters(2)
        server.downloadGate = null
        gate.complete(Unit)
        val files = listOf(awaitSoon(first), awaitSoon(second))

        // Then — the old bytes were kept for neither; both callers got the new object, downloaded
        // once more between them
        files.forEach { file -> assertEquals(NEW, contentOf(file)) }
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
    fun a_download_that_fails_fails_every_caller_waiting_for_it() = runTest {
        // Given — a download held before the server answers, with two callers waiting for it
        val held = CompletableDeferred<Unit>()
        server.gate = held
        val first = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.requestArrived)
        val second = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitWaiters(2)

        // When — the network goes away under the request
        server.online = false
        held.complete(Unit)
        val outcomes = listOf(awaitSoon(first), awaitSoon(second))

        // Then — each caller got the connectivity failure; one request was made, nothing is cached
        outcomes.forEach { outcome ->
            val failure = assertNotNull(outcome.exceptionOrNull())
            assertTrue(
                failure.isNetworkOrConnectivityFailure(),
                "expected the connectivity failure, got $failure",
            )
        }
        assertEquals(1, server.requests.size)
        assertNull(bucket().cachedFile(PATH))
    }

    @Test
    fun a_clear_during_a_shared_download_voids_it_for_every_caller() = runTest {
        // Given — two callers waiting for a download held at the server
        val gate = holdDownloads()
        val first = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitSoon(server.downloadHeld)
        val second = async(Dispatchers.Default) { runCatching { bucket().ensureCachedFile(PATH) } }
        awaitWaiters(2)

        // When — local data is cleared, as a sign-out does, then the bytes arrive
        cache.clear { store.cachedFileDao.deleteAll() }
        gate.complete(Unit)
        val outcomes = listOf(awaitSoon(first), awaitSoon(second))

        // Then — neither caller got the outgoing user's file, and nothing was put back
        outcomes.forEach { outcome -> assertIs<IllegalStateException>(outcome.exceptionOrNull()) }
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), regularFilesUnder(dataDirectory))
        assertEquals(1, downloads())
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val OLD = "old-avatar"
        const val NEW = "new-avatar"
    }
}

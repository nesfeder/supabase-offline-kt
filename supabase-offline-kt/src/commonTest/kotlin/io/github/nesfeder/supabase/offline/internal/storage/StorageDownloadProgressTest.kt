// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.DownloadProgress
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.testing.ValueConstructors
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * `ensureCachedFile` with a progress callback hands it the bytes received and the object's size
 * while the download runs: every call waiting for one shared download is handed it, one that joins
 * late from where the download stands, and each gets the last progress before it returns. A cached
 * copy is returned without a call, and nothing is published for a download no call watches.
 *
 * The server sends each body in two parts, the rest held until the test lets it through, so a test
 * sees the download under way.
 */
class StorageDownloadProgressTest {

    private lateinit var dataDirectory: Path
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-progress-${Random.nextLong().toULong()}"
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
        server.sendsContentLength = true
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun newClient(): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            baseDirectoryProvider = { dataDirectory },
        )

    private fun progress(bytesReceived: Long, totalBytes: Long?): DownloadProgress =
        ValueConstructors.downloadProgress(bytesReceived, totalBytes)

    /** Collects the progress one call is handed, and signals its first. */
    private class Watched {
        val events = mutableListOf<DownloadProgress>()
        val first = CompletableDeferred<DownloadProgress>()

        fun record(progress: DownloadProgress) {
            events += progress
            first.complete(progress)
        }
    }

    @Test
    fun a_download_hands_its_progress_from_the_first_bytes_to_the_last() = runTest {
        // Given — a body whose first bytes arrive, and the rest held
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        server.bodyGate = CompletableDeferred()
        val watched = Watched()

        // When
        val file = async { bucket.ensureCachedFile(PATH) { progress -> watched.record(progress) } }
        val first = watched.first.await()
        server.bodyGate?.complete(Unit)
        val cached = file.await()

        // Then — part of the object while the rest was held, then all of it, never going back
        assertTrue(first.bytesReceived in 1L..server.firstChunkBytes.toLong(), "$first")
        assertEquals(TOTAL, first.totalBytes)
        assertEquals(progress(TOTAL, TOTAL), watched.events.last())
        assertEquals(watched.events.sortedBy { it.bytesReceived }, watched.events)
        assertEquals(TOTAL, cached.sizeBytes)
        client.close()
    }

    @Test
    fun every_call_waiting_for_one_download_is_handed_its_progress() = runTest {
        // Given — a download under way for one call
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        server.bodyGate = CompletableDeferred()
        val early = Watched()
        val late = Watched()
        val first = async { bucket.ensureCachedFile(PATH) { progress -> early.record(progress) } }
        val underWay = early.first.await()

        // When — a second call joins it, then the rest of the body arrives
        val second = async { bucket.ensureCachedFile(PATH) { progress -> late.record(progress) } }
        val joinedAt = late.first.await()
        server.bodyGate?.complete(Unit)
        first.await()
        second.await()

        // Then — one request; the late call started from where the download stood, and both were
        // handed the end
        assertEquals(1, server.requests.count { it.method == "GET" })
        assertTrue(
            joinedAt.bytesReceived in underWay.bytesReceived..server.firstChunkBytes.toLong(),
            "$underWay, then $joinedAt",
        )
        assertEquals(TOTAL, joinedAt.totalBytes)
        assertEquals(progress(TOTAL, TOTAL), early.events.last())
        assertEquals(progress(TOTAL, TOTAL), late.events.last())
        client.close()
    }

    @Test
    fun a_cached_copy_is_returned_without_progress() = runTest {
        // Given
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        bucket.ensureCachedFile(PATH)
        val watched = Watched()

        // When
        bucket.ensureCachedFile(PATH) { progress -> watched.record(progress) }

        // Then
        assertEquals(emptyList(), watched.events)
        assertEquals(1, server.requests.size)
        client.close()
    }

    @Test
    fun the_size_is_unknown_when_the_answer_gives_none() = runTest {
        // Given
        server.sendsContentLength = false
        val client = newClient()
        val watched = Watched()

        // When
        client.storage.from(BUCKET).ensureCachedFile(PATH) { progress -> watched.record(progress) }

        // Then
        assertEquals(progress(TOTAL, null), watched.events.last())
        assertTrue(watched.events.all { it.totalBytes == null })
        client.close()
    }

    @Test
    fun a_callback_that_throws_ends_its_own_call_and_the_download_goes_on() = runTest {
        // Given — one call watching, and another whose callback throws
        val client = newClient()
        val bucket = client.storage.from(BUCKET)
        server.bodyGate = CompletableDeferred()
        val watched = Watched()
        val kept = async { bucket.ensureCachedFile(PATH) { progress -> watched.record(progress) } }
        watched.first.await()

        // When
        val thrown = async {
            assertFailsWith<IllegalStateException> {
                bucket.ensureCachedFile(PATH) { error("the app's callback failed") }
            }
        }
        val failure = thrown.await()
        server.bodyGate?.complete(Unit)
        val file = kept.await()

        // Then — that call failed with the callback's exception; the other got the file
        assertEquals("the app's callback failed", failure.message)
        assertEquals(TOTAL, file.sizeBytes)
        assertEquals(1, server.requests.count { it.method == "GET" })
        client.close()
    }

    @Test
    fun an_empty_object_hands_a_final_progress() = runTest {
        // Given — an object with no bytes
        server.objects["$BUCKET/empty.txt"] = ByteArray(0)
        val client = newClient()
        val watched = Watched()

        // When
        val file =
            client.storage.from(BUCKET).ensureCachedFile("empty.txt") { progress ->
                watched.record(progress)
            }

        // Then — one progress, that it is whole
        assertEquals(0L, file.sizeBytes)
        assertEquals(listOf(progress(0, 0)), watched.events)
        client.close()
    }

    @Test
    fun a_late_joiner_never_moves_the_progress_back() = runTest {
        // Given / When — on real threads, callers joining while the chunks of one download are
        // reported, many times over
        var backward = 0
        var staleLast = 0
        withContext(Dispatchers.Default) {
            repeat(RACE_ITERATIONS) {
                val relay = DownloadProgressRelay()
                val handed = mutableListOf<DownloadProgress>()
                val watching = CompletableDeferred<Unit>()
                val finish = CompletableDeferred<Unit>()
                // The caller that started the download, watching from before its first chunk
                val first = async {
                    relay.watching({ handed += it }) {
                        watching.complete(Unit)
                        finish.await()
                    }
                }
                watching.await()
                val done = AtomicBoolean(false)
                val joiners =
                    List(RACE_JOINERS) { async { while (!done.load()) relay.watching({}) {} } }
                val reporter = async {
                    for (bytes in 1L..RACE_CHUNKS) relay.report(bytes, RACE_CHUNKS)
                    done.store(true)
                }
                reporter.await()
                joiners.awaitAll()
                finish.complete(Unit)
                first.await()
                if (handed.sortedBy { it.bytesReceived } != handed) backward++
                if (handed.last() != progress(RACE_CHUNKS, RACE_CHUNKS)) staleLast++
            }
        }

        // Then — the first caller never saw the download go back, and was handed its end
        assertEquals(
            "backward 0, stale last 0",
            "backward $backward, stale last $staleLast",
            "of $RACE_ITERATIONS iterations",
        )
    }

    @Test
    fun nothing_is_published_for_a_download_no_call_watches() = runTest {
        // Given — a register that counts what its downloads publish
        var published = 0
        val downloads = InFlightDownloads(onProgressPublished = { published++ })

        // When — a download no call watches reports its chunks
        val unwatched =
            downloads.joinOrStart(KEY, backgroundScope) {
                report(4, TOTAL)
                report(TOTAL, TOTAL)
                CommitOutcome.COMMITTED
            }
        val publishedUnwatched = published

        // And a download a call watches
        val handed = mutableListOf<DownloadProgress>()
        downloads.joinOrStart(KEY, backgroundScope, onProgress = { handed += it }) {
            report(4, TOTAL)
            report(TOTAL, TOTAL)
            CommitOutcome.COMMITTED
        }

        // Then
        assertEquals(CommitOutcome.COMMITTED, (unwatched as SharedOutcome.Ended).outcome)
        assertEquals(0, publishedUnwatched)
        assertTrue(published > 0)
        assertEquals(progress(TOTAL, TOTAL), handed.last())
    }

    @Test
    fun a_relay_watched_before_any_chunk_hands_nothing_until_one_arrives() = runTest {
        // Given
        val relay = DownloadProgressRelay()
        val handed = mutableListOf<DownloadProgress>()

        // When — watched through a block that reports nothing, then through one that reports
        relay.watching({ handed += it }) {}
        val beforeAnyChunk = handed.toList()
        relay.watching({ handed += it }) { relay.report(TOTAL, null) }

        // Then
        assertEquals(emptyList(), beforeAnyChunk)
        assertEquals(progress(TOTAL, null), handed.last())
        assertNull(handed.last().totalBytes)
    }

    private companion object {
        const val BUCKET = "media"
        const val PATH = "photos/a.png"
        const val CONTENT = "the original bytes"
        const val TOTAL = 18L
        const val KEY = "media::photos/a.png"
        const val RACE_ITERATIONS = 2_000
        const val RACE_JOINERS = 3
        const val RACE_CHUNKS = 2_000L
    }
}

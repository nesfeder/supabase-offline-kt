// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.testing.InMemoryFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download still in flight when local data is cleared belongs to the outgoing user. It still
 * hands every byte to the caller that asked, whether the clear came before or part-way through
 * them, but it must not put a file back into the cache the clear just emptied, where the next user
 * would be served it.
 */
class StorageClearDuringDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: TestStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-clear-download-${Random.nextLong().toULong()}"
        store = newTestStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun cachedFiles(fileSystem: FileSystem = FileSystem.SYSTEM): List<Path> =
        if (!fileSystem.exists(dataDirectory)) {
            emptyList()
        } else {
            fileSystem
                .listRecursively(dataDirectory)
                .filter { fileSystem.metadata(it).isRegularFile }
                .toList()
        }

    /** Waits, on real time, until [condition] holds. */
    private suspend fun awaitOnRealTime(condition: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(5.seconds) { while (!condition()) delay(5) }
        }
    }

    /** Waits, on real time, for [deferred]. */
    private suspend fun <T> awaitSoon(deferred: Deferred<T>): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { deferred.await() } }

    @Test
    fun a_download_that_finishes_after_a_clear_is_not_cached() = runTest {
        // Given — a download parked at the server
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                cachedFileDao = store.cachedFileDao,
                baseDirectoryProvider = { dataDirectory },
            )
        server.gate = CompletableDeferred()
        val download = async { client.storage.from(BUCKET).downloadAuthenticated(PATH) {} }
        server.requestArrived.await()

        // When — the user signs out, then the response arrives
        client.clearLocalData()
        server.gate?.complete(Unit)
        val bytes = download.await()

        // Then — the caller got its bytes, and nothing was put back
        assertEquals(CONTENT, bytes.decodeToString())
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_channel_download_that_finishes_after_a_clear_is_not_cached() = runTest {
        // Given
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                cachedFileDao = store.cachedFileDao,
                baseDirectoryProvider = { dataDirectory },
            )
        server.gate = CompletableDeferred()
        val channel = ByteChannel()
        val download = async { client.storage.from(BUCKET).downloadAuthenticated(PATH, channel) {} }
        val received = async { channel.readRemaining().readByteArray() }
        server.requestArrived.await()

        // When
        client.clearLocalData()
        server.gate?.complete(Unit)
        download.await()
        channel.flushAndClose()

        // Then
        assertEquals(CONTENT, received.await().decodeToString())
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    @Test
    fun a_clear_part_way_through_a_channel_download_still_hands_the_caller_every_byte() = runTest {
        assertEveryByteReachesTheCallerAcrossAClear(FileSystem.SYSTEM)
    }

    @Test
    fun a_clear_part_way_through_a_channel_download_in_memory_still_hands_the_caller_every_byte() =
        runTest {
            assertEveryByteReachesTheCallerAcrossAClear(InMemoryFileSystem(Clock.System))
        }

    /**
     * Clears local data once a channel download's first bytes are in its temporary file, then lets
     * the rest arrive: the caller gets the whole object, nothing is cached, and no file is left.
     */
    private suspend fun assertEveryByteReachesTheCallerAcrossAClear(fileSystem: FileSystem) =
        coroutineScope {
            // Given — a download whose first bytes are on disk and whose rest waits at the server
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    cachedFileDao = store.cachedFileDao,
                    baseDirectoryProvider = { dataDirectory },
                    fileSystem = fileSystem,
                    syncFile = {},
                )
            server.bodyGate = CompletableDeferred()
            server.firstChunkBytes = 4
            val channel = ByteChannel()
            val download =
                async(Dispatchers.Default) {
                    client.storage.from(BUCKET).downloadAuthenticated(PATH, channel) {}
                }
            val received = async(Dispatchers.Default) { channel.readRemaining().readByteArray() }
            awaitOnRealTime { cachedFiles(fileSystem).any { it.name.endsWith(".tmp") } }

            // When — the user signs out, then the rest of the body arrives
            client.clearLocalData()
            server.bodyGate?.complete(Unit)
            awaitSoon(download)
            channel.flushAndClose()

            // Then — the caller got every byte from the one fetch, nothing was put back, and the
            // file is gone
            assertEquals(CONTENT, awaitSoon(received).decodeToString())
            assertEquals(1, server.requests.size, "the download was fetched again")
            assertNull(store.getStorageByKey(BUCKET, PATH))
            assertEquals(emptyList(), cachedFiles(fileSystem))
        }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val CONTENT = "outgoing-user-avatar"
    }
}

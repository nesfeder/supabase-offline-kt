// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A download still in flight when local data is cleared belongs to the outgoing user. It may still
 * hand its bytes to the caller that asked, but it must not put a file back into the cache the clear
 * just emptied, where the next user would be served it.
 */
class StorageClearDuringDownloadTest {

    private lateinit var dataDirectory: Path
    private lateinit var store: OfflineStore
    private lateinit var server: RecordingStorageServer

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-clear-download-${Random.nextLong().toULong()}"
        store = newTestOfflineStore()
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun cachedFiles(): List<Path> =
        if (!FileSystem.SYSTEM.exists(dataDirectory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(dataDirectory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    @Test
    fun a_download_that_finishes_after_a_clear_is_not_cached() = runTest {
        // Given — a download parked at the server
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                store = store,
                baseDirectoryProvider = { dataDirectory },
            )
        server.gate = CompletableDeferred()
        val download = async { client.storage(BUCKET).downloadAuthenticated(PATH) {} }
        server.requestArrived.await()

        // When — the user signs out, then the response arrives
        client.clearAllCache()
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
                store = store,
                baseDirectoryProvider = { dataDirectory },
            )
        server.gate = CompletableDeferred()
        val channel = ByteChannel()
        val download = async { client.storage(BUCKET).downloadAuthenticated(PATH, channel) {} }
        val received = async { channel.readRemaining().readByteArray() }
        server.requestArrived.await()

        // When
        client.clearAllCache()
        server.gate?.complete(Unit)
        download.await()
        channel.flushAndClose()

        // Then
        assertEquals(CONTENT, received.await().decodeToString())
        assertNull(store.getStorageByKey(BUCKET, PATH))
        assertEquals(emptyList(), cachedFiles())
    }

    private companion object {
        const val BUCKET = "avatars"
        const val PATH = "u-1/me.png"
        const val CONTENT = "outgoing-user-avatar"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingStorageServer
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.readRemaining
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * `offline.storage.clearFileCache()` frees the device's storage without the sign-out wipe: every
 * downloaded file and its row go, in every bucket, while the mutation queue and the cached query
 * results stay. A download still in flight when it runs is treated as during `clearLocalData()`:
 * its caller gets the bytes, and nothing is put back into the emptied cache.
 */
class StorageClearFileCacheTest {

    private lateinit var dataDirectory: Path
    private lateinit var server: RecordingStorageServer
    private val cachedFileDao = InMemoryCachedFileDao()
    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()

    @BeforeTest
    fun setUp() {
        dataDirectory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-clear-file-cache-${Random.nextLong().toULong()}"
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
        server.objects["$OTHER_BUCKET/$PATH"] = CONTENT.encodeToByteArray()
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(dataDirectory, mustExist = false)
    }

    private fun newClient(): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
            cachedFileDao = cachedFileDao,
            mutationDao = mutationDao,
            baseDirectoryProvider = { dataDirectory },
        )

    /** Every regular file under the data directory, temporary ones included. */
    private fun filesOnDisk(): List<Path> =
        if (!FileSystem.SYSTEM.exists(dataDirectory)) {
            emptyList()
        } else {
            FileSystem.SYSTEM.listRecursively(dataDirectory)
                .filter { FileSystem.SYSTEM.metadata(it).isRegularFile }
                .toList()
        }

    @Test
    fun it_deletes_every_file_and_row_and_leaves_the_queue_and_the_query_cache() = runTest {
        // Given — files in two buckets, a transform among them, a queued write and a cached list
        val client = newClient()
        client.storage.from(BUCKET).downloadAuthenticated(PATH) {}
        client.storage.from(BUCKET).downloadAuthenticated(PATH) { transform { size(64, 64) } }
        client.storage.from(OTHER_BUCKET).ensureCachedFile(PATH)
        client.mutationQueue.enqueueInsert(
            table = TABLE,
            body = JsonArray(listOf(buildJsonObject { put("title", JsonPrimitive("Tea")) })),
        )
        val cachedList = CachedQueryRow(TABLE, CACHE_KEY, payload = "[]", updatedAt = 1L)
        queryCacheDao.upsert(cachedList)
        assertEquals(3, cachedFileDao.all().size)
        assertEquals(3, filesOnDisk().size)

        // When
        client.storage.clearFileCache()

        // Then — no file and no row is left; the queued write and the cached list are
        assertEquals(emptyList(), cachedFileDao.all())
        assertEquals(emptyList(), filesOnDisk())
        assertEquals(1, mutationDao.countAll())
        assertEquals(cachedList, queryCacheDao.getRow(TABLE, CACHE_KEY))
        assertNull(client.storage.from(BUCKET).cachedFile(PATH))
        assertNull(client.storage.from(BUCKET).cachedFile(PATH) { size(64, 64) })
        assertNull(client.storage.from(OTHER_BUCKET).cachedFile(PATH))
        client.close()
    }

    @Test
    fun the_cache_fills_again_after_it() = runTest {
        // Given
        val client = newClient()
        client.storage.from(BUCKET).ensureCachedFile(PATH)
        client.storage.clearFileCache()

        // When
        val again = client.storage.from(BUCKET).ensureCachedFile(PATH)

        // Then — downloaded once more, and kept
        assertEquals(2, server.requests.size)
        assertEquals(CONTENT, FileSystem.SYSTEM.read(again.path.toPath()) { readUtf8() })
        assertNotNull(cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH)))
        client.close()
    }

    @Test
    fun a_download_that_finishes_after_it_is_handed_over_but_not_cached() = runTest {
        // Given — a download parked at the server
        val client = newClient()
        server.gate = CompletableDeferred()
        val download = async { client.storage.from(BUCKET).downloadAuthenticated(PATH) {} }
        server.requestArrived.await()

        // When — the file cache is cleared, then the response arrives
        client.storage.clearFileCache()
        server.gate?.complete(Unit)
        val bytes = download.await()

        // Then
        assertEquals(CONTENT, bytes.decodeToString())
        assertNull(cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH)))
        assertEquals(emptyList(), filesOnDisk())
        client.close()
    }

    @Test
    fun a_channel_download_that_finishes_after_it_is_handed_over_but_not_cached() = runTest {
        // Given
        val client = newClient()
        server.gate = CompletableDeferred()
        val channel = ByteChannel()
        val download = async {
            client.storage.from(BUCKET).downloadAuthenticated(PATH, channel) {}
        }
        val received = async { channel.readRemaining().readByteArray() }
        server.requestArrived.await()

        // When
        client.storage.clearFileCache()
        server.gate?.complete(Unit)
        download.await()
        channel.flushAndClose()

        // Then
        assertEquals(CONTENT, received.await().decodeToString())
        assertNull(cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH)))
        assertEquals(emptyList(), filesOnDisk())
        client.close()
    }

    @Test
    fun an_ensureCachedFile_running_across_it_fails_as_across_clearLocalData() = runTest {
        val acrossTheFileCacheClear = ensureCachedFileAcrossAClear { it.storage.clearFileCache() }
        val acrossClearLocalData = ensureCachedFileAcrossAClear { it.clearLocalData() }

        assertIs<IllegalStateException>(acrossTheFileCacheClear)
        assertIs<IllegalStateException>(acrossClearLocalData)
    }

    /**
     * Parks an `ensureCachedFile` at the server, runs [clear], then lets the download finish: what
     * the call threw, once it is checked that nothing was cached.
     */
    private suspend fun ensureCachedFileAcrossAClear(clear: suspend (Offline) -> Unit): Throwable? {
        server = RecordingStorageServer()
        server.objects["$BUCKET/$PATH"] = CONTENT.encodeToByteArray()
        val client = newClient()
        server.gate = CompletableDeferred()
        val failure = coroutineScope {
            val ensured = async {
                runCatching { client.storage.from(BUCKET).ensureCachedFile(PATH) }
            }
            server.requestArrived.await()
            clear(client)
            server.gate?.complete(Unit)
            ensured.await().exceptionOrNull()
        }
        assertNull(cachedFileDao.getByKey(cachedFileKeyOf(BUCKET, PATH)))
        assertEquals(emptyList(), filesOnDisk())
        assertEquals(1, server.requests.size, "the download was fetched again")
        client.close()
        return failure
    }

    @Test
    fun a_closed_client_refuses_it() = runTest {
        // Given
        val client = newClient()
        val storage = client.storage
        client.close()

        // When / Then
        assertFailsWith<IllegalStateException> { storage.clearFileCache() }
    }

    private companion object {
        const val BUCKET = "avatars"
        const val OTHER_BUCKET = "documents"
        const val PATH = "u-1/me.png"
        const val CONTENT = "cached-avatar"
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?select=*"
    }
}

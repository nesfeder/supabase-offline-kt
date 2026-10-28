// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.storage.STORAGE_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.storage.cachedFileKeyOf
import io.github.nesfeder.supabase.offline.internal.storage.hashedFilePath
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath

/**
 * A queued upload's bytes become the object's cached copy where the file cache places any copy:
 * under the object's own name, or its hashed file when an object and a folder of the same name
 * (`album` and `album/cover.png`) would share that place. The copy is served offline and evicted as
 * one the cache downloaded, and the other name's copy is left as it was.
 */
class QueuedUploadObjectAndFolderTest {

    private val server = UploadAndRowServer()
    private val fileSystem = InMemoryFileSystem(Clock.System)
    private val dataDirectory: Path = "/data".toPath()
    private val opened = mutableListOf<OfflineClient>()

    @AfterTest fun closeClients() = runTest { opened.forEach { client -> client.close() } }

    private fun openClient(): OfflineClient =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = FakeQueryCacheDao(),
                cachedFileDao = InMemoryCachedFileDao(),
                mutationDao = FakeMutationDao(),
                baseDirectoryProvider = { dataDirectory },
                fileSystem = fileSystem,
                syncFile = {},
                config = Offline.Config().apply { autoSync = false },
                engineLogger = RecordingLogSink().asEngineLogger(EngineLogLevel.DEBUG),
            )
            .also { client -> opened += client }

    /** Where [path] is cached under its own name. */
    private fun plainFile(path: String): Path =
        path.split('/').fold(dataDirectory / STORAGE_DIRECTORY_NAME / BUCKET) { parent, segment ->
            parent / segment
        }

    /** Where [path] is cached when its own name's place is taken. */
    private fun hashedFile(path: String): Path =
        hashedFilePath(dataDirectory, cachedFileKeyOf(BUCKET, path))

    private fun read(path: String): ByteArray = fileSystem.read(path.toPath()) { readByteArray() }

    /** Queues an upload of [bytes] to [path] offline, then sends the queue online. */
    private suspend fun queueThenSend(client: OfflineClient, path: String, bytes: ByteArray) {
        server.online = false
        assertIs<UploadOutcome.Queued>(
            client.storage.from(BUCKET).upload(path, bytes, MutationStrategy.QueueIfOffline)
        )
        server.online = true
        assertEquals(0, client.syncPendingMutations().remainingCount)
        assertContentEquals(bytes, server.objects.value["$BUCKET/$path"])
    }

    /** Asserts that [path] is served from the cache alone, offline, with [bytes] from [file]. */
    private suspend fun assertServedOffline(
        client: OfflineClient,
        path: String,
        bytes: ByteArray,
        file: Path,
    ) {
        val bucket = client.storage.from(BUCKET)
        val cached = assertNotNull(bucket.cachedFile(path), path)
        assertEquals(file.toString(), cached.path, path)
        assertContentEquals(bytes, read(cached.path), path)
        assertContentEquals(bytes, bucket.downloadAuthenticated(path), path)
        assertEquals(cached.path, bucket.ensureCachedFile(path).path, path)
    }

    @Test
    fun an_upload_under_the_name_of_a_cached_object_is_cached_under_its_hashed_file() = runTest {
        // Given — album is cached, a file where album/cover.png needs a directory
        val client = openClient()
        val bucket = client.storage.from(BUCKET)
        server.objects.value = mapOf("$BUCKET/$ALBUM" to ALBUM_BYTES)
        assertEquals(plainFile(ALBUM).toString(), bucket.ensureCachedFile(ALBUM).path)

        // When — album/cover.png is uploaded through the queue
        queueThenSend(client, COVER, COVER_BYTES)

        // Then — its copy is under its hashed file, album's under its own, both served offline
        server.online = false
        assertServedOffline(client, COVER, COVER_BYTES, hashedFile(COVER))
        assertServedOffline(client, ALBUM, ALBUM_BYTES, plainFile(ALBUM))

        // When — the upload's copy is evicted
        bucket.evictCachedFile(COVER)

        // Then — its file goes, and album is served as before
        assertFalse(fileSystem.exists(hashedFile(COVER)))
        assertNull(bucket.cachedFile(COVER))
        assertServedOffline(client, ALBUM, ALBUM_BYTES, plainFile(ALBUM))
    }

    @Test
    fun an_upload_named_like_a_cached_folder_is_cached_under_its_hashed_file() = runTest {
        // Given — album/cover.png is cached, a directory where album needs a file
        val client = openClient()
        val bucket = client.storage.from(BUCKET)
        server.objects.value = mapOf("$BUCKET/$COVER" to COVER_BYTES)
        assertEquals(plainFile(COVER).toString(), bucket.ensureCachedFile(COVER).path)

        // When — album is uploaded through the queue
        queueThenSend(client, ALBUM, ALBUM_BYTES)

        // Then
        server.online = false
        assertServedOffline(client, ALBUM, ALBUM_BYTES, hashedFile(ALBUM))
        assertServedOffline(client, COVER, COVER_BYTES, plainFile(COVER))

        // When
        bucket.evictCachedFile(ALBUM)

        // Then
        assertFalse(fileSystem.exists(hashedFile(ALBUM)))
        assertNull(bucket.cachedFile(ALBUM))
        assertServedOffline(client, COVER, COVER_BYTES, plainFile(COVER))
    }

    @Test
    fun an_upload_sent_at_once_under_a_cached_object_is_cached_under_its_hashed_file() = runTest {
        // Given
        val client = openClient()
        val bucket = client.storage.from(BUCKET)
        server.objects.value = mapOf("$BUCKET/$ALBUM" to ALBUM_BYTES)
        bucket.ensureCachedFile(ALBUM)

        // When — sent at once, not queued
        assertIs<UploadOutcome.Applied>(
            bucket.upload(COVER, COVER_BYTES, MutationStrategy.QueueIfOffline)
        )

        // Then
        server.online = false
        assertServedOffline(client, COVER, COVER_BYTES, hashedFile(COVER))
        assertServedOffline(client, ALBUM, ALBUM_BYTES, plainFile(ALBUM))
    }

    private companion object {
        const val BUCKET = "photos"
        const val ALBUM = "album"
        const val COVER = "album/cover.png"
        val ALBUM_BYTES = "the album object".encodeToByteArray()
        val COVER_BYTES = "the cover photo".encodeToByteArray()
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.storage.QUEUED_UPLOADS_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer.UploadAnswer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * A sent upload's file is gone by the time its row is: a sync that finds the queue empty never
 * finds a queued file left, however long the file system takes to delete one.
 */
class QueuedUploadFileRemovalTest {

    /** Takes [slowness] over each delete of a queued upload's file while [slow] is set. */
    private class SlowDeletes(delegate: FileSystem) : ForwardingFileSystem(delegate) {
        @Volatile var slow = false

        override fun delete(path: Path, mustExist: Boolean) {
            if (slow && QUEUED_UPLOADS_DIRECTORY_NAME in path.toString()) {
                val start = TimeSource.Monotonic.markNow()
                while (start.elapsedNow() < slowness) {
                    // A disk that takes its time; the sender is on an IO thread meanwhile
                }
            }
            super.delete(path, mustExist)
        }
    }

    private val server = UploadAndRowServer()
    private val fileSystem = SlowDeletes(InMemoryFileSystem(Clock.System))
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

    private fun queuedFiles(): List<String> {
        val directory = dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME
        if (!fileSystem.exists(directory)) return emptyList()
        return fileSystem.list(directory).map { file -> file.name }
    }

    private suspend fun awaitUploadsReached(count: Int) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (server.uploads().size < count) delay(1.milliseconds)
            }
        }
    }

    @Test
    fun a_sync_behind_the_pass_that_sent_an_upload_finds_its_file_gone() = runTest {
        // Given — an upload refused on replay, its file kept on the rejected list
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }
        val refused = client.syncPendingMutations().rejectedUploads.single()
        server.uploadAnswer = null
        fileSystem.slow = true

        // When — retried, the pass the retry asks for sends it, and a sync runs once its row has
        // left the queue
        client.retryRejectedMutation(refused.id)
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (client.pendingMutationCount() > 0) delay(1.milliseconds)
            }
        }
        val report = client.syncPendingMutations()

        // Then — nothing pending, and nothing of it left on the device
        assertEquals(0, report.remainingCount)
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun a_sync_behind_an_upload_sent_at_once_finds_its_file_gone() = runTest {
        // Given
        val client = openClient()
        fileSystem.slow = true

        // When — an upload sent at once, and a sync that meets it on the wire
        val uploading = async { client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) }
        awaitUploadsReached(1)
        val report = client.syncPendingMutations()

        // Then
        assertEquals(0, report.remainingCount)
        assertEquals(emptyList(), queuedFiles())
        assertIs<UploadOutcome.Applied>(uploading.await())
    }

    private companion object {
        val QueueIfOffline = MutationStrategy.QueueIfOffline
        val slowness = 300.milliseconds
        const val BUCKET = "photos"
        const val PATH = "user-a/avatar.jpg"
        val BYTES = "a photo's bytes".encodeToByteArray()
    }
}

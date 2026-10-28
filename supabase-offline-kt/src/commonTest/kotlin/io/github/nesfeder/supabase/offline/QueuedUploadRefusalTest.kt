// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.STORAGE_UPLOADS_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.mutationqueue.STORAGE_NOT_INSTALLED_REASON
import io.github.nesfeder.supabase.offline.internal.storage.MISSING_UPLOAD_FILE_REASON
import io.github.nesfeder.supabase.offline.internal.storage.QUEUED_UPLOADS_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.storage.UNREADABLE_UPLOAD_REASON
import io.github.nesfeder.supabase.offline.internal.storage.UPLOAD_OPERATION_NAME
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.FakeFailingFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * A queued upload the library can no longer send as it was made is refused onto the rejected list
 * with the library's reason, and the pass goes on: its file gone from the device, its stored body
 * unreadable, or no Storage plugin to send it through. An upload whose bytes the device cannot keep
 * is sent at once instead of being queued.
 */
class QueuedUploadRefusalTest {

    private val server = UploadAndRowServer()
    private val fileSystem = InMemoryFileSystem(Clock.System)
    private val dataDirectory: Path = "/data".toPath()
    private val queryCacheDao = FakeQueryCacheDao()
    private val cachedFileDao = InMemoryCachedFileDao()
    private val mutationDao = FakeMutationDao()
    private val logs = RecordingLogSink()
    private val opened = mutableListOf<OfflineClient>()
    private val temporaryDirectories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() = runTest {
        opened.forEach { client -> client.close() }
        temporaryDirectories.forEach { FileSystem.SYSTEM.deleteRecursively(it, mustExist = false) }
    }

    private fun openClient(
        supabase: SupabaseClient = server.supabaseClient(),
        files: FileSystem = fileSystem,
        directory: Path = dataDirectory,
    ): OfflineClient =
        newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                cachedFileDao = cachedFileDao,
                mutationDao = mutationDao,
                baseDirectoryProvider = { directory },
                fileSystem = files,
                syncFile = {},
                config = Offline.Config().apply { autoSync = false },
                engineLogger = logs.asEngineLogger(EngineLogLevel.DEBUG),
            )
            .also { client -> opened += client }

    @Test
    fun an_upload_whose_file_left_the_device_is_refused_and_the_pass_goes_on() = runTest {
        // Given — an upload queued offline, its file deleted before any pass could read it, then
        // a row queued behind it
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, MutationStrategy.QueueIfOffline)
        val directory = dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME
        fileSystem.list(directory).forEach { file -> fileSystem.delete(file) }
        client.from(PHOTOS).insert(JsonArray(emptyList()))

        // When
        server.online = true
        val report = client.syncPendingMutations()

        // Then — refused by the library (by this call, or a pass the library ran by itself),
        // never sent, and the row behind it went
        val refused = client.storage.rejectedUploadList().single()
        assertNull(refused.serverError)
        assertEquals(MISSING_UPLOAD_FILE_REASON, refused.message)
        assertFalse(refused.isRetryable)
        assertEquals(emptyList(), server.uploads())
        assertEquals(listOf("rest/$PHOTOS"), server.writes())
        assertEquals(0, report.remainingCount)
    }

    @Test
    fun an_upload_whose_stored_body_cannot_be_read_is_refused() = runTest {
        // Given — a queued upload row a later build cannot read
        val client = openClient()
        mutationDao.insert(
            MutationRow(
                table = STORAGE_UPLOADS_TABLE_NAME,
                operation = UPLOAD_OPERATION_NAME,
                bodyJson = """{"version":1,"bucket":"photos"}""",
                filterJson = null,
                createdAtMs = 0,
            )
        )

        // When
        val report = client.syncPendingMutations()

        // Then — refused, and listed as an upload the app can only dismiss
        assertEquals(0, report.remainingCount)
        val refused = client.storage.rejectedUploadList().single()
        assertEquals(UNREADABLE_UPLOAD_REASON, refused.message)
        assertEquals("", refused.bucketId)
        assertEquals("", refused.path)
        assertFalse(refused.isRetryable)
        assertEquals(emptyList(), client.rejectedMutationList())
        client.dismissRejectedMutation(refused.id)
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), server.uploads())
        assertEquals(emptyList(), client.storage.pendingUploads().first())
    }

    @Test
    fun an_upload_queued_before_storage_was_removed_is_refused() = runTest {
        // Given — an upload queued offline, then the app opened with no Storage plugin
        val before = openClient()
        server.online = false
        before.storage.from(BUCKET).upload(PATH, BYTES, MutationStrategy.QueueIfOffline)
        before.close()
        server.online = true
        val after =
            openClient(
                supabase =
                    newTestSupabaseClient(engine = server.engine) {
                        install(Postgrest) { maxRetries = 0 }
                    }
            )

        // When
        val report = after.syncPendingMutations()

        // Then — kept on the rejected list, with its file, for a client that has Storage
        val refused = report.rejectedUploads.single()
        assertEquals(STORAGE_NOT_INSTALLED_REASON, refused.message)
        assertTrue(refused.isRetryable)
        assertEquals(emptyList(), server.uploads())
    }

    @Test
    fun an_upload_whose_bytes_the_device_cannot_keep_is_sent_at_once() = runTest {
        // Given — a disk that refuses every write
        val directory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "supabase-offline-queued-upload-${Random.nextLong().toULong()}"
        temporaryDirectories += directory
        val disk = FakeFailingFileSystem().apply { failWritesAfterBytes = 0 }
        val client = openClient(files = disk, directory = directory)

        // When — online, then offline
        val online =
            client.storage.from(BUCKET).upload(PATH, BYTES, MutationStrategy.QueueIfOffline)
        server.online = false
        val offline =
            assertFailsWith<MutationNotQueueableException> {
                client.storage.from(BUCKET).upload(PATH, BYTES, MutationStrategy.QueueIfOffline)
            }

        // Then — sent, then refused for want of a connection; nothing was queued
        assertIs<UploadOutcome.Applied>(online)
        assertTrue(offline.cause!!.isNetworkOrConnectivityFailure())
        assertEquals(0, client.pendingMutationCount())
        assertEquals(1, server.uploads().size)
    }

    private companion object {
        const val BUCKET = "photos"
        const val PATH = "user-a/photo.jpg"
        const val PHOTOS = "task_photos"
        val BYTES = "a photo".encodeToByteArray()
    }
}

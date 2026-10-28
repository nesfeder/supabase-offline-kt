// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.storage.QUEUED_UPLOADS_DIRECTORY_NAME
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
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import okio.Path
import okio.Path.Companion.toPath

/** A caller canceled once its upload's row is stored leaves the upload queued with its file. */
class QueuedUploadCancellationTest {

    private val server = UploadAndRowServer()
    private val fileSystem = InMemoryFileSystem(Clock.System)
    private val dataDirectory: Path = "/data".toPath()
    private val opened = mutableListOf<OfflineClient>()

    /**
     * Commits the insert, then lets the caller be canceled, as withContext's prompt cancellation
     * does.
     */
    private class CancelAfterUploadInsert(private val real: FakeMutationDao) : MutationDao by real {
        var onUploadInserted: (() -> Unit)? = null

        override suspend fun insert(row: MutationRow): Long {
            val id = real.insert(row)
            if (row.operation == "UPLOAD") {
                onUploadInserted?.invoke()
                yield() // the Room call returns into a canceled caller
            }
            return id
        }
    }

    private val mutationDao = CancelAfterUploadInsert(FakeMutationDao())

    @AfterTest fun closeClients() = runTest { opened.forEach { client -> client.close() } }

    private fun openClient(): OfflineClient =
        newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = FakeQueryCacheDao(),
                cachedFileDao = InMemoryCachedFileDao(),
                mutationDao = mutationDao,
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

    @Test
    fun an_upload_canceled_after_its_row_committed_keeps_its_file() = runTest {
        // Given — offline, an upload whose caller is canceled right after its row is stored
        val client = openClient()
        server.online = false
        lateinit var job: Job
        mutationDao.onUploadInserted = { job.cancel() }
        job = launch { client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) }
        job.join()
        mutationDao.onUploadInserted = null

        // Then — the queue holds it, so its file must be there too
        assertTrue(job.isCancelled)
        assertEquals(1, client.storage.pendingUploads().first().size)
        assertEquals(1, queuedFiles().size, "the queued row's file was deleted")

        // When — the network is back
        server.online = true
        val report = client.syncPendingMutations()

        // Then — it is sent, not refused for a lost file
        assertEquals(emptyList(), report.rejectedUploads.map { it.message })
        assertContentEquals(BYTES, server.objects.value["$BUCKET/$PATH"])
    }

    private companion object {
        val QueueIfOffline = MutationStrategy.QueueIfOffline
        const val BUCKET = "photos"
        const val PATH = "user-a/avatar.jpg"
        val BYTES = "a photo's bytes".encodeToByteArray()
    }
}

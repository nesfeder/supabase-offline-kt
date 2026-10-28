// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.storage.QUEUED_UPLOADS_DIRECTORY_NAME
import io.github.nesfeder.supabase.offline.internal.storage.md5HexOrNull
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryFileSystem
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer.InfoAnswer
import io.github.nesfeder.supabase.offline.testdoubles.UploadAndRowServer.UploadAnswer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath

/**
 * A queued upload whose earlier send's answer was lost is sent again, and Storage's refusal of that
 * send counts as the upload stored only when the object at its path is verifiably its bytes: the
 * same size, and the same MD5 by an MD5 ETag or the same bytes downloaded. Any other object there
 * leaves the refusal standing, and a failure that may pass keeps the upload queued.
 */
class QueuedUploadLostAnswerTest {

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

    private fun queuedFiles(): List<String> {
        val directory = dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME
        if (!fileSystem.exists(directory)) return emptyList()
        return fileSystem.list(directory).map { file -> file.name }
    }

    private fun readFile(path: String): ByteArray =
        fileSystem.read(path.toPath()) { readByteArray() }

    /** A client whose upload of [BYTES] reached Storage, which stored it, and lost its answer. */
    private suspend fun clientWithLostAnswer(upsert: Boolean = false): OfflineClient {
        val client = openClient()
        server.uploadAnswer = { UploadAnswer.LoseAnswer }
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) { this.upsert = upsert }
        server.uploadAnswer = null
        return client
    }

    private fun infoRequests() = server.requests.count { it.target == "info/$BUCKET/$PATH" }

    @Test
    fun an_unconfirmed_upload_is_not_counted_sent_because_another_object_is_at_its_path() =
        runTest {
            // Given — another device already stored an object at the path; this device's upload of
            // the same path (upsert = false) went out once with no answer, so it is unconfirmed
            val client = openClient()
            val bucket = client.storage.from(BUCKET)
            server.objects.value = mapOf("$BUCKET/$PATH" to NEWER)
            server.online = false
            bucket.upload(PATH, BYTES, QueueIfOffline)

            // When — the queue is sent; Storage refuses a second object at the path
            server.online = true
            server.uploadAnswer = { UploadAnswer.Refuse(400, 409, "Duplicate") }
            val report = client.syncPendingMutations()

            // Then — the device's bytes never reached Storage, so they are not dropped as sent,
            // nor the cached copy of an object that holds other bytes
            assertContentEquals(NEWER, server.objects.value["$BUCKET/$PATH"])
            val cached = bucket.cachedFile(PATH)
            assertEquals(
                null,
                cached
                    ?.let { readFile(it.path).decodeToString() }
                    ?.takeIf { it == BYTES.decodeToString() },
                "the cache serves bytes Storage does not hold",
            )
            assertEquals(0, report.alreadyOnServerCount, "counted as already on the server")
            assertEquals(1, client.storage.rejectedUploadList().size)
            assertEquals(1, infoRequests())
        }

    @Test
    fun an_object_of_the_same_size_whose_md5_differs_leaves_the_refusal_standing() = runTest {
        // Given — the lost send landed, then another device replaced the object with other bytes
        // of the same size
        val client = clientWithLostAnswer()
        server.objects.value = mapOf("$BUCKET/$PATH" to SAME_SIZE)

        // When
        val report = client.syncPendingMutations()

        // Then — refused as the duplicate it is, its bytes kept for a retry
        assertEquals(0, report.alreadyOnServerCount)
        val refused = client.storage.rejectedUploadList().single()
        assertEquals(400, refused.serverError?.status)
        assertEquals("The resource already exists", refused.serverError?.message)
        assertEquals(1, queuedFiles().size)
        assertNull(client.storage.from(BUCKET).cachedFile(PATH))
        assertEquals(0, server.requests.count { it.target.startsWith("download/") })
    }

    @Test
    fun without_an_md5_etag_the_object_is_downloaded_and_counts_as_stored_when_it_is_the_bytes() =
        runTest {
            // Given — Storage gives no ETag for the object the lost send stored
            server.etagOf = { null }
            val client = clientWithLostAnswer()

            // When
            val report = client.syncPendingMutations()

            // Then — read back and compared, then counted as stored
            assertEquals(1, server.requests.count { it.target == "download/$BUCKET/$PATH" })
            assertEquals(1, report.alreadyOnServerCount)
            assertEquals(emptyList(), client.storage.rejectedUploadList())
            assertEquals(emptyList(), client.storage.pendingUploads().first())
            assertEquals(emptyList(), queuedFiles())
        }

    @Test
    fun a_multipart_etag_is_no_md5_so_the_downloaded_bytes_decide() = runTest {
        // Given — the object at the path has the upload's size but other bytes, and a multipart
        // upload's ETag, which carries no MD5 of them
        server.etagOf = { "\"0123456789abcdef0123456789abcdef-2\"" }
        val client = clientWithLostAnswer()
        server.objects.value = mapOf("$BUCKET/$PATH" to SAME_SIZE)

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1, server.requests.count { it.target == "download/$BUCKET/$PATH" })
        assertEquals(0, report.alreadyOnServerCount)
        assertEquals(1, client.storage.rejectedUploadList().size)
    }

    @Test
    fun a_refusal_with_no_object_at_the_path_stands() = runTest {
        // Given — the object the lost send stored is gone, and a policy now refuses the upload
        val client = clientWithLostAnswer()
        server.objects.value = emptyMap()
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }

        // When
        val report = client.syncPendingMutations()

        // Then — Storage's own refusal is what the rejected list keeps
        assertEquals(0, report.alreadyOnServerCount)
        assertEquals(
            "refused by the test",
            client.storage.rejectedUploadList().single().serverError?.message,
        )
        assertEquals(1, infoRequests())
    }

    @Test
    fun an_object_whose_info_does_not_read_leaves_the_refusal_standing() = runTest {
        // Given
        val client = clientWithLostAnswer()
        server.infoAnswer = { InfoAnswer.Respond(200, "not an object") }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(0, report.alreadyOnServerCount)
        assertEquals(1, client.storage.rejectedUploadList().size)
    }

    @Test
    fun an_info_answer_that_may_pass_keeps_the_upload_queued_until_it_can_be_told() = runTest {
        // Given — Storage cannot answer the question for now
        val client = clientWithLostAnswer()
        server.infoAnswer = { InfoAnswer.Respond(503, """{"message":"unavailable"}""") }

        // When
        val held = client.syncPendingMutations()

        // Then — neither refused nor counted stored
        assertEquals(1, held.remainingCount)
        assertEquals(emptyList(), client.storage.rejectedUploadList())

        // When — it can answer again
        server.infoAnswer = null
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1, report.alreadyOnServerCount)
        assertEquals(emptyList(), client.storage.pendingUploads().first())
    }

    @Test
    fun no_network_for_the_info_request_keeps_the_upload_queued() = runTest {
        // Given
        val client = clientWithLostAnswer()
        server.infoAnswer = { InfoAnswer.Unreachable }

        // When
        val report = client.syncPendingMutations()

        // Then
        assertEquals(1, report.remainingCount)
        assertEquals(SyncStopReason.NetworkUnavailable, report.stopReason)
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(1, client.storage.pendingUploads().first().size)
    }

    @Test
    fun an_upsert_refused_after_a_lost_answer_is_refused_without_reading_the_object() = runTest {
        // Given — an upload that may overwrite never meets a duplicate, so a refusal is the
        // policy's
        val client = clientWithLostAnswer(upsert = true)
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }

        // When
        client.syncPendingMutations()

        // Then
        assertEquals(1, client.storage.rejectedUploadList().size)
        assertEquals(0, infoRequests())
    }

    @Test
    fun an_answer_that_may_pass_after_a_lost_answer_keeps_it_queued_without_reading_the_object() =
        runTest {
            // Given
            val client = clientWithLostAnswer()
            server.uploadAnswer = { UploadAnswer.Refuse(503, 503, "Unavailable") }

            // When
            val report = client.syncPendingMutations()

            // Then
            assertEquals(1, report.remainingCount)
            assertEquals(emptyList(), client.storage.rejectedUploadList())
            assertEquals(0, infoRequests())
        }

    @Test
    fun an_etag_reads_as_an_md5_only_when_it_is_one() {
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            "\"0123456789ABCDEF0123456789ABCDEF\"".md5HexOrNull(),
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            "W/\"0123456789abcdef0123456789abcdef\"".md5HexOrNull(),
        )
        assertEquals(
            "0123456789abcdef0123456789abcdef",
            " 0123456789abcdef0123456789abcdef ".md5HexOrNull(),
        )
        assertNull("\"0123456789abcdef0123456789abcdef-2\"".md5HexOrNull())
        assertNull("\"0123456789abcdef0123456789abcdeg\"".md5HexOrNull())
        assertNull("\"\"".md5HexOrNull())
    }

    private companion object {
        val QueueIfOffline = MutationStrategy.QueueIfOffline
        const val BUCKET = "photos"
        const val PATH = "user-a/avatar.jpg"
        val BYTES = "a photo's bytes".encodeToByteArray()
        val SAME_SIZE = "other photo byt".encodeToByteArray().also { check(it.size == BYTES.size) }
        val NEWER = "the photo another device stored".encodeToByteArray()
    }
}

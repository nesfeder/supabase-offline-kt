// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
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
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.http.ContentType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath

/**
 * An upload made under `QueueIfOffline` is a write of the one queue: kept on the device with its
 * bytes when it cannot be sent, sent by the replay in the order it was made among the row writes
 * around it, kept across a restart, counted stored after a lost answer only when Storage holds its
 * bytes, refused onto the rejected list with its bytes kept, cleared with local data, and never
 * sent under another user's session. While it waits, `cachedFile` answers its bytes on the device.
 */
class QueuedUploadTest {

    private val server = UploadAndRowServer()
    private val fileSystem = InMemoryFileSystem(Clock.System)
    private val dataDirectory: Path = "/data".toPath()
    private val queryCacheDao = FakeQueryCacheDao()
    private val cachedFileDao = InMemoryCachedFileDao()
    private val mutationDao = FakeMutationDao()
    private val logs = RecordingLogSink()
    private val opened = mutableListOf<OfflineClient>()

    @AfterTest fun closeClients() = runTest { opened.forEach { client -> client.close() } }

    /** A client over this test's local data, which a second client opened later starts from. */
    private fun openClient(supabase: SupabaseClient = server.supabaseClient()): OfflineClient =
        newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                cachedFileDao = cachedFileDao,
                mutationDao = mutationDao,
                baseDirectoryProvider = { dataDirectory },
                fileSystem = fileSystem,
                syncFile = {},
                config = Offline.Config().apply { autoSync = false },
                engineLogger = logs.asEngineLogger(EngineLogLevel.DEBUG),
            )
            .also { client -> opened += client }

    private fun row(title: String) = buildJsonArray { add(buildJsonObject { put("title", title) }) }

    private fun queuedFiles(): List<String> {
        val directory = dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME
        if (!fileSystem.exists(directory)) return emptyList()
        return fileSystem.list(directory).map { file -> file.name }
    }

    private fun readFile(path: String): ByteArray =
        fileSystem.read(path.toPath()) { readByteArray() }

    @Test
    fun an_upload_queued_offline_is_sent_after_the_row_before_it_and_before_the_row_after_it() =
        runTest {
            // Given — offline, a task row, then its photo, then the photo's row
            val client = openClient()
            server.online = false
            val first = client.from(TASKS).insert(row("task"))
            val upload = client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
            val last = client.from(PHOTOS).insert(row("photo"))

            // When — the network is back and the queue is sent
            server.online = true
            val report = client.syncPendingMutations()

            // Then — each was queued, and they were sent in the order they were made
            assertIs<MutationOutcome.Queued>(first)
            assertIs<UploadOutcome.Queued>(upload)
            assertIs<MutationOutcome.Queued>(last)
            assertEquals(
                listOf("rest/$TASKS", "upload/$BUCKET/$PATH", "rest/$PHOTOS"),
                server.writes(),
            )
            // A pass the library ran by itself may have sent some first: this call waited it out
            assertEquals(0, report.remainingCount)
            assertEquals(SyncStopReason.Completed, report.stopReason)
            assertContentEquals(BYTES, server.objects.value["$BUCKET/$PATH"])
            assertEquals(emptyList(), client.storage.pendingUploads().first())
            assertEquals(emptyList(), queuedFiles())
        }

    @Test
    fun a_queued_upload_counts_with_the_writes_and_is_listed_apart_from_them() = runTest {
        // Given
        val client = openClient()
        server.online = false

        // When
        client.from(TASKS).insert(row("task"))
        val upload =
            assertIs<UploadOutcome.Queued>(
                client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) {
                    contentType = ContentType.Image.JPEG
                    upsert = true
                }
            )

        // Then
        val pending = client.storage.pendingUploads().first()
        assertEquals(listOf(upload.pendingUpload), pending)
        assertEquals(BUCKET, pending.single().bucketId)
        assertEquals(PATH, pending.single().path)
        assertEquals(BYTES.size.toLong(), pending.single().sizeBytes)
        assertEquals("image/jpeg", pending.single().contentType)
        assertTrue(pending.single().upsert)
        assertTrue(upload.isQueued)
        assertNull(upload.responseOrNull())
        assertEquals(listOf(TASKS), client.pendingMutations().first().map { it.table })
        assertEquals(2, client.pendingMutationCount())
        assertEquals(1, client.pendingMutationCount(TASKS))
        assertEquals(1, queuedFiles().size)
    }

    @Test
    fun a_queued_upload_keeps_its_options_when_it_is_sent() = runTest {
        // Given — queued offline with every option the queue keeps
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) {
            contentType = ContentType.Image.PNG
            upsert = true
            userMetadata { put("caption", JsonPrimitive("a cat")) }
        }

        // When
        server.online = true
        client.syncPendingMutations()

        // Then
        val sent = server.uploads().last { it.method == "POST" }
        assertEquals("true", sent.headers["x-upsert"])
        assertEquals("image/png", sent.headers["Content-Type"])
        assertNotNull(sent.headers["x-metadata"])
    }

    @Test
    fun a_queued_upload_survives_a_restart() = runTest {
        // Given — an upload queued offline, and the app closed
        val before = openClient()
        server.online = false
        val queued =
            assertIs<UploadOutcome.Queued>(
                before.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
            )
        before.close()

        // When — the app opens again, online
        server.online = true
        val after = openClient()
        val waiting = after.storage.pendingUploads().first()
        val report = after.syncPendingMutations()

        // Then — it was still queued, under its id, and is sent with its bytes, by this call or
        // by the pass the library starts for what an earlier run left
        assertEquals(listOf(queued.pendingUpload.id), waiting.map { it.id })
        assertEquals(0, report.remainingCount)
        assertContentEquals(BYTES, server.objects.value["$BUCKET/$PATH"])
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun an_upload_sent_at_once_is_applied_and_its_bytes_become_the_cached_copy() = runTest {
        // Given
        val client = openClient()

        // When — online, nothing queued ahead of it
        val outcome = client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)

        // Then
        val applied = assertIs<UploadOutcome.Applied>(outcome)
        assertEquals("$BUCKET/$PATH", applied.response.key)
        assertEquals(applied.response, outcome.responseOrNull())
        assertEquals(0, client.pendingMutationCount())
        assertEquals(emptyList(), queuedFiles())
        val cached = assertNotNull(client.storage.from(BUCKET).cachedFile(PATH))
        assertContentEquals(BYTES, readFile(cached.path))
    }

    @Test
    fun an_upload_whose_answer_was_lost_counts_as_stored_once_storage_holds_its_bytes() = runTest {
        // Given — sent at once, stored by Storage, and its answer lost on the way back
        val client = openClient()
        server.uploadAnswer = { UploadAnswer.LoseAnswer }
        val outcome = client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        server.uploadAnswer = null

        // When — the queue is sent, and Storage refuses the second send as a duplicate
        val report = client.syncPendingMutations()

        // Then — it stayed queued, then counted as already stored: the object is its bytes
        assertIs<UploadOutcome.Queued>(outcome)
        assertEquals(2, server.uploads().count { it.method == "POST" })
        assertEquals(1, server.requests.count { it.target == "info/$BUCKET/$PATH" })
        assertEquals(1, report.syncedCount)
        assertEquals(1, report.alreadyOnServerCount)
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), client.storage.pendingUploads().first())
        assertEquals(emptyList(), queuedFiles())
        // Only dropped from the cache: downloaded when next asked for, never written from here
        assertNull(client.storage.from(BUCKET).cachedFile(PATH))
    }

    @Test
    fun an_upload_that_may_overwrite_is_sent_again_after_a_lost_answer() = runTest {
        // Given — an upsert whose answer was lost
        val client = openClient()
        server.uploadAnswer = { UploadAnswer.LoseAnswer }
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) { upsert = true }
        server.uploadAnswer = null

        // When
        val report = client.syncPendingMutations()

        // Then — no question asked: sending it again overwrites with the same bytes
        assertEquals(2, server.uploads().count { it.method == "POST" })
        assertEquals(0, server.requests.count { it.target.startsWith("info/") })
        assertEquals(1, report.syncedCount)
        assertEquals(0, report.alreadyOnServerCount)
    }

    @Test
    fun a_refused_upload_lands_on_the_rejected_list_with_its_file_kept_for_a_retry() = runTest {
        // Given — queued offline, then refused by a Storage policy, as hosted Storage reports it
        val client = openClient()
        server.online = false
        val queued =
            assertIs<UploadOutcome.Queued>(
                client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
            )
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }

        // When
        val report = client.syncPendingMutations()

        // Then — it is on the rejected list, apart from the row writes, with its bytes on the
        // device
        val refused = report.rejectedUploads.single()
        assertEquals(queued.pendingUpload.id, refused.id)
        assertEquals(emptyList(), report.rejected)
        assertEquals(listOf(refused), client.storage.rejectedUploads().first())
        assertEquals(listOf(refused), client.storage.rejectedUploadList())
        assertEquals(emptyList(), client.rejectedMutationList())
        assertEquals(400, refused.serverError?.status)
        assertEquals("refused by the test", refused.message)
        assertTrue(refused.isRetryable)
        assertEquals(1, queuedFiles().size)
        waitUntil { client.syncStatus.value.rejectedMutationCount == 1 }

        // When — the cause is fixed and it is retried
        server.uploadAnswer = null
        client.retryRejectedMutation(refused.id)
        val retried = client.syncPendingMutations()

        // Then — it was sent from the bytes the device kept, by this call or by the pass the
        // retry asked for
        assertEquals(0, retried.remainingCount)
        assertContentEquals(BYTES, server.objects.value["$BUCKET/$PATH"])
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun a_dismissed_refused_upload_takes_its_file_with_it() = runTest {
        // Given — a refused upload on the rejected list
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(413, 413, "Payload too large") }
        val refused = client.syncPendingMutations().rejectedUploads.single()

        // When
        client.dismissRejectedMutation(refused.id)

        // Then
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun a_refused_upload_whose_file_is_gone_cannot_be_retried() = runTest {
        // Given — a refused upload whose file something else deleted
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 400, "InvalidRequest") }
        val refused = client.syncPendingMutations().rejectedUploads.single()
        queuedFiles().forEach { name ->
            fileSystem.delete(dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME / name)
        }

        // Then
        assertFalse(client.storage.rejectedUploadList().single().isRetryable)
        assertNull(client.storage.rejectedUploadList().single().localPath)
        assertFailsWith<IllegalArgumentException> { client.retryRejectedMutation(refused.id) }
    }

    @Test
    fun an_upload_storage_answers_with_a_failure_that_may_pass_stays_queued() = runTest {
        // Given — queued offline
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        client.from(PHOTOS).insert(row("photo"))
        server.online = true

        for ((status, error) in listOf(503 to "Unavailable", 401 to "Unauthorized", 500 to "x")) {
            // When — Storage fails it in a way another try may not
            server.uploadAnswer = { UploadAnswer.Refuse(status, status, error) }
            val report = client.syncPendingMutations()

            // Then — the pass stops there, the row behind it still queued
            assertEquals(0, report.syncedCount, "after a $status")
            assertEquals(2, report.remainingCount, "after a $status")
            assertEquals(emptyList(), report.rejectedUploads, "after a $status")
            assertFalse(server.writes().contains("rest/$PHOTOS"), "after a $status")
        }

        // When — a token Storage refused as expired, inside a 400
        server.uploadAnswer = { UploadAnswer.Refuse(400, 400, "InvalidJWT") }
        val expired = client.syncPendingMutations()

        // Then
        assertEquals(2, expired.remainingCount)
        assertIs<SyncStopReason.ServerFailure>(expired.stopReason)
    }

    @Test
    fun an_upload_refused_when_sent_at_once_is_thrown_and_leaves_the_queue() = runTest {
        // Given
        val client = openClient()
        server.uploadAnswer = { UploadAnswer.Refuse(400, 409, "Duplicate") }

        // When
        val failure = assertFails {
            client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        }

        // Then
        assertFalse(failure is MutationNotQueueableException)
        assertEquals(0, client.pendingMutationCount())
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun clearLocalData_removes_queued_and_refused_uploads_with_their_files() = runTest {
        // Given — one upload refused, one waiting
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }
        client.syncPendingMutations()
        server.online = false
        client.storage.from(BUCKET).upload(OTHER_PATH, BYTES, QueueIfOffline)
        assertEquals(2, queuedFiles().size)

        // When
        client.clearLocalData()

        // Then
        assertEquals(emptyList(), client.storage.pendingUploads().first())
        assertEquals(emptyList(), client.storage.rejectedUploadList())
        assertEquals(emptyList(), queuedFiles())
        assertNull(client.storage.from(BUCKET).cachedFile(OTHER_PATH))
    }

    @Test
    fun cachedFile_answers_the_queued_copy_then_the_uploaded_one_then_the_downloaded_one() =
        runTest {
            // Given — an upload queued offline
            val client = openClient()
            val bucket = client.storage.from(BUCKET)
            server.online = false
            bucket.upload(PATH, BYTES, QueueIfOffline)

            // Then — its bytes on the device answer at once, as ensureCachedFile does, offline
            val queued = assertNotNull(bucket.cachedFile(PATH))
            assertTrue(QUEUED_UPLOADS_DIRECTORY_NAME in queued.path, queued.path)
            assertContentEquals(BYTES, readFile(queued.path))
            assertEquals(BYTES.size.toLong(), queued.sizeBytes)
            assertEquals(queued, bucket.ensureCachedFile(PATH))
            assertEquals(0, server.requests.count { it.target.startsWith("download/") })

            // When — it is sent
            server.online = true
            client.syncPendingMutations()

            // Then — the file cache holds the bytes Storage now has
            val uploaded = assertNotNull(bucket.cachedFile(PATH))
            assertFalse(QUEUED_UPLOADS_DIRECTORY_NAME in uploaded.path, uploaded.path)
            assertContentEquals(BYTES, readFile(uploaded.path))

            // When — another device replaces the object, and this one downloads it
            server.objects.value = server.objects.value + ("$BUCKET/$PATH" to NEWER)
            bucket.downloadAuthenticated(PATH)

            // Then
            val downloaded = assertNotNull(bucket.cachedFile(PATH))
            assertContentEquals(NEWER, readFile(downloaded.path))
        }

    @Test
    fun a_refused_uploads_kept_file_is_named_by_its_entry_never_answered_by_cachedFile() = runTest {
        // Given — an upload queued offline, then refused
        val client = openClient()
        val bucket = client.storage.from(BUCKET)
        server.online = false
        bucket.upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }
        client.syncPendingMutations()
        val refused = client.storage.rejectedUploadList().single()

        // Then — the app can show what was refused from its entry; the cache does not answer
        // it, as Storage may hold other bytes at the path
        val kept = assertNotNull(refused.localPath)
        assertTrue(QUEUED_UPLOADS_DIRECTORY_NAME in kept, kept)
        assertContentEquals(BYTES, readFile(kept))
        assertNull(bucket.cachedFile(PATH))

        // When — dismissed
        client.dismissRejectedMutation(refused.id)

        // Then — its file goes with it
        assertFalse(fileSystem.exists(kept.toPath()))
    }

    @Test
    fun a_refused_upload_does_not_shadow_the_servers_object_once_it_is_downloaded() = runTest {
        // Given — another device already stored the object; this device's upload of the same path
        // (upsert = false) is queued offline behind a row write, so it never went out, and is
        // refused as a duplicate on replay
        val client = openClient()
        val bucket = client.storage.from(BUCKET)
        server.objects.value = mapOf("$BUCKET/$PATH" to NEWER)
        server.online = false
        client.from(TASKS).insert(row("t"))
        bucket.upload(PATH, BYTES, QueueIfOffline)
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(400, 409, "Duplicate") }
        client.syncPendingMutations()
        assertEquals(1, client.storage.rejectedUploadList().size)

        // When — the app downloads the object Storage holds
        assertContentEquals(NEWER, bucket.downloadAuthenticated(PATH))

        // Then — the cache answers Storage's object, not the refused bytes
        val cached = assertNotNull(bucket.cachedFile(PATH))
        assertContentEquals(NEWER, readFile(cached.path), "cachedFile answers the refused bytes")
        assertContentEquals(NEWER, readFile(bucket.ensureCachedFile(PATH).path))
    }

    @Test
    fun the_newest_queued_upload_of_a_path_is_the_one_cachedFile_answers() = runTest {
        // Given — the same path uploaded twice offline
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        client.storage.from(BUCKET).upload(PATH, NEWER, QueueIfOffline) { upsert = true }

        // Then
        val cached = assertNotNull(client.storage.from(BUCKET).cachedFile(PATH))
        assertContentEquals(NEWER, readFile(cached.path))
        assertNull(client.storage.from("other").cachedFile(PATH))
    }

    @Test
    fun a_discarded_upload_is_never_sent_and_takes_its_file_with_it() = runTest {
        // Given
        val client = openClient()
        server.online = false
        val queued =
            assertIs<UploadOutcome.Queued>(
                client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
            )

        // When
        client.discardPendingMutation(queued.pendingUpload.id)
        server.online = true
        client.syncPendingMutations()

        // Then
        assertEquals(emptyList(), server.uploads())
        assertEquals(emptyList(), queuedFiles())
        assertNull(client.storage.from(BUCKET).cachedFile(PATH))
    }

    @Test
    fun another_users_queued_upload_is_not_sent_under_the_current_session() = runTest {
        // Given — user A queues an upload offline, then B signs in on the device
        val supabase = server.supabaseClientWithAuth()
        supabase.signInForTest("user-a")
        val client = openClient(supabase)
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        supabase.signInForTest("user-b")
        server.online = true

        // When — B's session sends its queue
        val asB = client.syncPendingMutations()

        // Then — nothing of A's went out, and B is not shown it
        assertEquals(0, asB.syncedCount)
        assertEquals(emptyList(), server.uploads())
        assertEquals(emptyList(), client.storage.pendingUploads().first())
        assertNull(client.storage.from(BUCKET).cachedFile(PATH))

        // When — A signs in again
        supabase.signInForTest("user-a")
        val asA = client.syncPendingMutations()

        // Then — it goes out, with A's token
        assertEquals(1, asA.syncedCount)
        assertEquals("Bearer ${testJwt("user-a")}", server.uploads().single().authorization)
    }

    @Test
    fun an_upload_the_queue_cannot_keep_is_sent_at_once_and_not_queued() = runTest {
        // Given — offline
        val client = openClient()
        server.online = false

        // When — a header set on the bucket, an httpOverride in the options
        val withHeader =
            assertFailsWith<MutationNotQueueableException> {
                client.storage
                    .from(BUCKET)
                    .setHeader("x-custom", "1")
                    .upload(PATH, BYTES, QueueIfOffline)
            }
        val withOverride =
            assertFailsWith<MutationNotQueueableException> {
                client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline) {
                    httpOverride { headers.append("x-custom", "1") }
                }
            }

        // Then — nothing was kept
        assertTrue(withHeader.cause!!.isNetworkOrConnectivityFailure())
        assertTrue(withOverride.cause!!.isNetworkOrConnectivityFailure())
        assertEquals(0, client.pendingMutationCount())
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun an_online_only_upload_is_never_queued() = runTest {
        // Given
        val client = openClient()

        // When — online, then offline
        val applied = client.storage.from(BUCKET).upload(PATH, BYTES, MutationStrategy.OnlineOnly)
        server.online = false
        val failure = assertFails {
            client.storage.from(BUCKET).upload(OTHER_PATH, BYTES, MutationStrategy.OnlineOnly)
        }

        // Then
        assertIs<UploadOutcome.Applied>(applied)
        assertTrue(failure.isNetworkOrConnectivityFailure())
        assertEquals(0, client.pendingMutationCount())
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun what_is_logged_at_info_and_above_names_the_bucket_never_the_object_path() = runTest {
        // Given — an upload queued offline, held by a failure that may pass, then refused
        val client = openClient()
        server.online = false
        client.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        client.from(PHOTOS).insert(row("photo"))
        server.online = true
        server.uploadAnswer = { UploadAnswer.Refuse(503, 503, "Unavailable") }
        client.syncPendingMutations()
        server.uploadAnswer = { UploadAnswer.Refuse(400, 403, "Unauthorized") }
        client.syncPendingMutations()

        // Then
        val shown = logs.entries.filter { it.level >= EngineLogLevel.INFO }
        assertTrue(shown.isNotEmpty())
        assertEquals(emptyList(), shown.filter { PATH in it.message || "photo-1" in it.message })
    }

    @Test
    fun empty_data_is_refused_before_anything_is_kept() = runTest {
        // Given
        val client = openClient()

        // When / Then
        assertFailsWith<IllegalArgumentException> {
            client.storage.from(BUCKET).upload(PATH, ByteArray(0), QueueIfOffline)
        }
        assertEquals(emptyList(), server.requests)
        assertEquals(emptyList(), queuedFiles())
    }

    @Test
    fun a_file_no_upload_names_is_deleted_when_a_client_opens() = runTest {
        // Given — one upload queued, and a file a crash left with no row
        val before = openClient()
        server.online = false
        before.storage.from(BUCKET).upload(PATH, BYTES, QueueIfOffline)
        before.close()
        val kept = queuedFiles().single()
        fileSystem.write(dataDirectory / QUEUED_UPLOADS_DIRECTORY_NAME / ORPHAN) { write(BYTES) }

        // When
        val after = openClient()
        after.storage.pendingUploads().first()
        waitUntil { ORPHAN !in queuedFiles() }

        // Then
        assertEquals(listOf(kept), queuedFiles())
        assertNotEquals(kept, ORPHAN)
    }

    /** Waits, for at most ten seconds of real time, until [done]; fails when it never is. */
    private suspend fun waitUntil(done: () -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { while (!done()) delay(10.milliseconds) }
        }
    }

    private companion object {
        val QueueIfOffline = MutationStrategy.QueueIfOffline
        const val TASKS = "tasks"
        const val PHOTOS = "task_photos"
        const val BUCKET = "photos"
        const val PATH = "user-a/task-1/photo-1.jpg"
        const val OTHER_PATH = "user-a/task-1/photo-2.jpg"
        const val ORPHAN = "0123456789abcdef0123456789abcdef"
        val BYTES = "a photo's bytes".encodeToByteArray()
        val NEWER = "the photo, edited elsewhere".encodeToByteArray()
    }
}

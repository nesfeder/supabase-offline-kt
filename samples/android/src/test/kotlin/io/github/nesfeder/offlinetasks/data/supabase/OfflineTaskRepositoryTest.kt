// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.testing.FakeSteppingClock
import io.github.nesfeder.offlinetasks.testing.FakeSupabaseServer
import io.github.nesfeder.offlinetasks.testing.FakeUserSession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The real repository over the library's in-memory client and a fake server. The library works on
 * its own threads, so every test waits for the outcome it expects, never for a fixed time.
 */
class OfflineTaskRepositoryTest {

    private val server = FakeSupabaseServer()
    private val supabase: SupabaseClient = server.supabaseClient()
    private val session = FakeUserSession()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val repository =
        OfflineTaskRepository(supabase, session, scope, clock = FakeSteppingClock())

    @AfterTest
    fun closeClient() {
        runBlocking { supabase.close() }
        scope.cancel()
    }

    @Test
    fun an_online_task_reaches_the_server_with_the_apps_id_owner_and_time() = runTest {
        // When
        val outcome = repository.addTask("Water the tomatoes", notes = null)

        // Then — sent at once: the server has the row as the app made it
        assertEquals(TaskMutationOutcome.Applied, outcome)
        val row = server.rows(Tables.TASKS).single()
        assertEquals(FakeUserSession.FIRST_USER_ID, row["user_id"]?.jsonPrimitive?.content)
        assertEquals("2026-10-01T09:00:00Z", row["created_at"]?.jsonPrimitive?.content)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        assertEquals("Water the tomatoes", task.title)
        assertFalse(task.hasPendingMutations)
    }

    @Test
    fun a_task_added_offline_is_listed_at_once_marked_pending_and_sent_on_reconnect() = runTest {
        // Given — the list loaded, then the network switched off
        repository.addTask("Older", notes = null)
        repository.tasks().awaitValue { it.size == 1 }
        repository.setNetworkEnabled(false)

        // When
        val outcome = repository.addTask("Newer, made offline", notes = null)

        // Then — queued, shown first and marked; nothing reached the server
        assertIs<TaskMutationOutcome.Queued>(outcome)
        val shown = repository.tasks().awaitValue { it.size == 2 }
        assertEquals("Newer, made offline", shown.first().title)
        assertTrue(shown.first().hasPendingMutations)
        assertEquals(1, server.rows(Tables.TASKS).size)

        // When — back online, and the queue replayed
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()

        // Then — both on the server, and the list settles on both with nothing pending
        assertEquals(2, server.rows(Tables.TASKS).size)
        repository.tasks().awaitValue { tasks ->
            tasks.size == 2 && tasks.none { it.hasPendingMutations }
        }
    }

    @Test
    fun offline_a_task_never_opened_before_is_read_from_the_cached_lists() = runTest {
        // Given — the lists loaded online, then the network switched off
        repository.addTask("Opened offline", notes = "first time")
        val listed = repository.tasks().awaitValue { it.size == 1 }.single()
        repository.allPhotos().awaitValue { it.isEmpty() }
        repository.setNetworkEnabled(false)
        val sentBefore = server.requests.size

        // When — a task and its photos read for the first time
        val task = repository.task(listed.id).awaitValue { it.isNotEmpty() }.single()
        val photos = repository.photos(listed.id).awaitValue { true }

        // Then — answered from the device, with nothing sent
        assertEquals("first time", task.notes)
        assertTrue(photos.isEmpty())
        assertEquals(sentBefore, server.requests.size)
    }

    @Test
    fun clearing_local_data_online_reads_the_lists_again_for_a_screen_showing_them() = runTest {
        // Given — a task shown by a list that stays open, and a second one only the server has
        repository.addTask("Made here", notes = null)
        val shown = MutableStateFlow<List<Task>?>(null)
        val screen = scope.launch { repository.tasks().collect { shown.value = it.value } }
        withTimeout(WAIT) { shown.first { it?.size == 1 } }
        server.insertRow(Tables.TASKS, serverOnlyTask(title = "Made elsewhere"))
        val sentBefore = server.requests.size

        // When
        repository.clearLocalData()

        // Then — both lists asked of the server again, and the open list shows the server's copy
        val sentAfter = server.requests.drop(sentBefore)
        assertTrue("GET /rest/v1/tasks" in sentAfter)
        assertTrue("GET /rest/v1/task_photos" in sentAfter)
        val titles = withTimeout(WAIT) { shown.first { it?.size == 2 } }.orEmpty().map { it.title }
        assertEquals(setOf("Made here", "Made elsewhere"), titles.toSet())
        screen.cancel()
    }

    @Test
    fun a_write_refused_at_once_is_thrown_with_the_servers_error_and_not_kept() = runTest {
        // When
        val refused =
            assertFailsWith<TaskWriteRefusedException> { repository.addTask("   ", notes = null) }

        // Then
        assertEquals(400, refused.serverError.status)
        assertEquals("23514", refused.serverError.code)
        assertTrue("tasks_title_length" in refused.serverError.message)
        assertEquals(TaskMutationOperation.Insert, refused.operation)
        assertTrue(repository.rejectedMutations().first().isEmpty())
        assertTrue(server.rows(Tables.TASKS).isEmpty())
    }

    @Test
    fun a_write_refused_on_replay_is_kept_with_its_title_and_the_servers_error() = runTest {
        // Given — a blank title queued offline
        repository.setNetworkEnabled(false)
        repository.addTask("   ", notes = null)

        // When — back online: the library replays the queue by itself
        repository.setNetworkEnabled(true)

        // Then
        val rejection =
            withTimeout(WAIT) { repository.rejectedMutations().first { it.isNotEmpty() } }.single()
        assertEquals("   ", rejection.taskTitle)
        assertEquals(400, rejection.serverError?.status)
        assertEquals("23514", rejection.serverError?.code)
        assertEquals(TaskMutationOperation.Insert, rejection.operation)
        assertEquals(0, repository.syncPendingMutations().remainingCount)
        assertTrue(server.rows(Tables.TASKS).isEmpty())
    }

    @Test
    fun clearing_the_notes_sends_an_explicit_null() = runTest {
        // Given
        repository.addTask("Book the bike service", notes = "Brakes squeak")
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()

        // When
        repository.updateTask(task.id, title = "Book the bike service", notes = null)

        // Then
        val row = server.rows(Tables.TASKS).single()
        assertEquals(JsonNull, row["notes"])
    }

    @Test
    fun a_photo_uploads_with_progress_then_its_row_is_written_and_it_downloads() = runTest {
        // Given
        repository.addTask("With a photo", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        val jpeg = ByteArray(64 * 1024) { it.toByte() }

        // When
        val progress = repository.attachPhoto(task.id, EncodedPhoto(jpeg, 4, 3)).toList()

        // Then — progress, then the photo, at the one path the schema allows
        val attached = assertIs<PhotoUpload.Attached>(progress.last()).photo
        assertTrue(progress.dropLast(1).all { it is PhotoUpload.Uploading })
        assertEquals(
            "${FakeUserSession.FIRST_USER_ID}/${task.id}/${attached.id}.jpg",
            attached.objectPath,
        )
        assertContentEquals(jpeg, server.files.getValue(attached.objectPath))
        assertEquals(
            attached.objectPath,
            server.rows(Tables.TASK_PHOTOS).single()["object_path"]?.jsonPrimitive?.content,
        )
        assertNull(repository.cachedPhotoFile(attached))
        val downloaded = repository.downloadPhotoFile(attached)
        assertEquals(jpeg.size.toLong(), downloaded.sizeBytes)
        assertEquals(downloaded.path, repository.cachedPhotoFile(attached)?.path)
    }

    @Test
    fun a_photo_cannot_be_attached_offline() = runTest {
        // Given
        repository.addTask("Offline", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.setNetworkEnabled(false)

        // When / Then — at once, before any request
        val requestsBefore = server.requests.size
        assertFailsWith<NetworkUnavailableException> {
            repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList()
        }
        assertEquals(requestsBefore, server.requests.size)
    }

    @Test
    fun deleting_a_task_online_deletes_its_files_its_photo_rows_and_the_task() = runTest {
        // Given — a task with a photo, its photos read once
        repository.addTask("Photographed", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList()
        repository.photos(task.id).awaitValue { it.isNotEmpty() }

        // When
        repository.deleteTask(task.id)

        // Then — nothing of it is left on the server or on screen
        assertTrue(server.files.isEmpty())
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
        assertTrue(server.rows(Tables.TASKS).isEmpty())
        assertTrue(repository.tasks().awaitValue { it.isEmpty() }.isEmpty())
    }

    @Test
    fun a_refresh_names_why_it_failed() = runTest {
        // Given — a server that fails
        server.isFailing = true

        // Then
        assertFailsWith<ServerUnreachableException> { repository.refreshTasks() }

        // Given — the network switched off
        server.isFailing = false
        repository.setNetworkEnabled(false)

        // Then
        assertFailsWith<NetworkUnavailableException> { repository.refreshTasks() }
    }

    @Test
    fun the_network_switch_is_current_when_it_returns() = runTest {
        // When
        repository.setNetworkEnabled(false)

        // Then
        assertEquals(AppNetworkStatus.Disabled, repository.syncStatus.value.network)

        // When
        repository.setNetworkEnabled(true)

        // Then
        assertTrue(repository.syncStatus.value.network != AppNetworkStatus.Disabled)
    }

    @Test
    fun starting_over_deletes_the_users_files_and_rows_then_becomes_a_new_user() = runTest {
        // Given — a task with a photo
        repository.addTask("Mine", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList()

        // When
        repository.startOver()

        // Then — the server holds nothing of the first user, and the app is someone new
        assertTrue(server.files.isEmpty())
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
        assertTrue(server.rows(Tables.TASKS).isEmpty())
        assertEquals(1, session.newUserCount)
        assertTrue(repository.tasks().awaitValue { it.isEmpty() }.isEmpty())
    }

    @Test
    fun before_a_session_nothing_is_shown_and_nothing_is_asked() = runTest {
        // Given
        session.userId.value = null

        // When
        val snapshot = repository.tasks().first()

        // Then
        assertNull(snapshot.value)
        assertTrue(server.requests.none { it.contains("/rest/v1/") })
    }

    /** The first non-null rows of this read that [matches], within a bounded wait. */
    /** A task row as the server stores it, for one the app did not make. */
    private fun serverOnlyTask(title: String): JsonObject = buildJsonObject {
        put("id", "00000000-0000-4000-8000-0000000000aa")
        put("user_id", FakeUserSession.FIRST_USER_ID)
        put("title", title)
        put("notes", JsonNull)
        put("done", false)
        put("created_at", "2026-10-01T08:00:00Z")
        put("updated_at", "2026-10-01T08:00:00Z")
    }

    private suspend fun <T> Flow<RowsSnapshot<List<T>>>.awaitValue(
        matches: (List<T>) -> Boolean
    ): List<T> = withTimeout(WAIT) { first { it.value?.let(matches) == true } }.value.orEmpty()

    /** Runs [body] on a real thread with a bounded wait, since the library works on its own. */
    private fun runTest(body: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(WAIT * 3) { body() }
    }

    private companion object {
        val WAIT = 10.seconds
    }
}

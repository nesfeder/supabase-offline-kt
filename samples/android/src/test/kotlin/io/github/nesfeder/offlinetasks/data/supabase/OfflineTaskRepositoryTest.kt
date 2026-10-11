// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.serializer.KotlinXSerializer
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.InvalidRequestException
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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

        // Then — sent at once: the server has the row as the app made it, and answered with it
        val saved = assertIs<NewTaskOutcome.Saved>(outcome).task
        assertEquals("Water the tomatoes", saved.title)
        val row = server.rows(Tables.TASKS).single()
        assertEquals(FakeUserSession.FIRST_USER_ID, row["user_id"]?.jsonPrimitive?.content)
        assertEquals("2026-10-01T09:00:00Z", row["created_at"]?.jsonPrimitive?.content)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        assertEquals("Water the tomatoes", task.title)
        assertFalse(task.hasPendingMutations)
    }

    // --8<-- [start:offline-test]
    @Test
    fun a_task_added_offline_is_listed_at_once_marked_pending_and_sent_on_reconnect() = runTest {
        // Given — the list loaded, then the network switched off
        repository.addTask("Older", notes = null)
        repository.tasks().awaitValue { it.size == 1 }
        repository.setNetworkEnabled(false)

        // When
        val outcome = repository.addTask("Newer, made offline", notes = null)

        // Then — queued, shown first and marked; nothing reached the server
        assertEquals(NewTaskOutcome.SavedOnDevice, outcome)
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
    // --8<-- [end:offline-test]

    @Test
    fun a_task_that_needs_a_connection_is_refused_offline_and_never_queued() = runTest {
        // Given
        repository.setNetworkEnabled(false)

        // When / Then — online only: nothing waits in the queue
        assertFailsWith<NetworkSwitchedOffException> {
            repository.addTask("Now or never", notes = null, needsConnection = true)
        }
        assertEquals(0, repository.pendingMutationCountNow())
    }

    @Test
    fun five_sample_tasks_reach_the_server_in_one_write() = runTest {
        // When
        val outcome = repository.addSampleTasks()

        // Then
        assertEquals(TaskMutationOutcome.Applied, outcome)
        assertEquals(5, server.rows(Tables.TASKS).size)
        assertEquals(1, server.requests.count { it == "POST /rest/v1/tasks" })
    }

    @Test
    fun the_starter_list_imported_twice_changes_nothing() = runTest {
        // Given
        repository.importStarterList()
        val first = server.rows(Tables.TASKS).map { it["id"] }.toSet()

        // When
        repository.importStarterList()

        // Then — the same rows, once each
        assertEquals(first, server.rows(Tables.TASKS).map { it["id"] }.toSet())
        assertEquals(first.size, server.rows(Tables.TASKS).size)
    }

    @Test
    fun a_grouped_delete_offline_is_refused_and_nothing_is_queued() = runTest {
        // Given
        repository.setNetworkEnabled(false)

        // When / Then — the queue cannot hold a group, and the library says so
        val refused = assertFailsWith<WriteNeedsConnectionException> {
            repository.deleteThisWeeksDoneTasks()
        }
        assertTrue(refused.libraryMessage.isNotBlank())
        assertEquals(0, repository.pendingMutationCountNow())
    }

    @Test
    fun two_filters_on_one_column_are_refused_before_anything_is_sent() = runTest {
        // Given
        val sentBefore = server.requests.size

        // When / Then
        assertFailsWith<InvalidRequestException> { repository.readWithTwoFiltersOnOneColumn() }
        assertEquals(sentBefore, server.requests.size)
    }

    @Test
    fun mark_all_done_runs_the_database_function_on_the_server() = runTest {
        // Given
        repository.addSampleTasks()

        // When
        val changed = repository.markAllDone()

        // Then
        assertEquals(5, changed)
        assertTrue(server.rows(Tables.TASKS).all { it["done"]?.jsonPrimitive?.content == "true" })
    }

    @Test
    fun a_discarded_change_is_never_sent() = runTest {
        // Given — a task made offline
        repository.setNetworkEnabled(false)
        repository.addTask("Thrown away", notes = null)
        val change = withTimeout(WAIT) { repository.waitingChanges().first { it.isNotEmpty() } }.single()
        assertEquals("Thrown away", change.taskTitle)

        // When
        repository.discardWaitingChange(change.id)
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()

        // Then
        assertTrue(server.rows(Tables.TASKS).isEmpty())
    }

    @Test
    fun pull_to_refresh_offline_answers_from_the_device_and_says_so() = runTest {
        // Given — the list fetched once
        repository.addTask("Fetched", notes = null)
        assertFalse(repository.refreshTasks().isFromCache)
        repository.setNetworkEnabled(false)

        // When
        val provenance = repository.refreshTasks()

        // Then
        assertTrue(provenance.isFromCache)
        assertNotNull(provenance.fetchedAt)
    }

    @Test
    fun another_devices_change_is_applied_to_the_open_list() = runTest {
        // Given — the list read once, then the network switched off, so no refresh can run
        repository.tasks().awaitValue { it.isEmpty() }
        repository.setNetworkEnabled(false)
        val updates = LiveUpdates(supabase)
        val row = serverOnlyTask("From elsewhere")

        // When — Realtime reports a row inserted elsewhere
        updates.applyTaskChange(PostgresAction.Insert(row, emptyList(), now(), KotlinXSerializer()))

        // Then — the device's copy has it, with nothing asked of the server
        assertEquals("From elsewhere", repository.tasks().awaitValue { it.size == 1 }.single().title)

        // When — and then deleted
        updates.applyTaskChange(PostgresAction.Delete(row, emptyList(), now(), KotlinXSerializer()))

        // Then
        repository.tasks().awaitValue { it.isEmpty() }
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
    fun clearing_local_data_online_the_open_list_reads_the_servers_rows_again() = runTest {
        // Given — a task shown by a list that stays open, and a second one only the server has
        repository.addTask("Made here", notes = null)
        val shown = MutableStateFlow<List<Task>?>(null)
        val screen = scope.launch { repository.tasks().collect { shown.value = it.value } }
        withTimeout(WAIT) { shown.first { it?.size == 1 } }
        server.insertRow(Tables.TASKS, serverOnlyTask(title = "Made elsewhere"))
        val sentBefore = server.requests.size

        // When
        repository.clearLocalData()

        // Then — the library asks the server again for the open list, which shows its copy
        val titles = withTimeout(WAIT) { shown.first { it?.size == 2 } }.orEmpty().map { it.title }
        assertEquals(setOf("Made here", "Made elsewhere"), titles.toSet())
        assertTrue("GET /rest/v1/tasks" in server.requests.drop(sentBefore))
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
    fun a_photo_whose_row_is_refused_at_once_leaves_no_file_behind() = runTest {
        // Given — a task the server no longer has, deleted on another device meanwhile
        repository.addTask("Deleted elsewhere", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        server.deleteRows(Tables.TASKS)

        // When — the photo uploads, and its row is refused
        val failure =
            assertFailsWith<TaskWriteRefusedException> {
                repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList()
            }

        // Then — the server's reason reaches the app, and the file went with the row
        assertEquals("23503", failure.serverError.code)
        assertTrue(server.files.isEmpty(), "a file was left with no row: ${server.files.keys}")
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
    }

    @Test
    fun a_photo_whose_row_fails_but_is_not_refused_keeps_its_file() = runTest {
        // Given — a task, and its photo's row held on its way to the server
        repository.addTask("Kept", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        val release = CompletableDeferred<Boolean>()
        server.holdPhotoRowInserts = release
        val attach = scope.async {
            runCatching {
                repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList()
            }
        }
        withTimeout(WAIT) { server.photoRowInsertsHeld.first { it == 1 } }

        // When — local data is cleared meanwhile, and then the connection drops
        repository.clearLocalData()
        release.complete(false)

        // Then — not a refusal, which alone says no row will name the file: the file stays
        assertIs<NetworkUnavailableException>(
            withTimeout(WAIT) { attach.await() }.exceptionOrNull()
        )
        assertEquals(1, server.files.size)
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
    fun starting_over_finds_every_file_past_the_servers_row_limit() = runTest {
        // Given — more photos on the server than one read returns
        server.maxRows = 3
        repository.addTask("Many photos", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repeat(7) { repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).toList() }
        assertEquals(7, server.files.size)

        // When
        repository.startOver()

        // Then — every file deleted, not only the first page's
        assertTrue(server.files.isEmpty())
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
    }

    @Test
    fun a_photo_whose_screen_closes_after_the_upload_still_gets_its_row() = runTest {
        // Given — a task, and an upload whose answer is held once the file is stored
        repository.addTask("Closed too soon", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        val release = CompletableDeferred<Unit>()
        server.holdUploadAnswers = release
        val screen = scope.launch {
            repository.attachPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1)).collect {}
        }
        withTimeout(WAIT) { server.uploadsHeld.first { it == 1 } }

        // When — the screen goes before the app hears of the upload
        screen.cancelAndJoin()
        release.complete(Unit)

        // Then — the file has its row, on the server (a write shows in reads before it is sent)
        repository.allPhotos().awaitValue { photos ->
            photos.size == 1 && photos.none { it.hasPendingMutations }
        }
        assertEquals(1, server.rows(Tables.TASK_PHOTOS).size)
    }

    @Test
    fun clearing_local_data_while_the_server_fails_still_clears_the_device() = runTest {
        // Given — a task saved on the device, and a server that fails
        repository.addTask("Kept on the server", notes = null)
        repository.tasks().awaitValue { it.size == 1 }
        server.isFailing = true

        // When
        repository.clearLocalData()

        // Then — nothing of it is left on the device
        assertNull(repository.cachedTasks())
    }

    @Test
    fun before_a_session_nothing_is_shown_and_nothing_is_asked() = runTest {
        // Given
        session.userId.value = null

        // When — the list read for a while, as a screen would
        val shown = mutableListOf<TaskListSnapshot>()
        withTimeoutOrNull(NO_REQUEST_WAIT) { repository.tasks().collect { shown += it } }

        // Then — one "nothing yet", and no request in all that time
        assertNull(shown.single().value)
        assertEquals(TaskRefreshState.Paused, shown.single().refresh)
        assertTrue(server.requests.none { it.contains("/rest/v1/") })
    }

    /** The time a Realtime change is stamped with; the library does not read it. */
    private fun now() = Clock.System.now()

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

    /** The first non-null rows of this read that [matches], within a bounded wait. */
    private suspend fun <T> Flow<RowsSnapshot<List<T>>>.awaitValue(
        matches: (List<T>) -> Boolean
    ): List<T> = withTimeout(WAIT) { first { it.value?.let(matches) == true } }.value.orEmpty()

    /** Runs [body] on a real thread with a bounded wait, since the library works on its own. */
    private fun runTest(body: suspend CoroutineScope.() -> Unit) = runBlocking {
        withTimeout(WAIT * 3) { body() }
    }

    /** The tasks the device holds, or null when it holds none, without asking the server. */
    private suspend fun OfflineTaskRepository.cachedTasks(): List<Task>? {
        setNetworkEnabled(false)
        return tasks().first().value.also { setNetworkEnabled(true) }
    }

    private companion object {
        val WAIT = 10.seconds

        /** How long a read is watched to show it sends nothing. */
        val NO_REQUEST_WAIT = 1.seconds
    }
}

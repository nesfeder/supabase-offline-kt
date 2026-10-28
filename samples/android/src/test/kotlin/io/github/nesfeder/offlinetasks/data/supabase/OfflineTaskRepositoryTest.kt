// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.supabase

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.ColumnConflict
import io.github.nesfeder.offlinetasks.data.ConflictChoice
import io.github.nesfeder.offlinetasks.data.ConflictedTaskMutation
import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.InvalidRequestException
import io.github.nesfeder.offlinetasks.data.LiveUpdatesStatus
import io.github.nesfeder.offlinetasks.data.NetworkSwitchedOffException
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.NewTaskOutcome
import io.github.nesfeder.offlinetasks.data.PendingPhotoState
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.RowsSnapshot
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskFilter
import io.github.nesfeder.offlinetasks.data.TaskListSnapshot
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskQuery
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.WriteNeedsConnectionException
import io.github.nesfeder.offlinetasks.testing.FakeSteppingClock
import io.github.nesfeder.offlinetasks.testing.FakeSupabaseServer
import io.github.nesfeder.offlinetasks.testing.FakeUserSession
import io.github.nesfeder.offlinetasks.testing.addUploadedPhoto
import io.github.nesfeder.supabase.offline.offline
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
        assertIs<NewTaskOutcome.SavedOnDevice>(outcome)
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
    fun a_task_that_needs_a_connection_is_listed_at_once_when_it_is_saved() = runTest {
        // Given — the list read once
        repository.tasks().awaitValue { it.isEmpty() }

        // When — sent now or never, which the library does not patch into its cache
        repository.addTask("Call the plumber", notes = null, needsConnection = true)

        // Then — the device's copy has the stored row, with nothing fetched again
        assertEquals("Call the plumber", repository.cachedTasks()?.singleOrNull()?.title)
    }

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
        val refused =
            assertFailsWith<WriteNeedsConnectionException> {
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
        val change =
            withTimeout(WAIT) { repository.waitingChanges().first { it.isNotEmpty() } }.single()
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
    fun pull_to_refresh_reads_again_the_query_the_screen_shows() = runTest {
        // Given — the open tasks read once, then one more added on another device
        val open = TaskQuery(TaskFilter.Open)
        server.insertRow(Tables.TASKS, serverOnlyTask("From before"))
        repository.tasks(open).awaitValue { it.size == 1 }
        server.insertRow(Tables.TASKS, serverOnlyTask("From elsewhere", id = OTHER_TASK_ID))

        // When
        repository.refreshTasks(open)

        // Then — the device's copy of that query has both, with nothing asked of the server
        repository.setNetworkEnabled(false)
        assertEquals(2, repository.tasks(open).first().value?.size)
    }

    @Test
    fun a_live_read_that_fails_fails_in_the_apps_terms() = runTest {
        // Given — Prefer fresh data on, and the network switched off before the open tasks were
        // ever read, so the device has nothing to fall back on
        repository.setPreferFreshData(true)
        repository.setNetworkEnabled(false)

        // When / Then — the app's own failure, which has a sentence of its own on screen
        assertFailsWith<NetworkSwitchedOffException> {
            repository.tasks(TaskQuery(TaskFilter.Open)).first()
        }
    }

    @Test
    fun live_updates_follow_the_plugins_channel_and_its_switch() = runTest {
        // Given — the plugin names both tables, so its channel is joined as soon as it may be
        server.realtime.subscribedTables.awaitState {
            Tables.TASKS in it && Tables.TASK_PHOTOS in it
        }
        repository.liveUpdatesStatus.awaitState { it == LiveUpdatesStatus.Connected }

        // When — the switch goes off, then on again
        repository.setLiveUpdates(false)

        // Then — the channel is left, and the status says so
        repository.liveUpdatesStatus.awaitState { it == LiveUpdatesStatus.Off }
        server.realtime.subscribedTables.awaitState { it.isEmpty() }
        assertFalse(repository.settings.value.liveUpdates)
        repository.setLiveUpdates(true)
        repository.liveUpdatesStatus.awaitState { it == LiveUpdatesStatus.Connected }
        server.realtime.subscribedTables.awaitState { Tables.TASKS in it }
    }

    @Test
    fun another_devices_change_is_applied_to_the_open_list() = runTest {
        // Given — the list on screen, read once, and the plugin's channel following the table
        val shown = repository.tasks().map { it.value }.stateIn(scope, SharingStarted.Eagerly, null)
        shown.awaitState { it != null && it.isEmpty() }
        server.realtime.subscribedTables.awaitState { Tables.TASKS in it }
        val sentBefore = server.requests.size

        // When — Realtime reports a row inserted elsewhere
        server.realtime.reportInsert(Tables.TASKS, serverOnlyTask("From elsewhere"))

        // Then — the list on screen has it, with nothing asked of the server
        assertEquals("From elsewhere", shown.awaitState { it?.size == 1 }?.single()?.title)

        // When — and then deleted; a delete carries the key alone
        server.realtime.reportDelete(Tables.TASKS, buildJsonObject { put("id", SERVER_TASK_ID) })

        // Then
        shown.awaitState { it?.isEmpty() == true }
        assertEquals(sentBefore, server.requests.size)
        assertEquals(0, server.realtime.droppedReportCount)
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
    fun a_photo_added_offline_is_listed_for_its_task_at_once_from_the_device() = runTest {
        // Given — a task, its photos read once, then the network switched off
        val task = taskWithPhotosRead("Photographed offline")
        repository.setNetworkEnabled(false)

        // When
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))

        // Then — listed for its task before any upload, marked waiting, its file the library's
        // copy on the device, at the path it will have in the bucket
        val photo = repository.photos(task.id).awaitValue { it.isNotEmpty() }.single()
        assertEquals(PendingPhotoState.Waiting, photo.pendingUpload?.state)
        assertTrue(photo.hasPendingMutations)
        assertEquals(8L, repository.cachedPhotoFile(photo)?.sizeBytes)
        assertEquals(
            "${FakeUserSession.FIRST_USER_ID}/${task.id}/${photo.id}.jpg",
            photo.objectPath,
        )
        assertTrue(server.files.isEmpty())
    }

    @Test
    fun pending_photos_wait_while_the_network_is_off_and_upload_when_it_comes_back() = runTest {
        // Given — a photo added with the network switched off
        val task = taskWithPhotosRead("Later")
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))

        // When — a while offline
        delay(NO_REQUEST_WAIT)

        // Then — never tried: still waiting, and nothing reached Storage
        val waiting = repository.photos(task.id).awaitValue { it.isNotEmpty() }.single()
        assertEquals(PendingPhotoState.Waiting, waiting.pendingUpload?.state)
        assertTrue(server.requests.none { it.startsWith("POST /storage/v1/object/") })

        // When — the network back on
        repository.setNetworkEnabled(true)

        // Then — uploaded with nothing more asked of the app
        repository.photos(task.id).awaitValue { photos -> photos.isUploaded() }
        withTimeout(WAIT) { while (server.rows(Tables.TASK_PHOTOS).isEmpty()) delay(POLL) }
        assertEquals(1, server.files.size)
    }

    @Test
    fun a_photo_is_uploaded_before_its_row_is_written_and_then_kept_in_the_cache() = runTest {
        // Given
        repository.addTask("With a photo", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        val jpeg = ByteArray(64 * 1024) { it.toByte() }

        // When
        val photo = repository.addUploadedPhoto(task.id, EncodedPhoto(jpeg, 4, 3))

        // Then — the file reached Storage before the row reached the table
        val upload =
            server.requests.indexOf("POST /storage/v1/object/task-photos/${photo.objectPath}")
        val row = server.requests.indexOf("POST /rest/v1/task_photos")
        assertTrue(upload in 0 until row, "not the file first, then the row: ${server.requests}")
        assertContentEquals(jpeg, server.files.getValue(photo.objectPath))
        // And the library's cache holds the bytes it sent, with no download
        assertEquals(jpeg.size.toLong(), repository.cachedPhotoFile(photo)?.sizeBytes)
        assertTrue(server.requests.none { it.contains("/object/authenticated/") })
    }

    @Test
    fun a_refused_upload_keeps_the_photo_marked_failed_and_retry_uploads_it() = runTest {
        // Given — a photo added offline, and Storage refusing it once the network is back
        val task = taskWithPhotosRead("Retried")
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        server.isUploadRefused = true

        // When
        repository.setNetworkEnabled(true)

        // Then — marked failed with Storage's words, its file kept for a retry; the row behind it
        // was sent all the same
        val failed = repository.photos(task.id).awaitValue { it.hasFailedUpload() }.single()
        val upload = assertNotNull(failed.pendingUpload)
        assertTrue(upload.retryable)
        assertEquals("new row violates row-level security policy", upload.failure)
        assertEquals(8L, repository.cachedPhotoFile(failed)?.sizeBytes)
        assertTrue(server.files.isEmpty())

        // When — Storage back, and Retry
        server.isUploadRefused = false
        repository.retryPendingPhoto(failed.id)

        // Then
        repository.photos(task.id).awaitValue { photos -> photos.isUploaded() }
        withTimeout(WAIT) { while (failed.objectPath !in server.files) delay(POLL) }
        assertEquals(1, server.rows(Tables.TASK_PHOTOS).size)
    }

    @Test
    fun an_upload_storage_fails_at_once_is_thrown_and_nothing_is_kept() = runTest {
        // Given — a task, and Storage failing
        repository.addTask("Not kept", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        server.isUploadFailing = true

        // When — Storage answers a 500, which the app reads as a server out of reach
        assertFailsWith<ServerUnreachableException> {
            repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        }

        // Then — no photo, no row, nothing waiting
        assertTrue(repository.photos(task.id).awaitValue { true }.isEmpty())
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
        assertEquals(0, repository.pendingMutationCountNow())
    }

    @Test
    fun an_upload_whose_answer_was_lost_is_not_sent_again() = runTest {
        // Given — a photo whose file was stored, and whose answer the connection lost
        repository.addTask("Stored once", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        server.loseUploadAnswers = true
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        assertEquals(1, server.files.size)

        // When — the connection back, and the queue sent
        server.loseUploadAnswers = false
        repository.syncPendingMutations()

        // Then — the file was found in the bucket rather than sent twice, and the row written
        repository.photos(task.id).awaitValue { photos -> photos.isUploaded() }
        assertEquals(1, server.requests.count { it.startsWith("POST /storage/v1/object/") })
        assertEquals(1, server.rows(Tables.TASK_PHOTOS).size)
    }

    @Test
    fun a_photo_removed_while_it_uploads_leaves_no_file_and_no_row() = runTest {
        // Given — a photo added offline, its upload on the wire once the network is back
        val task = taskWithPhotosRead("Changed my mind")
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        val photo = repository.photos(task.id).awaitValue { it.isNotEmpty() }.single()
        val release = CompletableDeferred<Unit>()
        server.holdUploadAnswers = release
        repository.setNetworkEnabled(true)
        withTimeout(WAIT) { server.uploadsHeld.first { it == 1 } }

        // When — removed while the answer is on its way, which then arrives
        val removing = scope.async { repository.removePendingPhoto(photo.id) }
        release.complete(Unit)

        // Then — the file is deleted again, and no row names it
        assertTrue(removing.await())
        withTimeout(WAIT) { while (server.files.isNotEmpty()) delay(POLL) }
        repository.photos(task.id).awaitValue { it.isEmpty() }
        repository.syncPendingMutations()
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
    }

    @Test
    fun a_photo_of_a_task_still_queued_is_written_after_the_task() = runTest {
        // Given — a task made offline, and a photo added to it
        repository.allPhotos().awaitValue { it.isEmpty() }
        repository.setNetworkEnabled(false)
        repository.addTask("Made offline", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))

        // When — back online
        repository.setNetworkEnabled(true)

        // Then — the task, then the file, then the photo's row, which its key requires
        repository.photos(task.id).awaitValue { photos -> photos.isUploaded() }
        withTimeout(WAIT) { while (server.rows(Tables.TASK_PHOTOS).isEmpty()) delay(POLL) }
        val taskInsert = server.requests.indexOf("POST /rest/v1/tasks")
        val upload = server.requests.indexOfFirst { it.startsWith("POST /storage/v1/object/") }
        val photoRow = server.requests.indexOf("POST /rest/v1/task_photos")
        assertTrue(taskInsert in 0 until upload && upload < photoRow, "${server.requests}")
    }

    @Test
    fun a_photo_whose_row_is_refused_at_once_leaves_no_file_behind() = runTest {
        // Given — a task the server no longer has, deleted on another device meanwhile
        repository.addTask("Deleted elsewhere", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        server.deleteRows(Tables.TASKS)

        // When — the photo uploads, and its row is refused
        assertFailsWith<TaskWriteRefusedException> {
            repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1))
        }

        // Then — the file went with the row
        assertTrue(server.files.isEmpty(), "a file was left with no row: ${server.files.keys}")
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
    }

    @Test
    fun a_photo_whose_row_is_lost_on_the_way_keeps_its_file_and_the_queue_sends_the_row() =
        runTest {
            // Given — a task, and its photo's row held on its way to the server
            repository.addTask("Kept", notes = null)
            val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
            val release = CompletableDeferred<Boolean>()
            server.holdPhotoRowInserts = release
            val adding = scope.async {
                repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 1, 1))
            }
            withTimeout(WAIT) { server.photoRowInsertsHeld.first { it == 1 } }

            // When — the connection drops on the way
            release.complete(false)
            adding.await()

            // Then — not a refusal: the file stays, and the row waits in the mutation queue
            repository.photos(task.id).awaitValue { photos -> photos.isUploaded() }
            assertEquals(1, server.files.size)
            server.holdPhotoRowInserts = null
            repository.syncPendingMutations()
            assertEquals(1, server.rows(Tables.TASK_PHOTOS).size)
        }

    @Test
    fun deleting_a_task_drops_the_uploads_of_its_photos() = runTest {
        // Given — a task with a photo waiting offline
        val task = taskWithPhotosRead("Thrown away with its photo")
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        repository.photos(task.id).awaitValue { it.isNotEmpty() }

        // When — deleted, then the network back
        repository.deleteTask(task.id)
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()

        // Then — its upload never went
        assertTrue(supabase.offline.storage.pendingUploads().first().isEmpty())
        assertTrue(server.requests.none { it.startsWith("POST /storage/v1/object/") })
        assertTrue(server.files.isEmpty())
    }

    @Test
    fun a_task_delete_storage_refuses_keeps_its_photos() = runTest {
        // Given — a task with an uploaded photo, and one Storage refused, kept on the device
        val task = taskWithPhotosRead("Kept after all")
        repository.addUploadedPhoto(task.id)
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        server.isUploadRefused = true
        repository.setNetworkEnabled(true)
        repository.photos(task.id).awaitValue { it.hasFailedUpload() }
        server.isDeleteRefused = true

        // When — the delete fails at its files step
        assertFailsWith<PhotoStorageException> { repository.deleteTask(task.id) }

        // Then — nothing is deleted: the task, and the refused upload with its file
        assertEquals(1, server.rows(Tables.TASKS).size)
        assertEquals(1, supabase.offline.storage.rejectedUploadList().size)
        assertTrue(repository.photos(task.id).awaitValue { it.size == 2 }.any { it.hasPending() })
    }

    @Test
    fun clear_local_cache_and_start_over_drop_the_photos_waiting_to_upload() = runTest {
        // Given — a photo waiting offline
        val task = taskWithPhotosRead("Cleared")
        repository.setNetworkEnabled(false)
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        repository.photos(task.id).awaitValue { it.isNotEmpty() }

        // When
        repository.clearLocalData()

        // Then
        assertTrue(supabase.offline.storage.pendingUploads().first().isEmpty())

        // Given — another, which Storage refuses once the network is back
        repository.addPhoto(task.id, EncodedPhoto(ByteArray(8), 4, 3))
        server.isUploadRefused = true
        repository.setNetworkEnabled(true)
        repository.photos(task.id).awaitValue { it.hasFailedUpload() }

        // When
        repository.startOver()

        // Then
        assertTrue(supabase.offline.storage.rejectedUploadList().isEmpty())
        assertEquals(1, session.newUserCount)
    }

    @Test
    fun deleting_a_task_online_deletes_its_files_its_photo_rows_and_the_task() = runTest {
        // Given — a task with a photo, its photos read once
        repository.addTask("Photographed", notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.addUploadedPhoto(task.id)

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
        repository.addUploadedPhoto(task.id)

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
        repeat(7) { repository.addUploadedPhoto(task.id) }
        assertEquals(7, server.files.size)

        // When
        repository.startOver()

        // Then — every file deleted, not only the first page's
        assertTrue(server.files.isEmpty())
        assertTrue(server.rows(Tables.TASK_PHOTOS).isEmpty())
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

    /** A task on the server and the device, with the photo list read once, as a screen would. */
    private suspend fun taskWithPhotosRead(title: String): Task {
        repository.addTask(title, notes = null)
        val task = repository.tasks().awaitValue { it.isNotEmpty() }.single()
        repository.allPhotos().awaitValue { true }
        return task
    }

    /** Whether one of these photos is pending with its upload failed. */
    private fun List<TaskPhoto>.hasFailedUpload(): Boolean = any {
        it.pendingUpload?.state == PendingPhotoState.Failed
    }

    /** Whether Storage does not have this photo's file yet. */
    private fun TaskPhoto.hasPending(): Boolean = pendingUpload != null

    // --8<-- [start:conflict-test]
    @Test
    fun a_title_changed_on_both_sides_waits_for_the_user_and_keep_mine_sends_it() = runTest {
        // Given — a task the device has from the server, edited offline while another writer
        // changed the same title on the server
        val conflict = stageTitleConflict(mine = "Buy milk", theirs = "Buy oat milk")

        // Then — held for the user, with both sides of the title, and counted in the status
        assertEquals("Buy milk", conflict.taskTitle)
        assertEquals(TaskMutationOperation.Update, conflict.operation)
        assertEquals(
            listOf(ColumnConflict("title", theirs = "Buy oat milk", mine = "Buy milk")),
            conflict.columns,
        )
        assertTrue(conflict.canKeepMine)
        repository.syncStatus.awaitState { it.conflictedMutationCount == 1 }
        assertEquals("Buy oat milk", serverTitle())

        // When — Keep mine
        repository.resolveConflictedMutation(conflict.id, ConflictChoice.KeepMine)
        repository.syncPendingMutations()

        // Then — this device's title reaches the server, and nothing is left to settle
        withTimeout(WAIT) { while (serverTitle() != "Buy milk") delay(POLL) }
        repository.conflictedMutations().awaitState { it.isEmpty() }
        repository.syncStatus.awaitState {
            it.conflictedMutationCount == 0 && it.pendingMutationCount == 0
        }
    }

    // --8<-- [end:conflict-test]

    @Test
    fun keep_theirs_drops_the_edit_and_the_list_shows_the_servers_title() = runTest {
        // Given
        val conflict = stageTitleConflict(mine = "Buy milk", theirs = "Buy oat milk")

        // When
        repository.resolveConflictedMutation(conflict.id, ConflictChoice.KeepTheirs)

        // Then — nothing sent: the server's title stands, on the device too
        repository.conflictedMutations().awaitState { it.isEmpty() }
        repository.tasks().awaitValue { tasks ->
            tasks.any { it.id == SERVER_TASK_ID && it.title == "Buy oat milk" }
        }
        assertEquals("Buy oat milk", serverTitle())
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
    }

    @Test
    fun an_edit_of_a_row_moved_on_another_column_is_sent_again_without_asking() = runTest {
        // Given — the device's title edit queued while another writer ticked the task done
        server.insertRow(Tables.TASKS, serverOnlyTask("Milk"))
        repository.tasks().awaitValue { tasks -> tasks.any { it.id == SERVER_TASK_ID } }
        repository.setNetworkEnabled(false)
        repository.updateTask(SERVER_TASK_ID, "Buy milk", notes = null)
        server.replaceRow(Tables.TASKS, SERVER_TASK_ID, buildJsonObject { put("done", true) })

        // When
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()

        // Then — both changes are on the server, and none waits for the user
        withTimeout(WAIT) { while (serverTitle() != "Buy milk") delay(POLL) }
        val row = server.rows(Tables.TASKS).single()
        assertEquals("true", row["done"]?.jsonPrimitive?.content)
        assertTrue(repository.conflictedMutations().first().isEmpty())
    }

    @Test
    fun an_edit_of_a_task_deleted_on_the_server_is_dropped() = runTest {
        // Given — the device's title edit queued while another writer deleted the task
        server.insertRow(Tables.TASKS, serverOnlyTask("Milk"))
        repository.tasks().awaitValue { tasks -> tasks.any { it.id == SERVER_TASK_ID } }
        repository.setNetworkEnabled(false)
        repository.updateTask(SERVER_TASK_ID, "Buy milk", notes = null)
        server.deleteRows(Tables.TASKS)

        // When
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()

        // Then — the resolver keeps the server's side: nothing parked, refused or brought back
        repository.syncStatus.awaitState { it.pendingMutationCount == 0 }
        repository.tasks().awaitValue { it.isEmpty() }
        assertTrue(repository.conflictedMutations().first().isEmpty())
        assertTrue(repository.rejectedMutationsNow().isEmpty())
        assertTrue(server.rows(Tables.TASKS).isEmpty())
    }

    // --8<-- [start:make-conflict]
    /**
     * Stages the tour's conflict through the fake server: a task the list read from the server, its
     * title edited to [mine] offline, then changed to [theirs] on the server, then the network back
     * and the queue replayed. Returns the entry the app's resolver held.
     */
    private suspend fun stageTitleConflict(mine: String, theirs: String): ConflictedTaskMutation {
        server.insertRow(Tables.TASKS, serverOnlyTask("Milk"))
        repository.tasks().awaitValue { tasks -> tasks.any { it.id == SERVER_TASK_ID } }
        repository.setNetworkEnabled(false)
        assertIs<TaskMutationOutcome.Queued>(
            repository.updateTask(SERVER_TASK_ID, mine, notes = null)
        )
        server.replaceRow(Tables.TASKS, SERVER_TASK_ID, buildJsonObject { put("title", theirs) })
        repository.setNetworkEnabled(true)
        repository.syncPendingMutations()
        return repository.conflictedMutations().awaitState { it.isNotEmpty() }.single()
    }

    // --8<-- [end:make-conflict]

    /** The title of the server-only task, as the server holds it. */
    private fun serverTitle(): String? =
        server
            .rows(Tables.TASKS)
            .single { it["id"]?.jsonPrimitive?.content == SERVER_TASK_ID }["title"]
            ?.jsonPrimitive
            ?.content

    /** Whether these photos are listed and every one has finished uploading. */
    private fun List<TaskPhoto>.isUploaded(): Boolean =
        isNotEmpty() &&
            all {
                it.pendingUpload == null
            }

    /** A task row as the server stores it, for one the app did not make. */
    private fun serverOnlyTask(title: String, id: String = SERVER_TASK_ID): JsonObject =
        buildJsonObject {
            put("id", id)
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

    /** The first value of this state that [matches], within a bounded wait. */
    private suspend fun <T> Flow<T>.awaitState(matches: (T) -> Boolean): T =
        withTimeout(WAIT) { first(matches) }

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

        /** The id of a task only the server has. */
        const val SERVER_TASK_ID = "00000000-0000-4000-8000-0000000000aa"

        /** The id of a second task only the server has. */
        const val OTHER_TASK_ID = "00000000-0000-4000-8000-0000000000ab"

        /** How long a read is watched to show it sends nothing. */
        val NO_REQUEST_WAIT = 1.seconds

        /** How often a test looks again at the server for a write it waits on. */
        val POLL = 20.milliseconds
    }
}

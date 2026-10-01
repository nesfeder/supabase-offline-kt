// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.data.inmemory

import io.github.nesfeder.supabase.offline.sample.data.EncodedPhoto
import io.github.nesfeder.supabase.offline.sample.data.MutationOperation
import io.github.nesfeder.supabase.offline.sample.data.MutationOutcome
import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.data.NetworkUnavailableException
import io.github.nesfeder.supabase.offline.sample.data.PhotoUpload
import io.github.nesfeder.supabase.offline.sample.data.RefreshState
import io.github.nesfeder.supabase.offline.sample.data.ServerUnreachableException
import io.github.nesfeder.supabase.offline.sample.data.Tables
import io.github.nesfeder.supabase.offline.sample.testing.awaitServerTasks
import io.github.nesfeder.supabase.offline.sample.testing.awaitSnapshot
import io.github.nesfeder.supabase.offline.sample.testing.repositoryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class InMemoryTaskRepositoryTest {

    @Test
    fun the_first_snapshot_knows_nothing_and_the_server_answer_follows() = runTest {
        // Given — a first launch with the sample tasks on the server
        val repository = repositoryFixture(seedSampleTasks = true).repository

        // When
        val first = repository.tasks().first()
        val fetched = repository.tasks().awaitSnapshot { it.value != null }

        // Then — nothing known, then the server's rows, confirmed and fresh
        assertNull(first.value)
        assertTrue(first.isFromCache)
        assertEquals(5, fetched.value?.size)
        assertFalse(fetched.isFromCache)
        assertTrue(fetched.fetchedAt != null)
        assertEquals(RefreshState.Idle, fetched.refresh)
        assertFalse(fetched.hasPendingMutations)
    }

    @Test
    fun a_write_while_offline_is_queued_shown_at_once_and_marked_pending() = runTest {
        // Given — tasks loaded, then the network switched off
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()
        repository.setNetworkEnabled(false)

        // When
        val outcome = repository.addTask("Buy stamps", notes = null)

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        val snapshot = repository.tasks().first()
        val task = snapshot.value.orEmpty().single()
        assertEquals("Buy stamps", task.title)
        assertTrue(task.hasPendingMutations)
        assertTrue(snapshot.hasPendingMutations)
        assertTrue(snapshot.isFromCache)
        assertEquals(RefreshState.Paused, snapshot.refresh)
        val status = repository.syncStatus.value
        assertEquals(NetworkStatus.Disabled, status.network)
        assertEquals(1, status.pendingMutationCount)
    }

    @Test
    fun the_queue_drains_in_order_on_reconnect() = runTest {
        // Given — offline: a task added, then renamed, then given a photo row, all queued
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        repository.addTask("Draft", notes = "first")
        val taskId = repository.tasks().first().value.orEmpty().single().id
        repository.updateTask(taskId, title = "Final", notes = null)
        repository.setTaskDone(taskId, isDone = true)
        assertEquals(3, repository.syncStatus.value.pendingMutationCount)

        // When — the device comes back online
        fixture.goOnline()
        val synced =
            repository.tasks().awaitSnapshot {
                !it.hasPendingMutations && !it.isFromCache && it.value?.isNotEmpty() == true
            }

        // Then — the rename and the done flag reached a row that existed: the insert went first
        val task = synced.value.orEmpty().single()
        assertEquals("Final", task.title)
        assertNull(task.notes)
        assertTrue(task.isDone)
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
        assertTrue(repository.syncStatus.value.lastSyncedAt != null)
        assertTrue(repository.rejectedMutations().first().isEmpty())
    }

    @Test
    fun a_photo_attached_to_a_task_created_offline_reaches_the_server() = runTest {
        // Given — a task created offline
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        repository.addTask("With a photo", notes = null)
        val taskId = repository.tasks().first().value.orEmpty().single().id

        // When — online again, a photo is attached
        fixture.goOnline()
        val attached = repository.attachPhoto(taskId, photo()).toList().last()
        repository.syncPendingMutations()

        // Then — both rows reached the server: the photo's foreign key found its task
        assertIs<PhotoUpload.Attached>(attached)
        assertTrue(repository.rejectedMutations().first().isEmpty())
        val photos = repository.photos(taskId).awaitSnapshot { !it.hasPendingMutations }
        assertEquals(listOf(attached.photo.id), photos.value.orEmpty().map { it.id })
    }

    @Test
    fun a_refused_write_is_kept_as_a_rejection_and_the_row_goes() = runTest {
        // Given — offline, a task with a blank title queued
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        repository.addTask("   ", notes = null)
        assertEquals(1, repository.tasks().first().value?.size)

        // When — it replays
        fixture.goOnline()
        val report = repository.syncPendingMutations()

        // Then — refused with the schema's check, kept, and gone from the list
        val rejection = repository.rejectedMutations().first().single()
        assertEquals(Tables.TASKS, rejection.table)
        assertEquals(MutationOperation.Insert, rejection.operation)
        assertEquals("23514", rejection.serverError?.code)
        assertEquals(400, rejection.serverError?.status)
        assertEquals("   ", rejection.taskTitle)
        assertEquals(listOf(rejection), report.rejected)
        val tasks = repository.tasks().awaitSnapshot { !it.hasPendingMutations }
        assertTrue(tasks.value.orEmpty().isEmpty())
        assertEquals(1, repository.syncStatus.value.rejectedMutationCount)
    }

    @Test
    fun a_rejection_can_be_dismissed() = runTest {
        // Given — one refused write
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()
        repository.addTask("", notes = null)
        val rejection = repository.rejectedMutations().first { it.isNotEmpty() }.single()

        // When
        repository.dismissRejectedMutation(rejection.id)

        // Then
        assertTrue(repository.rejectedMutations().first().isEmpty())
        assertEquals(0, repository.syncStatus.value.rejectedMutationCount)
    }

    @Test
    fun a_retried_rejection_is_queued_again_and_refused_again() = runTest {
        // Given — one refused write
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()
        repository.addTask("", notes = null)
        val first = repository.rejectedMutations().first { it.isNotEmpty() }.single()

        // When
        repository.retryRejectedMutation(first.id)
        repository.syncPendingMutations()

        // Then — the same write, under a new id, refused for the same reason
        val again = repository.rejectedMutations().first().single()
        assertTrue(again.id != first.id)
        assertEquals(first.serverError, again.serverError)
    }

    @Test
    fun an_online_write_is_applied_before_the_call_returns() = runTest {
        // Given
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()

        // When
        val outcome = repository.addTask("Now", notes = null)

        // Then
        assertEquals(MutationOutcome.Applied, outcome)
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
    }

    @Test
    fun a_failing_server_stalls_the_refresh_and_keeps_writes_queued() = runTest {
        // Given — loaded, then every request fails while the network is up
        val repository = repositoryFixture(seedSampleTasks = true).repository
        repository.awaitServerTasks()
        repository.setServerOutage(true)

        // When
        repository.refreshTasks()
        val outcome = repository.addTask("Kept", notes = null)

        // Then
        val snapshot = repository.tasks().first()
        val stalled = assertIs<RefreshState.Stalled>(snapshot.refresh)
        assertIs<ServerUnreachableException>(stalled.cause)
        assertTrue(snapshot.isFromCache)
        assertEquals(6, snapshot.value?.size)
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(NetworkStatus.Online, repository.syncStatus.value.network)
    }

    @Test
    fun deleting_a_task_online_deletes_its_files_then_its_photo_rows_then_the_task() = runTest {
        // Given — a task with one photo, on the server and on the device
        val fixture = repositoryFixture()
        val repository = fixture.repository
        repository.awaitServerTasks()
        repository.addTask("Photographed", notes = null)
        val taskId = repository.tasks().first().value.orEmpty().single().id
        val photo =
            assertIs<PhotoUpload.Attached>(repository.attachPhoto(taskId, photo()).toList().last())
                .photo
        repository.downloadPhotoFile(photo)
        assertEquals(setOf("${photo.id}.jpg"), fixture.photoFiles.fileNames)

        // When
        repository.deleteTask(taskId)

        // Then — no file anywhere, and neither row
        assertTrue(fixture.photoFiles.fileNames.isEmpty())
        val tasks = repository.tasks().awaitSnapshot { !it.hasPendingMutations }
        assertTrue(tasks.value.orEmpty().isEmpty())
        assertTrue(repository.photos(taskId).first().value.orEmpty().isEmpty())
        assertFailsWith<Exception> { repository.downloadPhotoFile(photo) }
    }

    @Test
    fun a_photo_cannot_be_attached_offline() = runTest {
        // Given
        val repository = repositoryFixture(online = false).repository
        repository.addTask("Offline", notes = null)
        val taskId = repository.tasks().first().value.orEmpty().single().id

        // When
        val progress = repository.attachPhoto(taskId, photo()).toList()

        // Then — it fails at once, and nothing is queued for photos
        val failed = assertIs<PhotoUpload.Failed>(progress.single())
        assertIs<NetworkUnavailableException>(failed.cause)
        assertEquals(0, repository.pendingMutationCount(Tables.TASK_PHOTOS).first())
    }

    @Test
    fun clearing_local_data_drops_queued_writes_and_reloads_from_the_server() = runTest {
        // Given — the sample tasks loaded, and a write queued offline
        val repository = repositoryFixture(seedSampleTasks = true).repository
        repository.awaitServerTasks()
        repository.setNetworkEnabled(false)
        repository.addTask("Never sent", notes = null)

        // When
        repository.clearLocalData()
        repository.setNetworkEnabled(true)

        // Then — the queue is empty and the list is the server's again
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
        val tasks = repository.awaitServerTasks()
        assertEquals(5, tasks.size)
        assertTrue(tasks.none { it.title == "Never sent" })
    }

    @Test
    fun starting_over_is_a_new_user_with_no_tasks() = runTest {
        // Given — the first user's sample tasks
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val firstUserTasks = repository.awaitServerTasks()

        // When
        repository.startOver()

        // Then — an empty list, confirmed by the server, for a different user
        val tasks = repository.awaitServerTasks()
        assertEquals(5, firstUserTasks.size)
        assertTrue(tasks.isEmpty())
        repository.addTask("Mine", notes = null)
        val mine = repository.tasks().first().value.orEmpty().single()
        assertTrue(mine.userId != firstUserTasks.first().userId)
    }

    @Test
    fun the_pending_count_is_kept_per_table() = runTest {
        // Given — a task and a deletion of its photos queued offline
        val repository = repositoryFixture(online = false).repository

        // When
        repository.addTask("One", notes = null)
        repository.addTask("Two", notes = null)

        // Then
        assertEquals(2, repository.pendingMutationCount(Tables.TASKS).first())
        assertEquals(0, repository.pendingMutationCount(Tables.TASK_PHOTOS).first())
        assertEquals(2, repository.pendingMutationCount().first())
    }

    private fun photo() = EncodedPhoto(jpeg = byteArrayOf(1, 2, 3), width = 3, height = 2)
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.Tables
import io.github.nesfeder.offlinetasks.data.TaskMutationOperation
import io.github.nesfeder.offlinetasks.data.TaskMutationOutcome
import io.github.nesfeder.offlinetasks.data.TaskNetworkStatus
import io.github.nesfeder.offlinetasks.data.TaskRefreshState
import io.github.nesfeder.offlinetasks.data.TaskWriteRefusedException
import io.github.nesfeder.offlinetasks.data.characterCount
import io.github.nesfeder.offlinetasks.data.isTitleRefusedByServer
import io.github.nesfeder.offlinetasks.data.takeCharacters
import io.github.nesfeder.offlinetasks.testing.awaitPendingCount
import io.github.nesfeder.offlinetasks.testing.awaitRejectedCount
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.awaitSnapshot
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
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
        assertEquals(TaskRefreshState.Idle, fetched.refresh)
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
        assertIs<TaskMutationOutcome.Queued>(outcome)
        val snapshot = repository.tasks().first()
        val task = snapshot.value.orEmpty().single()
        assertEquals("Buy stamps", task.title)
        assertTrue(task.hasPendingMutations)
        assertTrue(snapshot.hasPendingMutations)
        assertTrue(snapshot.isFromCache)
        assertEquals(TaskRefreshState.Paused, snapshot.refresh)
        // The network is current when the switch returns; the count catches up with the write.
        assertEquals(TaskNetworkStatus.Disabled, repository.syncStatus.value.network)
        repository.awaitPendingCount(1)
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
        repository.awaitPendingCount(3)

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
        assertTrue(repository.awaitPendingCount(0).lastSyncedAt != null)
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
        assertEquals(TaskMutationOperation.Insert, rejection.operation)
        assertEquals("23514", rejection.serverError?.code)
        assertEquals(400, rejection.serverError?.status)
        assertEquals("   ", rejection.taskTitle)
        assertEquals(listOf(rejection), report.rejected)
        val tasks = repository.tasks().awaitSnapshot { !it.hasPendingMutations }
        assertTrue(tasks.value.orEmpty().isEmpty())
        repository.awaitRejectedCount(1)
    }

    @Test
    fun an_online_write_the_server_refuses_is_thrown_and_not_kept() = runTest {
        // Given — online, with nothing queued, so the write is sent at once
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()

        // When
        val refused = assertFailsWith<TaskWriteRefusedException> { repository.addTask("  ", null) }

        // Then — the caller is told, the row is gone, and nothing is kept as a rejection
        assertEquals("23514", refused.serverError.code)
        assertEquals("  ", refused.taskTitle)
        assertEquals(TaskMutationOperation.Insert, refused.operation)
        val tasks = repository.tasks().awaitSnapshot { !it.hasPendingMutations }
        assertTrue(tasks.value.orEmpty().isEmpty())
        assertTrue(repository.rejectedMutations().first().isEmpty())
        repository.awaitPendingCount(0)
    }

    @Test
    fun a_rejection_can_be_dismissed() = runTest {
        // Given — one write refused on replay
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        repository.addTask("", notes = null)
        fixture.goOnline()
        val rejection = repository.rejectedMutations().first { it.isNotEmpty() }.single()

        // When
        repository.dismissRejectedMutation(rejection.id)

        // Then
        assertTrue(repository.rejectedMutations().first().isEmpty())
        repository.awaitRejectedCount(0)
    }

    @Test
    fun a_retried_rejection_is_queued_again_and_refused_again() = runTest {
        // Given — one write refused on replay
        val fixture = repositoryFixture(online = false)
        val repository = fixture.repository
        repository.addTask("", notes = null)
        fixture.goOnline()
        val first = repository.rejectedMutations().first { it.isNotEmpty() }.single()

        // When — retried while online: replayed, never sent at once, so never thrown
        repository.retryRejectedMutation(first.id)
        repository.syncPendingMutations()

        // Then — the same write, under a new id, refused for the same reason
        val again =
            repository.rejectedMutations().first { it.singleOrNull()?.id != first.id }.single()
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
        assertEquals(TaskMutationOutcome.Applied, outcome)
        repository.awaitPendingCount(0)
    }

    @Test
    fun a_failing_server_stalls_the_refresh_and_keeps_writes_queued() = runTest {
        // Given — loaded, then every request fails while the network is up
        val repository = repositoryFixture(seedSampleTasks = true).repository
        repository.awaitServerTasks()
        repository.setServerOutage(true)

        // When — a pull-to-refresh fails, as a read that must reach the server does
        assertFailsWith<ServerUnreachableException> { repository.refreshTasks() }
        val outcome = repository.addTask("Kept", notes = null)

        // Then
        val snapshot = repository.tasks().first()
        val stalled = assertIs<TaskRefreshState.Stalled>(snapshot.refresh)
        assertIs<ServerUnreachableException>(stalled.cause)
        assertTrue(snapshot.isFromCache)
        assertEquals(6, snapshot.value?.size)
        assertIs<TaskMutationOutcome.Queued>(outcome)
        assertEquals(TaskNetworkStatus.Online, repository.syncStatus.value.network)
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
        val progress = mutableListOf<PhotoUpload>()
        assertFailsWith<NetworkUnavailableException> {
            repository.attachPhoto(taskId, photo()).toList(progress)
        }

        // Then — it fails at once, before any progress, and nothing is queued for photos
        assertTrue(progress.isEmpty())
        assertEquals(0, repository.pendingMutationCount(Tables.TASK_PHOTOS).first())
    }

    @Test
    fun a_refresh_needs_the_network() = runTest {
        // Given — loaded, then the network switched off by the app
        val repository = repositoryFixture(seedSampleTasks = true).repository
        repository.awaitServerTasks()
        repository.setNetworkEnabled(false)

        // When / Then — it fails at once instead of waiting for the network
        assertFailsWith<NetworkUnavailableException> { repository.refreshTasks() }
    }

    @Test
    fun storage_refusals_fail_photo_calls_and_change_nothing() = runTest {
        // Given — a task with one photo, then Storage refusing everything
        val repository = repositoryFixture().repository
        repository.awaitServerTasks()
        repository.addTask("Photographed", notes = null)
        val taskId = repository.tasks().first().value.orEmpty().single().id
        val photo =
            assertIs<PhotoUpload.Attached>(repository.attachPhoto(taskId, photo()).toList().last())
                .photo
        repository.setStorageFailing(true)

        // When / Then — every photo call fails with Storage's refusal
        assertFailsWith<PhotoStorageException> { repository.deletePhoto(photo.id) }
        assertFailsWith<PhotoStorageException> { repository.deleteTask(taskId) }
        assertFailsWith<PhotoStorageException> { repository.downloadPhotoFile(photo) }
        assertFailsWith<PhotoStorageException> {
            repository.attachPhoto(taskId, photo()).toList()
        }

        // Then — the task and its one photo are still there
        assertEquals(1, repository.awaitServerTasks().size)
        assertEquals(
            listOf(photo.id),
            repository.photos(taskId).first().value.orEmpty().map { it.id },
        )
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
        repository.awaitPendingCount(0)
        val tasks = repository.awaitServerTasks()
        assertEquals(5, tasks.size)
        assertTrue(tasks.none { it.title == "Never sent" })
    }

    @Test
    fun starting_over_online_deletes_the_users_rows_and_files_from_the_server() = runTest {
        // Given — the first user's tasks, one with a photo
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val taskId = repository.awaitServerTasks().first().id
        val photo =
            assertIs<PhotoUpload.Attached>(repository.attachPhoto(taskId, photo()).toList().last())
                .photo

        // When
        repository.startOver()

        // Then — the new user has nothing, and the first user's file is gone from the server
        assertTrue(repository.awaitServerTasks().isEmpty())
        assertFailsWith<PhotoNotFoundException> { repository.downloadPhotoFile(photo) }
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

    @Test
    fun lengths_are_counted_in_characters_as_the_server_counts_them() {
        // Given — a title of 200 emoji, each two UTF-16 units but one character
        val emoji = "\uD83D\uDE80"
        val title = emoji.repeat(TASK_TITLE_MAX_LENGTH)

        // Then — 200 characters: within the limit, and cut there without splitting one
        assertEquals(TASK_TITLE_MAX_LENGTH, characterCount(title))
        assertFalse(isTitleRefusedByServer(title))
        assertTrue(isTitleRefusedByServer(title + emoji))
        assertEquals(title, takeCharacters(title + emoji, TASK_TITLE_MAX_LENGTH))
    }

    private fun photo() = EncodedPhoto(jpeg = byteArrayOf(1, 2, 3), width = 3, height = 2)
}

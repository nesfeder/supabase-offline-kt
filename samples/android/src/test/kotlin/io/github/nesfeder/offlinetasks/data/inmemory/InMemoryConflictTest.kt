// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.data.inmemory

import io.github.nesfeder.offlinetasks.testing.awaitPendingCount
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * The in-memory repository's conflicts against the library's rules: the row an accepted edit
 * returns is forwarded to the edits queued behind it, and only a conflict the resolver settled is
 * counted as resolved.
 */
class InMemoryConflictTest {

    @Test
    fun two_offline_edits_of_one_title_park_nothing() = runTest {
        // Given — a task from the server, its title edited twice offline
        val fixture = repositoryFixture()
        val repository = fixture.repository
        repository.addTask("Milk", notes = null)
        val task = repository.awaitServerTasks().single()
        fixture.deviceOnline.value = false
        repository.updateTask(task.id, "Buy milk", notes = null)
        repository.updateTask(task.id, "Buy oat milk", notes = null)

        // When
        fixture.goOnline()
        repository.awaitPendingCount(0)

        // Then — the second edit replays on the version the first left, so nothing conflicts
        assertEquals(emptyList(), repository.conflictedMutations().first().map { it.taskTitle })
        assertEquals("Buy oat milk", repository.awaitServerTasks().single().title)
    }

    @Test
    fun an_edit_of_a_row_moved_on_another_column_is_resent_and_not_counted_as_resolved() = runTest {
        // Given — a title edit queued while another writer ticked the task done
        val fixture = repositoryFixture()
        val repository = fixture.repository
        repository.addTask("Milk", notes = null)
        val task = repository.awaitServerTasks().single()
        fixture.deviceOnline.value = false
        repository.updateTask(task.id, "Buy milk", notes = null)
        repository.changeTaskOnServer(task.id) { it.copy(isDone = true) }

        // When
        fixture.goOnline()
        val report = repository.syncPendingMutations()

        // Then — a stale base: sent again without asking, and no conflict counted
        assertEquals(1, report.syncedCount)
        assertEquals(0, report.conflictsResolvedCount)
        val settled = repository.awaitServerTasks().single()
        assertEquals("Buy milk", settled.title)
        assertTrue(settled.isDone)
    }

    @Test
    fun an_edit_the_server_already_holds_is_synced_as_already_there() = runTest {
        // Given — another writer set the same title the device queued
        val fixture = repositoryFixture()
        val repository = fixture.repository
        repository.addTask("Milk", notes = null)
        val task = repository.awaitServerTasks().single()
        fixture.deviceOnline.value = false
        repository.updateTask(task.id, "Buy milk", notes = null)
        repository.changeTitleOnServer(task.id, "Buy milk")

        // When
        fixture.goOnline()
        val report = repository.syncPendingMutations()

        // Then
        assertEquals(1, report.syncedCount)
        assertEquals(1, report.alreadyOnServerCount)
        assertEquals(0, report.conflictsResolvedCount)
        assertTrue(repository.conflictedMutations().first().isEmpty())
    }
}

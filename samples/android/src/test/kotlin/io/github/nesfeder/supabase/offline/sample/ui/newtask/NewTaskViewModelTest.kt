// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.newtask

import io.github.nesfeder.supabase.offline.sample.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.supabase.offline.sample.testing.MainDispatcherRule
import io.github.nesfeder.supabase.offline.sample.testing.repositoryFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class NewTaskViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun a_blank_title_cannot_be_added() = runTest {
        // Given
        val repository = repositoryFixture().repository
        val viewModel = NewTaskViewModel(repository, backgroundScope)

        // When
        viewModel.onTitleChange("  \t ")
        viewModel.add()

        // Then — still editing, and nothing written
        val state = assertIs<NewTaskUiState.Editing>(viewModel.uiState.value)
        assertFalse(state.canAdd)
        assertEquals(0, repository.syncStatus.value.pendingMutationCount)
    }

    @Test
    fun add_writes_the_trimmed_task_and_closes() = runTest {
        // Given
        val repository = repositoryFixture(online = false).repository
        val viewModel = NewTaskViewModel(repository, backgroundScope)
        viewModel.onTitleChange("  Buy stamps ")
        viewModel.onNotesChange("   ")
        assertTrue((viewModel.uiState.value as NewTaskUiState.Editing).canAdd)

        // When
        viewModel.add()

        // Then — the screen closes and the task is on the device, notes left out
        assertIs<NewTaskUiState.Added>(viewModel.uiState.value)
        val task = repository.tasks().first { it.value?.isNotEmpty() == true }.value.orEmpty().single()
        assertEquals("Buy stamps", task.title)
        assertNull(task.notes)
    }

    @Test
    fun the_title_stops_at_the_schema_limit() = runTest {
        // Given
        val viewModel = NewTaskViewModel(repositoryFixture().repository, backgroundScope)

        // When
        viewModel.onTitleChange("x".repeat(TASK_TITLE_MAX_LENGTH + 20))

        // Then
        val state = assertIs<NewTaskUiState.Editing>(viewModel.uiState.value)
        assertEquals(TASK_TITLE_MAX_LENGTH, state.title.length)
    }
}

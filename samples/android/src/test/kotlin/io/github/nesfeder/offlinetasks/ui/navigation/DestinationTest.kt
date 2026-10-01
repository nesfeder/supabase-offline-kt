// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DestinationTest {

    @Test
    fun switching_top_level_keeps_tasks_at_the_bottom() {
        // Given — deep in Tasks
        val backStack = mutableListOf<NavKey>(Destination.Tasks, Destination.TaskDetail("a"))

        // When
        backStack.selectTopLevel(TopLevelDestination.Sync)

        // Then — back from Sync returns to Tasks
        assertEquals(listOf<NavKey>(Destination.Tasks, Destination.Sync), backStack)
        assertEquals(TopLevelDestination.Sync, backStack.currentTopLevel())
    }

    @Test
    fun selecting_tasks_returns_to_the_list() {
        // Given
        val backStack =
            mutableListOf<NavKey>(
                Destination.Tasks,
                Destination.Photos,
                Destination.PhotoViewer("p"),
            )

        // When
        backStack.selectTopLevel(TopLevelDestination.Tasks)

        // Then
        assertEquals(listOf<NavKey>(Destination.Tasks), backStack)
    }

    @Test
    fun the_photo_viewer_belongs_to_photos() {
        // Given
        val backStack =
            listOf<NavKey>(Destination.Tasks, Destination.Photos, Destination.PhotoViewer("p"))

        // Then
        assertEquals(TopLevelDestination.Photos, backStack.currentTopLevel())
    }

    @Test
    fun opening_a_task_beside_another_replaces_it() {
        // Given — a task open beside the list
        val backStack = mutableListOf<NavKey>(Destination.Tasks, Destination.TaskDetail("a"))

        // When
        backStack.openTask("b")

        // Then
        assertEquals(listOf<NavKey>(Destination.Tasks, Destination.TaskDetail("b")), backStack)
    }

    @Test
    fun opening_the_open_task_again_keeps_it_as_it_is() {
        // Given — a task open beside the list, opened when it had another title
        val opened =
            Destination.TaskDetail("a", ShownTask("Old title", notes = null, isDone = false))
        val backStack = mutableListOf<NavKey>(Destination.Tasks, opened)

        // When
        backStack.openTask("a", ShownTask("New title", notes = null, isDone = false))

        // Then — the same entry, so its screen and its typing are kept
        assertEquals(listOf<NavKey>(Destination.Tasks, opened), backStack)
    }
}

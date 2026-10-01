// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.tasks

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.Task
import io.github.nesfeder.supabase.offline.sample.ui.status.SYNC_BANNER_TAG
import io.github.nesfeder.supabase.offline.sample.ui.status.SyncHeadline
import io.github.nesfeder.supabase.offline.sample.ui.theme.OfflineTasksTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The Tasks screen's three designed states, rendered on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class TasksScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_first_launch_offline_says_nothing_is_saved_yet() {
        // Given — nothing cached and no network
        show(TasksUiState.NoDataYet(SyncHeadline.Offline(0), TaskFilter.All, isLoading = false))

        // Then — a designed state, not a spinner, under the offline banner
        compose.onNodeWithText(text(R.string.tasks_no_data_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.status_offline)).assertIsDisplayed()
    }

    @Test
    fun an_empty_list_invites_a_first_task() {
        // Given — the server answered with no tasks
        show(loaded(open = emptyList(), done = emptyList()))

        // Then
        compose.onNodeWithText(text(R.string.tasks_empty_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.new_task), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun a_stalled_list_shows_the_saved_tasks_under_the_banner() {
        // Given — saved tasks, and a server that does not answer
        show(
            loaded(
                open = listOf(task("Water the tomatoes"), task("Book the bike service")),
                done = listOf(task("Renew the parking permit", isDone = true)),
                headline = SyncHeadline.ShowingSavedData,
            )
        )

        // Then
        compose.onNodeWithTag(SYNC_BANNER_TAG).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.status_showing_saved_data)).assertIsDisplayed()
        compose.onNodeWithText("Water the tomatoes").assertIsDisplayed()
        compose.onNodeWithText("Renew the parking permit").assertIsDisplayed()
    }

    @Test
    fun the_filter_buttons_report_the_choice() {
        // Given
        var chosen: TaskFilter? = null
        show(
            loaded(open = listOf(task("One")), done = emptyList()),
            onFilterChange = { chosen = it },
        )

        // When
        compose.onNodeWithText(text(R.string.filter_done)).performClick()

        // Then
        assertEquals(TaskFilter.Done, chosen)
    }

    private fun show(state: TasksUiState, onFilterChange: (TaskFilter) -> Unit = {}) {
        compose.setContent {
            OfflineTasksTheme(dynamicColor = false) {
                TasksScreen(
                    state = state,
                    snackbarHostState = SnackbarHostState(),
                    onFilterChange = onFilterChange,
                    onDoneChange = { _, _ -> },
                    onDelete = {},
                    onRefresh = {},
                    onOpenTask = {},
                    onNewTask = {},
                )
            }
        }
    }

    private fun loaded(
        open: List<Task>,
        done: List<Task>,
        headline: SyncHeadline = SyncHeadline.UpToDate,
    ) =
        TasksUiState.Loaded(
            headline = headline,
            filter = TaskFilter.All,
            openTasks = open,
            doneTasks = done,
            openCount = open.size,
            isRefreshing = false,
        )

    private fun task(title: String, isDone: Boolean = false): Task {
        val at = Instant.parse("2026-10-01T09:00:00Z")
        return Task(
            id = title,
            userId = "user",
            title = title,
            notes = null,
            isDone = isDone,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun text(@StringRes id: Int): String = context.getString(id)
}

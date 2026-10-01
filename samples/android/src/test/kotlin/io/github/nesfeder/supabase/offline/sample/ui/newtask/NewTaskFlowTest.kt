// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.newtask

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.inmemory.InMemoryTaskRepository
import io.github.nesfeder.supabase.offline.sample.testing.FakePhotoFileStore
import io.github.nesfeder.supabase.offline.sample.ui.theme.OfflineTasksTheme
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Adding a task through the New-task screen, offline, on the JVM. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class NewTaskFlowTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    /** Runs every launch at once, so the write is on the device when the click returns. */
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())

    @AfterTest
    fun cancelScope() {
        scope.cancel()
    }

    @Test
    fun a_typed_title_is_added_and_the_screen_closes() {
        // Given — an offline device with no tasks, and the New-task screen
        val repository =
            InMemoryTaskRepository(
                scope = scope,
                photoFiles = FakePhotoFileStore(Clock.System),
                networkAvailability = flowOf(false),
                seedSampleTasks = false,
            )
        val viewModel = NewTaskViewModel(repository, scope)
        var closed = false
        compose.setContent {
            OfflineTasksTheme(dynamicColor = false) {
                NewTaskRoute(onClose = { closed = true }, viewModel = viewModel)
            }
        }
        val add = compose.onNodeWithText(context.getString(R.string.action_add))
        add.assertIsNotEnabled()

        // When
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput("Buy stamps")
        add.assertIsEnabled()
        add.performClick()
        compose.waitForIdle()

        // Then — closed, and the task is on the device, waiting to sync
        assertTrue(closed)
        val tasks = runBlocking { repository.tasks().first().value.orEmpty() }
        assertEquals(listOf("Buy stamps"), tasks.map { it.title })
        assertTrue(tasks.single().hasPendingMutations)
    }
}

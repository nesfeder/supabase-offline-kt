// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

import android.content.Context
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbars
import io.github.nesfeder.offlinetasks.ui.feedback.LocalAppSnackbars
import io.github.nesfeder.offlinetasks.ui.photos.PhotoFileState
import io.github.nesfeder.offlinetasks.ui.theme.OfflineTasksTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The task detail screen's layout, rendered on the JVM. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h914dp-xxhdpi")
class TaskDetailScreenTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun a_message_shows_above_the_floating_toolbar() {
        // Given — the detail screen, and a failure reported while it is on top
        val snackbars = SnackbarHostState()
        compose.setContent {
            OfflineTasksTheme(dynamicColor = false) {
                CompositionLocalProvider(
                    LocalAppSnackbars provides AppSnackbars(snackbars, isDrawnHere = true)
                ) {
                    TaskDetailScreen(
                        taskId = "a",
                        state = editing(),
                        showBackButton = true,
                        onBack = {},
                        onTitleChange = {},
                        onNotesChange = {},
                        onDoneChange = {},
                        onAddPhoto = {},
                        onOpenPhoto = {},
                        onDelete = {},
                        loadPhotoFile = { PhotoFileState.Loading },
                    )
                    LaunchedEffect(Unit) {
                        snackbars.showSnackbar(
                            "Storage refused it",
                            duration = SnackbarDuration.Indefinite,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()

        // Then — the whole snackbar ends above the toolbar's buttons
        val snackbar =
            compose
                .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
                .getBoundsInRoot()
        val toolbarButton =
            compose
                .onNodeWithContentDescription(context.getString(R.string.detail_add_photo))
                .getBoundsInRoot()
        assertTrue(
            snackbar.bottom <= toolbarButton.top,
            "snackbar ends at ${snackbar.bottom}, the toolbar starts at ${toolbarButton.top}",
        )
    }

    private fun editing() =
        TaskDetailUiState.Editing(
            taskId = "a",
            title = "Book the bike service",
            notes = "Brakes squeak",
            isTitleBlank = false,
            isDone = false,
            syncState = TaskDetailSyncState.Synced,
            photos = emptyList(),
            uploads = emptyList(),
            canChangePhotos = true,
        )
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import io.github.nesfeder.offlinetasks.data.EncodedPhoto
import io.github.nesfeder.offlinetasks.data.PhotoStorageException
import io.github.nesfeder.offlinetasks.data.PhotoUpload
import io.github.nesfeder.offlinetasks.data.inmemory.InMemoryTaskRepository
import io.github.nesfeder.offlinetasks.testing.FailingReadsTaskRepository
import io.github.nesfeder.offlinetasks.testing.MainDispatcherRule
import io.github.nesfeder.offlinetasks.testing.awaitServerTasks
import io.github.nesfeder.offlinetasks.testing.repositoryFixture
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule

class PhotoViewModelsTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val failures = FailureReports()

    @Test
    fun a_photo_storage_refuses_shows_as_unavailable_in_its_tile() = runTest {
        // Given — a photo not on this device, and Storage refusing
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val taskId = repository.awaitServerTasks().first().id
        val photo = repository.attachedPhoto(taskId)
        repository.setStorageFailing(true)
        val viewModel = PhotosViewModel(repository, failures)

        // When
        val file = viewModel.photoFile(photo)

        // Then — a state its tile draws, not an exception in the composition
        assertEquals(PhotoFileState.Unavailable, file)
    }

    @Test
    fun a_delete_storage_refuses_is_a_message_and_the_viewer_stays() = runTest {
        // Given — the viewer on a photo, and Storage refusing
        val repository = repositoryFixture(seedSampleTasks = true).repository
        val taskId = repository.awaitServerTasks().first().id
        val photo = repository.attachedPhoto(taskId)
        val viewModel = PhotoViewerViewModel(photo.id, repository, failures)
        viewModel.uiState.first { it is PhotoViewerUiState.Showing }
        repository.setStorageFailing(true)

        // When
        viewModel.delete()

        // Then — reported, not thrown, and the photo is still showing
        assertIs<PhotoStorageException>(failures.reported.first())
        assertIs<PhotoViewerUiState.Showing>(viewModel.uiState.value)
    }

    @Test
    fun a_failing_read_on_the_photos_screen_is_a_message_and_the_photos_stay() = runTest {
        // Given — a photo, then the photo list's read failing after it loads
        val fixture = repositoryFixture(seedSampleTasks = true).repository
        fixture.attachedPhoto(fixture.awaitServerTasks().first().id)
        val failure = IllegalStateException("A row the app cannot read")
        val viewModel = PhotosViewModel(FailingReadsTaskRepository(fixture, failure), failures)

        // When
        viewModel.uiState.first { it is PhotosUiState.Loaded }

        // Then — reported, not thrown, and the photo still shows
        assertEquals(failure, failures.reported.first())
        assertEquals(1, assertIs<PhotosUiState.Loaded>(viewModel.uiState.value).photos.size)
    }

    @Test
    fun a_failing_read_in_the_viewer_is_a_message_and_the_photo_stays() = runTest {
        // Given — the viewer on a photo, and the photo list's read failing after it loads
        val fixture = repositoryFixture(seedSampleTasks = true).repository
        val photo = fixture.attachedPhoto(fixture.awaitServerTasks().first().id)
        val failure = IllegalStateException("A row the app cannot read")
        val viewModel =
            PhotoViewerViewModel(photo.id, FailingReadsTaskRepository(fixture, failure), failures)

        // When
        viewModel.uiState.first { it is PhotoViewerUiState.Showing }

        // Then — reported, not thrown, and the photo is still showing
        assertEquals(failure, failures.reported.first())
        assertIs<PhotoViewerUiState.Showing>(viewModel.uiState.value)
    }

    private suspend fun InMemoryTaskRepository.attachedPhoto(taskId: String) =
        assertIs<PhotoUpload.Attached>(
                attachPhoto(taskId, EncodedPhoto(byteArrayOf(1), width = 1, height = 1))
                    .toList()
                    .last()
            )
            .photo
}

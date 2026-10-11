// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** How many photos one page of the grid shows. */
const val PHOTO_PAGE_SIZE: Int = 24

/** What the Photos screen shows. */
sealed interface PhotosUiState {
    /** Nothing saved on this device yet; [isLoading] while the server is being asked. */
    data class NoDataYet(val isLoading: Boolean) : PhotosUiState

    /** The first page or pages of photos, newest first; possibly none. */
    data class Loaded(
        val photos: List<TaskPhoto>,
        val canReachServer: Boolean,
        /** Whether a full page came back, so a next page may follow. */
        val hasMore: Boolean = false,
    ) : PhotosUiState
}

/**
 * The photos the user has, across tasks, a page at a time: each page size is a query of its own,
 * cached on its own, so offline the pages read online are the ones there are.
 *
 * @param failures where a failed read is reported, to be shown as a message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosViewModel(
    private val repository: TaskRepository,
    private val failures: FailureReports,
) : ViewModel() {
    private val shownCount = MutableStateFlow(PHOTO_PAGE_SIZE)

    /** The screen's state. */
    val uiState: StateFlow<PhotosUiState> =
        combine(
                shownCount.flatMapLatest { count ->
                    repository.photoPage(count).reportingFailures(failures).map { count to it }
                },
                repository.syncStatus,
            ) { (count, snapshot), status ->
                val online = status.network == AppNetworkStatus.Online
                val photos = snapshot.value
                if (photos == null) {
                    PhotosUiState.NoDataYet(isLoading = online)
                } else {
                    PhotosUiState.Loaded(photos, online, hasMore = photos.size >= count)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PhotosUiState.NoDataYet(isLoading = true),
            )

    /** Reads one more page: the newest photos, a page more of them. */
    fun showMore() {
        shownCount.update { it + PHOTO_PAGE_SIZE }
    }

    /** The device's copy of [photo], fetched first if needed. */
    suspend fun photoFile(photo: TaskPhoto): PhotoFileState = repository.loadPhotoFile(photo)

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                PhotosViewModel(application.repository, application.failures)
            }
        }
    }
}

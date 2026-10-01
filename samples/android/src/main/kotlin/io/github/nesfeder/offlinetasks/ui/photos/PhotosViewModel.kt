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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the Photos screen shows. */
sealed interface PhotosUiState {
    /** Nothing saved on this device yet; [isLoading] while the server is being asked. */
    data class NoDataYet(val isLoading: Boolean) : PhotosUiState

    /** Every photo, newest first; possibly none. */
    data class Loaded(val photos: List<TaskPhoto>, val canReachServer: Boolean) : PhotosUiState
}

/** Every photo the user has, across tasks. */
class PhotosViewModel(private val repository: TaskRepository) : ViewModel() {

    /** The screen's state. */
    val uiState: StateFlow<PhotosUiState> =
        combine(repository.allPhotos(), repository.syncStatus) { snapshot, status ->
                val online = status.network == AppNetworkStatus.Online
                val photos = snapshot.value
                if (photos == null) {
                    PhotosUiState.NoDataYet(isLoading = online)
                } else {
                    PhotosUiState.Loaded(photos, canReachServer = online)
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PhotosUiState.NoDataYet(isLoading = true),
            )

    /** The device's copy of [photo], fetched first if needed. */
    suspend fun photoFile(photo: TaskPhoto): PhotoFileState = repository.loadPhotoFile(photo)

    companion object {
        /** Builds the ViewModel from the app's single repository. */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { PhotosViewModel(offlineTasksApplication.repository) }
        }
    }
}

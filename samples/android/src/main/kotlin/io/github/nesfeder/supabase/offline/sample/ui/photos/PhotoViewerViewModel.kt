// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.supabase.offline.sample.data.NetworkStatus
import io.github.nesfeder.supabase.offline.sample.data.NetworkUnavailableException
import io.github.nesfeder.supabase.offline.sample.data.ServerUnreachableException
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository
import io.github.nesfeder.supabase.offline.sample.offlineTasksApplication
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the photo viewer shows. */
sealed interface PhotoViewerUiState {
    /** Nothing known about the photo yet. */
    data object Loading : PhotoViewerUiState

    /** The photo is gone: deleted, or never synced. */
    data object Gone : PhotoViewerUiState

    /** The photo; deleting it needs the server, for its file. */
    data class Showing(val photo: TaskPhoto, val canDelete: Boolean) : PhotoViewerUiState
}

/** Something the photo viewer says once. */
sealed interface PhotoViewerMessage {
    /** The photo is deleted; the viewer closes. */
    data object Deleted : PhotoViewerMessage

    /** The server could not be reached, so nothing was deleted. */
    data object DeleteNeedsNetwork : PhotoViewerMessage
}

/** One photo, full screen, with its deletion. */
class PhotoViewerViewModel(private val photoId: String, private val repository: TaskRepository) :
    ViewModel() {

    private val messageChannel = Channel<PhotoViewerMessage>(Channel.BUFFERED)

    /** One-off messages, each delivered once. */
    val messages: Flow<PhotoViewerMessage> = messageChannel.receiveAsFlow()

    /** The screen's state. */
    val uiState: StateFlow<PhotoViewerUiState> =
        combine(repository.allPhotos(), repository.syncStatus) { snapshot, status ->
                val photos = snapshot.value ?: return@combine PhotoViewerUiState.Loading
                val photo = photos.firstOrNull { it.id == photoId }
                if (photo == null) {
                    PhotoViewerUiState.Gone
                } else {
                    PhotoViewerUiState.Showing(
                        photo,
                        canDelete = status.network == NetworkStatus.Online,
                    )
                }
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PhotoViewerUiState.Loading,
            )

    /** The device's copy of [photo], fetched first if needed. */
    suspend fun photoFile(photo: TaskPhoto): PhotoFileState = repository.loadPhotoFile(photo)

    /** Deletes the photo's file, then its row; the repository finishes it if the viewer closes. */
    fun delete() {
        viewModelScope.launch {
            try {
                repository.deletePhoto(photoId)
                messageChannel.send(PhotoViewerMessage.Deleted)
            } catch (offline: NetworkUnavailableException) {
                messageChannel.send(PhotoViewerMessage.DeleteNeedsNetwork)
            } catch (unreachable: ServerUnreachableException) {
                messageChannel.send(PhotoViewerMessage.DeleteNeedsNetwork)
            }
        }
    }

    companion object {
        /** Builds the ViewModel for the photo [photoId] from the app's single instances. */
        fun factory(photoId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                PhotoViewerViewModel(photoId, offlineTasksApplication.repository)
            }
        }
    }
}

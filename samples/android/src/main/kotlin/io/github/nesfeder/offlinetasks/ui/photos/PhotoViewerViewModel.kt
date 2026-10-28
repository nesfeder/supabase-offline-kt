// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.nesfeder.offlinetasks.data.AppNetworkStatus
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.PhotoEncoder
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskRepository
import io.github.nesfeder.offlinetasks.offlineTasksApplication
import io.github.nesfeder.offlinetasks.ui.feedback.FailureReports
import io.github.nesfeder.offlinetasks.ui.feedback.Notice
import io.github.nesfeder.offlinetasks.ui.feedback.Notices
import io.github.nesfeder.offlinetasks.ui.feedback.awaitNetworkChange
import io.github.nesfeder.offlinetasks.ui.feedback.launchReporting
import io.github.nesfeder.offlinetasks.ui.feedback.readingAgainAfterFailures
import io.github.nesfeder.offlinetasks.ui.feedback.reportingFailures
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** What the photo viewer shows. */
sealed interface PhotoViewerUiState {
    /** Nothing known about the photo yet. */
    data object Loading : PhotoViewerUiState

    /** The photo is gone: deleted, or never synced. */
    data object Gone : PhotoViewerUiState

    /**
     * The photo. Everything that changes its file needs the server: [canChange] says whether it can
     * be reached, and is false for a photo still waiting to upload, which has no file there yet.
     * [otherTasks] are where Move and Duplicate can take it; [version] changes when the file is
     * replaced, so the image is read again.
     */
    data class Showing(
        val photo: TaskPhoto,
        val canChange: Boolean,
        val otherTasks: List<Task> = emptyList(),
        val version: Int = 0,
    ) : PhotoViewerUiState {
        /** True while Storage does not have the photo's file: its upload waits, or was refused. */
        val isPending: Boolean
            get() = photo.pendingUpload != null

        /**
         * Deleting needs the server, for the file; a pending photo is only on this device, and is
         * removed at any time.
         */
        val canDelete: Boolean
            get() = isPending || canChange
    }
}

/** Something the photo viewer says once. */
sealed interface PhotoViewerMessage {
    /** The photo is deleted; the viewer closes. */
    data object Deleted : PhotoViewerMessage

    /** The photo's file on this device, at [path], to hand to the share sheet. */
    data class SharePhoto(val path: String) : PhotoViewerMessage

    /** A link to the photo, to hand to the share sheet. */
    data class ShareLink(val link: String) : PhotoViewerMessage
}

/**
 * One photo, full screen: its details, sharing it or a link to it, replacing its file, moving or
 * copying it to another task, and deleting it.
 *
 * @param failures where a failed read or Storage call is reported, to be shown as a message.
 * @param notices where a replace, move or copy says it is done.
 */
class PhotoViewerViewModel(
    private val photoId: String,
    private val repository: TaskRepository,
    private val failures: FailureReports,
    private val photoEncoder: PhotoEncoder? = null,
    private val notices: Notices = Notices(),
) : ViewModel() {
    private val messageChannel = Channel<PhotoViewerMessage>(Channel.BUFFERED)
    private val version = MutableStateFlow(0)
    private val shownDetails = MutableStateFlow<PhotoDetails?>(null)

    /** One-off messages, each delivered once. */
    val messages: Flow<PhotoViewerMessage> = messageChannel.receiveAsFlow()

    /** The Details sheet's contents while it is open. */
    val details: StateFlow<PhotoDetails?> = shownDetails.asStateFlow()

    /** The screen's state. */
    val uiState: StateFlow<PhotoViewerUiState> =
        combine(
                repository.allPhotos().reportingFailures(failures),
                repository.tasks().readingAgainAfterFailures(failures) {
                    repository.syncStatus.awaitNetworkChange()
                },
                repository.syncStatus,
                version,
            ) { snapshot, tasks, status, replaced ->
                val photos = snapshot.value ?: return@combine PhotoViewerUiState.Loading
                val photo =
                    photos.firstOrNull { it.id == photoId }
                        ?: return@combine PhotoViewerUiState.Gone
                PhotoViewerUiState.Showing(
                    photo = photo,
                    canChange =
                        status.network == AppNetworkStatus.Online && photo.pendingUpload == null,
                    otherTasks = tasks.value.orEmpty().filter { it.id != photo.taskId },
                    version = replaced,
                )
            }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                PhotoViewerUiState.Loading,
            )

    /** The device's copy of [photo], fetched first if needed. */
    suspend fun photoFile(photo: TaskPhoto): PhotoFileState = repository.loadPhotoFile(photo)

    /** Opens the Details sheet: what Storage and this device know about the file. */
    fun showDetails() {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            shownDetails.value = repository.photoDetails(showing.photo)
        }
    }

    /** Closes the Details sheet. */
    fun hideDetails() {
        shownDetails.value = null
    }

    /**
     * Hands the photo itself to the share sheet: the library's copy on the device (a pending
     * photo's queued file, or the cached copy, downloaded first if needed).
     */
    fun share() {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            val path = repository.photoFileToShare(showing.photo)
            messageChannel.send(PhotoViewerMessage.SharePhoto(path))
        }
    }

    /** Uploads the photo again after its upload failed, as soon as the network allows. */
    fun retryUpload() {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            repository.retryPendingPhoto(showing.photo.id)
        }
    }

    /** Hands a link to a 512-wide copy, for an hour, to the share sheet. */
    fun shareLink() {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            messageChannel.send(
                PhotoViewerMessage.ShareLink(repository.photoShareLink(showing.photo))
            )
        }
    }

    /** Re-encodes the image at [contentUri] and replaces the photo's file with it, in place. */
    fun replace(contentUri: String) {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        val encoder = photoEncoder ?: return
        viewModelScope.launchReporting(failures) {
            repository.replacePhoto(showing.photo, encoder.encode(contentUri))
            version.update { it + 1 }
            notices.post(Notice.PhotoReplaced)
        }
    }

    /** Moves the photo to [task]: its file, then its row. */
    fun moveTo(task: Task) {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            repository.movePhoto(showing.photo, task.id)
            notices.post(Notice.PhotoMoved(task.title))
        }
    }

    /** Adds a copy of the photo to [task]. */
    fun copyTo(task: Task) {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            repository.copyPhoto(showing.photo, task.id)
            notices.post(Notice.PhotoCopied(task.title))
        }
    }

    /**
     * Deletes the photo's file, then its row, and closes the viewer; a failure is reported and the
     * viewer stays. The repository finishes a delete it has started even if the viewer closes. A
     * photo waiting to upload is removed from the device; if its upload finished meanwhile, it
     * stays, as an uploaded photo.
     */
    fun delete() {
        val showing = uiState.value as? PhotoViewerUiState.Showing ?: return
        viewModelScope.launchReporting(failures) {
            if (!showing.isPending) {
                repository.deletePhoto(showing.photo)
            } else if (!repository.removePendingPhoto(showing.photo.id)) {
                return@launchReporting // it finished uploading first: it stays, uploaded
            }
            messageChannel.send(PhotoViewerMessage.Deleted)
        }
    }

    companion object {
        /** Builds the ViewModel for the photo [photoId] from the app's single instances. */
        fun factory(photoId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application = offlineTasksApplication
                PhotoViewerViewModel(
                    photoId = photoId,
                    repository = application.repository,
                    failures = application.failures,
                    photoEncoder = application.photoEncoder,
                    notices = application.notices,
                )
            }
        }
    }
}

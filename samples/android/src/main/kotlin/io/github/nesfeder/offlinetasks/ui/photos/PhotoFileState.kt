// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.core.net.toUri
import io.github.nesfeder.offlinetasks.data.NetworkUnavailableException
import io.github.nesfeder.offlinetasks.data.PhotoNotFoundException
import io.github.nesfeder.offlinetasks.data.ServerUnreachableException
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.TaskRepository
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/** Whether a photo can be drawn, and from where. */
sealed interface PhotoFileState {
    /** Looking on the device, or downloading. */
    data object Loading : PhotoFileState

    /** On the device, at the `file:` URI [uri]: it shows offline too. */
    data class OnDevice(val uri: String) : PhotoFileState

    /** Not on the device, and the server is out of reach to fetch it. */
    data object NotOnDevice : PhotoFileState

    /** Neither on the device nor in the bucket. */
    data object Missing : PhotoFileState

    /** Storage refused it, or something else went wrong: it cannot be shown. */
    data object Unavailable : PhotoFileState
}

/**
 * The device's copy of [photo]: a pending photo's own file, or the library's cached copy,
 * downloaded first when it is not there and the server is. It never throws: a photo that cannot be
 * shown says why in its tile, so no failure reaches the composition that asked.
 */
suspend fun TaskRepository.loadPhotoFile(photo: TaskPhoto): PhotoFileState =
    try {
        val pending = photo.pendingUpload
        if (pending != null) PhotoFileState.OnDevice(File(pending.localPath).toUri().toString())
        else PhotoFileState.OnDevice(photoUriIfCached(photo) ?: photoUri(photo))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (offline: NetworkUnavailableException) {
        PhotoFileState.NotOnDevice
    } catch (unreachable: ServerUnreachableException) {
        PhotoFileState.NotOnDevice
    } catch (missing: PhotoNotFoundException) {
        PhotoFileState.Missing
    } catch (failure: Exception) {
        PhotoFileState.Unavailable
    }

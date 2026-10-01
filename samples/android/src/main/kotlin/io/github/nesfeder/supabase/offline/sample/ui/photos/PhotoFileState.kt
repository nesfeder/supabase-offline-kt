// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.photos

import io.github.nesfeder.supabase.offline.sample.data.NetworkUnavailableException
import io.github.nesfeder.supabase.offline.sample.data.PhotoNotFoundException
import io.github.nesfeder.supabase.offline.sample.data.ServerUnreachableException
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto
import io.github.nesfeder.supabase.offline.sample.data.TaskRepository

/** Whether a photo can be drawn, and from where. */
sealed interface PhotoFileState {
    /** Looking on the device, or downloading. */
    data object Loading : PhotoFileState

    /** On the device at [path]: it shows offline too. */
    data class OnDevice(val path: String) : PhotoFileState

    /** Not on the device, and the server is out of reach to fetch it. */
    data object NotOnDevice : PhotoFileState

    /** Neither on the device nor in the bucket. */
    data object Missing : PhotoFileState
}

/** The device's copy of [photo], downloading it first when it is not there and the server is. */
suspend fun TaskRepository.loadPhotoFile(photo: TaskPhoto): PhotoFileState {
    cachedPhotoFile(photo)?.let {
        return PhotoFileState.OnDevice(it.path)
    }
    return try {
        PhotoFileState.OnDevice(downloadPhotoFile(photo).path)
    } catch (offline: NetworkUnavailableException) {
        PhotoFileState.NotOnDevice
    } catch (unreachable: ServerUnreachableException) {
        PhotoFileState.NotOnDevice
    } catch (missing: PhotoNotFoundException) {
        PhotoFileState.Missing
    }
}

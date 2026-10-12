// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.ui.navigation.SharedElement
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys

/**
 * A photo, cropped to fill its tile, drawn from the device's copy. [canReachServer] is a key: when
 * the server comes back in reach, a photo that was not on the device is fetched. A photo still
 * waiting to upload is drawn from its own file, with a strip saying where its upload stands.
 *
 * @param showOnDeviceMark whether to mark a photo that is on the device ("available offline").
 */
@Composable
fun PhotoThumbnail(
    photo: TaskPhoto,
    loadFile: suspend (TaskPhoto) -> PhotoFileState,
    canReachServer: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showOnDeviceMark: Boolean = false,
) {
    // Read again once the upload is done: the device's own copy then gives way to the cache's.
    val isPending = photo.pendingUpload != null
    val file by
        produceState<PhotoFileState>(PhotoFileState.Loading, photo.id, canReachServer, isPending) {
            value = loadFile(photo)
        }
    val shape = MaterialTheme.shapes.large
    Box(
        modifier =
            modifier
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = file) {
            is PhotoFileState.OnDevice -> {
                SharedElement(SharedKeys.photo(photo.id), Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = state.uri,
                        contentDescription = stringResource(R.string.photo_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                val pending = photo.pendingUpload
                if (pending != null) {
                    PendingUploadMark(pending, Modifier.align(Alignment.BottomCenter))
                } else if (showOnDeviceMark) {
                    Icon(
                        painter = painterResource(R.drawable.ic_offline_pin_filled),
                        contentDescription = stringResource(R.string.photo_on_device),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
            PhotoFileState.NotOnDevice ->
                Icon(
                    painter = painterResource(R.drawable.ic_hide_image),
                    contentDescription = stringResource(R.string.photo_not_on_device),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            PhotoFileState.Missing ->
                Icon(
                    painter = painterResource(R.drawable.ic_report),
                    contentDescription = stringResource(R.string.photo_missing),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            PhotoFileState.Unavailable ->
                Icon(
                    painter = painterResource(R.drawable.ic_report),
                    contentDescription = stringResource(R.string.photo_unavailable),
                    tint = MaterialTheme.colorScheme.error,
                )
            PhotoFileState.Loading -> Unit
        }
    }
}

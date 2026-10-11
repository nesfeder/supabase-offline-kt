// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.PendingPhotoState
import io.github.nesfeder.offlinetasks.data.PendingUpload

/**
 * The strip across the foot of a photo still in the app's pending-photo queue: a mark and a word
 * for where its upload stands, as a task waiting to sync carries its cloud mark, and the progress
 * while it uploads.
 */
@Composable
fun PendingUploadMark(upload: PendingUpload, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val failed = upload.state == PendingPhotoState.Failed
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .background(if (failed) colors.errorContainer else colors.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                painter = painterResource(upload.state.icon),
                contentDescription = null,
                tint = if (failed) colors.onErrorContainer else colors.primary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(upload.state.label),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) colors.onErrorContainer else colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (upload.state == PendingPhotoState.Uploading) {
            val fraction = upload.progress
            if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth())
        }
    }
}

/** Why a pending photo's upload failed, and how many tries it has had, in a sentence or two. */
@Composable
fun failedUploadText(upload: PendingUpload): String =
    pluralStringResource(
        R.plurals.photo_upload_failed,
        upload.attempts,
        upload.attempts,
        upload.failureMessage.orEmpty(),
    )

/** The word for where a pending photo's upload stands. */
val PendingPhotoState.label: Int
    get() =
        when (this) {
            PendingPhotoState.Waiting -> R.string.photo_pending_waiting
            PendingPhotoState.Uploading -> R.string.photo_pending_uploading
            PendingPhotoState.Failed -> R.string.photo_pending_failed
        }

/** The mark beside that word: the cloud a waiting task carries, or a warning once it failed. */
private val PendingPhotoState.icon: Int
    get() =
        when (this) {
            PendingPhotoState.Waiting -> R.drawable.ic_cloud_upload
            PendingPhotoState.Uploading -> R.drawable.ic_cloud_sync
            PendingPhotoState.Failed -> R.drawable.ic_sync_problem
        }

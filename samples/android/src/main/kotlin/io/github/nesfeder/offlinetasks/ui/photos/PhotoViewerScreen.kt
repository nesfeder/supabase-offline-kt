// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.PhotoDetails
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.ui.components.shareJpeg
import io.github.nesfeder.offlinetasks.ui.components.shareText
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.status.relativeTime
import kotlinx.coroutines.launch
import io.github.nesfeder.offlinetasks.ui.navigation.SharedElement
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys

/** The largest zoom a pinch reaches. */
private const val MAX_ZOOM = 5f

/** The photo viewer destination, wired to its ViewModel; it closes when the photo goes. */
@Composable
fun PhotoViewerRoute(
    photoId: String,
    onClose: () -> Unit,
    viewModel: PhotoViewerViewModel =
        viewModel(key = photoId, factory = PhotoViewerViewModel.factory(photoId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pickReplacement =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let { viewModel.replace(it.toString()) }
        }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                PhotoViewerMessage.Deleted -> onClose()
                is PhotoViewerMessage.SharePhoto -> launch { context.shareJpeg(message.path) }
                is PhotoViewerMessage.ShareLink -> context.shareText(message.link)
            }
        }
    }
    LaunchedEffect(state) { if (state == PhotoViewerUiState.Gone) onClose() }
    PhotoViewerScreen(
        state = state,
        onClose = onClose,
        onDelete = viewModel::delete,
        loadPhotoFile = viewModel::photoFile,
        actions =
            PhotoActions(
                onDetails = viewModel::showDetails,
                onShare = viewModel::share,
                onShareLink = viewModel::shareLink,
                onReplace = {
                    pickReplacement.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
                },
                onMoveTo = viewModel::moveTo,
                onCopyTo = viewModel::copyTo,
            ),
    )
    details?.let { shown -> PhotoDetailsSheet(shown, onDismiss = viewModel::hideDetails) }
}

/** What the viewer's bar and menu can ask for, beside closing and deleting. */
class PhotoActions(
    val onDetails: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onShareLink: () -> Unit = {},
    val onReplace: () -> Unit = {},
    val onMoveTo: (Task) -> Unit = {},
    val onCopyTo: (Task) -> Unit = {},
)

/** Where the task picker takes the photo. */
private enum class PhotoDestination {
    Move,
    Copy,
}

/**
 * A photo, full bleed on black: pinch to zoom, tap to show or hide the bar, which fades. The image
 * is the far end of the thumbnail's shared-element transition.
 */
@Composable
fun PhotoViewerScreen(
    state: PhotoViewerUiState,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
    modifier: Modifier = Modifier,
    actions: PhotoActions = PhotoActions(),
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var picking by remember { mutableStateOf<PhotoDestination?>(null) }
    LightStatusBarIcons()
    Box(
        modifier =
            modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            }
    ) {
        if (state is PhotoViewerUiState.Showing) {
            ZoomablePhoto(state.photo, state.version, loadPhotoFile)
        }
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.defaultEffectsSpec()),
        ) {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    val showing = state as? PhotoViewerUiState.Showing
                    IconButton(onClick = actions.onShare, enabled = showing != null) {
                        Icon(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.photo_share),
                        )
                    }
                    IconButton(onClick = actions.onDetails, enabled = showing?.canChange == true) {
                        Icon(
                            painter = painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.photo_details),
                        )
                    }
                    IconButton(onClick = onDelete, enabled = showing?.canDelete == true) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.photo_delete),
                        )
                    }
                    PhotoMenu(
                        canChange = showing?.canChange == true,
                        hasOtherTasks = showing?.otherTasks?.isNotEmpty() == true,
                        actions = actions,
                        onPick = { picking = it },
                    )
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                modifier =
                    Modifier.fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                            )
                        ),
            )
        }
        AppSnackbarHost(Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        val showing = state as? PhotoViewerUiState.Showing
        val destination = picking
        if (showing != null && destination != null) {
            TaskPicker(
                destination = destination,
                tasks = showing.otherTasks,
                onPick = { task ->
                    picking = null
                    when (destination) {
                        PhotoDestination.Move -> actions.onMoveTo(task)
                        PhotoDestination.Copy -> actions.onCopyTo(task)
                    }
                },
                onDismiss = { picking = null },
            )
        }
        if (state is PhotoViewerUiState.Showing && !state.canDelete && chromeVisible) {
            Text(
                text = stringResource(R.string.photo_delete_offline),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }
    }
}

/** The viewer's menu: everything that changes or shares the photo's file beyond the bar. */
@Composable
private fun PhotoMenu(
    canChange: Boolean,
    hasOtherTasks: Boolean,
    actions: PhotoActions,
    onPick: (PhotoDestination) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.action_more))
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        MenuItem(R.string.photo_share_link, canChange) {
            expanded = false
            actions.onShareLink()
        }
        MenuItem(R.string.photo_replace, canChange) {
            expanded = false
            actions.onReplace()
        }
        MenuItem(R.string.photo_move, canChange && hasOtherTasks) {
            expanded = false
            onPick(PhotoDestination.Move)
        }
        MenuItem(R.string.photo_copy, canChange && hasOtherTasks) {
            expanded = false
            onPick(PhotoDestination.Copy)
        }
    }
}

@Composable
private fun MenuItem(label: Int, enabled: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(stringResource(label)) }, enabled = enabled, onClick = onClick)
}

/** The tasks a photo can be moved or copied to, as a dialog of choices. */
@Composable
private fun TaskPicker(
    destination: PhotoDestination,
    tasks: List<Task>,
    onPick: (Task) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    when (destination) {
                        PhotoDestination.Move -> R.string.photo_move
                        PhotoDestination.Copy -> R.string.photo_copy
                    }
                )
            )
        },
        text = {
            LazyColumn {
                items(tasks, key = { it.id }) { task ->
                    TextButton(onClick = { onPick(task) }, modifier = Modifier.fillMaxWidth()) {
                        Text(task.title.ifBlank { stringResource(R.string.task_untitled) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** The Details sheet: what Storage says about the file, its two links, and the copy here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoDetailsSheet(details: PhotoDetails, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.photo_details), style = MaterialTheme.typography.titleLarge)
            DetailLine(
                R.string.photo_details_in_bucket,
                stringResource(
                    if (details.existsInBucket) R.string.photo_details_exists
                    else R.string.photo_details_missing
                ),
            )
            if (details.existsInBucket) {
                DetailLine(
                    R.string.photo_details_size,
                    stringResource(R.string.photo_details_bytes, details.sizeBytes),
                )
                details.contentType?.let { DetailLine(R.string.photo_details_type, it) }
                details.updatedAt?.let { DetailLine(R.string.photo_details_updated, relativeTime(it)) }
                details.etag?.let { DetailLine(R.string.photo_details_etag, it) }
            }
            DetailLine(R.string.photo_details_public_url, details.publicUrl)
            DetailLine(R.string.photo_details_authenticated_url, details.authenticatedUrl)
            val cached = details.cachedFile
            DetailLine(
                R.string.photo_details_on_device,
                if (cached == null) stringResource(R.string.photo_details_not_on_device)
                else
                    stringResource(
                        R.string.photo_details_cached,
                        cached.sizeBytes,
                        relativeTime(cached.fetchedAt),
                    ),
            )
        }
    }
}

@Composable
private fun DetailLine(label: Int, value: String) {
    Column {
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ZoomablePhoto(
    photo: TaskPhoto,
    version: Int,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
) {
    // Read again when the file moves or is replaced: the cached copy went with the old one.
    val file by
        produceState<PhotoFileState>(PhotoFileState.Loading, photo.objectPath, version) {
            value = loadPhotoFile(photo)
        }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val transformState = rememberTransformableState { _, zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        offset = clampedPan(offset + panChange, scale, size)
    }
    val onDevice = file as? PhotoFileState.OnDevice ?: return
    SharedElement(SharedKeys.photo(photo.id), Modifier.fillMaxSize()) {
        AsyncImage(
            // A replaced file keeps its path, so the version keeps the old image out of memory.
            model =
                ImageRequest.Builder(LocalContext.current)
                    .data(onDevice.uri)
                    .memoryCacheKey("${onDevice.uri}#$version")
                    .build(),
            contentDescription = stringResource(R.string.photo_description),
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize()
                    .onSizeChanged { size = it }
                    .transformable(transformState)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
        )
    }
}

/**
 * [offset] kept within what a zoom of [scale] can reveal: the zoomed photo's edges never move in
 * past the edges of the [size] it fills, so it cannot be panned off screen.
 */
private fun clampedPan(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1) / 2
    val maxY = size.height * (scale - 1) / 2
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** White status bar icons while this screen shows, on its black, whatever the theme. */
@Composable
private fun LightStatusBarIcons() {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    DisposableEffect(window, view) {
        val controller = WindowCompat.getInsetsController(window, view)
        val previous = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose { controller.isAppearanceLightStatusBars = previous }
    }
}

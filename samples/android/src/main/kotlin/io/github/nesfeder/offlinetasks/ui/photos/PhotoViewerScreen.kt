// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.photos

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.navigation.SharedElement
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys
import java.io.File

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
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                PhotoViewerMessage.Deleted -> onClose()
            }
        }
    }
    LaunchedEffect(state) { if (state == PhotoViewerUiState.Gone) onClose() }
    PhotoViewerScreen(
        state = state,
        onClose = onClose,
        onDelete = viewModel::delete,
        loadPhotoFile = viewModel::photoFile,
    )
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
) {
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    LightStatusBarIcons()
    Box(
        modifier =
            modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
                detectTapGestures(onTap = { chromeVisible = !chromeVisible })
            }
    ) {
        if (state is PhotoViewerUiState.Showing) {
            ZoomablePhoto(state.photo, loadPhotoFile)
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
                    IconButton(
                        onClick = onDelete,
                        enabled = (state as? PhotoViewerUiState.Showing)?.canDelete == true,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.photo_delete),
                        )
                    }
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

@Composable
private fun ZoomablePhoto(photo: TaskPhoto, loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState) {
    val file by
        produceState<PhotoFileState>(PhotoFileState.Loading, photo.id) {
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
            model = File(onDevice.path),
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

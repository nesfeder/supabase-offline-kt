// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.photos

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto
import io.github.nesfeder.supabase.offline.sample.ui.components.EmptyState

/** The Photos destination: the grid wired to its ViewModel. */
@Composable
fun PhotosRoute(
    onOpenPhoto: (photoId: String) -> Unit,
    viewModel: PhotosViewModel = viewModel(factory = PhotosViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PhotosScreen(state = state, onOpenPhoto = onOpenPhoto, loadPhotoFile = viewModel::photoFile)
}

/** Every photo across tasks, newest first, each marked when it is available offline. */
@Composable
fun PhotosScreen(
    state: PhotosUiState,
    onOpenPhoto: (photoId: String) -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.photos_title)) },
                subtitle =
                    (state as? PhotosUiState.Loaded)?.let { loaded ->
                        {
                            Text(
                                if (loaded.canReachServer) {
                                    pluralStringResource(
                                        R.plurals.photos_count,
                                        loaded.photos.size,
                                        loaded.photos.size,
                                    )
                                } else {
                                    stringResource(R.string.photos_offline_subtitle)
                                }
                            )
                        }
                    },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        when (state) {
            is PhotosUiState.NoDataYet ->
                EmptyState(
                    icon = R.drawable.ic_cloud_off,
                    title =
                        stringResource(
                            if (state.isLoading) R.string.photos_loading_title
                            else R.string.photos_no_data_title
                        ),
                    body =
                        stringResource(
                            if (state.isLoading) R.string.photos_loading_body
                            else R.string.photos_no_data_body
                        ),
                    isLoading = state.isLoading,
                    modifier = Modifier.padding(padding),
                )
            is PhotosUiState.Loaded ->
                if (state.photos.isEmpty()) {
                    EmptyState(
                        icon = R.drawable.ic_photo_library,
                        title = stringResource(R.string.photos_empty_title),
                        body = stringResource(R.string.photos_empty_body),
                        modifier = Modifier.padding(padding),
                    )
                } else {
                    PhotoGrid(state, onOpenPhoto, loadPhotoFile, Modifier.padding(padding))
                }
        }
    }
}

@Composable
private fun PhotoGrid(
    state: PhotosUiState.Loaded,
    onOpenPhoto: (photoId: String) -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
    modifier: Modifier = Modifier,
) {
    val motion = MaterialTheme.motionScheme
    LazyVerticalGrid(
        columns = GridCells.Adaptive(120.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.photos, key = { it.id }) { photo ->
            PhotoThumbnail(
                photo = photo,
                loadFile = loadPhotoFile,
                canReachServer = state.canReachServer,
                onClick = { onOpenPhoto(photo.id) },
                showOnDeviceMark = true,
                modifier =
                    Modifier.animateItem(
                            fadeInSpec = motion.defaultEffectsSpec(),
                            placementSpec = motion.defaultSpatialSpec(),
                            fadeOutSpec = motion.defaultEffectsSpec(),
                        )
                        .aspectRatio(1f),
            )
        }
    }
}

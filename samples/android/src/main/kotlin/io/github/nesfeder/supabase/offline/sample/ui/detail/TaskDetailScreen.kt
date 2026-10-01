// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.detail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.supabase.offline.sample.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.supabase.offline.sample.data.TaskPhoto
import io.github.nesfeder.supabase.offline.sample.ui.components.EmptyState
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedBounds
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedElement
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedKeys
import io.github.nesfeder.supabase.offline.sample.ui.newtask.LengthCounter
import io.github.nesfeder.supabase.offline.sample.ui.photos.PhotoFileState
import io.github.nesfeder.supabase.offline.sample.ui.photos.PhotoThumbnail

/** The task detail destination: the screen wired to its ViewModel and the photo picker. */
@Composable
fun TaskDetailRoute(
    taskId: String,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (photoId: String) -> Unit,
    viewModel: TaskDetailViewModel =
        viewModel(key = taskId, factory = TaskDetailViewModel.factory(taskId)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val pickPhoto =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let { viewModel.attachPhoto(it.toString()) }
        }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                TaskDetailMessage.Deleted -> onBack()
                TaskDetailMessage.PhotoUnreadable ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.photo_unreadable))
                TaskDetailMessage.UploadFailed ->
                    snackbarHostState.showSnackbar(
                        resources.getString(R.string.photo_upload_failed)
                    )
            }
        }
    }
    TaskDetailScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        showBackButton = showBackButton,
        onBack = onBack,
        onTitleChange = viewModel::onTitleChange,
        onNotesChange = viewModel::onNotesChange,
        onDoneChange = viewModel::setDone,
        onAddPhoto = {
            pickPhoto.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
        },
        onOpenPhoto = onOpenPhoto,
        onDelete = viewModel::delete,
        loadPhotoFile = viewModel::photoFile,
        taskId = taskId,
    )
}

/**
 * One task, to edit: a flexible app bar whose title the list row's title flies into, the fields,
 * the done row, the photos with their uploads, and a floating toolbar of actions.
 */
@Composable
fun TaskDetailScreen(
    taskId: String,
    state: TaskDetailUiState,
    snackbarHostState: SnackbarHostState,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDoneChange: (Boolean) -> Unit,
    onAddPhoto: () -> Unit,
    onOpenPhoto: (photoId: String) -> Unit,
    onDelete: () -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    SharedBounds(SharedKeys.taskContainer(taskId), modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                // The medium flexible app bar, built from the call that says which of its two
                // titles is which: the row's title flies into the expanded one only, since one
                // key on both would draw it twice.
                val editing = state as? TaskDetailUiState.Editing
                TwoRowsTopAppBar(
                    title = { expanded ->
                        val title =
                            when {
                                editing == null -> ""
                                editing.title.isBlank() -> stringResource(R.string.task_untitled)
                                else -> editing.title
                            }
                        if (expanded) {
                            SharedElement(SharedKeys.taskTitle(taskId)) {
                                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    subtitle =
                        editing?.let { loaded ->
                            { _ -> Text(stringResource(loaded.syncState.label)) }
                        },
                    collapsedHeight = TopAppBarDefaults.MediumAppBarCollapsedHeight,
                    expandedHeight =
                        TopAppBarDefaults.MediumFlexibleAppBarWithSubtitleExpandedHeight,
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_back),
                                    contentDescription = stringResource(R.string.action_back),
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            when (state) {
                TaskDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding))
                TaskDetailUiState.Gone ->
                    EmptyState(
                        icon = R.drawable.ic_checklist,
                        title = stringResource(R.string.detail_gone_title),
                        body = stringResource(R.string.detail_gone_body),
                        modifier = Modifier.padding(padding),
                    )
                is TaskDetailUiState.Editing ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        TaskFields(
                            state = state,
                            onTitleChange = onTitleChange,
                            onNotesChange = onNotesChange,
                            onDoneChange = onDoneChange,
                            onOpenPhoto = onOpenPhoto,
                            loadPhotoFile = loadPhotoFile,
                        )
                        HorizontalFloatingToolbar(
                            expanded = true,
                            modifier =
                                Modifier.align(Alignment.BottomCenter)
                                    .navigationBarsPadding()
                                    .offset(y = -FloatingToolbarDefaults.ScreenOffset),
                        ) {
                            FilledIconToggleButton(
                                checked = state.isDone,
                                onCheckedChange = onDoneChange,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.detail_done),
                                )
                            }
                            IconButton(onClick = onAddPhoto, enabled = state.canChangePhotos) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_add_photo_alternate),
                                    contentDescription = stringResource(R.string.detail_add_photo),
                                )
                            }
                            IconButton(onClick = { confirmingDelete = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = stringResource(R.string.detail_delete),
                                )
                            }
                        }
                    }
            }
        }
    }
    if (confirmingDelete) {
        val canDeleteFiles = (state as? TaskDetailUiState.Editing)?.canChangePhotos == true
        val hasPhotos = (state as? TaskDetailUiState.Editing)?.photos?.isNotEmpty() == true
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.detail_delete_title)) },
            text = {
                Text(
                    stringResource(
                        when {
                            !hasPhotos -> R.string.detail_delete_body
                            canDeleteFiles -> R.string.detail_delete_body_photos
                            else -> R.string.detail_delete_body_photos_offline
                        }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        onDelete()
                    }
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun TaskFields(
    state: TaskDetailUiState.Editing,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDoneChange: (Boolean) -> Unit,
    onOpenPhoto: (photoId: String) -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
) {
    Column(
        modifier =
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.title,
            onValueChange = onTitleChange,
            label = { Text(stringResource(R.string.field_title)) },
            isError = state.isTitleBlank,
            supportingText = {
                if (state.isTitleBlank) {
                    Text(stringResource(R.string.field_title_required))
                } else {
                    LengthCounter(state.title.length, TASK_TITLE_MAX_LENGTH)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.field_notes)) },
            supportingText = { LengthCounter(state.notes.length, TASK_NOTES_MAX_LENGTH) },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        ListItem(
            checked = state.isDone,
            onCheckedChange = onDoneChange,
            leadingContent = { Checkbox(checked = state.isDone, onCheckedChange = null) },
            supportingContent = { Text(stringResource(R.string.detail_done_supporting)) },
        ) {
            Text(stringResource(R.string.detail_done))
        }
        PhotosSection(state, onOpenPhoto, loadPhotoFile)
    }
}

/** The photos row: thumbnails, then a wavy progress tile per upload. */
@Composable
private fun PhotosSection(
    state: TaskDetailUiState.Editing,
    onOpenPhoto: (photoId: String) -> Unit,
    loadPhotoFile: suspend (TaskPhoto) -> PhotoFileState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.detail_photos),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                stringResource(
                    if (state.canChangePhotos) R.string.detail_photos_supporting
                    else R.string.detail_photos_offline
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.photos.isEmpty() && state.uploads.isEmpty()) return@Column
        val motion = MaterialTheme.motionScheme
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(state.photos, key = { it.id }) { photo ->
                PhotoThumbnail(
                    photo = photo,
                    loadFile = loadPhotoFile,
                    canReachServer = state.canChangePhotos,
                    onClick = { onOpenPhoto(photo.id) },
                    modifier =
                        Modifier.animateItem(
                                fadeInSpec = motion.defaultEffectsSpec(),
                                placementSpec = motion.defaultSpatialSpec(),
                                fadeOutSpec = motion.defaultEffectsSpec(),
                            )
                            .size(104.dp),
                )
            }
            items(state.uploads, key = { "upload-${it.id}" }) { upload ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.animateItem().size(104.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        LinearWavyProgressIndicator(
                            progress = { upload.fraction },
                            modifier = Modifier.width(80.dp).fillMaxHeight(0.2f),
                        )
                    }
                }
            }
        }
    }
}

private val TaskSyncState.label: Int
    get() =
        when (this) {
            TaskSyncState.Synced -> R.string.detail_sync_synced
            TaskSyncState.SavedOnDevice -> R.string.detail_sync_saved_on_device
            TaskSyncState.WaitingToSync -> R.string.detail_sync_waiting
            TaskSyncState.Syncing -> R.string.detail_sync_syncing
        }

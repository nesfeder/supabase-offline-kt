// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TwoRowsTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TaskPhoto
import io.github.nesfeder.offlinetasks.data.characterCount
import io.github.nesfeder.offlinetasks.ui.components.EmptyState
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.navigation.SharedBounds
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys
import io.github.nesfeder.offlinetasks.ui.navigation.ShownTask
import io.github.nesfeder.offlinetasks.ui.newtask.LengthCounter
import io.github.nesfeder.offlinetasks.ui.photos.PhotoFileState
import io.github.nesfeder.offlinetasks.ui.photos.PhotoThumbnail

/**
 * The task detail destination: the screen wired to its ViewModel and the photo picker.
 *
 * @param shown the task as the list showed it, drawn until the device's copy is read.
 */
@Composable
fun TaskDetailRoute(
    taskId: String,
    shown: ShownTask?,
    showBackButton: Boolean,
    onBack: () -> Unit,
    onOpenPhoto: (photoId: String) -> Unit,
    viewModel: TaskDetailViewModel =
        viewModel(key = taskId, factory = TaskDetailViewModel.factory(taskId, shown)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickPhoto =
        rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            uri?.let { viewModel.attachPhoto(it.toString()) }
        }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            when (message) {
                TaskDetailMessage.Deleted -> onBack()
            }
        }
    }
    TaskDetailScreen(
        state = state,
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
 * One task, to edit: a flexible app bar with its title, the fields, the done row, the photos with
 * their uploads, and a floating toolbar of actions. The whole screen grows out of the list row.
 * While the task is read it is drawn as the list showed it, with the fields not yet editable.
 */
@Composable
fun TaskDetailScreen(
    taskId: String,
    state: TaskDetailUiState,
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
                // The medium flexible app bar, built from the call that takes one title per row
                // height, so the expanded and collapsed titles can wrap differently.
                val editing = state as? TaskDetailUiState.Editing
                val fields = state.fields()
                TwoRowsTopAppBar(
                    title = { expanded ->
                        val title =
                            when {
                                fields == null -> ""
                                fields.title.isBlank() -> stringResource(R.string.task_untitled)
                                else -> fields.title
                            }
                        Text(
                            title,
                            maxLines = if (expanded) 2 else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
            snackbarHost = { AppSnackbarHost() },
        ) { padding ->
            if (state == TaskDetailUiState.Gone) {
                EmptyState(
                    icon = R.drawable.ic_checklist,
                    title = stringResource(R.string.detail_gone_title),
                    body = stringResource(R.string.detail_gone_body),
                    modifier = Modifier.padding(padding),
                )
                return@Scaffold
            }
            // Loading draws the same screen as Editing, from what the list showed.
            val editing = state as? TaskDetailUiState.Editing
            val fields = state.fields()
            Box(Modifier.fillMaxSize().padding(padding)) {
                TaskFields(
                    fields = fields,
                    editable = editing != null,
                    onTitleChange = onTitleChange,
                    onNotesChange = onNotesChange,
                    onDoneChange = onDoneChange,
                ) {
                    if (editing != null) PhotosSection(editing, onOpenPhoto, loadPhotoFile)
                }
                HorizontalFloatingToolbar(
                    expanded = true,
                    modifier =
                        Modifier.align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .offset(y = -FloatingToolbarDefaults.ScreenOffset),
                ) {
                    FilledIconToggleButton(
                        checked = fields?.isDone == true,
                        onCheckedChange = onDoneChange,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = stringResource(R.string.detail_done),
                        )
                    }
                    IconButton(onClick = onAddPhoto, enabled = editing?.canChangePhotos == true) {
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

/** The task's text and done flag, as the fields show them. */
private data class TaskFieldValues(
    val title: String,
    val notes: String,
    val isTitleBlank: Boolean,
    val isDone: Boolean,
)

/** What the fields show: the task as edited, as the list showed it, or nothing. */
private fun TaskDetailUiState.fields(): TaskFieldValues? =
    when (this) {
        is TaskDetailUiState.Editing -> TaskFieldValues(title, notes, isTitleBlank, isDone)
        is TaskDetailUiState.Loading ->
            TaskFieldValues(
                title = shown?.title.orEmpty(),
                notes = shown?.notes.orEmpty(),
                isTitleBlank = false,
                isDone = shown?.isDone ?: false,
            )
        TaskDetailUiState.Gone -> null
    }

/** The fields, the done row, then [photos]; read-only until the task has been read. */
@Composable
private fun TaskFields(
    fields: TaskFieldValues?,
    editable: Boolean,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onDoneChange: (Boolean) -> Unit,
    photos: @Composable () -> Unit = {},
) {
    val state = fields ?: return
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
            readOnly = !editable,
            label = { Text(stringResource(R.string.field_title)) },
            isError = state.isTitleBlank,
            supportingText = {
                if (state.isTitleBlank) {
                    Text(stringResource(R.string.field_title_required))
                } else {
                    LengthCounter(characterCount(state.title), TASK_TITLE_MAX_LENGTH)
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.notes,
            onValueChange = onNotesChange,
            readOnly = !editable,
            label = { Text(stringResource(R.string.field_notes)) },
            supportingText = { LengthCounter(characterCount(state.notes), TASK_NOTES_MAX_LENGTH) },
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
        photos()
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

private val TaskDetailSyncState.label: Int
    get() =
        when (this) {
            TaskDetailSyncState.Synced -> R.string.detail_sync_synced
            TaskDetailSyncState.SavedOnDevice -> R.string.detail_sync_saved_on_device
            TaskDetailSyncState.WaitingToSync -> R.string.detail_sync_waiting
            TaskDetailSyncState.Syncing -> R.string.detail_sync_syncing
        }

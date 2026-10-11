// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.newtask

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.offlinetasks.data.characterCount
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbarHost
import io.github.nesfeder.offlinetasks.ui.navigation.SharedBounds
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys

/** The New-task destination: the form wired to its ViewModel; it closes once the task is added. */
@Composable
fun NewTaskRoute(
    onClose: () -> Unit,
    viewModel: NewTaskViewModel = viewModel(factory = NewTaskViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val close = {
        // The keyboard belongs to this screen; it must not stay up over the list.
        keyboard?.hide()
        onClose()
    }
    LaunchedEffect(state) { if (state is NewTaskUiState.Added) close() }
    // The system photo picker, several at once; it needs no storage permission.
    val pickPhotos =
        rememberLauncherForActivityResult(PickMultipleVisualMedia()) { uris ->
            viewModel.onPhotosPicked(uris.map { it.toString() })
        }
    NewTaskScreen(
        state = state,
        onTitleChange = viewModel::onTitleChange,
        onNotesChange = viewModel::onNotesChange,
        onNeedsConnectionChange = viewModel::onNeedsConnectionChange,
        onAdd = viewModel::add,
        onClose = close,
        onAddPhotos = { pickPhotos.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
        onRemovePhoto = viewModel::onRemovePhoto,
    )
}

/**
 * The New-task form, full screen. It is the far end of the New-task button's container transform:
 * the button grows into it, and it shrinks back into the button on back or Add.
 */
@Composable
fun NewTaskScreen(
    state: NewTaskUiState,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onNeedsConnectionChange: (Boolean) -> Unit = {},
    onAddPhotos: () -> Unit = {},
    onRemovePhoto: (contentUri: String) -> Unit = {},
) {
    val (title, notes, canAdd) =
        when (state) {
            is NewTaskUiState.Editing -> Triple(state.title, state.notes, state.canAdd)
            is NewTaskUiState.Added -> Triple(state.title, state.notes, false)
        }
    val needsConnection = (state as? NewTaskUiState.Editing)?.needsConnection == true
    val photos = (state as? NewTaskUiState.Editing)?.photos.orEmpty()
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { titleFocus.requestFocus() }
    SharedBounds(SharedKeys.NEW_TASK, modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.new_task)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(
                                painter = painterResource(R.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_close),
                            )
                        }
                    },
                )
            },
            snackbarHost = { AppSnackbarHost() },
        ) { padding ->
            Column(
                // The keyboard's inset already includes the navigation bar's, which the
                // scaffold's padding holds: consumed here, it is not counted twice.
                modifier =
                    Modifier.fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding)
                        .imePadding()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        label = { Text(stringResource(R.string.field_title)) },
                        supportingText = {
                            LengthCounter(characterCount(title), TASK_TITLE_MAX_LENGTH)
                        },
                        singleLine = true,
                        keyboardOptions =
                            KeyboardOptions(
                                capitalization = KeyboardCapitalization.Sentences,
                                imeAction = ImeAction.Next,
                            ),
                        modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    )
                    OutlinedTextField(
                        value = notes,
                        onValueChange = onNotesChange,
                        label = { Text(stringResource(R.string.field_notes)) },
                        supportingText = {
                            LengthCounter(characterCount(notes), TASK_NOTES_MAX_LENGTH)
                        },
                        minLines = 3,
                        keyboardOptions =
                            KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    NeedsConnectionSwitch(needsConnection, onNeedsConnectionChange)
                    PickedPhotos(photos, onAddPhotos, onRemovePhoto)
                }
                Button(
                    onClick = onAdd,
                    enabled = canAdd,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.action_add))
                }
            }
        }
    }
}

/**
 * "Needs a connection": off, the task is saved on this device when the server cannot be reached and
 * synced later; on, it is sent now or not at all.
 */
@Composable
private fun NeedsConnectionSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .toggleable(checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.new_task_needs_connection))
            Text(
                stringResource(R.string.new_task_needs_connection_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The photos picked for the new task, each with a button that takes it off, and Add photo. They are
 * filed with the task when it is added, and wait on the device until they upload.
 */
@Composable
private fun PickedPhotos(
    photos: List<String>,
    onAddPhotos: () -> Unit,
    onRemovePhoto: (contentUri: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.detail_photos))
                Text(
                    stringResource(R.string.new_task_photos_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalButton(onClick = onAddPhotos) {
                Icon(painterResource(R.drawable.ic_add_photo_alternate), contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.detail_add_photo))
            }
        }
        if (photos.isEmpty()) return@Column
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos, key = { it }) { contentUri ->
                Box(Modifier.animateItem().size(88.dp)) {
                    AsyncImage(
                        model = contentUri,
                        contentDescription = stringResource(R.string.photo_description),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                    )
                    FilledTonalIconButton(
                        onClick = { onRemovePhoto(contentUri) },
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.new_task_remove_photo),
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "12 / 200": how much of a field's limit is used, in characters as the server counts them. */
@Composable
fun LengthCounter(length: Int, limit: Int) {
    Text(stringResource(R.string.field_counter, length, limit))
}

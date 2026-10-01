// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.newtask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.data.TASK_NOTES_MAX_LENGTH
import io.github.nesfeder.supabase.offline.sample.data.TASK_TITLE_MAX_LENGTH
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedBounds
import io.github.nesfeder.supabase.offline.sample.ui.navigation.SharedKeys

/** The New-task destination: the form wired to its ViewModel; it closes once the task is added. */
@Composable
fun NewTaskRoute(
    onClose: () -> Unit,
    viewModel: NewTaskViewModel = viewModel(factory = NewTaskViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state is NewTaskUiState.Added) onClose() }
    NewTaskScreen(
        state = state,
        onTitleChange = viewModel::onTitleChange,
        onNotesChange = viewModel::onNotesChange,
        onAdd = viewModel::add,
        onClose = onClose,
    )
}

/**
 * The New-task form, full screen. It is the far end of the New-task button's container
 * transform: the button grows into it, and it shrinks back into the button on back or Add.
 */
@Composable
fun NewTaskScreen(
    state: NewTaskUiState,
    onTitleChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onAdd: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (title, notes, canAdd) =
        when (state) {
            is NewTaskUiState.Editing -> Triple(state.title, state.notes, state.canAdd)
            is NewTaskUiState.Added -> Triple(state.title, state.notes, false)
        }
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
            }
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp),
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
                        supportingText = { LengthCounter(title.length, TASK_TITLE_MAX_LENGTH) },
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
                        supportingText = { LengthCounter(notes.length, TASK_NOTES_MAX_LENGTH) },
                        minLines = 3,
                        keyboardOptions =
                            KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth(),
                    )
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

/** "12 / 200": how much of a field's limit is used. */
@Composable
fun LengthCounter(length: Int, limit: Int) {
    Text(stringResource(R.string.field_counter, length, limit))
}

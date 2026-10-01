// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.ui.components.EmptyState

/** The detail pane beside the task list before a task is chosen, on a wide window. */
@Composable
fun DetailPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        EmptyState(
            icon = R.drawable.ic_checklist,
            title = stringResource(R.string.detail_placeholder_title),
            body = stringResource(R.string.detail_placeholder_body),
        )
    }
}

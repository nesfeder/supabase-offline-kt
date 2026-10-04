// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.nesfeder.offlinetasks.R

/** The whole app when `local.properties` names no Supabase project: how to add one. */
@Composable
fun NotConfiguredScreen(modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxSize()) {
        EmptyState(
            icon = R.drawable.ic_cloud_off,
            title = stringResource(R.string.not_configured_title),
            body = stringResource(R.string.not_configured_body),
            modifier = Modifier.safeDrawingPadding(),
        )
    }
}

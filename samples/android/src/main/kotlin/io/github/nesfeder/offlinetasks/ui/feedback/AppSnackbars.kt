// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.feedback

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The app's one queue of snackbars. Every screen posts to the same [hostState], so two messages
 * never cover each other; only the destination on top draws it ([isDrawnHere]), in its own
 * scaffold, where it sits clear of that screen's buttons.
 */
class AppSnackbars(val hostState: SnackbarHostState, val isDrawnHere: Boolean)

/** The [AppSnackbars] of the destination being composed; previews and tests get a queue alone. */
val LocalAppSnackbars: ProvidableCompositionLocal<AppSnackbars> = staticCompositionLocalOf {
    AppSnackbars(SnackbarHostState(), isDrawnHere = true)
}

/** The app's snackbars, drawn here if this destination is the one on top. */
@Composable
fun AppSnackbarHost(modifier: Modifier = Modifier) {
    val snackbars = LocalAppSnackbars.current
    if (snackbars.isDrawnHere) SnackbarHost(snackbars.hostState, modifier)
}

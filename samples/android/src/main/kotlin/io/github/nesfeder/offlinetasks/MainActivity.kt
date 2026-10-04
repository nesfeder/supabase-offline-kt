// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nesfeder.offlinetasks.ui.components.NotConfiguredScreen
import io.github.nesfeder.offlinetasks.ui.navigation.OfflineTasksApp
import io.github.nesfeder.offlinetasks.ui.theme.OfflineTasksTheme

/**
 * The app's one activity: edge to edge, with everything drawn by Compose. Without a Supabase
 * project in `local.properties` it shows how to add one instead.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val isConfigured = (application as OfflineTasksApplication).isConfigured
        setContent {
            OfflineTasksTheme { if (isConfigured) OfflineTasksApp() else NotConfiguredScreen() }
        }
    }
}

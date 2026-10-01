// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.nesfeder.supabase.offline.sample.ui.navigation.OfflineTasksApp
import io.github.nesfeder.supabase.offline.sample.ui.theme.OfflineTasksTheme

/** The app's one activity: edge to edge, with everything drawn by Compose. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { OfflineTasksTheme { OfflineTasksApp() } }
    }
}

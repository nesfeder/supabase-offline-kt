// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Material 3 Expressive with the expressive motion scheme: springs that may overshoot for
 * position and size, and none for colour and fade. The colours come from the wallpaper on Android
 * 12 and later, and from Material's baseline otherwise.
 */
@Composable
fun OfflineTasksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = colorSchemeFor(darkTheme, dynamicColor),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}

@Composable
private fun colorSchemeFor(darkTheme: Boolean, dynamicColor: Boolean): ColorScheme =
    when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        // Material ships no expressive dark scheme; the standard dark one pairs with it.
        darkTheme -> darkColorScheme()
        else -> expressiveLightColorScheme()
    }

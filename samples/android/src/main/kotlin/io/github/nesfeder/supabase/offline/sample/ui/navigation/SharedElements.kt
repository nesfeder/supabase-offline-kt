// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect

/**
 * What a shared element needs: the layout that draws it during a transition and the navigation
 * transition it follows. Absent in previews and tests, where elements simply do not travel.
 */
class SharedElementScopes(
    val transition: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
)

/** The [SharedElementScopes] of the destination being composed, if any. */
val LocalSharedElementScopes: ProvidableCompositionLocal<SharedElementScopes?> =
    compositionLocalOf {
        null
    }

/** The keys that pair an element on one destination with its twin on the next. */
object SharedKeys {
    /** A task row's container, which grows into the detail screen. */
    fun taskContainer(taskId: String): String = "task-container-$taskId"

    /** A task's title, which flies into the detail screen's app bar. */
    fun taskTitle(taskId: String): String = "task-title-$taskId"

    /** A photo, from its thumbnail to the viewer. */
    fun photo(photoId: String): String = "photo-$photoId"

    /** The New-task button, which grows into the New-task screen. */
    const val NEW_TASK: String = "new-task"
}

/**
 * [content] whose bounds travel to the element with the same [key] on the other side of a
 * navigation, the content cross-fading on the way: Material's container transform.
 */
@Composable
fun SharedBounds(key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scopes = LocalSharedElementScopes.current
    if (scopes == null) {
        Box(modifier) { content() }
        return
    }
    val motion = MaterialTheme.motionScheme
    with(scopes.transition) {
        Box(
            modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = scopes.visibility,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec<Rect>() },
                resizeMode = RemeasureToBounds,
            )
        ) {
            content()
        }
    }
}

/** [content] that moves, as one element, to the element with the same [key] after a navigation. */
@Composable
fun SharedElement(key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scopes = LocalSharedElementScopes.current
    if (scopes == null) {
        Box(modifier) { content() }
        return
    }
    val motion = MaterialTheme.motionScheme
    with(scopes.transition) {
        Box(
            modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = scopes.visibility,
                boundsTransform = BoundsTransform { _, _ -> motion.defaultSpatialSpec<Rect>() },
            )
        ) {
            content()
        }
    }
}

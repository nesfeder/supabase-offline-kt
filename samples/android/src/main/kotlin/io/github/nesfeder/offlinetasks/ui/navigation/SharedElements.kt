// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode.Companion.RemeasureToBounds
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import kotlin.math.roundToInt

/**
 * What a shared element needs: the layout that draws it during a transition and the navigation
 * transition it follows. Absent in previews and tests, where elements simply do not travel.
 *
 * @property followsGesture true while a predictive back gesture drives the transition, until the
 *   screen it lands on has settled.
 */
class SharedElementScopes(
    val transition: SharedTransitionScope,
    val visibility: AnimatedVisibilityScope,
    val followsGesture: Boolean,
)

/** The [SharedElementScopes] of the destination being composed, if any. */
val LocalSharedElementScopes: ProvidableCompositionLocal<SharedElementScopes?> =
    compositionLocalOf {
        null
    }

/** The keys that pair an element on one destination with its twin on the next. */
object SharedKeys {
    /** A task row's container, which grows into the detail screen, carrying the row with it. */
    fun taskContainer(taskId: String): String = "task-container-$taskId"

    /** A photo, from its thumbnail to the viewer. */
    fun photo(photoId: String): String = "photo-$photoId"

    /** The New-task button, which grows into the New-task screen. */
    const val NEW_TASK: String = "new-task"
}

/**
 * How long a predictive back gesture's animations last, end to end. Every one of them lasts this
 * long and runs at a constant rate, so the gesture's progress maps straight onto all of them and
 * the screen follows the finger.
 */
const val PREDICTIVE_BACK_MILLIS: Int = 400

/**
 * A spec that runs at a constant rate for a predictive back: over all of [PREDICTIVE_BACK_MILLIS],
 * or only over the part of the gesture from [from] to [to], as fractions of it.
 */
fun <T> predictiveBackSpec(from: Float = 0f, to: Float = 1f): FiniteAnimationSpec<T> =
    tween(
        durationMillis = ((to - from) * PREDICTIVE_BACK_MILLIS).roundToInt(),
        delayMillis = (from * PREDICTIVE_BACK_MILLIS).roundToInt(),
        easing = LinearEasing,
    )

/**
 * [content] whose bounds travel to the element with the same [key] on the other side of a
 * navigation, the content cross-fading on the way: Material's container transform. The content
 * fills the travelling bounds, so its own background is the container that changes shape.
 */
@Composable
fun SharedBounds(key: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scopes = LocalSharedElementScopes.current
    if (scopes == null) {
        Box(modifier, propagateMinConstraints = true) { content() }
        return
    }
    val motion = MaterialTheme.motionScheme
    // A predictive back seeks the transition by the gesture's progress: springs there would finish
    // in the first part of the gesture, so it gets constant-rate specs instead. The container
    // shrinks with the finger from the start, still showing the screen it holds; the contents
    // change over only near the end, so the two are never half drawn over each other for long.
    val seeking = scopes.followsGesture
    with(scopes.transition) {
        Box(
            modifier.sharedBounds(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = scopes.visibility,
                enter =
                    fadeIn(
                        if (seeking) predictiveBackSpec(from = 0.65f)
                        else motion.defaultEffectsSpec()
                    ),
                exit =
                    fadeOut(
                        if (seeking) predictiveBackSpec(from = 0.5f, to = 0.8f)
                        else motion.fastEffectsSpec()
                    ),
                boundsTransform = boundsTransform(seeking),
                resizeMode = RemeasureToBounds,
            ),
            // The travelling bounds are fixed constraints; the content takes them, not its own
            // size.
            propagateMinConstraints = true,
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
    val seeking = scopes.followsGesture
    with(scopes.transition) {
        Box(
            modifier.sharedElement(
                sharedContentState = rememberSharedContentState(key),
                animatedVisibilityScope = scopes.visibility,
                boundsTransform = boundsTransform(seeking),
            )
        ) {
            content()
        }
    }
}

/** The motion scheme's spatial spring, or a constant rate while a predictive back seeks. */
@Composable
private fun boundsTransform(seeking: Boolean): BoundsTransform {
    val motion = MaterialTheme.motionScheme
    return BoundsTransform { _, _ ->
        if (seeking) predictiveBackSpec() else motion.defaultSpatialSpec<Rect>()
    }
}

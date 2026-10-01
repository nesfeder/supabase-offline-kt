// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.data.Task
import io.github.nesfeder.offlinetasks.ui.navigation.SharedBounds
import io.github.nesfeder.offlinetasks.ui.navigation.SharedKeys

/**
 * One task in the list: a checkbox that springs, a title that strikes through and fades when done,
 * a mark while its change waits to sync, and a swipe from the end to delete it.
 *
 * @param selected whether the task is the one open beside the list.
 */
@Composable
fun TaskRow(
    task: Task,
    selected: Boolean,
    shapes: ListItemShapes,
    onDoneChange: (Task, Boolean) -> Unit,
    onDelete: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
) {
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Remembered, not saved: a row that comes back from Undo must come back unswiped.
    val dismissState = remember {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, threshold)
    }
    val haptics = LocalHapticFeedback.current
    // The box re-runs its dismiss callback whenever the callback changes, so it must be stable.
    val latestTask by rememberUpdatedState(task)
    val latestOnDelete by rememberUpdatedState(onDelete)
    val onDismiss = remember { { _: SwipeToDismissBoxValue -> latestOnDelete(latestTask) } }
    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        onDismiss = onDismiss,
        backgroundContent = { DeleteBackground(dismissState, shapes) },
    ) {
        SharedBounds(SharedKeys.taskContainer(task.id)) {
            val title = task.title.ifBlank { stringResource(R.string.task_untitled) }
            val checkboxLabel = stringResource(R.string.task_done_checkbox, title)
            SegmentedListItem(
                selected = selected,
                onClick = { onOpen(task) },
                shapes = shapes,
                leadingContent = {
                    Checkbox(
                        checked = task.isDone,
                        // Its own focus stop, so it says which task it marks done.
                        modifier = Modifier.semantics { contentDescription = checkboxLabel },
                        onCheckedChange = { isDone ->
                            haptics.performHapticFeedback(
                                if (isDone) HapticFeedbackType.ToggleOn
                                else HapticFeedbackType.ToggleOff
                            )
                            onDoneChange(task, isDone)
                        },
                    )
                },
                trailingContent = { PendingMark(visible = task.hasPendingMutations) },
                supportingContent =
                    task.notes
                        ?.takeIf { it.isNotBlank() }
                        ?.let { notes ->
                            { Text(notes, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
            ) {
                TaskTitle(task, title)
            }
        }
    }
}

/** The title, struck through and faded to the variant color once the task is done. */
@Composable
private fun TaskTitle(task: Task, title: String) {
    val color by
        animateColorAsState(
            targetValue =
                if (task.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
            label = "title color",
        )
    Text(
        text = title,
        color = color,
        textDecoration = if (task.isDone) TextDecoration.LineThrough else TextDecoration.None,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A cloud-upload mark while the task's change waits in the mutation queue. */
@Composable
private fun PendingMark(visible: Boolean) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_cloud_upload),
            contentDescription = stringResource(R.string.task_not_synced),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Behind a row being swiped: the color deepens and the bin grows as the swipe nears deletion. */
@Composable
private fun DeleteBackground(state: SwipeToDismissBoxState, shapes: ListItemShapes) {
    val colors = MaterialTheme.colorScheme
    val armed = state.targetValue == SwipeToDismissBoxValue.EndToStart
    val swiping = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
    val iconScale by
        animateFloatAsState(
            targetValue = if (armed) 1.25f else 0.85f,
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            label = "bin scale",
        )
    // Nothing behind a row at rest, which a container transform leaves uncovered as it flies.
    if (!swiping && !armed) return
    val progress = if (swiping) state.progress.coerceIn(0f, 1f) else 0f
    val background =
        if (armed) colors.errorContainer
        else lerp(colors.surfaceContainerHigh, colors.errorContainer, progress)
    Row(
        modifier =
            Modifier.fillMaxSize()
                .clip(shapes.shape)
                .background(background)
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_delete),
            contentDescription = stringResource(R.string.action_delete),
            tint = colors.onErrorContainer,
            modifier = Modifier.scale(iconScale),
        )
    }
}

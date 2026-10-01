// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Every place in the app, as a key on the back stack. Each is serializable, so the back stack
 * survives process death.
 */
@Serializable
sealed interface Destination : NavKey {
    /** The task list, and the start of every back stack. */
    @Serializable data object Tasks : Destination

    /** Every photo, across tasks. */
    @Serializable data object Photos : Destination

    /** Connection, queued and refused writes, and the offline switches. */
    @Serializable data object Sync : Destination

    /** Adding a task. */
    @Serializable data object NewTask : Destination

    /** One task, to edit. */
    @Serializable data class TaskDetail(val taskId: String) : Destination

    /** One photo, full screen. */
    @Serializable data class PhotoViewer(val photoId: String) : Destination
}

/** The three destinations the navigation bar or rail switches between. */
enum class TopLevelDestination(val destination: Destination) {
    Tasks(Destination.Tasks),
    Photos(Destination.Photos),
    Sync(Destination.Sync),
}

/**
 * The top-level destination the back stack is in: the last top-level key on it. Tasks is always at
 * the bottom, so back from Photos or Sync returns to it.
 */
fun List<NavKey>.currentTopLevel(): TopLevelDestination =
    when (lastOrNull { it == Destination.Photos || it == Destination.Sync }) {
        Destination.Photos -> TopLevelDestination.Photos
        Destination.Sync -> TopLevelDestination.Sync
        else -> TopLevelDestination.Tasks
    }

/** Switches to [topLevel]: Tasks alone at the bottom, then [topLevel] if it is not Tasks. */
fun MutableList<NavKey>.selectTopLevel(topLevel: TopLevelDestination) {
    while (size > 1) removeAt(lastIndex)
    if (isEmpty()) add(Destination.Tasks)
    if (topLevel != TopLevelDestination.Tasks) add(topLevel.destination)
}

/** Opens a task: replaces the open one beside the list, or pushes it on a phone. */
fun MutableList<NavKey>.openTask(taskId: String) {
    if (lastOrNull() is Destination.TaskDetail || lastOrNull() == Destination.NewTask) {
        removeAt(lastIndex)
    }
    add(Destination.TaskDetail(taskId))
}

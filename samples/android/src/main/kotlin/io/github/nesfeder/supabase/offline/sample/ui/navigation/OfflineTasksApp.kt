// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.ui.navigation

import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import io.github.nesfeder.supabase.offline.sample.R
import io.github.nesfeder.supabase.offline.sample.ui.detail.DetailPlaceholder
import io.github.nesfeder.supabase.offline.sample.ui.detail.TaskDetailRoute
import io.github.nesfeder.supabase.offline.sample.ui.newtask.NewTaskRoute
import io.github.nesfeder.supabase.offline.sample.ui.photos.PhotoViewerRoute
import io.github.nesfeder.supabase.offline.sample.ui.photos.PhotosRoute
import io.github.nesfeder.supabase.offline.sample.ui.status.RollingText
import io.github.nesfeder.supabase.offline.sample.ui.sync.SyncRoute
import io.github.nesfeder.supabase.offline.sample.ui.tasks.TasksRoute

/**
 * The whole app: a navigation bar (a rail on wider windows) for Tasks, Photos and Sync, around a
 * Navigation 3 display inside a shared-transition layout. On a tablet or an unfolded foldable,
 * the task list and one task sit side by side.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun OfflineTasksApp(shellViewModel: AppShellViewModel = viewModel(factory = AppShellViewModel.Factory)) {
    val backStack = rememberNavBackStack(Destination.Tasks)
    val badges by shellViewModel.uiState.collectAsStateWithLifecycle()
    val listDetailStrategy = rememberListDetailSceneStrategy<NavKey>()
    val twoPanes = listDetailStrategy.directive.maxHorizontalPartitions > 1
    val suiteState = rememberNavigationSuiteScaffoldState()
    val showSuite =
        when (backStack.lastOrNull()) {
            Destination.NewTask,
            is Destination.PhotoViewer -> false
            is Destination.TaskDetail -> twoPanes
            else -> true
        }
    LaunchedEffect(showSuite) { if (showSuite) suiteState.show() else suiteState.hide() }
    val currentTopLevel = backStack.currentTopLevel()
    NavigationSuiteScaffold(
        state = suiteState,
        navigationItems = {
            TopLevelDestination.entries.forEach { topLevel ->
                val selected = topLevel == currentTopLevel
                NavigationSuiteItem(
                    selected = selected,
                    onClick = { backStack.selectTopLevel(topLevel) },
                    icon = {
                        BadgedBox(
                            badge = {
                                val count = badges.pendingCount
                                if (topLevel == TopLevelDestination.Sync && count > 0) {
                                    Badge { RollingText(text = count.toString(), count = count) }
                                }
                            }
                        ) {
                            Icon(painterResource(topLevel.icon(selected)), contentDescription = null)
                        }
                    },
                    label = { Text(stringResource(topLevel.label)) },
                )
            }
        },
    ) {
        val motion = MaterialTheme.motionScheme
        val enter = fadeIn(motion.defaultEffectsSpec())
        val exit = fadeOut(motion.fastEffectsSpec())
        val predictiveExit = fadeOut(motion.defaultEffectsSpec()) + scaleOut(targetScale = 0.9f)
        SharedTransitionLayout {
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                sceneStrategies = listOf(listDetailStrategy),
                sharedTransitionScope = this,
                transitionSpec = { enter togetherWith exit },
                popTransitionSpec = { enter togetherWith exit },
                predictivePopTransitionSpec = { enter togetherWith predictiveExit },
                entryProvider =
                    entryProvider {
                        entry<Destination.Tasks>(
                            metadata =
                                ListDetailSceneStrategy.listPane(
                                    detailPlaceholder = { DetailPlaceholder() }
                                )
                        ) {
                            WithSharedElements {
                                TasksRoute(
                                    onOpenTask = { backStack.openTask(it) },
                                    onNewTask = { backStack.add(Destination.NewTask) },
                                    onShowSync = {
                                        backStack.selectTopLevel(TopLevelDestination.Sync)
                                    },
                                )
                            }
                        }
                        entry<Destination.TaskDetail>(
                            metadata = ListDetailSceneStrategy.detailPane()
                        ) { key ->
                            WithSharedElements {
                                TaskDetailRoute(
                                    taskId = key.taskId,
                                    showBackButton = !twoPanes,
                                    onBack = { backStack.popIfOnTop(key) },
                                    onOpenPhoto = { backStack.add(Destination.PhotoViewer(it)) },
                                )
                            }
                        }
                        entry<Destination.NewTask>(metadata = ListDetailSceneStrategy.detailPane()) {
                            WithSharedElements {
                                NewTaskRoute(onClose = { backStack.popIfOnTop(Destination.NewTask) })
                            }
                        }
                        entry<Destination.Photos> {
                            WithSharedElements {
                                PhotosRoute(
                                    onOpenPhoto = { backStack.add(Destination.PhotoViewer(it)) }
                                )
                            }
                        }
                        entry<Destination.Sync> { SyncRoute() }
                        entry<Destination.PhotoViewer> { key ->
                            WithSharedElements {
                                PhotoViewerRoute(
                                    photoId = key.photoId,
                                    onClose = { backStack.popIfOnTop(key) },
                                )
                            }
                        }
                    },
            )
        }
    }
}

/** Gives [content] the scopes its shared elements need, inside a navigation entry. */
@Composable
private fun SharedTransitionScope.WithSharedElements(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSharedElementScopes provides
            SharedElementScopes(transition = this, visibility = LocalNavAnimatedContentScope.current),
        content = content,
    )
}

/** Pops [key] only if it is still on top, so a second "close" can never pop the screen below. */
private fun MutableList<NavKey>.popIfOnTop(key: NavKey) {
    if (lastOrNull() == key) removeAt(lastIndex)
}

private val TopLevelDestination.label: Int
    get() =
        when (this) {
            TopLevelDestination.Tasks -> R.string.tasks_title
            TopLevelDestination.Photos -> R.string.photos_title
            TopLevelDestination.Sync -> R.string.sync_title
        }

private fun TopLevelDestination.icon(selected: Boolean): Int =
    when (this) {
        TopLevelDestination.Tasks -> R.drawable.ic_task_alt
        TopLevelDestination.Photos ->
            if (selected) R.drawable.ic_photo_library_filled else R.drawable.ic_photo_library
        TopLevelDestination.Sync ->
            if (selected) R.drawable.ic_cloud_sync_filled else R.drawable.ic_cloud_sync
    }

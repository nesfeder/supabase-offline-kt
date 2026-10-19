// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
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
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
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
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.github.nesfeder.offlinetasks.R
import io.github.nesfeder.offlinetasks.ui.detail.DetailPlaceholder
import io.github.nesfeder.offlinetasks.ui.detail.TaskDetailRoute
import io.github.nesfeder.offlinetasks.ui.feedback.AppSnackbars
import io.github.nesfeder.offlinetasks.ui.feedback.LocalAppSnackbars
import io.github.nesfeder.offlinetasks.ui.feedback.failureMessage
import io.github.nesfeder.offlinetasks.ui.feedback.noticeMessage
import io.github.nesfeder.offlinetasks.ui.newtask.NewTaskRoute
import io.github.nesfeder.offlinetasks.ui.photos.PhotoViewerRoute
import io.github.nesfeder.offlinetasks.ui.photos.PhotosRoute
import io.github.nesfeder.offlinetasks.ui.status.RollingText
import io.github.nesfeder.offlinetasks.ui.sync.SyncRoute
import io.github.nesfeder.offlinetasks.ui.tasks.TasksRoute
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The whole app: a navigation bar (a rail on wider windows) for Tasks, Photos and Sync, around a
 * Navigation 3 display, all inside one shared-transition layout so that an element in flight is
 * drawn above the bar too. On a tablet or an unfolded foldable, the task list and one task sit side
 * by side. Every failure any screen reports is shown here, once, in the app's one snackbar queue.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun OfflineTasksApp(
    shellViewModel: AppShellViewModel = viewModel(factory = AppShellViewModel.Factory)
) {
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
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current
    LaunchedEffect(shellViewModel) {
        shellViewModel.failures.collect { failure ->
            snackbarHostState.showSnackbar(failureMessage(resources, failure))
        }
    }
    LaunchedEffect(shellViewModel) {
        shellViewModel.notices.collect { notice ->
            snackbarHostState.showSnackbar(noticeMessage(resources, notice))
        }
    }
    val currentTopLevel = backStack.currentTopLevel()
    // The open task, marked in the list while both panes show.
    val selectedTaskId = (backStack.lastOrNull() as? Destination.TaskDetail)?.taskId
    SharedTransitionLayout {
        val sharedTransitionScope = this
        NavigationSuiteScaffold(
            state = suiteState,
            navigationItems = {
                TopLevelDestination.entries.forEach { topLevel ->
                    val selected = topLevel == currentTopLevel
                    NavigationSuiteItem(
                        selected = selected,
                        onClick = { backStack.selectTopLevel(topLevel) },
                        icon = {
                            BadgedBox(badge = { DestinationBadge(topLevel, badges) }) {
                                Icon(
                                    painterResource(topLevel.icon(selected)),
                                    contentDescription = null,
                                )
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
            // The gesture seeks these by its progress, so they run at a constant rate: the screen
            // shrinks and fades exactly as far as the finger has gone.
            val predictiveEnter = fadeIn(predictiveBackSpec())
            val predictiveExit =
                fadeOut(predictiveBackSpec()) +
                    scaleOut(predictiveBackSpec(), targetScale = PREDICTIVE_BACK_SCALE)
            NavDisplay(
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators =
                    listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                sceneStrategies = listOf(listDetailStrategy),
                sharedTransitionScope = sharedTransitionScope,
                transitionSpec = { enter togetherWith exit },
                popTransitionSpec = { enter togetherWith exit },
                predictivePopTransitionSpec = { predictiveEnter togetherWith predictiveExit },
                entryProvider =
                    entryProvider {
                        entry<Destination.Tasks>(
                            metadata =
                                ListDetailSceneStrategy.listPane(
                                    detailPlaceholder = { DetailPlaceholder() }
                                )
                        ) { key ->
                            DestinationContent(key, backStack, snackbarHostState) {
                                TasksRoute(
                                    selectedTaskId = selectedTaskId.takeIf { twoPanes },
                                    onOpenTask = { taskId, shown ->
                                        backStack.openTask(taskId, shown)
                                    },
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
                            DestinationContent(key, backStack, snackbarHostState) {
                                TaskDetailRoute(
                                    taskId = key.taskId,
                                    shown = key.shown,
                                    showBackButton = !twoPanes,
                                    onBack = { backStack.popIfOnTop(key) },
                                    onOpenPhoto = { backStack.add(Destination.PhotoViewer(it)) },
                                )
                            }
                        }
                        entry<Destination.NewTask>(
                            metadata = ListDetailSceneStrategy.detailPane()
                        ) { key ->
                            DestinationContent(key, backStack, snackbarHostState) {
                                NewTaskRoute(onClose = { backStack.popIfOnTop(key) })
                            }
                        }
                        entry<Destination.Photos> { key ->
                            DestinationContent(key, backStack, snackbarHostState) {
                                PhotosRoute(
                                    onOpenPhoto = { backStack.add(Destination.PhotoViewer(it)) }
                                )
                            }
                        }
                        entry<Destination.Sync> { key ->
                            DestinationContent(key, backStack, snackbarHostState) { SyncRoute() }
                        }
                        entry<Destination.PhotoViewer> { key ->
                            DestinationContent(key, backStack, snackbarHostState) {
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

/**
 * The badge on a destination: the open-task count on Tasks; on Sync, the refused and conflicting
 * writes in the error color when there are any, the waiting writes otherwise.
 */
@Composable
private fun DestinationBadge(topLevel: TopLevelDestination, badges: AppShellUiState) {
    val count =
        when (topLevel) {
            TopLevelDestination.Tasks -> badges.openTaskCount ?: 0
            TopLevelDestination.Photos -> 0
            TopLevelDestination.Sync ->
                if (badges.needsAttentionCount > 0) badges.needsAttentionCount
                else badges.pendingCount
        }
    if (count == 0) return
    val refused = topLevel == TopLevelDestination.Sync && badges.needsAttentionCount > 0
    Badge(
        containerColor =
            if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        contentColor =
            if (refused) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary,
    ) {
        RollingText(text = count.toString(), count = count)
    }
}

/**
 * How small a screen gets, at the end of a predictive back gesture, as it shrinks with the finger.
 */
private const val PREDICTIVE_BACK_SCALE = 0.9f

/**
 * Gives a destination's [content] what every screen expects: the scopes its shared elements need,
 * and the app's snackbars, drawn by whichever destination is on top of [backStack].
 */
@Composable
private fun SharedTransitionScope.DestinationContent(
    key: NavKey,
    backStack: List<NavKey>,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit,
) {
    val visibility = LocalNavAnimatedContentScope.current
    CompositionLocalProvider(
        LocalSharedElementScopes provides
            SharedElementScopes(
                transition = this,
                visibility = visibility,
                followsGesture = followsBackGesture(visibility),
            ),
        LocalAppSnackbars provides
            AppSnackbars(snackbarHostState, isDrawnHere = backStack.lastOrNull() == key),
        content = content,
    )
}

/**
 * True from the moment a predictive back gesture starts until [visibility]'s transition has
 * settled, released or canceled: the specs it picks must not change while the gesture's transition
 * finishes, or the screen would jump.
 */
@Composable
private fun followsBackGesture(visibility: AnimatedVisibilityScope): Boolean {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    val noGesture = remember {
        MutableStateFlow<NavigationEventTransitionState>(NavigationEventTransitionState.Idle)
    }
    val gesture by (dispatcher?.transitionState ?: noGesture).collectAsState()
    val inGesture = gesture is NavigationEventTransitionState.InProgress
    var finishing by remember { mutableStateOf(false) }
    val settled = visibility.transition.currentState == visibility.transition.targetState
    LaunchedEffect(inGesture, settled) {
        if (inGesture) finishing = true else if (settled) finishing = false
    }
    return inGesture || finishing
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

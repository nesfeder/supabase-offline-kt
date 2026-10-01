// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.status

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.github.nesfeder.offlinetasks.R

/** The test tag of the banner, so the Tasks screen's tests can find it. */
const val SYNC_BANNER_TAG: String = "sync-banner"

/**
 * The always-visible status element under the Tasks app bar: it expands in when there is something
 * to say, cross-fades its color as the state moves from offline to syncing to synced, and collapses
 * when everything is on the server.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SyncStatusBanner(headline: SyncHeadline, modifier: Modifier = Modifier) {
    val motion = MaterialTheme.motionScheme
    // While collapsing, keep showing what was last said rather than an empty card.
    var shown by remember { mutableStateOf(headline) }
    if (headline != SyncHeadline.UpToDate) shown = headline
    AnimatedVisibility(
        visible = headline != SyncHeadline.UpToDate,
        modifier = modifier,
        enter = expandVertically(motion.defaultSpatialSpec()) + fadeIn(motion.defaultEffectsSpec()),
        exit = shrinkVertically(motion.defaultSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
    ) {
        val colors = MaterialTheme.colorScheme
        val container by
            animateColorAsState(colors.containerFor(shown), motion.defaultEffectsSpec())
        val content by animateColorAsState(colors.contentFor(shown), motion.defaultEffectsSpec())
        Surface(
            color = container,
            contentColor = content,
            shape = MaterialTheme.shapes.large,
            modifier =
                Modifier.fillMaxWidth().testTag(SYNC_BANNER_TAG).semantics {
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    if (shown is SyncHeadline.Syncing) {
                        LoadingIndicator(Modifier.size(28.dp), color = content)
                    } else {
                        Icon(painterResource(iconFor(shown)), contentDescription = null)
                    }
                }
                RollingText(
                    text = syncHeadlineText(shown).orEmpty(),
                    count = shown.pendingCount(),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}

/**
 * [text] that rolls up when [count] grows and down when it shrinks, so a changing number reads as a
 * counter rather than a jump.
 */
@Composable
fun RollingText(
    text: String,
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedContent(
        targetState = text to count,
        modifier = modifier,
        transitionSpec = {
            val growing = targetState.second >= initialState.second
            (slideInVertically(motion.defaultSpatialSpec()) { if (growing) it else -it } +
                    fadeIn(motion.defaultEffectsSpec()))
                .togetherWith(
                    slideOutVertically(motion.defaultSpatialSpec()) { if (growing) -it else it } +
                        fadeOut(motion.fastEffectsSpec())
                )
        },
        label = "rolling text",
    ) { (shownText, _) ->
        Text(shownText, style = style, color = color)
    }
}

/** What the app says for [headline], or null when it says nothing. */
@Composable
fun syncHeadlineText(headline: SyncHeadline): String? =
    when (headline) {
        SyncHeadline.UpToDate -> null
        SyncHeadline.JustSynced -> stringResource(R.string.status_just_synced)
        SyncHeadline.ShowingSavedData -> stringResource(R.string.status_showing_saved_data)
        is SyncHeadline.Offline ->
            if (headline.pendingCount == 0) {
                stringResource(R.string.status_offline)
            } else {
                pluralStringResource(
                    R.plurals.status_offline_with_changes,
                    headline.pendingCount,
                    headline.pendingCount,
                )
            }
        is SyncHeadline.Syncing ->
            pluralStringResource(
                R.plurals.status_syncing,
                headline.pendingCount,
                headline.pendingCount,
            )
        is SyncHeadline.Waiting ->
            pluralStringResource(
                R.plurals.status_waiting,
                headline.pendingCount,
                headline.pendingCount,
            )
    }

private fun SyncHeadline.pendingCount(): Int =
    when (this) {
        is SyncHeadline.Offline -> pendingCount
        is SyncHeadline.Syncing -> pendingCount
        is SyncHeadline.Waiting -> pendingCount
        else -> 0
    }

private fun iconFor(headline: SyncHeadline): Int =
    when (headline) {
        is SyncHeadline.Offline -> R.drawable.ic_cloud_off
        is SyncHeadline.Waiting -> R.drawable.ic_schedule
        SyncHeadline.ShowingSavedData -> R.drawable.ic_sync_problem
        SyncHeadline.JustSynced,
        SyncHeadline.UpToDate,
        is SyncHeadline.Syncing -> R.drawable.ic_cloud_done
    }

private fun ColorScheme.containerFor(headline: SyncHeadline): Color =
    when (headline) {
        is SyncHeadline.Offline -> surfaceContainerHighest
        is SyncHeadline.Waiting -> secondaryContainer
        is SyncHeadline.Syncing -> primaryContainer
        SyncHeadline.JustSynced,
        SyncHeadline.UpToDate -> tertiaryContainer
        SyncHeadline.ShowingSavedData -> errorContainer
    }

private fun ColorScheme.contentFor(headline: SyncHeadline): Color =
    when (headline) {
        is SyncHeadline.Offline -> onSurfaceVariant
        is SyncHeadline.Waiting -> onSecondaryContainer
        is SyncHeadline.Syncing -> onPrimaryContainer
        SyncHeadline.JustSynced,
        SyncHeadline.UpToDate -> onTertiaryContainer
        SyncHeadline.ShowingSavedData -> onErrorContainer
    }

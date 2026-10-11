// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.ui.status

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.nesfeder.offlinetasks.R
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** [instant] said against [now]: "just now" within a minute, then "3 minutes ago" and so on. */
@Composable
fun relativeTime(instant: Instant, now: Instant = Clock.System.now()): String =
    if (now - instant < 1.minutes) {
        stringResource(R.string.time_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(
                instant.toEpochMilliseconds(),
                now.toEpochMilliseconds(),
                DateUtils.MINUTE_IN_MILLIS,
            )
            .toString()
    }

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.sample.testing

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler

/** A [Clock] that reads the test scheduler's virtual time, so delays move it. */
@OptIn(ExperimentalCoroutinesApi::class)
class TestSchedulerClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant = Instant.parse("2026-10-01T09:00:00Z"),
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(start.toEpochMilliseconds() + scheduler.currentTime)
}

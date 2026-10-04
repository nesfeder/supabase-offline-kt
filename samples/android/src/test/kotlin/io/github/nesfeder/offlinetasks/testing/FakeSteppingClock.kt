// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.offlinetasks.testing

import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.time.Instant

/** A [Clock] one second later at every read, so rows made in a test sort in the order made. */
class FakeSteppingClock(start: Instant = Instant.parse("2026-10-01T09:00:00Z")) : Clock {
    private val nextSecond = AtomicLong(start.epochSeconds)

    override fun now(): Instant = Instant.fromEpochSeconds(nextSecond.getAndIncrement())
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import kotlin.time.Clock
import kotlin.time.Instant

/** A clock a test moves by hand: [now] is the time it reports. */
class FakeClock(var now: Instant) : Clock {
    override fun now(): Instant = now
}

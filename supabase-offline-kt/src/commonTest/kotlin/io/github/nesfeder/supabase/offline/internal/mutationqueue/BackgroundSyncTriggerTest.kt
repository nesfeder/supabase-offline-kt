// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.SdkLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The passes the engine starts on its own are coalesced — at most one runs and one waits, however
 * many are asked for — and wait after a pass that stopped on a failure, doubling up to a cap, so a
 * write the server keeps failing is not sent again for every write queued behind it.
 */
class BackgroundSyncTriggerTest {

    private val logger = SdkLogger(null, OfflineLogLevel.INFO)

    @Test
    fun requests_made_while_a_pass_runs_start_one_more_pass() = runTest {
        // Given — a pass that is held open
        var passes = 0
        val release = CompletableDeferred<Unit>()
        val trigger =
            BackgroundSyncTrigger(scope = backgroundScope, sdkLogger = logger) {
                passes++
                if (passes == 1) release.await()
                SyncReport(0, emptyList(), 0, stop = null)
            }
        trigger.request()
        runCurrent()

        // When — ten more requests arrive while it runs, then it finishes
        repeat(10) { trigger.request() }
        release.complete(Unit)
        runCurrent()

        // Then — one more pass, not ten
        assertEquals(2, passes)
    }

    @Test
    fun a_failed_pass_holds_the_next_one_back_and_the_wait_doubles() = runTest {
        // Given — every pass stops on a failure
        var passes = 0
        val trigger =
            BackgroundSyncTrigger(scope = backgroundScope, sdkLogger = logger) {
                passes++
                SyncReport(
                    0,
                    emptyList(),
                    1,
                    SyncStop.TransientFailure(IllegalStateException("500")),
                )
            }

        // When / Then — the first runs at once
        trigger.request()
        runCurrent()
        assertEquals(1, passes)

        // The next waits out one second
        trigger.request()
        advanceTimeBy(999.milliseconds)
        assertEquals(1, passes)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, passes)

        // Then two
        trigger.request()
        advanceTimeBy(1_999.milliseconds)
        assertEquals(2, passes)
        advanceTimeBy(2.milliseconds)
        assertEquals(3, passes)
    }

    @Test
    fun the_wait_doubles_up_to_its_cap() = runTest {
        // Given — every pass fails and asks for another straight away
        val passTimes = mutableListOf<Long>()
        lateinit var trigger: BackgroundSyncTrigger
        trigger =
            BackgroundSyncTrigger(
                scope = backgroundScope,
                sdkLogger = logger,
                maxBackoff = 4.seconds,
            ) {
                passTimes += testScheduler.currentTime
                if (passTimes.size < 6) trigger.request()
                SyncReport(
                    0,
                    emptyList(),
                    1,
                    SyncStop.TransientFailure(IllegalStateException("500")),
                )
            }

        // When
        trigger.request()
        advanceTimeBy(20.seconds)

        // Then — waits of 1, 2 and 4 seconds, then 4 again
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L, 15_000L), passTimes)
    }

    @Test
    fun a_pass_that_ends_normally_resets_the_wait() = runTest {
        // Given — a failed pass, then a good one
        var passes = 0
        val trigger =
            BackgroundSyncTrigger(scope = backgroundScope, sdkLogger = logger) {
                passes++
                val stop =
                    if (passes == 1) SyncStop.TransientFailure(IllegalStateException("500"))
                    else null
                SyncReport(0, emptyList(), 0, stop)
            }
        trigger.request()
        runCurrent()
        trigger.request()
        advanceTimeBy(1.seconds + 1.milliseconds)

        // When — the next request comes after the good pass
        trigger.request()
        runCurrent()

        // Then — it ran at once
        assertEquals(3, passes)
    }

    @Test
    fun no_pass_runs_unless_one_is_asked_for() = runTest {
        // Given
        var passes = 0
        BackgroundSyncTrigger(scope = backgroundScope, sdkLogger = logger) {
            passes++
            SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(IllegalStateException("500")))
        }

        // When
        advanceTimeBy(10.seconds)

        // Then — nothing retries on its own
        assertEquals(0, passes)
    }
}

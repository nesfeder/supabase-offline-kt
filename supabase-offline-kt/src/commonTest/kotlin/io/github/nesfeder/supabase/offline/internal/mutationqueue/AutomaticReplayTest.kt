// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The passes the engine starts on its own are coalesced — at most one runs and one waits, however
 * many are asked for — and wait after a pass that stopped on a failure, doubling up to a cap, so a
 * write the server keeps failing is not sent again for every write queued behind it. A reconnection
 * or a settled session cuts that wait short, and no pass runs while the network is known down.
 */
class AutomaticReplayTest {

    private val logger = EngineLogger(null, EngineLogLevel.INFO)

    @Test
    fun requests_made_while_a_pass_runs_start_one_more_pass() = runTest {
        // Given — a pass that is held open
        var passes = 0
        val release = CompletableDeferred<Unit>()
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passes++
                if (passes == 1) release.await()
                SyncReport(0, emptyList(), 0, stop = null)
            }
        automaticReplay.requestPass()
        runCurrent()

        // When — ten more requests arrive while it runs, then it finishes
        repeat(10) { automaticReplay.requestPass() }
        release.complete(Unit)
        runCurrent()

        // Then — one more pass, not ten
        assertEquals(2, passes)
    }

    @Test
    fun a_failed_pass_holds_the_next_one_back_and_the_wait_doubles() = runTest {
        // Given — every pass stops on a failure
        var passes = 0
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passes++
                SyncReport(
                    0,
                    emptyList(),
                    1,
                    SyncStop.TransientFailure(IllegalStateException("500")),
                )
            }

        // When / Then — the first runs at once
        automaticReplay.requestPass()
        runCurrent()
        assertEquals(1, passes)

        // The next waits out one second
        automaticReplay.requestPass()
        advanceTimeBy(999.milliseconds)
        assertEquals(1, passes)
        advanceTimeBy(2.milliseconds)
        assertEquals(2, passes)

        // Then two
        automaticReplay.requestPass()
        advanceTimeBy(1_999.milliseconds)
        assertEquals(2, passes)
        advanceTimeBy(2.milliseconds)
        assertEquals(3, passes)
    }

    @Test
    fun the_wait_doubles_up_to_its_cap() = runTest {
        // Given — every pass fails and asks for another straight away
        val passTimes = mutableListOf<Long>()
        lateinit var automaticReplay: AutomaticReplay
        automaticReplay =
            AutomaticReplay(
                scope = backgroundScope,
                engineLogger = logger,
                maxBackoff = 4.seconds,
            ) {
                passTimes += testScheduler.currentTime
                if (passTimes.size < 6) automaticReplay.requestPass()
                SyncReport(
                    0,
                    emptyList(),
                    1,
                    SyncStop.TransientFailure(IllegalStateException("500")),
                )
            }

        // When
        automaticReplay.requestPass()
        advanceTimeBy(20.seconds)

        // Then — waits of 1, 2 and 4 seconds, then 4 again
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 11_000L, 15_000L), passTimes)
    }

    @Test
    fun a_pass_that_ends_normally_resets_the_wait() = runTest {
        // Given — a failed pass, then a good one
        var passes = 0
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passes++
                val stop =
                    if (passes == 1) SyncStop.TransientFailure(IllegalStateException("500"))
                    else null
                SyncReport(0, emptyList(), 0, stop)
            }
        automaticReplay.requestPass()
        runCurrent()
        automaticReplay.requestPass()
        advanceTimeBy(1.seconds + 1.milliseconds)

        // When — the next request comes after the good pass
        automaticReplay.requestPass()
        runCurrent()

        // Then — it ran at once
        assertEquals(3, passes)
    }

    @Test
    fun an_edge_cuts_short_the_wait_failed_passes_built() = runTest {
        // Given — passes that keep failing have grown the wait to its cap
        val passTimes = mutableListOf<Long>()
        var failing = true
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passTimes += testScheduler.currentTime
                val stop =
                    if (failing) SyncStop.TransientFailure(IllegalStateException("offline"))
                    else null
                SyncReport(0, emptyList(), 1, stop)
            }
        repeat(8) {
            automaticReplay.requestPass()
            advanceTimeBy(65.seconds)
        }
        automaticReplay.requestPass()
        runCurrent()
        val lastFailedPassAt = passTimes.last()

        // When — the network comes back 2 seconds into the minute's wait
        advanceTimeBy(2.seconds)
        failing = false
        automaticReplay.requestPassOnEdge()
        runCurrent()

        // Then — the pass ran at once
        assertEquals(lastFailedPassAt + 2_000L, passTimes.last())
    }

    @Test
    fun after_an_edge_cut_it_short_the_wait_starts_again_from_one_second() = runTest {
        // Given — every pass fails; three have grown the wait to four seconds
        val passTimes = mutableListOf<Long>()
        val automaticReplay =
            AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
                passTimes += testScheduler.currentTime
                SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(IllegalStateException("x")))
            }
        repeat(3) {
            automaticReplay.requestPass()
            advanceTimeBy(10.seconds)
        }

        // When — an edge cuts the wait short, its pass fails too, and another pass is asked for
        automaticReplay.requestPassOnEdge()
        runCurrent()
        val edgePassAt = passTimes.last()
        automaticReplay.requestPass()
        advanceTimeBy(5.seconds)

        // Then — the next pass waited one second, not eight
        assertEquals(edgePassAt + 1_000L, passTimes.last())
    }

    @Test
    fun a_pass_asked_for_while_the_network_is_known_down_is_skipped() = runTest {
        // Given — the network is known to be down
        var down = true
        var passes = 0
        val automaticReplay =
            AutomaticReplay(
                scope = backgroundScope,
                engineLogger = logger,
                isNetworkKnownDown = { down },
            ) {
                passes++
                SyncReport(0, emptyList(), 0, stop = null)
            }

        // When — passes are asked for, then the network comes back
        repeat(3) { automaticReplay.requestPass() }
        advanceTimeBy(2.minutes)
        val whileDown = passes
        down = false
        automaticReplay.requestPassOnEdge()
        runCurrent()

        // Then — none ran while it was down, and the reconnection's ran at once
        assertEquals(0, whileDown)
        assertEquals(1, passes)
    }

    @Test
    fun no_pass_runs_unless_one_is_asked_for() = runTest {
        // Given
        var passes = 0
        AutomaticReplay(scope = backgroundScope, engineLogger = logger) {
            passes++
            SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(IllegalStateException("500")))
        }

        // When
        advanceTimeBy(10.seconds)

        // Then — nothing retries on its own
        assertEquals(0, passes)
    }
}

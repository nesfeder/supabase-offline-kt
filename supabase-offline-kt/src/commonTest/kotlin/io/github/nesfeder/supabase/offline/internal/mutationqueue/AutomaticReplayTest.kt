// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.NetworkStatus
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
import kotlinx.io.IOException

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
    fun no_pass_runs_while_network_access_is_off() = runTest {
        // Given — the app turned network access off
        var status = NetworkStatus.DISABLED
        var passes = 0
        val automaticReplay =
            AutomaticReplay(
                scope = backgroundScope,
                engineLogger = logger,
                networkStatus = { status },
            ) {
                passes++
                SyncReport(0, emptyList(), 0, stop = null)
            }

        // When — passes are asked for, then access is back on
        repeat(3) { automaticReplay.requestPass() }
        advanceTimeBy(2.minutes)
        val whileOff = passes
        status = NetworkStatus.UNKNOWN
        automaticReplay.requestPassOnEdge()
        runCurrent()

        // Then — none ran while it was off, and the reconnection's ran at once
        assertEquals(0, whileOff)
        assertEquals(1, passes)
    }

    @Test
    fun while_the_monitor_says_offline_one_failed_attempt_is_made_and_the_wait_does_not_grow() =
        runTest {
            // Given — the monitor says offline, and every pass fails for want of a connection
            var status = NetworkStatus.OFFLINE
            val passTimes = mutableListOf<Long>()
            var online = false
            val automaticReplay =
                AutomaticReplay(
                    scope = backgroundScope,
                    engineLogger = logger,
                    networkStatus = { status },
                ) {
                    passTimes += testScheduler.currentTime
                    val stop =
                        if (online) null else SyncStop.TransientFailure(IOException("offline"))
                    SyncReport(0, emptyList(), 1, stop)
                }

            // When — writes ask for passes over ten minutes, then the app syncs, then the network
            // is back
            repeat(10) {
                automaticReplay.requestPass()
                advanceTimeBy(1.minutes)
            }
            val inTheOfflinePeriod = passTimes.size
            automaticReplay.allowAnOfflineAttempt()
            automaticReplay.requestPass()
            runCurrent()
            val afterTheAppSynced = passTimes.size
            advanceTimeBy(1.minutes)
            online = true
            status = NetworkStatus.ONLINE
            val reconnectedAt = testScheduler.currentTime
            automaticReplay.requestPass()
            runCurrent()

            // Then — one attempt in the offline period, one more after the app's sync, and none
            // of them left a wait: the pass once online ran at once
            assertEquals(1, inTheOfflinePeriod)
            assertEquals(2, afterTheAppSynced)
            assertEquals(reconnectedAt, passTimes.last())
        }

    @Test
    fun while_the_monitor_says_offline_passes_that_reach_the_server_keep_running() = runTest {
        // Given — the monitor wrongly says offline; the server answers
        var passes = 0
        val automaticReplay =
            AutomaticReplay(
                scope = backgroundScope,
                engineLogger = logger,
                networkStatus = { NetworkStatus.OFFLINE },
            ) {
                passes++
                SyncReport(1, emptyList(), 0, stop = null)
            }

        // When — writes ask for passes over time
        repeat(5) {
            automaticReplay.requestPass()
            advanceTimeBy(10.seconds)
        }

        // Then — every one ran: a wrong monitor holds nothing back
        assertEquals(5, passes)
    }

    @Test
    fun an_edge_while_the_monitor_says_offline_allows_another_attempt() = runTest {
        // Given — an attempt made while the monitor says offline failed for want of a connection
        var passes = 0
        val automaticReplay =
            AutomaticReplay(
                scope = backgroundScope,
                engineLogger = logger,
                networkStatus = { NetworkStatus.OFFLINE },
            ) {
                passes++
                SyncReport(0, emptyList(), 1, SyncStop.TransientFailure(IOException("offline")))
            }
        automaticReplay.requestPass()
        runCurrent()

        // When — a write asks again, then the session settles anew
        automaticReplay.requestPass()
        runCurrent()
        val beforeTheEdge = passes
        automaticReplay.requestPassOnEdge()
        runCurrent()

        // Then — the write's request was skipped and the edge's ran
        assertEquals(1, beforeTheEdge)
        assertEquals(2, passes)
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

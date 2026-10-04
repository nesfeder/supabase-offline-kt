// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * After a clear, the rows it deleted must not be readable from the database's files. The rewrite
 * runs under the clear's locks and tries the write-ahead log once; a read that holds the log is
 * waited for briefly outside the locks, then in the background, then once more at close. Nothing
 * here fails its caller or waits without bound.
 */
class DatabaseScrubTest {

    private val logs = RecordingLogSink()

    @Test
    fun a_rewrite_that_empties_the_log_leaves_nothing_more_to_do() = runTest {
        // Given
        var logTries = 0
        val scrub =
            DatabaseScrub(
                scope = backgroundScope,
                engineLogger = logs.asEngineLogger(),
                rewrite = { true },
                emptyLog = {
                    logTries++
                    true
                },
            )

        // When
        scrub.rewriteAfterDelete()
        scrub.finishSoon()
        advanceTimeBy(1.minutes)
        scrub.finishBeforeClose()

        // Then
        assertEquals(0, logTries)
        assertEquals(emptyList(), logs.entries.filter { it.level >= EngineLogLevel.INFO })
    }

    @Test
    fun a_read_holding_the_log_is_waited_for_a_second_then_in_the_background() = runTest {
        // Given — a read holds the log until it is let go
        var held = true
        var logTries = 0
        var emptied = false
        val scrub =
            DatabaseScrub(
                scope = backgroundScope,
                engineLogger = logs.asEngineLogger(),
                rewrite = { false },
                emptyLog = {
                    logTries++
                    emptied = !held
                    emptied
                },
            )

        // When — the clear's part ends, then the read is let go a while later
        scrub.rewriteAfterDelete()
        val clearStarted = currentTime
        scrub.finishSoon()
        val clearTook = currentTime - clearStarted
        advanceTimeBy(30.seconds)
        held = false
        advanceTimeBy(6.seconds)
        val triesWhenEmptied = logTries
        advanceTimeBy(1.minutes)

        // Then — the clear waited a second at most, said so, and the background emptied the log
        // once it could, then stopped trying
        assertTrue(clearTook in 1_000L..1_100L, "the clear waited $clearTook ms")
        assertTrue(
            logs.entries.any { it.level == EngineLogLevel.INFO && "open read" in it.message }
        )
        assertTrue(emptied, "the background never emptied the log")
        assertEquals(triesWhenEmptied, logTries)
        assertEquals(emptyList(), logs.entries.filter { it.level >= EngineLogLevel.WARNING })
    }

    @Test
    fun a_clear_cancelled_while_it_waits_still_leaves_the_log_to_the_background() = runTest {
        // Given — a read holds the log
        var held = true
        var emptied = false
        val scrub =
            DatabaseScrub(
                scope = backgroundScope,
                engineLogger = logs.asEngineLogger(),
                rewrite = { false },
                emptyLog = {
                    emptied = !held
                    emptied
                },
            )
        scrub.rewriteAfterDelete()

        // When — the clear's caller is cancelled during its wait, then the read ends
        val clear = launch { scrub.finishSoon() }
        advanceTimeBy(200.milliseconds)
        clear.cancelAndJoin()
        held = false
        advanceTimeBy(10.seconds)

        // Then — the background emptied the log all the same
        assertTrue(emptied, "nothing emptied the log after the cancelled clear")
    }

    @Test
    fun a_failed_rewrite_is_tried_again_outside_the_locks_and_logged_once() = runTest {
        // Given — the rewrite fails twice, then succeeds
        var rewrites = 0
        val scrub =
            DatabaseScrub(
                scope = backgroundScope,
                engineLogger = logs.asEngineLogger(),
                rewrite = {
                    rewrites++
                    if (rewrites <= 2) error("Scripted failure of the rewrite") else true
                },
                emptyLog = { true },
            )

        // When
        scrub.rewriteAfterDelete()
        scrub.finishSoon()

        // Then — rewritten on the third try, one warning, by type
        assertEquals(3, rewrites)
        val warnings = logs.entries.filter { it.level >= EngineLogLevel.WARNING }
        assertEquals(1, warnings.size, "$warnings")
        assertTrue("IllegalStateException" in warnings.single().message)
    }

    @Test
    fun close_tries_once_more_and_warns_when_a_read_still_holds_the_log() = runTest {
        // Given — a read holds the log for good, and the background is not running
        var logTries = 0
        val scrub =
            DatabaseScrub(
                scope = backgroundScope,
                engineLogger = logs.asEngineLogger(),
                rewrite = { false },
                emptyLog = {
                    logTries++
                    false
                },
            )
        scrub.rewriteAfterDelete()
        val triesBeforeClose = logTries

        // When
        scrub.finishBeforeClose()

        // Then
        assertEquals(triesBeforeClose + 1, logTries)
        assertTrue(
            logs.entries.any { it.level == EngineLogLevel.WARNING && "until close" in it.message }
        )
    }
}

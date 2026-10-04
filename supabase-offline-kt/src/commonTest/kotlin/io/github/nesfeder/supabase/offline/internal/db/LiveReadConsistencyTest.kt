// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.assertNone
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * A live list stays one consistent view of the cache, the queue and the refresh over the in-memory
 * tables, whatever order their changes reach it in.
 */
class LiveReadConsistencyTest {

    private val openClient: OpenOfflineClient = { supabase, config ->
        newTestOfflineClient(
            supabase = supabase,
            config = config,
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
        )
    }
    private val configFor: (Int) -> Offline.Config = { Offline.createConfig { autoSync = false } }

    @Test
    fun an_applied_keyed_insert_is_shown_once() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.anAppliedInsertIsShownOnce(
                    openClient,
                    configFor,
                    keyed = true,
                    returnsRows = false,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun an_applied_keyed_insert_returning_its_rows_is_shown_once() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.anAppliedInsertIsShownOnce(
                    openClient,
                    configFor,
                    keyed = true,
                    returnsRows = true,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun an_applied_keyless_insert_is_shown_once() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.anAppliedInsertIsShownOnce(
                    openClient,
                    configFor,
                    keyed = false,
                    returnsRows = false,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun an_applied_keyless_insert_returning_its_rows_is_shown_once() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.anAppliedInsertIsShownOnce(
                    openClient,
                    configFor,
                    keyed = false,
                    returnsRows = true,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun a_replayed_insert_shows_in_a_list_opened_after_the_sync() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.aReplayedInsertShowsInAListOpenedAfterTheSync(
                    openClient,
                    configFor,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun a_cold_refresh_is_idle_only_with_the_rows_it_fetched() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.aRefreshIsIdleOnlyWithTheRowsItFetched(
                    openClient,
                    configFor,
                    warm = false,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    @Test
    fun a_warm_refresh_is_idle_only_with_the_rows_it_fetched() =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.aRefreshIsIdleOnlyWithTheRowsItFetched(
                    openClient,
                    configFor,
                    warm = true,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    private companion object {
        /** Iterations per test, each one chance for the sources to reach the list out of order. */
        const val ITERATIONS = 10
    }
}

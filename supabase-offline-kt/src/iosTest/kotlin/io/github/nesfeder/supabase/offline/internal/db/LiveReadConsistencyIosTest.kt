// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.assertNone
import io.github.nesfeder.supabase.offline.internal.openPlatformClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * A live list stays one consistent view of the cache, the queue and the refresh over the database
 * the iOS open path opens, on this build's SQLite, whatever order their changes reach it in.
 */
class LiveReadConsistencyIosTest {

    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-live-read-${Random.nextLong().toULong()}"
    private val openClient: OpenOfflineClient = { supabase, config ->
        openPlatformClient(supabase, config)
    }
    private val configFor: (Int) -> Offline.Config = { iteration ->
        Offline.createConfig {
            autoSync = false
            dataDirectory = (directory / "client-$iteration").toString()
        }
    }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun an_applied_keyed_insert_is_shown_once() = appliedInsert(keyed = true, returnsRows = false)

    @Test
    fun an_applied_keyed_insert_returning_its_rows_is_shown_once() =
        appliedInsert(keyed = true, returnsRows = true)

    @Test
    fun an_applied_keyless_insert_is_shown_once() =
        appliedInsert(keyed = false, returnsRows = false)

    @Test
    fun an_applied_keyless_insert_returning_its_rows_is_shown_once() =
        appliedInsert(keyed = false, returnsRows = true)

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

    @Test fun a_cold_refresh_is_idle_only_with_the_rows_it_fetched() = refresh(warm = false)

    @Test fun a_warm_refresh_is_idle_only_with_the_rows_it_fetched() = refresh(warm = true)

    private fun appliedInsert(keyed: Boolean, returnsRows: Boolean) =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.anAppliedInsertIsShownOnce(
                    openClient,
                    configFor,
                    keyed,
                    returnsRows,
                    ITERATIONS,
                ),
                ITERATIONS,
            )
        }

    private fun refresh(warm: Boolean) =
        runTest(timeout = 5.minutes) {
            assertNone(
                LiveReadConsistency.aRefreshIsIdleOnlyWithTheRowsItFetched(
                    openClient,
                    configFor,
                    warm,
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

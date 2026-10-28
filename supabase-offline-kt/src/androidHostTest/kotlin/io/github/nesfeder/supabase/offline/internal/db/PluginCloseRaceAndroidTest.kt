// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.internal.ApplicationContextInitializer
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith

/**
 * The close races, with every client built through `install(Offline)` on the Android database and
 * closed through `SupabaseClient.close()`, which closes supabase-kt's HTTP client before the
 * plugin: no caller gets a cancellation it did not cause.
 */
@RunWith(AndroidJUnit4::class)
class PluginCloseRaceAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "offline-plugin-close-race-${Random.nextLong().toULong()}")

    @BeforeTest
    fun setUp() {
        // androidx.startup's provider does not run under Robolectric; record the context as it
        // would
        ApplicationContextInitializer().create(context)
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_clear_racing_close_completes_or_is_refused_and_never_crashes() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.aClearRacingClose(
                directory.toOkioPath(),
                openThroughPlugin,
                iterations = RACE_ITERATIONS,
            )
        }

    @Test
    fun a_write_in_flight_when_close_runs_is_answered_and_never_sent_again() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.aWriteInFlightWhenCloseRuns(directory.toOkioPath(), openThroughPlugin)
        }

    @Test
    fun objects_obtained_before_close_refuse_after_it() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.objectsObtainedBeforeCloseRefuseAfterIt(
                directory.toOkioPath(),
                openThroughPlugin,
            )
        }

    @Test
    fun flows_made_after_close_fail_with_the_closed_error_when_collected() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.flowsMadeAfterCloseFailWhenCollected(
                directory.toOkioPath(),
                openThroughPlugin,
            )
        }

    @Test
    fun reads_racing_close_all_end_with_the_closed_error_and_never_crash() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.readersRacingClose(
                directory.toOkioPath(),
                openThroughPlugin,
                iterations = RACE_ITERATIONS,
            )
        }

    @Test
    fun a_logout_with_a_live_read_clears_closes_and_ends_the_read() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.logoutWithALiveRead(
                directory.toOkioPath(),
                openThroughPlugin,
                iterations = RACE_ITERATIONS,
            )
        }

    private companion object {
        /** As many races as the platform's own close race class runs. */
        const val RACE_ITERATIONS = 150
    }
}

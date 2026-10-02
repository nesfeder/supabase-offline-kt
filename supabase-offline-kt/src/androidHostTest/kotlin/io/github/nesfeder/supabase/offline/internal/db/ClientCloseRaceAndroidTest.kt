// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.internal.openAndroidClient
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith

/** `close()` never closes the database the Android factory opened under a call still using it. */
@RunWith(AndroidJUnit4::class)
class ClientCloseRaceAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "offline-close-race-${Random.nextLong().toULong()}")
    private val openClient: OpenOfflineClient = { supabase, config ->
        openAndroidClient(context, supabase, config)
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_clear_racing_close_completes_or_is_refused_and_never_crashes() =
        runTest(timeout = 10.minutes) {
            val (completed, refused) =
                ClientCloseRaces.aClearRacingClose(
                    directory.toOkioPath(),
                    openClient,
                    iterations = RACE_ITERATIONS,
                )
            println("clear racing close: $completed completed, $refused refused")
        }

    @Test
    fun a_write_in_flight_when_close_runs_is_answered_and_never_sent_again() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.aWriteInFlightWhenCloseRuns(directory.toOkioPath(), openClient)
        }

    @Test
    fun objects_obtained_before_close_refuse_after_it() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.objectsObtainedBeforeCloseRefuseAfterIt(
                directory.toOkioPath(),
                openClient,
            )
        }

    @Test
    fun reads_racing_close_all_end_with_the_closed_error_and_never_crash() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.readersRacingClose(
                directory.toOkioPath(),
                openClient,
                iterations = RACE_ITERATIONS,
            )
        }

    @Test
    fun a_logout_with_a_live_read_clears_closes_and_ends_the_read() =
        runTest(timeout = 10.minutes) {
            ClientCloseRaces.logoutWithALiveRead(
                directory.toOkioPath(),
                openClient,
                iterations = RACE_ITERATIONS,
            )
        }

    private companion object {
        /** The reviews needed 150 races to see each crash. */
        const val RACE_ITERATIONS = 150
    }
}

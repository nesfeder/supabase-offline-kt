package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** `close()` never closes the database the Android factory opened under a call still using it. */
@RunWith(AndroidJUnit4::class)
class ClientCloseRaceAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "offline-close-race-${Random.nextLong().toULong()}")
    private val openClient: OpenOfflineClient = { supabase, config -> createSupabaseOfflineClient(context, supabase, config) }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_clear_racing_close_completes_or_is_refused_and_never_crashes() = runTest(timeout = 10.minutes) {
        val (completed, refused) = ClientCloseRaces.aClearRacingClose(directory.toOkioPath(), openClient, iterations = RACE_ITERATIONS)
        println("clear racing close: $completed completed, $refused refused")
    }

    @Test
    fun a_write_in_flight_when_close_runs_is_answered_and_never_sent_again() = runTest(timeout = 10.minutes) {
        ClientCloseRaces.aWriteInFlightWhenCloseRuns(directory.toOkioPath(), openClient)
    }

    @Test
    fun objects_obtained_before_close_refuse_after_it() = runTest(timeout = 10.minutes) {
        ClientCloseRaces.objectsObtainedBeforeCloseRefuseAfterIt(directory.toOkioPath(), openClient)
    }

    private companion object {
        /** The review needed 150 races to see the crash. */
        const val RACE_ITERATIONS = 150
    }
}

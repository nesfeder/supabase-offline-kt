package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** `close()` never closes the database the iOS factory opened, on this build's SQLite, under a call still using it. */
class ClientCloseRaceIosTest {

    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-close-race-${Random.nextLong().toULong()}"
    private val openClient: OpenOfflineClient = { supabase, config -> createSupabaseOfflineClient(supabase, config) }

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun a_clear_racing_close_completes_or_is_refused_and_never_crashes() = runTest(timeout = 10.minutes) {
        val (completed, refused) = ClientCloseRaces.aClearRacingClose(directory, openClient, iterations = RACE_ITERATIONS)
        println("clear racing close: $completed completed, $refused refused")
    }

    @Test
    fun a_write_in_flight_when_close_runs_is_answered_and_never_sent_again() = runTest(timeout = 10.minutes) {
        ClientCloseRaces.aWriteInFlightWhenCloseRuns(directory, openClient)
    }

    @Test
    fun objects_obtained_before_close_refuse_after_it() = runTest(timeout = 10.minutes) {
        ClientCloseRaces.objectsObtainedBeforeCloseRefuseAfterIt(directory, openClient)
    }

    private companion object {
        /** The review needed 150 races to see the crash. */
        const val RACE_ITERATIONS = 150
    }
}

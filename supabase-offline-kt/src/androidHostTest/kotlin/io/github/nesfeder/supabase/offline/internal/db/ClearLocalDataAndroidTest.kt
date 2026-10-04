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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toOkioPath
import org.junit.runner.RunWith

/**
 * Clearing local data leaves nothing of the cleared user, the mutation queue's reserved rows
 * included, readable in the files of the database the Android factory opens.
 */
@RunWith(AndroidJUnit4::class)
class ClearLocalDataAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "offline-clear-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun nothing_of_a_cleared_user_can_be_read_back_from_the_database_files() = runTest {
        // Given / When — cached reads and a queued write carrying a marker, then a clear
        val report =
            runClearLocalDataForensics(directory.toOkioPath()) { supabase, config ->
                openAndroidClient(context, supabase, config)
            }

        // Then — the mutation queue's reserved rows were there, and nothing is left of any of it
        assertTrue(
            report.reservedRowsBeforeClear >= 2,
            "the queue's reserved rows were not present: $report",
        )
        assertEquals(0, report.reservedRowsAfterClear, "a reserved row survived the clear: $report")
        assertFalse(report.anyRecoverable, "a cleared user's data can be read back: $report")
    }

    @Test
    fun a_read_holding_the_log_neither_holds_the_clear_nor_keeps_the_rows_once_it_ends() = runTest {
        // Given / When — rows carrying a marker, cleared while another connection holds a read
        val report =
            runClearUnderAHeldRead(directory.toOkioPath()) { supabase, config ->
                openAndroidClient(context, supabase, config)
            }

        // Then — the read did hold the log, the clear did not wait it out, and once the read
        // ended the rows left the files with the client still open
        assertTrue(report.heldReadKeptTheMarker, "the read did not hold the log: $report")
        assertTrue(report.clearTook < CLEAR_BOUND, "the clear waited for the read: $report")
        assertTrue(report.cleanAfterTheReadEnded, "the cleared rows stayed in the files: $report")
    }

    private companion object {
        /** Under Room's three-second busy timeout: the clear never waits it out. */
        val CLEAR_BOUND = 2.5.seconds
    }
}

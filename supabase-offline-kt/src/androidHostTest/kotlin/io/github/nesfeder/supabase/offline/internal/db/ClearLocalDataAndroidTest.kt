// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:Suppress("DEPRECATION")

package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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
                createSupabaseOfflineClient(context, supabase, config)
            }

        // Then — the mutation queue's reserved rows were there, and nothing is left of any of it
        assertTrue(
            report.reservedRowsBeforeClear >= 2,
            "the queue's reserved rows were not present: $report",
        )
        assertEquals(0, report.reservedRowsAfterClear, "a reserved row survived the clear: $report")
        assertFalse(report.anyRecoverable, "a cleared user's data can be read back: $report")
    }
}

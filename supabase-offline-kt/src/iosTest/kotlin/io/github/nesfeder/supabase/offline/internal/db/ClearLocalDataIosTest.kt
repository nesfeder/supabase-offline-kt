// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM

/**
 * Clearing local data leaves nothing of the cleared user, the mutation queue's reserved rows
 * included, readable in the files of the database the iOS factory opens with this build's SQLite.
 */
class ClearLocalDataIosTest {

    private val directory =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-clear-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun nothing_of_a_cleared_user_can_be_read_back_from_the_database_files() = runTest {
        // Given / When — cached reads and a queued write carrying a marker, then a clear
        val report =
            runClearLocalDataForensics(directory) { supabase, config ->
                createSupabaseOfflineClient(supabase, config)
            }

        // Then — the mutation queue's reserved rows were there, and nothing is left of any of it
        assertTrue(
            report.reservedRowsBeforeClear >= 2,
            "the queue's reserved rows were not present: $report",
        )
        assertEquals(0, report.reservedRowsAfterClear, "a reserved row survived the clear: $report")
        assertFalse(
            report.anyRecoverable,
            "a cleared user's data can be read back with the ${SqliteVariant.NAME} SQLite: $report",
        )
    }
}

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.createSupabaseOfflineClient
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * Clearing local data leaves nothing of the cleared user readable in the raw database files, on the
 * database the iOS factory opens with this build's SQLite — bundled, or Apple's, whose default
 * `secure_delete` is FAST and leaves freed pages as they were: not in the database, its `-wal` or
 * its `-shm`, while the client is open or after it closes.
 */
class ClearLocalDataIosTest {

    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-clear-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun nothing_of_a_cleared_user_can_be_read_back_from_the_database_files() = runTest {
        // Given / When — cached reads and a queued write carrying a marker, then a clear
        val report = runClearLocalDataForensics(directory) { supabase, config -> createSupabaseOfflineClient(supabase, config) }

        // Then
        assertFalse(report.anyRecoverable, "a cleared user's data can be read back with the ${SqliteVariant.NAME} SQLite: $report")
    }
}

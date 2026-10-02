// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

/**
 * `install(Offline)` on iOS: the plugin opens the real Room database in its data directory, keeps
 * it out of backups, and holds it for one client at a time.
 *
 * Every case uses its own data directory, so none touches the simulator's real Documents folder.
 */
class OfflinePluginIosTest {

    private val directory: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "supabase-offline-ios-plugin-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    /** A client with PostgREST and the plugin in [directory]; its engine is never reached here. */
    private fun pluginClient(): SupabaseClient = newTestSupabaseClient {
        install(Postgrest)
        install(Offline) { dataDirectory = directory.toString() }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun isExcludedFromBackup(path: Path): Boolean {
        val values =
            NSURL.fileURLWithPath(path.toString())
                .resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
        return (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue ?: false
    }

    @Test
    fun the_database_is_opened_in_the_data_directory_and_kept_out_of_backups() = runTest {
        // Given
        val supabase = pluginClient()

        // When — the first query opens the database
        assertEquals(0, supabase.offline.pendingMutationCount())

        // Then
        assertTrue(FileSystem.SYSTEM.exists(directory / "supabase_offline.db"), "no database")
        assertTrue(isExcludedFromBackup(directory / "supabase_offline.db"), "backed up")
        assertTrue(isExcludedFromBackup(directory / "storage"), "the file cache is backed up")
        supabase.close()
    }

    @Test
    fun a_second_client_on_the_same_data_directory_fails_until_the_first_closes() = runTest {
        // Given
        val first = pluginClient()

        // When
        val failure = assertFailsWith<IllegalStateException> { pluginClient() }
        first.close()

        // Then — the claim says why, and closing the first client frees the database
        assertTrue("already open" in failure.message.orEmpty(), "unclear: ${failure.message}")
        val second = pluginClient()
        assertEquals(0, second.offline.pendingMutationCount())
        second.close()
    }
}

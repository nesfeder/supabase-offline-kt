package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The iOS client's lifecycle and on-disk placement: one client per database, a configured data
 * directory that really holds the database, and no backup of the cache, the queue or the files.
 *
 * Every case uses its own data directory, so none touches the simulator's real Documents folder.
 */
class IosClientLifecycleTest {

    private val directory: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-ios-client-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    private fun config() = SupabaseOfflineConfig(offlineStoragePath = directory)

    @OptIn(ExperimentalForeignApi::class)
    private fun isExcludedFromBackup(path: Path): Boolean {
        val values = NSURL.fileURLWithPath(path.toString()).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
        return (values?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue ?: false
    }

    @Test
    fun a_second_client_on_the_same_database_fails_fast_while_the_first_is_open() {
        // Given
        createSupabaseOfflineClient(newTestSupabaseClient(), config()).use {
            // When / Then
            val failure = assertFailsWith<IllegalStateException> { createSupabaseOfflineClient(newTestSupabaseClient(), config()) }
            assertTrue("already open" in failure.message.orEmpty(), "unclear message: ${failure.message}")
        }
    }

    @Test
    fun closing_a_client_lets_another_open_the_same_database() = runTest {
        // Given
        val first = createSupabaseOfflineClient(newTestSupabaseClient(), config())
        assertEquals(0, first.pendingMutationCount())

        // When
        first.close()

        // Then
        createSupabaseOfflineClient(newTestSupabaseClient(), config()).use { second ->
            assertEquals(0, second.pendingMutationCount())
        }
    }

    @Test
    fun offlineStoragePath_moves_the_database_as_well_as_the_downloaded_files() = runTest {
        // Given
        createSupabaseOfflineClient(newTestSupabaseClient(), config()).use { client ->
            // When — the first query opens the database
            client.pendingMutationCount()
        }

        // Then
        assertTrue(FileSystem.SYSTEM.exists(directory / "supabase_offline.db"), "the database is not in offlineStoragePath")
    }

    @Test
    fun the_database_and_the_file_cache_are_excluded_from_backup() = runTest {
        // Given
        createSupabaseOfflineClient(newTestSupabaseClient(), config()).use { client ->
            // When — the first query opens the database
            client.pendingMutationCount()

            // Then — a restored backup must not bring one user's cache and queue to another device
            assertTrue(isExcludedFromBackup(directory / "supabase_offline.db"), "the database is backed up")
            assertTrue(isExcludedFromBackup(directory / "supabase_offline.db-wal"), "the database's WAL is backed up")
            assertTrue(isExcludedFromBackup(directory / "storage"), "the file cache is backed up")
            assertTrue(isExcludedFromBackup(directory / "storage-transformed"), "the transformed-image cache is backed up")
        }
    }

    @Test
    fun clearing_local_data_keeps_the_file_cache_out_of_backups() = runTest {
        // Given
        createSupabaseOfflineClient(newTestSupabaseClient(), config()).use { client ->
            // When — the clear deletes and re-creates the cache directories
            client.clearAllCache()

            // Then
            assertTrue(isExcludedFromBackup(directory / "storage"), "the re-created file cache is backed up")
        }
    }
}

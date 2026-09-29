package io.github.nesfeder.supabase.offline.internal.db

import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The file cache's storage-row queries against a real database, on this build's SQLite — so they
 * run on the bundled SQLite and, with `-Psupabase.offline.iosSqlite=system`, on the system one.
 */
class StorageQueriesIosTest {

    private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "supabase-offline-storage-queries-${Random.nextLong().toULong()}"

    @AfterTest
    fun tearDown() {
        FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
    }

    @Test
    fun the_storage_queries_return_the_same_rows_on_this_sqlite() = runTest {
        // Given
        FileSystem.SYSTEM.createDirectories(directory)
        val database = getRoomDatabase(getDatabaseBuilder((directory / "supabase_offline.db").toString()))

        // When
        val results = try {
            database.runStorageQueries()
        } finally {
            database.close()
        }

        // Then
        assertEquals(EXPECTED_STORAGE_QUERY_RESULTS, results)
    }
}

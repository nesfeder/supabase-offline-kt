// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The in-memory file-cache rows, which `keepLocalDataInMemory` keeps, answer the file cache's
 * queries as SQLite does: the scenario the platform tests run on a real database.
 */
class StorageQueriesInMemoryTest {

    @Test
    fun the_in_memory_rows_answer_the_file_cache_queries_as_a_database_does() = runTest {
        // Given
        val dao = InMemoryCachedFileDao()

        // When
        val results = dao.runStorageQueries()

        // Then
        assertEquals(EXPECTED_STORAGE_QUERY_RESULTS, results)
    }
}

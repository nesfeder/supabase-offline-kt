// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/** The file cache's storage-row queries against a real database on the bundled SQLite. */
@RunWith(AndroidJUnit4::class)
class StorageQueriesAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory = File(context.cacheDir, "storage-queries-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun the_storage_queries_return_the_same_rows_on_the_bundled_sqlite() = runTest {
        // Given
        directory.mkdirs()
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(context, File(directory, "supabase_offline.db").absolutePath)
            )

        // When
        val results =
            try {
                database.runStorageQueries()
            } finally {
                database.close()
            }

        // Then
        assertEquals(EXPECTED_STORAGE_QUERY_RESULTS, results)
    }
}

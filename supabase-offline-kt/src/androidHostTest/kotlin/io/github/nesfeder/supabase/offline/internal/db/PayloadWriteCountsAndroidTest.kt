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

/**
 * On a real database, a write made inside a transaction of the counting view is counted once Room
 * has committed it, not as its statement returns: Room's transaction keeps the caller's coroutine
 * context, which the view holds the counts in.
 */
@RunWith(AndroidJUnit4::class)
class PayloadWriteCountsAndroidTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val directory =
        File(context.cacheDir, "payload-write-counts-${Random.nextLong().toULong()}")

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun a_write_inside_a_room_transaction_is_counted_once_it_has_committed() = runTest {
        // Given
        directory.mkdirs()
        val database =
            buildOfflineDatabase(
                offlineDatabaseBuilder(context, File(directory, "supabase_offline.db").path)
            )
        try {
            val view = PayloadWriteCountingDao(database.queryCacheDao())
            val before = view.writesTo(TABLE)
            var inside: Pair<Long, Long>? = null

            // When — two writes to the table in one transaction
            view.runInTransaction {
                view.upsert(CachedQueryRow(TABLE, "first", "[]", updatedAt = 1L))
                view.upsert(CachedQueryRow(TABLE, "second", "[]", updatedAt = 1L))
                inside = view.writesTo(TABLE)
            }

            // Then — neither was counted inside it; the table once after, with both rows there
            assertEquals(before, inside)
            assertEquals(before.first to before.second + 1, view.writesTo(TABLE))
            assertEquals(setOf("first", "second"), view.cacheKeysForTable(TABLE).toSet())
        } finally {
            database.close()
        }
    }

    private companion object {
        const val TABLE = "tasks"
    }
}

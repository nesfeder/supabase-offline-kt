// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.testing.InMemoryQueryCacheDao
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The counts a live reader hears of payload writes through: a write to its table, or to every
 * table, moves them; another table's does not; and a write inside a transaction moves them only
 * once the transaction has returned, so no reader reads before the commit.
 */
class PayloadWriteCountsTest {

    private val dao = PayloadWriteCountingDao(InMemoryQueryCacheDao())

    @Test
    fun a_reader_hears_of_its_tables_writes_and_every_tables_but_not_anothers() = runTest {
        // Given — a reader of one table, which hears once at once
        val heard = heardOf(TABLE)
        assertEquals(1, heard.size)

        // When / Then — another table's write, and a dropped orphaned shape record, are not heard
        dao.upsert(rowOf(OTHER_TABLE))
        dao.deleteOrphanedQueryShapes()
        runCurrent()
        assertEquals(1, heard.size)
        // and its own table's, a clear and a change counted for every table are
        dao.upsert(rowOf(TABLE))
        runCurrent()
        dao.deleteAll()
        runCurrent()
        dao.countWriteToEveryTable()
        runCurrent()
        assertEquals(4, heard.size)
    }

    @Test
    fun a_write_inside_a_transaction_is_counted_once_it_has_returned() = runTest {
        // Given
        val before = dao.writesTo(TABLE)
        var inside: Pair<Long, Long>? = null

        // When — two writes in one transaction, the second in a transaction of its own within it
        dao.runInTransaction {
            dao.upsert(rowOf(TABLE))
            dao.runInTransaction { dao.upsert(rowOf(TABLE, cacheKey = "second")) }
            inside = dao.writesTo(TABLE)
        }

        // Then — neither was counted before the outer one returned; then the table once
        assertEquals(before, inside)
        assertEquals(before.first to before.second + 1, dao.writesTo(TABLE))
    }

    @Test
    fun a_failed_transaction_is_counted_too() = runTest {
        // Given
        val before = dao.writesTo(TABLE)

        // When — a transaction writes, then fails and is rolled back
        assertFailsWith<IllegalStateException> {
            dao.runInTransaction {
                dao.upsert(rowOf(TABLE))
                error("scripted failure")
            }
        }

        // Then — its readers read again, and find the row gone
        assertEquals(before.first to before.second + 1, dao.writesTo(TABLE))
        assertNull(dao.get(TABLE, CACHE_KEY))
    }

    /** Each time a reader of [table] hears of a write, collected in the test's background. */
    private fun TestScope.heardOf(table: String): List<Unit> {
        val heard = mutableListOf<Unit>()
        backgroundScope.launch { dao.writeChangesOf(table).collect { heard += it } }
        runCurrent()
        return heard
    }

    private companion object {
        const val TABLE = "tasks"
        const val OTHER_TABLE = "projects"
        const val CACHE_KEY = "list"

        fun rowOf(table: String, cacheKey: String = CACHE_KEY) =
            CachedQueryRow(table = table, cacheKey = cacheKey, payload = "[]", updatedAt = 1L)
    }
}

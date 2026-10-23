// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

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

    @Test
    fun a_write_that_outlives_its_transaction_is_counted_as_it_returns() = runTest {
        // Given — the context a transaction's block runs in, kept past its end
        var blockContext: CoroutineContext? = null
        dao.runInTransaction { blockContext = currentCoroutineContext() }
        val before = dao.writesTo(TABLE)

        // When — a write made in that context, but for its finished job, once the transaction has
        // returned
        withContext(blockContext!!.minusKey(Job)) { dao.upsert(rowOf(TABLE)) }

        // Then — it is counted at once
        assertEquals(before.first to before.second + 1, dao.writesTo(TABLE))
    }

    @Test
    fun an_eviction_pass_is_counted_against_the_tables_it_deleted_from_alone() = runTest {
        // Given — a bounded cache whose oldest lists are all another table's
        val table = FakeQueryCacheDao()
        repeat(10) { index ->
            table.seedPayload(OTHER_TABLE, "old-$index", arrayOfLength(1_000), index.toLong())
        }
        table.seedPayload(TABLE, CACHE_KEY, arrayOfLength(1_000), updatedAt = 100L)
        var clock = 1_000L
        val store =
            newTestStore(
                queryCacheDao = table,
                queryCacheLimits =
                    QueryCacheLimits(maxPayloadBytes = 5_000L, evictionHeadroomBytes = 0L),
                nowMillis = { clock++ },
                backgroundScope = backgroundScope,
            )
        val before = store.queryCacheDao.writesTo(TABLE)

        // When — a response of the other table goes over the bound, and the pass it starts runs
        store.queryCache.cacheResponse(OTHER_TABLE, "new", arrayOfLength(1_000))
        runCurrent()

        // Then — the other table's oldest lists went, and the table's readers heard of nothing
        assertEquals(4, table.rowsOf(OTHER_TABLE).size)
        assertEquals(before, store.queryCacheDao.writesTo(TABLE))
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

        /** A payload of [length] characters shaped as a JSON array. */
        fun arrayOfLength(length: Int): String = "[" + "x".repeat(length - 2) + "]"

        fun rowOf(table: String, cacheKey: String = CACHE_KEY) =
            CachedQueryRow(table = table, cacheKey = cacheKey, payload = "[]", updatedAt = 1L)
    }
}

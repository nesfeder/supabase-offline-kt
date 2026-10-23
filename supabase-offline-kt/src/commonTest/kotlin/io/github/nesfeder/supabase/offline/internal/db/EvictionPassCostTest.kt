// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.PayloadScanCountingDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * What the size bound costs the table: a response is returned before the pass it starts runs, a
 * write within the bound reads no payload once the running count is seeded, a pass reads every
 * payload's length once and deletes what it chose in one transaction, and a burst of writes costs
 * one pass. Before this, every response of 4 KiB or more summed every payload's length before it
 * was returned, and a pass summed them again after each ten rows it deleted: 52 sums and 50 delete
 * statements, none in a transaction, for the 500 rows below.
 */
class EvictionPassCostTest {

    private val fake = FakeQueryCacheDao()
    private val counting = PayloadScanCountingDao(fake)

    /** Stamps each written response later than every seeded row. */
    private var clock = 1_000L

    @Test
    fun the_pass_runs_after_the_response_is_returned() = runTest {
        // Given 600 cached lists at six times the bound
        seedRows(600, length = 1_000)
        val store = store(maxPayloadBytes = 100_000L)

        // When a response is written
        val outcome = store.queryCache.cacheResponse(TABLE, "items?page=new", arrayOfLength(1_000))

        // Then it is written and returned before any payload is read or deleted
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(0, counting.payloadLengthReads)
        assertEquals(601, fake.rowsOf(TABLE).size)

        // And the pass it started runs in the background: one read, the oldest lists gone
        runCurrent()
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(100, fake.rowsOf(TABLE).size)
        assertNotNull(fake.getRow(TABLE, "items?page=new"))
    }

    @Test
    fun a_write_within_the_bound_reads_no_payload_once_the_count_is_seeded() = runTest {
        // Given a cache well within its bound
        seedRows(10, length = 1_000)
        val store = store(maxPayloadBytes = 500_000L)

        // When the first response of the process is written, and the background runs
        store.queryCache.cacheResponse(TABLE, "items?page=first", arrayOfLength(4_096))
        runCurrent()

        // Then the count was seeded by one read of the lengths, off the read path
        assertEquals(1, counting.payloadLengthReads)

        // When 40 more responses of the size that once summed the cache each time are written
        repeat(40) { index ->
            store.queryCache.cacheResponse(TABLE, "items?page=w$index", arrayOfLength(4_096))
        }
        runCurrent()

        // Then nothing was read, summed or deleted: the count said the cache is within its bound
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(0, counting.payloadSums)
        assertEquals(0, counting.evictionDeletes)
    }

    @Test
    fun a_pass_over_500_rows_reads_the_lengths_once_and_deletes_in_one_transaction() = runTest {
        // Given 600 cached lists of 1,000 characters against a bound of 100,000
        seedRows(600, length = 1_000)
        val store = store(maxPayloadBytes = 100_000L)

        // When a pass runs
        store.queryCache.evictOverBudget()

        // Then it read every length once, summed nothing, and deleted the 500 oldest lists with
        // one statement in one transaction
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(0, counting.payloadSums)
        assertEquals(1, counting.transactions)
        assertEquals(1, counting.evictionDeletes)
        assertEquals(500, fake.evictedRows)
        assertEquals(
            (500 until 600).map { index -> "items?page=$index" },
            fake.rowsOf(TABLE).map { row -> row.cacheKey },
        )
    }

    @Test
    fun a_burst_of_writes_over_the_bound_runs_one_pass() = runTest {
        // Given an empty cache with a bound
        val store = store(maxPayloadBytes = 50_000L)

        // When twenty responses arrive at once, together twice the bound, and the background runs
        repeat(20) { index ->
            store.queryCache.cacheResponse(TABLE, "items?page=$index", arrayOfLength(5_000))
        }
        runCurrent()

        // Then one pass ran for the burst, and left the newest half
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(1, counting.transactions)
        assertEquals(
            (10 until 20).map { index -> "items?page=$index" },
            fake.rowsOf(TABLE).map { row -> row.cacheKey },
        )
    }

    @Test
    fun after_a_clear_the_next_write_reads_the_lengths_again() = runTest {
        // Given a cache whose count is seeded
        seedRows(10, length = 1_000)
        val store = store(maxPayloadBytes = 100_000L)
        store.queryCache.cacheResponse(TABLE, "items?page=a", arrayOfLength(1_000))
        runCurrent()
        assertEquals(1, counting.payloadLengthReads)

        // When local data is cleared and a response is written
        store.localData.clearLocalData()
        store.queryCache.cacheResponse(TABLE, "items?page=b", arrayOfLength(1_000))
        runCurrent()

        // Then the count was read again, once, and the next write trusts it
        assertEquals(2, counting.payloadLengthReads)
        store.queryCache.cacheResponse(TABLE, "items?page=c", arrayOfLength(1_000))
        runCurrent()
        assertEquals(2, counting.payloadLengthReads)
        assertEquals(0, counting.evictionDeletes)
    }

    /** Seeds [count] lists of [TABLE], `items?page=0` the oldest, each [length] characters. */
    private fun seedRows(count: Int, length: Int) {
        repeat(count) { index ->
            fake.seedPayload(TABLE, "items?page=$index", arrayOfLength(length), index.toLong())
        }
    }

    /** A store over the counting DAO whose eviction pass runs in the test's background scope. */
    private fun TestScope.store(maxPayloadBytes: Long): TestStore =
        newTestStore(
            queryCacheDao = counting,
            queryCacheLimits =
                QueryCacheLimits(maxPayloadBytes = maxPayloadBytes, evictionHeadroomBytes = 0L),
            nowMillis = { clock++ },
            backgroundScope = backgroundScope,
        )

    /** A payload of [length] characters shaped as a JSON array, the only shape the cache writes. */
    private fun arrayOfLength(length: Int): String = "[" + "x".repeat(length - 2) + "]"

    private companion object {
        const val TABLE = "items"
    }
}

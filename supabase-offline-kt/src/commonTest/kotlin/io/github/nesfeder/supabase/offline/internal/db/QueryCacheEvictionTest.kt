// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.PayloadScanCountingDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Tests for [QueryCache]'s size-based eviction — the pass that keeps the object cache within
 * [QueryCacheLimits.maxPayloadBytes].
 *
 * Eviction is driven by the cache's **logical** size (summed payload length) rather than the
 * on-disk file size, which never shrinks after `DELETE`. A pass reads every payload's length once,
 * oldest fetch first, chooses what to delete and deletes it in one transaction, so it ends by
 * construction; it reads again only while writes during the pass keep the count over the bound, and
 * a bounded number of times. These tests lock in the oldest-first order, the unlimited-cache
 * short-circuit, the small-cache hysteresis handling, that a write of any size starts a pass once
 * the cache is past its bound, that every live reader's payload is kept, and deadlock-free
 * concurrent writes. The pass a written response starts runs in the test's background scope, so a
 * test that writes runs what is due (`runCurrent`) to see it.
 */
class QueryCacheEvictionTest {

    private val queryCacheDao = FakeQueryCacheDao()

    /** The DAO as the store sees it, counting what a pass reads and deletes. */
    private val counting = PayloadScanCountingDao(queryCacheDao)

    @Test
    fun eviction_removes_oldest_rows_until_within_limit_keeping_newest() = runTest {
        // Given a cache over its logical-size limit, with rows of known age and equal size
        seedRows(5, length = 100) // 0 = oldest ... 4 = newest
        val store = buildStore(maxPayloadBytes = 250L)

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then the cache is back within the limit
        assertTrue(queryCacheDao.totalPayloadBytes() <= 250L, "cache should be within the limit")
        // And the oldest rows were evicted while the newest survive
        assertNull(queryCacheDao.getRow("ratings", "sig-0"), "oldest row should be evicted")
        assertNull(queryCacheDao.getRow("ratings", "sig-1"))
        assertNull(queryCacheDao.getRow("ratings", "sig-2"))
        assertNotNull(queryCacheDao.getRow("ratings", "sig-3"), "newest rows should survive")
        assertNotNull(queryCacheDao.getRow("ratings", "sig-4"))
    }

    @Test
    fun eviction_over_an_empty_cache_reads_it_once_and_deletes_nothing() = runTest {
        // Given an empty cache with a bound
        val store = buildStore(maxPayloadBytes = 1_000L)

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then it read the lengths once, found nothing over the bound, and ran no delete
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(0, counting.evictionDeletes)
        assertEquals(0, counting.transactions)
        assertEquals(0, queryCacheDao.evictedRows)
    }

    @Test
    fun eviction_does_nothing_when_the_cache_is_exactly_at_its_limit() = runTest {
        // Given a cache whose summed length equals the limit
        seedRows(10, length = 100)
        val store = buildStore(maxPayloadBytes = 1_000L)

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then nothing is deleted: eviction starts only above the limit
        assertEquals(1, counting.payloadLengthReads)
        assertEquals(0, counting.evictionDeletes)
        assertEquals(10, queryCacheDao.rowsOf("ratings").size)
    }

    @Test
    fun eviction_not_blocked_when_min_retained_exceeds_small_limit() = runTest {
        // Given a small cache limit and a default-scale evictionHeadroomBytes far larger than it
        seedRows(4, length = 4_096)
        // 4 * 4096 = 16_384 bytes of payload; limit 8 KiB; minRetained 50 MB >> limit
        val store =
            buildStore(maxPayloadBytes = 8_192L, evictionHeadroomBytes = 50L * 1024L * 1024L)

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then the oversized evictionHeadroomBytes headroom coerced to a no-op and eviction still
        // ran
        assertTrue(
            queryCacheDao.totalPayloadBytes() <= 8_192L,
            "small cache should evict to the ceiling",
        )
        assertTrue(
            queryCacheDao.evictedRows >= 1,
            "eviction must not be blocked by evictionHeadroomBytes",
        )
    }

    @Test
    fun eviction_drains_below_the_ceiling_to_the_hysteresis_floor() = runTest {
        // Given a cache over its limit with a headroom configured well below the limit
        seedRows(10, length = 100) // 0 = oldest ... 9 = newest
        // 10 * 100 = 1_000 bytes cached; ceiling 700; headroom 300 -> drain down to 400.
        val store = buildStore(maxPayloadBytes = 700L, evictionHeadroomBytes = 300L)

        // When enforcement runs after the cache crosses the ceiling
        store.queryCache.evictOverBudget()

        // Then eviction continues past the ceiling down to the hysteresis floor (ceiling minus
        // headroom), not merely to the ceiling — so subsequent writes don't immediately
        // re-trigger a pass.
        assertTrue(
            queryCacheDao.totalPayloadBytes() <= 400L,
            "eviction should drain to the hysteresis floor, not stop at the ceiling",
        )
        // And it stops at the floor rather than wiping the whole cache: rows above the floor go,
        // the newest rows within it survive.
        assertNull(
            queryCacheDao.getRow("ratings", "sig-5"),
            "rows above the floor should be evicted",
        )
        assertNotNull(
            queryCacheDao.getRow("ratings", "sig-6"),
            "the newest rows within the floor should survive",
        )
    }

    @Test
    fun unlimited_cache_never_evicts() = runTest {
        // Given a store whose cache size is unlimited
        seedRows(3, length = 1_000)
        val store = buildStore(maxPayloadBytes = null)

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then nothing is read or evicted
        assertEquals(0, counting.payloadLengthReads)
        assertEquals(0, queryCacheDao.evictedRows)
        assertNotNull(queryCacheDao.getRow("ratings", "sig-0"))
    }

    @Test
    fun add_payload_with_unlimited_cache_skips_enforcement() = runTest {
        // Given an unlimited cache
        val store = buildStore(maxPayloadBytes = null, nowMillis = incrementingClock())

        // When a payload large enough to otherwise start a pass is written, and the background
        // runs
        store.queryCache.cacheResponse("ratings", "sig", arrayOfLength(8_192))
        runCurrent()

        // Then no pass ran: nothing was read or deleted (kept off the hot path)
        assertEquals(0, counting.payloadLengthReads)
        assertEquals(0, counting.evictionDeletes)
    }

    @Test
    fun add_payload_larger_than_limit_evicts_older_entries() = runTest {
        // Given a small cache holding an older entry, its size counted
        val store = buildStore(maxPayloadBytes = 9_500L, nowMillis = incrementingClock())
        store.queryCache.cacheResponse("ratings", "old", arrayOfLength(800))
        runCurrent()

        // When a large payload pushes the cache over the limit, and the pass it starts runs
        store.queryCache.cacheResponse("ratings", "new", arrayOfLength(9_000))
        runCurrent()

        // Then the large write's enforcement evicted the older entry, keeping the newest
        assertTrue(queryCacheDao.totalPayloadBytes() <= 9_500L)
        assertNull(queryCacheDao.getRow("ratings", "old"), "older entry should be evicted")
        assertNotNull(
            queryCacheDao.getRow("ratings", "new"),
            "the just-written entry should survive",
        )
    }

    @Test
    fun add_payload_of_any_size_starts_a_pass_over_a_cache_past_its_bound() = runTest {
        // Given a cache already over its limit, left so by an earlier run
        queryCacheDao.seedPayload("ratings", "seed", "x".repeat(2_000), updatedAt = 0L)
        val store = buildStore(maxPayloadBytes = 1_000L, nowMillis = incrementingClock(from = 1L))

        // When one small write arrives — far below the size that once summed the cache at once —
        // and the background runs
        store.queryCache.cacheResponse("ratings", "small", "[1]")
        runCurrent()

        // Then the pass ran and took the old payload: no write is too small to start one
        assertEquals(1, counting.payloadLengthReads)
        assertNull(queryCacheDao.getRow("ratings", "seed"))
        assertNotNull(queryCacheDao.getRow("ratings", "small"))
        assertTrue(queryCacheDao.totalPayloadBytes() <= 1_000L)
    }

    @Test
    fun a_live_readers_payload_is_kept_however_many_readers_there_are() = runTest {
        // Given 600 cached lists far over the bound, 550 of them shown by live readers: more than
        // the 500 an SQL delete could once be told to keep
        seedRows(600, length = 100)
        val live = (0 until 550).mapTo(mutableSetOf()) { index -> "sig-$index" }
        val store = buildStore(maxPayloadBytes = 10_000L, allLiveCacheKeys = { live })

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then every shown list stayed, whatever its age, and only the unshown ones went
        val remaining =
            queryCacheDao.rowsOf("ratings").mapTo(mutableSetOf()) { row -> row.cacheKey }
        assertEquals(live, remaining)
        assertEquals(50, queryCacheDao.evictedRows)
        // And the pass stopped once nothing more could go, within its bounded reads
        assertTrue(counting.payloadLengthReads <= QueryCache.MAX_EVICTION_PASSES)
    }

    @Test
    fun writes_during_a_pass_are_counted_and_read_again_a_bounded_number_of_times() = runTest {
        // Given a cache at twice its bound, and writers that add 60_000 characters each time the
        // pass reads the lengths, so no pass can bring the count under the bound
        seedRows(20, length = 5_000)
        val store =
            buildStore(
                maxPayloadBytes = 50_000L,
                nowMillis = incrementingClock(from = 100L),
                backgroundScope = backgroundScope,
            )
        var late = 0
        counting.afterPayloadLengthsRead = {
            repeat(2) {
                store.queryCache.cacheResponse("ratings", "late-${late++}", arrayOfLength(30_000))
            }
        }

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then it read the lengths again for the writes it had not seen, and stopped at the cap
        // rather than chase them
        assertEquals(QueryCache.MAX_EVICTION_PASSES, counting.payloadLengthReads)

        // And the pass the late writes queued drains the cache once the writers stop
        counting.afterPayloadLengthsRead = null
        runCurrent()
        assertTrue(queryCacheDao.totalPayloadBytes() <= 50_000L)
        assertEquals(QueryCache.MAX_EVICTION_PASSES + 1, counting.payloadLengthReads)
    }

    @Test
    fun concurrent_add_payload_calls_do_not_deadlock() = runTest {
        // Given a small cache and many large concurrent writers, each of which may start a pass
        val store = buildStore(maxPayloadBytes = 40_960L, nowMillis = incrementingClock())

        // When 50 writes race through, and the passes they queued run
        val writers =
            List(50) { index ->
                launch {
                    store.queryCache.cacheResponse("ratings", "sig-$index", arrayOfLength(4_096))
                }
            }
        writers.forEach { writer -> writer.join() }
        runCurrent()

        // Then every writer completed without deadlocking and the cache stayed bounded
        assertTrue(queryCacheDao.totalPayloadBytes() <= 40_960L, "cache should remain bounded")
    }

    /** Seeds [count] rows of `ratings`, `sig-0` the oldest, each [length] characters. */
    private fun seedRows(count: Int, length: Int) {
        repeat(count) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(length),
                updatedAt = index.toLong(),
            )
        }
    }

    /**
     * Builds the components over the counting DAO with the given eviction settings; the pass a
     * write starts runs in the test's background scope.
     */
    private fun TestScope.buildStore(
        maxPayloadBytes: Long?,
        evictionHeadroomBytes: Long = 0L,
        nowMillis: () -> Long = { 0L },
        allLiveCacheKeys: () -> Set<String> = { emptySet() },
        backgroundScope: kotlinx.coroutines.CoroutineScope = this.backgroundScope,
    ): TestStore =
        newTestStore(
            queryCacheDao = counting,
            queryCacheLimits =
                QueryCacheLimits(
                    maxPayloadBytes = maxPayloadBytes,
                    evictionHeadroomBytes = evictionHeadroomBytes,
                ),
            nowMillis = nowMillis,
            allLiveCacheKeys = allLiveCacheKeys,
            backgroundScope = backgroundScope,
        )

    /** A payload of [length] characters shaped as a JSON array, the only shape the cache writes. */
    private fun arrayOfLength(length: Int): String = "[" + "x".repeat(length - 2) + "]"

    /** A monotonically increasing clock so each write receives a distinct, ordered timestamp. */
    private fun incrementingClock(from: Long = 0L): () -> Long {
        var tick = from
        return { tick++ }
    }
}

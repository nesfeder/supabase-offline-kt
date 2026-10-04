// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Tests for [OfflineStore]'s size-based eviction — the loop that keeps the object cache within
 * [QueryCacheLimits.maxPayloadBytes].
 *
 * The core guarantee is termination: eviction is driven by the cache's **logical** size (summed
 * payload length) rather than the on-disk file size, which never shrinks after `DELETE` and
 * previously made the loop spin forever holding the eviction mutex once the cache first crossed its
 * limit. These tests lock in that termination (progress guard and hard iteration cap), the
 * oldest-first eviction order, the unlimited-cache short-circuit, the small-cache hysteresis
 * handling, and deadlock-free concurrent writes.
 */
class OfflineStoreEvictionTest {

    @Test
    fun eviction_removes_oldest_rows_until_within_limit_keeping_newest() = runTest {
        // Given a cache over its logical-size limit, with rows of known age and equal size
        val queryCacheDao = FakeQueryCacheDao()
        repeat(5) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(), // 0 = oldest ... 4 = newest
            )
        }
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 250L,
                evictionBatchSize = 1,
            )

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
    fun eviction_terminates_when_measured_size_stays_over_limit_but_table_is_empty() = runTest {
        // Given an empty cache whose measured size is pinned above the limit — the exact
        // pathological condition that made file-size-based eviction spin forever.
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.fixedTotalPayloadBytes = 10_000L
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 1_000L,
                evictionBatchSize = 10,
            )

        // When enforcement runs it must return rather than spin
        store.queryCache.evictOverBudget()

        // Then the progress guard stopped it after the first zero-row delete batch
        assertEquals(1, queryCacheDao.deleteCallCount, "should stop after one empty delete batch")
        assertEquals(0, queryCacheDao.evictedRows)
    }

    @Test
    fun eviction_stops_at_iteration_cap_when_deletes_never_reduce_measured_size() = runTest {
        // Given a degenerate store where every delete claims progress but the measured size stays
        // pinned above the limit forever, so only the hard iteration cap can stop the loop.
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.fixedTotalPayloadBytes = 10_000L
        queryCacheDao.forcedDeleteCount = 5
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 1_000L,
                evictionBatchSize = 5,
            )

        // When enforcement runs it must still terminate
        store.queryCache.evictOverBudget()

        // Then the loop was bounded by the hard iteration cap (belt-and-braces backstop)
        assertEquals(
            1_000,
            queryCacheDao.deleteCallCount,
            "loop must be bounded by the iteration cap",
        )
    }

    @Test
    fun eviction_does_nothing_when_the_cache_is_exactly_at_its_limit() = runTest {
        // Given a cache whose measured size equals the limit
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.fixedTotalPayloadBytes = 1_000L
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 1_000L,
                evictionBatchSize = 5,
            )

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then nothing is deleted: eviction starts only above the limit
        assertEquals(0, queryCacheDao.deleteCallCount)
    }

    @Test
    fun eviction_not_blocked_when_min_retained_exceeds_small_limit() = runTest {
        // Given a small cache limit and a default-scale evictionHeadroomBytes far larger than it
        val queryCacheDao = FakeQueryCacheDao()
        repeat(4) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(4_096),
                updatedAt = index.toLong(),
            )
        }
        // 4 * 4096 = 16_384 bytes of payload; limit 8 KiB; minRetained 50 MB >> limit
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 8_192L,
                evictionBatchSize = 1,
                evictionHeadroomBytes = 50L * 1024L * 1024L,
            )

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
        val queryCacheDao = FakeQueryCacheDao()
        repeat(10) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(), // 0 = oldest ... 9 = newest
            )
        }
        // 10 * 100 = 1_000 bytes cached; ceiling 700; headroom 300 -> drain down to 400.
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 700L,
                evictionBatchSize = 1,
                evictionHeadroomBytes = 300L,
            )

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
        val queryCacheDao = FakeQueryCacheDao()
        repeat(3) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(1_000),
                updatedAt = index.toLong(),
            )
        }
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = null,
                evictionBatchSize = 10,
            )

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then nothing is measured or evicted
        assertEquals(0, queryCacheDao.deleteCallCount)
        assertEquals(0, queryCacheDao.evictedRows)
        assertNotNull(queryCacheDao.getRow("ratings", "sig-0"))
    }

    @Test
    fun add_payload_with_unlimited_cache_skips_enforcement() = runTest {
        // Given an unlimited cache
        val queryCacheDao = FakeQueryCacheDao()
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = null,
                nowMillis = incrementingClock(),
            )

        // When a payload large enough to otherwise trigger enforcement is written
        store.queryCache.cacheResponse("ratings", "sig", "x".repeat(8_192))

        // Then enforcement was skipped entirely (kept off the hot path)
        assertEquals(0, queryCacheDao.deleteCallCount)
    }

    @Test
    fun add_payload_larger_than_limit_evicts_older_entries() = runTest {
        // Given a small cache holding an older entry
        val queryCacheDao = FakeQueryCacheDao()
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 9_500L,
                evictionBatchSize = 1,
                nowMillis = incrementingClock(),
            )
        store.queryCache.cacheResponse("ratings", "old", "x".repeat(800))

        // When a large payload pushes the cache over the limit
        store.queryCache.cacheResponse("ratings", "new", "x".repeat(9_000))

        // Then the large write's enforcement evicted the older entry, keeping the newest
        assertTrue(queryCacheDao.totalPayloadBytes() <= 9_500L)
        assertNull(queryCacheDao.getRow("ratings", "old"), "older entry should be evicted")
        assertNotNull(
            queryCacheDao.getRow("ratings", "new"),
            "the just-written entry should survive",
        )
    }

    @Test
    fun add_payload_enforces_on_the_write_interval_for_small_writes() = runTest {
        // Given a cache already over its limit, receiving only small writes — none individually
        // large enough to trigger an enforcement pass on its own
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "seed",
            payload = "x".repeat(2_000),
            updatedAt = 0L,
        )
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 1_000L,
                evictionBatchSize = 50,
                nowMillis = incrementingClock(),
            )

        // When 31 small writes arrive, the periodic backstop has not yet fired
        repeat(31) { index -> store.queryCache.cacheResponse("ratings", "small-$index", "x") }
        assertEquals(0, queryCacheDao.deleteCallCount, "no enforcement before the write interval")

        // When the 32nd write arrives, the rolling counter reaches the interval
        store.queryCache.cacheResponse("ratings", "small-31", "x")

        // Then the periodic backstop triggered an eviction pass off the per-write hot path
        assertTrue(queryCacheDao.deleteCallCount >= 1, "enforcement should fire on the 32nd write")
    }

    @Test
    fun eviction_makes_progress_with_non_positive_batch_size() = runTest {
        // Given an over-limit cache misconfigured with a non-positive eviction batch size
        val queryCacheDao = FakeQueryCacheDao()
        repeat(5) { index ->
            queryCacheDao.seedPayload(
                table = "ratings",
                cacheKey = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(),
            )
        }
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 250L,
                evictionBatchSize = 0,
            )

        // When enforcement runs
        store.queryCache.evictOverBudget()

        // Then the batch size is coerced to at least one row so eviction still drains to the
        // limit instead of stalling (a zero-row batch would trip the progress guard immediately
        // and leave the cache permanently over budget)
        assertTrue(
            queryCacheDao.totalPayloadBytes() <= 250L,
            "eviction must make progress despite a non-positive batch size",
        )
        assertTrue(
            queryCacheDao.evictedRows >= 1,
            "a non-positive batch size must not disable eviction",
        )
    }

    @Test
    fun concurrent_add_payload_calls_do_not_deadlock() = runTest {
        // Given a small cache and many large concurrent writers, each of which triggers eviction
        val queryCacheDao = FakeQueryCacheDao()
        val store =
            buildStore(
                queryCacheDao = queryCacheDao,
                maxPayloadBytes = 40_960L,
                evictionBatchSize = 5,
                nowMillis = incrementingClock(),
            )

        // When 50 writes race through the eviction mutex
        val writers =
            List(50) { index ->
                launch {
                    store.queryCache.cacheResponse("ratings", "sig-$index", "x".repeat(4_096))
                }
            }
        writers.forEach { writer -> writer.join() }

        // Then every writer completed without deadlocking and the cache stayed bounded
        assertTrue(queryCacheDao.totalPayloadBytes() <= 40_960L, "cache should remain bounded")
    }

    /**
     * Build an [OfflineStore] wired to the supplied [queryCacheDao] with the given eviction
     * settings.
     */
    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        maxPayloadBytes: Long?,
        evictionBatchSize: Int = 10,
        evictionHeadroomBytes: Long = 0L,
        nowMillis: () -> Long = { 0L },
    ): OfflineStore =
        newTestOfflineStore(
            queryCacheDao = queryCacheDao,
            queryCacheLimits =
                QueryCacheLimits(
                    maxPayloadBytes = maxPayloadBytes,
                    evictionBatchSize = evictionBatchSize,
                    evictionHeadroomBytes = evictionHeadroomBytes,
                ),
            nowMillis = nowMillis,
        )

    /** A monotonically increasing clock so each write receives a distinct, ordered timestamp. */
    private fun incrementingClock(): () -> Long {
        var tick = 0L
        return { tick++ }
    }
}

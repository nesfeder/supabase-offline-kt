@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore

/**
 * Tests for [OfflineStore]'s size-based eviction — the loop that keeps the object cache within
 * [OfflineCacheSettings.maxDbSizeBytes].
 *
 * The core guarantee is termination: eviction is driven by the cache's **logical** size (summed
 * payload length) rather than the on-disk file size, which never shrinks after `DELETE` and
 * previously made the loop spin forever holding the eviction mutex once the cache first crossed
 * its limit. These tests lock in that termination (progress guard and hard iteration cap), the
 * oldest-first eviction order, the unlimited-cache short-circuit, the small-cache hysteresis
 * handling, and deadlock-free concurrent writes.
 */
class OfflineStoreEvictionTest {

    @Test
    fun eviction_removes_oldest_rows_until_within_limit_keeping_newest() = runTest {
        // Given a cache over its logical-size limit, with rows of known age and equal size
        val objectDao = FakeObjectDao()
        repeat(5) { index ->
            objectDao.seedPayload(
                table = "ratings",
                signature = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(), // 0 = oldest ... 4 = newest
            )
        }
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 250L,
            evictionBatchSize = 1,
        )

        // When enforcement runs
        store.enforceDbSizeLimitIfNeeded()

        // Then the cache is back within the limit
        assertTrue(objectDao.totalPayloadBytes() <= 250L, "cache should be within the limit")
        // And the oldest rows were evicted while the newest survive
        assertNull(objectDao.getRow("ratings", "sig-0"), "oldest row should be evicted")
        assertNull(objectDao.getRow("ratings", "sig-1"))
        assertNull(objectDao.getRow("ratings", "sig-2"))
        assertNotNull(objectDao.getRow("ratings", "sig-3"), "newest rows should survive")
        assertNotNull(objectDao.getRow("ratings", "sig-4"))
    }

    @Test
    fun eviction_terminates_when_measured_size_stays_over_limit_but_table_is_empty() = runTest {
        // Given an empty cache whose measured size is pinned above the limit — the exact
        // pathological condition that made file-size-based eviction spin forever.
        val objectDao = FakeObjectDao()
        objectDao.fixedTotalPayloadBytes = 10_000L
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 1_000L,
            evictionBatchSize = 10,
        )

        // When enforcement runs it must return rather than spin
        store.enforceDbSizeLimitIfNeeded()

        // Then the progress guard stopped it after the first zero-row delete batch
        assertEquals(1, objectDao.deleteCallCount, "should stop after one empty delete batch")
        assertEquals(0, objectDao.evictedRows)
    }

    @Test
    fun eviction_stops_at_iteration_cap_when_deletes_never_reduce_measured_size() = runTest {
        // Given a degenerate store where every delete claims progress but the measured size stays
        // pinned above the limit forever, so only the hard iteration cap can stop the loop.
        val objectDao = FakeObjectDao()
        objectDao.fixedTotalPayloadBytes = 10_000L
        objectDao.forcedDeleteCount = 5
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 1_000L,
            evictionBatchSize = 5,
        )

        // When enforcement runs it must still terminate
        store.enforceDbSizeLimitIfNeeded()

        // Then the loop was bounded by the hard iteration cap (belt-and-braces backstop)
        assertEquals(1_000, objectDao.deleteCallCount, "loop must be bounded by the iteration cap")
    }

    @Test
    fun eviction_not_blocked_when_min_retained_exceeds_small_limit() = runTest {
        // Given a small cache limit and a default-scale minRetainedBytes far larger than it
        val objectDao = FakeObjectDao()
        repeat(4) { index ->
            objectDao.seedPayload(
                table = "ratings",
                signature = "sig-$index",
                payload = "x".repeat(4_096),
                updatedAt = index.toLong(),
            )
        }
        // 4 * 4096 = 16_384 bytes of payload; limit 8 KiB; minRetained 50 MB >> limit
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 8_192L,
            evictionBatchSize = 1,
            minRetainedBytes = 50L * 1024L * 1024L,
        )

        // When enforcement runs
        store.enforceDbSizeLimitIfNeeded()

        // Then the oversized minRetainedBytes headroom coerced to a no-op and eviction still ran
        assertTrue(objectDao.totalPayloadBytes() <= 8_192L, "small cache should evict to the ceiling")
        assertTrue(objectDao.evictedRows >= 1, "eviction must not be blocked by minRetainedBytes")
    }

    @Test
    fun eviction_drains_below_the_ceiling_to_the_hysteresis_floor() = runTest {
        // Given a cache over its limit with a headroom configured well below the limit
        val objectDao = FakeObjectDao()
        repeat(10) { index ->
            objectDao.seedPayload(
                table = "ratings",
                signature = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(), // 0 = oldest ... 9 = newest
            )
        }
        // 10 * 100 = 1_000 bytes cached; ceiling 700; headroom 300 -> drain down to 400.
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 700L,
            evictionBatchSize = 1,
            minRetainedBytes = 300L,
        )

        // When enforcement runs after the cache crosses the ceiling
        store.enforceDbSizeLimitIfNeeded()

        // Then eviction continues past the ceiling down to the hysteresis floor (ceiling minus
        // headroom), not merely to the ceiling — so subsequent writes don't immediately
        // re-trigger a pass.
        assertTrue(
            objectDao.totalPayloadBytes() <= 400L,
            "eviction should drain to the hysteresis floor, not stop at the ceiling",
        )
        // And it stops at the floor rather than wiping the whole cache: rows above the floor go,
        // the newest rows within it survive.
        assertNull(objectDao.getRow("ratings", "sig-5"), "rows above the floor should be evicted")
        assertNotNull(
            objectDao.getRow("ratings", "sig-6"),
            "the newest rows within the floor should survive",
        )
    }

    @Test
    fun unlimited_cache_never_evicts() = runTest {
        // Given a store whose cache size is unlimited
        val objectDao = FakeObjectDao()
        repeat(3) { index ->
            objectDao.seedPayload(
                table = "ratings",
                signature = "sig-$index",
                payload = "x".repeat(1_000),
                updatedAt = index.toLong(),
            )
        }
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = null,
            evictionBatchSize = 10,
        )

        // When enforcement runs
        store.enforceDbSizeLimitIfNeeded()

        // Then nothing is measured or evicted
        assertEquals(0, objectDao.deleteCallCount)
        assertEquals(0, objectDao.evictedRows)
        assertNotNull(objectDao.getRow("ratings", "sig-0"))
    }

    @Test
    fun add_payload_with_unlimited_cache_skips_enforcement() = runTest {
        // Given an unlimited cache
        val objectDao = FakeObjectDao()
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = null,
            nowMillis = incrementingClock(),
        )

        // When a payload large enough to otherwise trigger enforcement is written
        store.addPayload("ratings", "sig", "x".repeat(8_192))

        // Then enforcement was skipped entirely (kept off the hot path)
        assertEquals(0, objectDao.deleteCallCount)
    }

    @Test
    fun add_payload_larger_than_limit_evicts_older_entries() = runTest {
        // Given a small cache holding an older entry
        val objectDao = FakeObjectDao()
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 9_500L,
            evictionBatchSize = 1,
            nowMillis = incrementingClock(),
        )
        store.addPayload("ratings", "old", "x".repeat(800))

        // When a large payload pushes the cache over the limit
        store.addPayload("ratings", "new", "x".repeat(9_000))

        // Then the large write's enforcement evicted the older entry, keeping the newest
        assertTrue(objectDao.totalPayloadBytes() <= 9_500L)
        assertNull(objectDao.getRow("ratings", "old"), "older entry should be evicted")
        assertNotNull(objectDao.getRow("ratings", "new"), "the just-written entry should survive")
    }

    @Test
    fun add_payload_enforces_on_the_write_interval_for_small_writes() = runTest {
        // Given a cache already over its limit, receiving only small writes — none individually
        // large enough to trigger an enforcement pass on its own
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "ratings",
            signature = "seed",
            payload = "x".repeat(2_000),
            updatedAt = 0L,
        )
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 1_000L,
            evictionBatchSize = 50,
            nowMillis = incrementingClock(),
        )

        // When 31 small writes arrive, the periodic backstop has not yet fired
        repeat(31) { index -> store.addPayload("ratings", "small-$index", "x") }
        assertEquals(0, objectDao.deleteCallCount, "no enforcement before the write interval")

        // When the 32nd write arrives, the rolling counter reaches the interval
        store.addPayload("ratings", "small-31", "x")

        // Then the periodic backstop triggered an eviction pass off the per-write hot path
        assertTrue(objectDao.deleteCallCount >= 1, "enforcement should fire on the 32nd write")
    }

    @Test
    fun eviction_makes_progress_with_non_positive_batch_size() = runTest {
        // Given an over-limit cache misconfigured with a non-positive eviction batch size
        val objectDao = FakeObjectDao()
        repeat(5) { index ->
            objectDao.seedPayload(
                table = "ratings",
                signature = "sig-$index",
                payload = "x".repeat(100),
                updatedAt = index.toLong(),
            )
        }
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 250L,
            evictionBatchSize = 0,
        )

        // When enforcement runs
        store.enforceDbSizeLimitIfNeeded()

        // Then the batch size is coerced to at least one row so eviction still drains to the
        // limit instead of stalling (a zero-row batch would trip the progress guard immediately
        // and leave the cache permanently over budget)
        assertTrue(
            objectDao.totalPayloadBytes() <= 250L,
            "eviction must make progress despite a non-positive batch size",
        )
        assertTrue(objectDao.evictedRows >= 1, "a non-positive batch size must not disable eviction")
    }

    @Test
    fun concurrent_add_payload_calls_do_not_deadlock() = runTest {
        // Given a small cache and many large concurrent writers, each of which triggers eviction
        val objectDao = FakeObjectDao()
        val store = buildStore(
            objectDao = objectDao,
            maxDbSizeBytes = 40_960L,
            evictionBatchSize = 5,
            nowMillis = incrementingClock(),
        )

        // When 50 writes race through the eviction mutex
        val writers = List(50) { index ->
            launch {
                store.addPayload("ratings", "sig-$index", "x".repeat(4_096))
            }
        }
        writers.forEach { writer -> writer.join() }

        // Then every writer completed without deadlocking and the cache stayed bounded
        assertTrue(objectDao.totalPayloadBytes() <= 40_960L, "cache should remain bounded")
    }

    /**
     * Build an [OfflineStore] wired to the supplied [objectDao] with the given eviction settings.
     * The file-oriented [DbPathProvider]/`dbSizeProvider` are stubbed since eviction now measures
     * logical payload bytes; they exist only for constructor compatibility.
     */
    private fun buildStore(
        objectDao: ObjectDao,
        maxDbSizeBytes: Long?,
        evictionBatchSize: Int = 10,
        minRetainedBytes: Long = 0L,
        nowMillis: () -> Long = { 0L },
    ): OfflineStore =
        newTestOfflineStore(
            objectDao = objectDao,
            settings = OfflineCacheSettings(
                maxDbSizeBytes = maxDbSizeBytes,
                evictionBatchSize = evictionBatchSize,
                minRetainedBytes = minRetainedBytes,
            ),
            nowMillis = nowMillis,
        )

    /** A monotonically increasing clock so each write receives a distinct, ordered timestamp. */
    private fun incrementingClock(): () -> Long {
        var tick = 0L
        return { tick++ }
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Tests for the stale-refresh watermark in [QueryCache.cacheResponse] — the guard that refuses a
 * network SELECT response whose request went out *before* the table's most recent local write.
 *
 * The hazard is ordering, not emptiness. A refresh starts at t0 against server state S0; at t1 a
 * local mutation succeeds online and [CachePatcher.patchCachedQueries] patches every cached payload
 * to S0+M; at t2 the refresh's S0 response arrives and overwrites that patch. Nothing replays M —
 * the online-success path uses an ephemeral overlay carrier and never enqueues — so the row stays
 * gone until some later SELECT happens to run. The user sees "I saved it and it vanished".
 *
 * The guard keys purely on **when the request started** relative to the write, which is what makes
 * it compose with the authoritative-empty accept rather than fight it: an in-flight refresh's
 * pre-insert `[]` is refused because it is stale, while an empty from a request that started after
 * the write still replaces cached rows and lets a remote delete converge.
 *
 * Every test drives the store's tick source by hand so the interleavings are exact rather than
 * timing-dependent.
 */
class OfflineStoreStaleRefreshWatermarkTest {

    private val oneProject = """[{"id":"project-1"}]"""
    private val bothProjects = """[{"id":"project-1"},{"id":"project-2"}]"""

    @Test
    fun stale_non_empty_response_is_dropped_and_the_local_write_survives() = runTest {
        // Given — a cached payload, and a refresh whose request went out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a local insert succeeds online while that request is still in flight
        tick = 20L
        store.insertProjectTwo()

        // And — the in-flight refresh's pre-insert response finally lands
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the response is refused and the just-saved row is still in the cache
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals(bothProjects, row.payload)
    }

    @Test
    fun stale_authoritative_empty_response_is_dropped() = runTest {
        // Given — nothing cached yet, and a refresh that went out at tick 10 (its response will
        // be the server's pre-insert `[]`, authoritative because the session was authenticated)
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a local insert materializes into the observed signature mid-flight
        tick = 20L
        store.insertProjectTwo(liveCacheKeys = setOf("sig-A"))

        // And — the pre-insert `[]` lands afterwards
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — staleness is decided before authority: the empty is refused rather than erasing
        // the row the user just saved
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"project-2"}]""", row.payload)
    }

    @Test
    fun response_started_after_the_local_write_is_applied() = runTest {
        // Given — a local insert landed at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        store.insertProjectTwo()

        // When — a refresh issued *after* that write returns the server's current rows
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the watermark does not stand in the way of genuinely fresh server state
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals(bothProjects, row.payload)
    }

    @Test
    fun authoritative_empty_started_after_the_local_write_still_replaces_cached_rows() = runTest {
        // Given — a local write at tick 10, after which every matching row is deleted remotely
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        store.insertProjectTwo()

        // When — a refresh issued after the write carries an authenticated session and returns []
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the authoritative-empty accept is untouched: the empty replaces the cached rows
        // so a delete performed on another device still converges here
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun response_is_applied_when_no_local_write_was_recorded() = runTest {
        // Given — a store that has seen no local mutation at all (the overwhelmingly common case)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()

        // When — a refresh lands
        tick = 20L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — no watermark, no drop
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(bothProjects, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun authoritative_empty_is_applied_when_no_local_write_was_recorded() = runTest {
        // Given — cached rows and no local mutation on record
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()

        // When — an auth-ready refresh reports the rows are gone
        tick = 20L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the empty lands, exactly as it did before the watermark existed
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals("[]", queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun writers_that_carry_no_request_tick_are_never_dropped() = runTest {
        // Given — a local write at tick 20 that would disqualify anything older
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 20L
        val store = buildStore(queryCacheDao, tick = { tick })
        store.insertProjectTwo()

        // When — a caller with no request behind it writes a payload (no tick to compare)
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
            )

        // Then — the write goes through; the guard only judges responses it can date
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(oneProject, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun the_watermark_is_scoped_per_table() = runTest {
        // Given — an in-flight refresh for `plans`, and a local write on `projects`
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        queryCacheDao.seedPayload("plans", "sig-P", """[{"id":"plan-1"}]""")
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 20L
        store.insertProjectTwo()

        // When — the `plans` refresh lands after the unrelated `projects` write
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "plans",
                cacheKey = "sig-P",
                payload = """[{"id":"plan-2"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — a write to one table never disqualifies another table's refreshes
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals("""[{"id":"plan-2"}]""", queryCacheDao.getRow("plans", "sig-P")?.payload)
    }

    @Test
    fun a_queue_replay_marked_synced_advances_the_watermark() = runTest {
        // Given — a mutation queued while offline, and a refresh already in flight
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, mutationDao = mutationDao, tick = { tick })
        store.mutationQueue.enqueueInsert(table = "projects", body = projectTwoBody())
        val queued = mutationDao.inserted.single()
        val requestStartedAtTick = store.freshness.tickNow()

        // When — reconnect drains the queue and the replay succeeds
        tick = 20L
        store.mutationQueue.recordReplayed(id = queued.id, table = "projects")

        // And — the refresh that was already in flight returns the pre-replay server state
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the replay counts as local truth too. Accepting here would rewrite the
        // pre-replay payload *and* retire the overlay in the same pass, losing the write twice
        // over; the queue-replay path writes no cache row of its own, so this is its only guard.
        assertEquals(CacheWriteOutcome.REFUSED_STALE, outcome)
    }

    @Test
    fun a_response_that_left_before_sign_out_never_repopulates_the_wiped_cache() = runTest {
        // Given — the signed-in user's rows are cached and a refresh went out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val requestStartedAtTick = store.freshness.tickNow()

        // When — the user signs out (or switches accounts) and the cache is torn down
        tick = 20L
        store.localData.clearLocalData()

        // And — the refresh that was still in flight delivers the outgoing user's rows
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = bothProjects,
                authoritativeEmpty = true,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — the response is refused and the wipe holds. Writing it would undo the teardown
        // and put one account's rows in front of the next user.
        assertEquals(CacheWriteOutcome.REFUSED_AFTER_CLEAR, outcome)
        assertNull(queryCacheDao.getRow("projects", "sig-A"))
    }

    @Test
    fun a_response_issued_after_teardown_is_written_normally() = runTest {
        // Given — a cache torn down at tick 10 (the next user has since signed in)
        val queryCacheDao = FakeQueryCacheDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        store.localData.clearLocalData()

        // When — a refresh issued after the teardown returns rows
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 30L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )

        // Then — teardown disqualifies only what was already in flight; the cache refills
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        assertEquals(oneProject, queryCacheDao.getRow("projects", "sig-A")?.payload)
    }

    @Test
    fun a_replay_that_lands_between_the_response_and_the_retirement_keeps_its_overlay() = runTest {
        // Given — a mutation queued while offline and a refresh that went out at tick 10
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, mutationDao = mutationDao, tick = { tick })
        store.mutationQueue.enqueueInsert(table = "projects", body = projectTwoBody())
        val queued = mutationDao.inserted.single()
        val requestStartedAtTick = store.freshness.tickNow()

        // When — that response is accepted (no watermark yet, so nothing disqualifies it)
        tick = 15L
        val outcome =
            store.queryCache.cacheResponse(
                table = "projects",
                cacheKey = "sig-A",
                payload = oneProject,
                requestStartedAtTick = requestStartedAtTick,
            )
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)

        // And — the queue drains in the gap before the resolver retires synced mutations
        tick = 20L
        store.mutationQueue.recordReplayed(id = queued.id, table = "projects")
        tick = 25L
        store.mutationQueue.retireCoveredSyncedMutations("projects", requestStartedAtTick)

        // Then — retirement is refused. The cached payload predates the replay, so dropping the
        // mutation row here would erase the write from the cache and the overlay at once.
        assertEquals(1, mutationDao.inserted.size)
        assertNotNull(mutationDao.inserted.single().syncedAtMs)
    }

    @Test
    fun synced_mutations_are_retired_when_no_replay_raced_the_response() = runTest {
        // Given — a mutation that replayed at tick 10, before the refresh even went out
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", oneProject)
        val mutationDao = FakeMutationDao()
        var tick = 10L
        val store = buildStore(queryCacheDao, mutationDao = mutationDao, tick = { tick })
        store.mutationQueue.enqueueInsert(table = "projects", body = projectTwoBody())
        val queued = mutationDao.inserted.single()
        store.mutationQueue.recordReplayed(id = queued.id, table = "projects")

        // When — a refresh issued afterwards brings back server state that already includes it
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()
        tick = 30L
        store.queryCache.cacheResponse(
            table = "projects",
            cacheKey = "sig-A",
            payload = bothProjects,
            requestStartedAtTick = requestStartedAtTick,
        )
        store.mutationQueue.retireCoveredSyncedMutations("projects", requestStartedAtTick)

        // Then — the ordinary retirement still happens; the queue does not grow without bound
        assertEquals(emptyList(), mutationDao.inserted)
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Applies the online-success insert of `project-2` — the write the stale response would erase.
     */
    private suspend fun TestStore.insertProjectTwo(liveCacheKeys: Set<String>? = null) {
        cachePatcher.patchCachedQueries(
            table = "projects",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"project-2"}]""",
            filterJson = null,
            defaultToNull = true,
            liveCacheKeys = liveCacheKeys,
        )
    }

    private fun projectTwoBody(): JsonArray =
        JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("project-2")))))

    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        mutationDao: MutationDao = FakeMutationDao(),
        tick: () -> Long,
    ): TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            monotonicTick = tick,
        )
}

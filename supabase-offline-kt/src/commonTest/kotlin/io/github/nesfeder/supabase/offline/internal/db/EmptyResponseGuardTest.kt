// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.CacheWriteOutcome
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest

/**
 * Tests for the empty-clobber guard in [QueryCache.cacheResponse]: **an unattributed empty never
 * persists.**
 *
 * The dominant cause of an empty SELECT response in practice is a cold-start auth race — a refresh
 * fires before the supabase-kt session has finished restoring, the request goes out without a
 * usable JWT, RLS filters every row, and the server returns `200 OK` with body `[]`. Written over
 * previously-cached good data, that response makes every reactive `selectAsFlow` observer re-emit
 * an empty list — a loading placeholder that never resolves, or a list screen that goes blank —
 * until the subscription is recreated. Written into a *cold* slot it is the same bug one step
 * earlier: nothing is overwritten, but the phantom empty becomes the answer every later cache-first
 * read is served. Both are refused, along with an empty over an already-empty slot (which would
 * only refresh the timestamp keeping the phantom alive).
 *
 * The guard is bypassed by `authoritativeEmpty = true` — the resolver's signal that the producing
 * request went out with an authenticated session, so an empty result is the server's real answer
 * (every matching row deleted, e.g. from another device, or the user genuinely has no rows). An
 * attributed empty writes into a cold slot too, which is how a legitimately-empty first read still
 * seeds the cache for collectors.
 */
class EmptyResponseGuardTest {

    @Test
    fun authoritative_empty_replaces_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload (e.g. the user's plan graph)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            table = "plans",
            cacheKey = "sig-A",
            payload = """[{"id":"plan-1"}]""",
        )
        val store = buildStore(queryCacheDao)

        // When — a refresh whose request carried an authenticated session returns an empty
        // array (every row matching the query was deleted from another device)
        store.queryCache.cacheResponse(
            table = "plans",
            cacheKey = "sig-A",
            payload = "[]",
            authoritativeEmpty = true,
        )

        // Then — the authoritative empty replaces the stale rows so observers converge
        val row = queryCacheDao.getRow("plans", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun authoritative_flag_has_no_effect_on_non_empty_payloads() = runTest {
        // Given — cache holds a stale non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("plans", "sig-A", """[{"id":"plan-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — an auth-ready refresh returns different non-empty data
        store.queryCache.cacheResponse(
            table = "plans",
            cacheKey = "sig-A",
            payload = """[{"id":"plan-2"}]""",
            authoritativeEmpty = true,
        )

        // Then — the new payload replaces the cached one exactly as an ordinary write would
        val row = queryCacheDao.getRow("plans", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"plan-2"}]""", row.payload)
    }

    @Test
    fun empty_response_does_not_clobber_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload from a previous successful refresh
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao)

        // When — a refresh fires before the auth session is restored and the server
        // responds 200 OK with an empty array (RLS-filtered)
        store.queryCache.cacheResponse(table = "ratings", cacheKey = "sig-A", payload = "[]")

        // Then — the previously-cached non-empty payload is preserved
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":1,"rating":80}]""", row.payload)
    }

    @Test
    fun whitespace_only_response_does_not_clobber_non_empty_cache() = runTest {
        // Given — cache holds a non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("projects", "sig-A", """[{"id":"project-1"}]""")
        val store = buildStore(queryCacheDao)

        // When — a refresh returns a whitespace-only body (some PostgREST proxy edge cases)
        store.queryCache.cacheResponse(table = "projects", cacheKey = "sig-A", payload = "   ")

        // Then — the cache row is unchanged
        val row = queryCacheDao.getRow("projects", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":"project-1"}]""", row.payload)
    }

    @Test
    fun unattributed_empty_is_dropped_when_no_prior_row_exists() = runTest {
        // Given — no cached row for this signature yet (first read of this query, cold slot)
        val queryCacheDao = FakeQueryCacheDao()
        val store = buildStore(queryCacheDao)

        // When — a refresh that could not attribute its emptiness to an authenticated session
        // returns `[]` (the restore-window / signed-out shape, indistinguishable on the wire
        // from a genuinely-empty table)
        val outcome =
            store.queryCache.cacheResponse(
                table = "attachments",
                cacheKey = "sig-A",
                payload = "[]",
            )

        // Then — nothing is written. A cold slot is exactly where an RLS-shaped empty does its
        // damage: with no prior row to protect, it used to be upserted with a fresh updatedAt and
        // every later cache-first read served that phantom empty as the user's data. A genuinely
        // empty table still seeds the cache — via `authoritativeEmpty`, not via this path.
        assertEquals(CacheWriteOutcome.REFUSED_EMPTY, outcome)
        assertNull(queryCacheDao.getRow("attachments", "sig-A"))
        assertEquals(0, queryCacheDao.writes)
    }

    @Test
    fun unattributed_empty_is_dropped_when_existing_row_is_already_empty() = runTest {
        // Given — the cache row already holds an empty `[]`
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", "[]")
        val store = buildStore(queryCacheDao)

        // When — an unattributed refresh returns an empty array
        val outcome =
            store.queryCache.cacheResponse(table = "ratings", cacheKey = "sig-A", payload = "[]")

        // Then — refused as well. Nothing is lost by dropping it (the payload is identical), and
        // rewriting would only stamp a fresh updatedAt, keeping a possibly-phantom empty alive
        // past the cache duration that would otherwise expire it.
        assertEquals(CacheWriteOutcome.REFUSED_EMPTY, outcome)
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
        assertEquals(0, queryCacheDao.writes)
    }

    @Test
    fun authoritative_empty_is_written_into_a_cold_slot() = runTest {
        // Given — no cached row for this signature yet
        val queryCacheDao = FakeQueryCacheDao()
        val store = buildStore(queryCacheDao)

        // When — the request carried an authenticated session and the user genuinely has no rows
        val outcome =
            store.queryCache.cacheResponse(
                table = "attachments",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
            )

        // Then — the attributed empty seeds the cold slot, so collectors observe an empty list
        // instead of waiting forever for a row that is never coming. This is the half of the
        // cold-slot rule that must NOT change: only *unattributed* empties are dropped.
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("attachments", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
    }

    @Test
    fun authoritative_empty_rewrites_an_already_empty_row_and_advances_its_timestamp() = runTest {
        // Given — a cache row holding an empty `[]` stamped long ago
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", "[]", updatedAt = 0L)
        val store = buildStore(queryCacheDao, nowMillis = { 5_000L })

        // When — an auth-ready refresh confirms the table is still empty
        val outcome =
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = "[]",
                authoritativeEmpty = true,
            )

        // Then — the row is rewritten so `updatedAt` moves forward. The attributed path must keep
        // doing this even though the unattributed one now refuses it: a cache-duration read that
        // has just re-confirmed the emptiness with the server should not immediately expire and
        // re-fetch, which is exactly what a frozen timestamp would cause.
        assertEquals(CacheWriteOutcome.WRITTEN, outcome)
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("[]", row.payload)
        assertEquals(5_000L, row.updatedAt)
    }

    @Test
    fun non_empty_response_overwrites_existing_non_empty_cache() = runTest {
        // Given — cache holds a stale non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — a refresh succeeds with new server data
        store.queryCache.cacheResponse(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":92}]""",
        )

        // Then — the new payload replaces the cached one
        val row = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(row)
        assertEquals("""[{"id":1,"rating":92}]""", row.payload)
    }

    @Test
    fun guard_is_scoped_per_cache_key() = runTest {
        // Given — the same table has two distinct cached signatures (e.g. one per user_id)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — an authoritative empty arrives for a *different* signature (no prior row)
        store.queryCache.cacheResponse(
            table = "ratings",
            cacheKey = "sig-B",
            payload = "[]",
            authoritativeEmpty = true,
        )

        // Then — sig-A is untouched and sig-B gets the empty payload as its initial seed: the
        // guard reasons per signature, not per table, so one query's emptiness never reaches
        // another query's rows
        val rowA = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(rowA)
        assertEquals("""[{"id":1,"rating":80}]""", rowA.payload)
        val rowB = queryCacheDao.getRow("ratings", "sig-B")
        assertNotNull(rowB)
        assertEquals("[]", rowB.payload)
    }

    @Test
    fun guard_does_not_block_explicit_clear_table() = runTest {
        // Given — cache holds a non-empty payload
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao)

        // When — the caller explicitly clears the table (e.g. on logout)
        store.clearTable("ratings")

        // Then — the cache row is removed; the empty-clobber guard only protects against
        // implicit empty SELECT responses, not explicit deletes
        assertNull(queryCacheDao.getRow("ratings", "sig-A"))
    }

    private fun buildStore(
        queryCacheDao: QueryCacheDao,
        nowMillis: () -> Long = { 0L },
    ): TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            nowMillis = nowMillis,
        )
}

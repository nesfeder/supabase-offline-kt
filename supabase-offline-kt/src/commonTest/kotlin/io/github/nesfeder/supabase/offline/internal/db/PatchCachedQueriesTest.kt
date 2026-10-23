// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.query.Order
import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.ServerRowChange
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Tests for [CachePatcher.patchCachedQueries] — the online-success overlay path that honors
 * [io.github.nesfeder.supabase.offline.MutationStrategy.QueueIfOffline]'s "UI reflects immediately"
 * promise without requiring a round-trip SELECT.
 *
 * The whole point is: after any successful online mutation, the `object_cache` payloads that drive
 * `selectAsFlow(...)` must be patched so reactive observers emit the new state within flow dispatch
 * latency — no tab switch, no pull-to-refresh.
 */
class PatchCachedQueriesTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── UPSERT ────────────────────────────────────────────────────────────────

    @Test
    fun upsert_patches_existing_row_in_cached_payload() = runTest {
        // Given — cache holds an older row for id 1
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — the same row is upserted online with a new rating
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":92}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
        )

        // Then — cached payload reflects the new rating
        val updated = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"rating":92}]""", updated.payload)
        // And — mutation_cache is not touched (this is the online-success path)
        assertEquals(0, mutationDao.inserted.size)
    }

    @Test
    fun upsert_appends_new_row_when_primary_key_not_in_cache() = runTest {
        // Given
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a different id is upserted
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":2,"rating":70}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
        )

        // Then — the new row is appended
        val updated = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"rating":80},{"id":2,"rating":70}]""", updated.payload)
    }

    @Test
    fun upsert_with_ignoreDuplicates_leaves_matching_cached_row_untouched() = runTest {
        // Given — cache holds the canonical server row for id 1 (e.g. a rating another
        // device already wrote)
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — an insert-if-missing (ON CONFLICT DO NOTHING) races the existing row. The
        // server preserved its row and returned nothing, so the overlay falls back to the
        // request body — which must NOT be merged over the canonical value.
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":92}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
            ignoreDuplicates = true,
        )

        // Then — cached payload still holds the canonical rating
        val updated = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"rating":80}]""", updated.payload)
    }

    @Test
    fun upsert_with_ignoreDuplicates_still_appends_genuinely_new_row() = runTest {
        // Given — cache holds a row for id 1 only
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "sig-A",
            payload = """[{"id":1,"rating":80}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — an insert-if-missing for an id the cache doesn't hold
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":2,"rating":70}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
            ignoreDuplicates = true,
        )

        // Then — the genuinely new row is appended; ignoreDuplicates only guards existing rows
        val updated = queryCacheDao.getRow("ratings", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"rating":80},{"id":2,"rating":70}]""", updated.payload)
    }

    @Test
    fun upsert_across_multiple_cached_queries_patches_all() = runTest {
        // Given — two live observers on the same table, under different filter signatures
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        queryCacheDao.seedPayload("ratings", "sig-B", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao, mutationDao)

        // When
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — both signatures got patched
        assertEquals(
            """[{"id":1,"rating":92}]""",
            queryCacheDao.getRow("ratings", "sig-A")?.payload,
        )
        assertEquals(
            """[{"id":1,"rating":92}]""",
            queryCacheDao.getRow("ratings", "sig-B")?.payload,
        )
    }

    @Test
    fun upsert_with_composite_onConflict_patches_targeted_row_in_user_scoped_payload() = runTest {
        // Given — a `ratings` cache populated under a user_id-only signature, as a per-user
        // reactive read would cache it. Two rows for two items; the
        // body shape mirrors what Supabase returns (id + user_id + item_id + nested payload).
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "ratings",
            cacheKey = "ratings?user_id=eq.user-uuid",
            payload =
                """[{"id":"r1","user_id":"user-uuid","item_id":1,"payload":{"rating":80}},""" +
                    """{"id":"r2","user_id":"user-uuid","item_id":2,"payload":{"rating":75}}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — re-rate for item 2 via a composite-key UPSERT (onConflict names the
        // table's natural key).
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson =
                """[{"id":"r2","user_id":"user-uuid","item_id":2,"payload":{"rating":92}}]""",
            filterJson = null,
            onConflict = "user_id,item_id",
        )

        // Then — item 2's row reflects the new rating; item 1 stays unchanged. The
        // updated payload is what a cold-start `selectAsFlow` collector would observe after
        // a process restart, since the same in-memory map backs the on-disk Room cache.
        val updated = queryCacheDao.getRow("ratings", "ratings?user_id=eq.user-uuid")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"item_id\":1"))
        assertTrue(
            updated.contains("\"rating\":80"),
            "item 1 rating should remain 80, got $updated",
        )
        assertTrue(updated.contains("\"item_id\":2"))
        assertTrue(updated.contains("\"rating\":92"), "item 2 rating should be 92, got $updated")
        assertFalse(updated.contains("\"rating\":75"), "stale rating 75 must not survive overlay")
    }

    @Test
    fun upsert_with_no_cached_payloads_is_noop() = runTest {
        // Given — empty cache (no live observers yet)
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(queryCacheDao, mutationDao)

        // When
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — nothing written, no crash
        assertEquals(0, queryCacheDao.writes)
        assertEquals(0, mutationDao.inserted.size)
    }

    // ── INSERT ────────────────────────────────────────────────────────────────

    @Test
    fun insert_appends_row_to_cached_payload() = runTest {
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload("notes", "sig-A", """[{"id":"a","notes":"one"}]""")
        val store = buildStore(queryCacheDao, mutationDao)

        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"b","notes":"two"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        assertEquals(
            """[{"id":"a","notes":"one"},{"id":"b","notes":"two"}]""",
            queryCacheDao.getRow("notes", "sig-A")?.payload,
        )
    }

    // ── Query-aware overlay gating ────────────────────────────────────────────

    @Test
    fun insert_does_not_land_in_a_cached_query_whose_eq_filter_excludes_it() = runTest {
        // Given — a cached exhaustive `type=eq.OPEN` query holding one OPEN row
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "notes",
            cacheKey = "notes?type=eq.OPEN",
            payload = """[{"id":"r1","type":"OPEN"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a DONE row is inserted online
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w1","type":"DONE"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — the DONE row is NOT appended to the OPEN-only query, and the cache is untouched
        assertEquals(
            """[{"id":"r1","type":"OPEN"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.OPEN")?.payload,
        )
        assertEquals(0, queryCacheDao.writes, "excluded row must not trigger a cache write")
    }

    @Test
    fun insert_lands_in_a_matching_exhaustive_query() = runTest {
        // Given — a cached exhaustive `type=eq.OPEN` query
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "notes",
            cacheKey = "notes?type=eq.OPEN",
            payload = """[{"id":"r1","type":"OPEN"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a matching OPEN row is inserted
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"r2","type":"OPEN"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — the OPEN row is appended
        assertEquals(
            """[{"id":"r1","type":"OPEN"},{"id":"r2","type":"OPEN"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.OPEN")?.payload,
        )
    }

    @Test
    fun insert_fans_out_only_to_the_matching_queries_of_several() = runTest {
        // Given — two live observers on the same table under mutually-exclusive type filters
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload("notes", "notes?type=eq.OPEN", """[{"id":"r1","type":"OPEN"}]""")
        queryCacheDao.seedPayload("notes", "notes?type=eq.DONE", """[{"id":"w1","type":"DONE"}]""")
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a DONE row is inserted online
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w2","type":"DONE"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — it lands only in the DONE query; the OPEN query is untouched
        assertEquals(
            """[{"id":"r1","type":"OPEN"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.OPEN")?.payload,
        )
        assertEquals(
            """[{"id":"w1","type":"DONE"},{"id":"w2","type":"DONE"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.DONE")?.payload,
        )
    }

    @Test
    fun insert_does_not_land_in_an_ordered_query_whose_filter_excludes_it() = runTest {
        // Given — an ordered `type=eq.OPEN` query
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "notes",
            cacheKey = "notes?type=eq.OPEN&order=created_at.desc",
            payload = """[{"id":"r1","type":"OPEN"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a DONE row is inserted
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w1","type":"DONE"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — the order does not stop the filter from excluding it
        assertEquals(
            """[{"id":"r1","type":"OPEN"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.OPEN&order=created_at.desc")?.payload,
        )
    }

    @Test
    fun upsert_append_is_gated_by_an_exhaustive_query() = runTest {
        // Given — a cached exhaustive `type=eq.OPEN` query
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "notes",
            cacheKey = "notes?type=eq.OPEN",
            payload = """[{"id":"r1","type":"OPEN"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — a DONE row is upserted (onConflict key doesn't match any cached row → append path)
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":"w1","type":"DONE"}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — the append is suppressed for the excluding query
        assertEquals(
            """[{"id":"r1","type":"OPEN"}]""",
            queryCacheDao.getRow("notes", "notes?type=eq.OPEN")?.payload,
        )
        assertEquals(
            0,
            queryCacheDao.writes,
            "excluded upsert-append must not trigger a cache write",
        )
    }

    @Test
    fun upsert_merge_into_matching_row_unchanged_under_an_exhaustive_query() = runTest {
        // Given — a cached exhaustive `type=eq.OPEN` query with an existing row
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "notes",
            cacheKey = "notes?type=eq.OPEN",
            payload = """[{"id":"r1","type":"OPEN","notes":"old"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        // When — the same row (which belongs in this query) is re-upserted with new data
        store.cachePatcher.patchCachedQueries(
            table = "notes",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":"r1","type":"OPEN","notes":"new"}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — the merge into the matching row is unaffected by the gate
        val updated = queryCacheDao.getRow("notes", "notes?type=eq.OPEN")?.payload
        assertNotNull(updated)
        assertTrue(
            updated.contains("\"notes\":\"new\""),
            "matching row should be merged, got $updated",
        )
        assertFalse(
            updated.contains("\"notes\":\"old\""),
            "stale value must not survive, got $updated",
        )
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Test
    fun update_patches_rows_matching_filter() = runTest {
        // Given — two projects in cache, filter targets only the second
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "projects",
            cacheKey = "sig-A",
            payload = """[{"id":"s1","retired":false},{"id":"s2","retired":false}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        val filter =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "s2")
                    ),
            )

        // When — update retire=true for id s2
        store.cachePatcher.patchCachedQueries(
            table = "projects",
            operation = MutationOperation.UPDATE,
            bodyJson = buildJsonObject { put("retired", JsonPrimitive(true)) }.toString(),
            filterJson = encodeFilter(filter, json),
        )

        // Then — only s2 is patched; s1 is unchanged
        val updated = queryCacheDao.getRow("projects", "sig-A")?.payload
        assertNotNull(updated)
        // Use substring checks since the overlay may preserve key order from the original row.
        assertTrue(updated.contains("\"id\":\"s1\"") && updated.contains("\"retired\":false"))
        assertTrue(updated.contains("\"id\":\"s2\"") && updated.contains("\"retired\":true"))
    }

    @Test
    fun update_with_is_null_condition_patches_unread_row_and_skips_read_row() = runTest {
        // Given — a summaries cache with one unread row (read_at is JSON null) and one
        // already-read row. Mirrors a first-write-wins "mark read" update's
        // filter: [eq user_id, eq period_id, read_at IS NULL].
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "summaries",
            cacheKey = "summaries?user_id=eq.user-uuid",
            payload =
                """[{"user_id":"user-uuid","period_id":"2026-P26","read_at":null},""" +
                    """{"user_id":"user-uuid","period_id":"2026-P25","read_at":"2026-06-22T08:00:00Z"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        fun markReadFilter(periodId: String) =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        FilterDescriptor.Condition(
                            column = "user_id",
                            operatorId = "eq",
                            value = "user-uuid",
                        ),
                        FilterDescriptor.Condition(
                            column = "period_id",
                            operatorId = "eq",
                            value = periodId,
                        ),
                        FilterDescriptor.Condition(
                            column = "read_at",
                            operatorId = "is",
                            value = "null",
                        ),
                    ),
            )

        // When — mark the unread period read, then re-mark the already-read period
        store.cachePatcher.patchCachedQueries(
            table = "summaries",
            operation = MutationOperation.UPDATE,
            bodyJson =
                buildJsonObject { put("read_at", JsonPrimitive("2026-06-29T09:00:00Z")) }
                    .toString(),
            filterJson = encodeFilter(markReadFilter("2026-P26"), json),
        )
        store.cachePatcher.patchCachedQueries(
            table = "summaries",
            operation = MutationOperation.UPDATE,
            bodyJson =
                buildJsonObject { put("read_at", JsonPrimitive("2026-06-29T09:05:00Z")) }
                    .toString(),
            filterJson = encodeFilter(markReadFilter("2026-P25"), json),
        )

        // Then — the unread row is stamped; the read row keeps its first-open timestamp
        val updated = queryCacheDao.getRow("summaries", "summaries?user_id=eq.user-uuid")?.payload
        assertNotNull(updated)
        assertTrue(
            updated.contains("\"read_at\":\"2026-06-29T09:00:00Z\""),
            "unread 2026-P26 row should be stamped, got $updated",
        )
        assertTrue(
            updated.contains("\"read_at\":\"2026-06-22T08:00:00Z\""),
            "already-read 2026-P25 row must keep its first-open timestamp, got $updated",
        )
        assertFalse(
            updated.contains("2026-06-29T09:05:00Z"),
            "IS NULL guard must skip the already-read row, got $updated",
        )
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    fun delete_removes_rows_matching_filter() = runTest {
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload(
            table = "note_attachments",
            cacheKey = "sig-A",
            payload =
                """[{"note_id":"a","attachment_id":"att1"},{"note_id":"a","attachment_id":"att2"}]""",
        )
        val store = buildStore(queryCacheDao, mutationDao)

        val filter =
            FilterDescriptor(
                mode = FilterDescriptor.Mode.AND,
                conditions =
                    listOf(
                        FilterDescriptor.Condition(
                            column = "attachment_id",
                            operatorId = "eq",
                            value = "att1",
                        )
                    ),
            )

        store.cachePatcher.patchCachedQueries(
            table = "note_attachments",
            operation = MutationOperation.DELETE,
            bodyJson = "{}",
            filterJson = encodeFilter(filter, json),
        )

        val updated = queryCacheDao.getRow("note_attachments", "sig-A")?.payload
        assertNotNull(updated)
        assertFalse(updated.contains("att1"), "att1 row should be removed")
        assertTrue(updated.contains("att2"))
    }

    // ── Cross-cutting invariants ──────────────────────────────────────────────

    @Test
    fun identity_mutation_does_not_rewrite_unchanged_payload() = runTest {
        // Given — cache already reflects what the mutation would produce
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":92}]""")
        val store = buildStore(queryCacheDao, mutationDao)

        // When — same payload re-upserted
        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — objectDao.upsert() skipped because the payload string is unchanged
        assertEquals(0, queryCacheDao.writes, "should not touch cache when payload is identical")
    }

    @Test
    fun mutation_cache_stays_empty_on_online_success_path() = runTest {
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[]""")
        val store = buildStore(queryCacheDao, mutationDao)

        store.cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"rating":80}]""",
            filterJson = null,
            onConflict = "id",
        )

        // The whole point of the helper: only `object_cache` is written, never `mutation_cache`.
        assertEquals(0, mutationDao.inserted.size)
    }

    // ── The server's changes, as one batch ───────────────────────────────────

    @Test
    fun a_batch_of_server_changes_is_applied_in_order() = runTest {
        // Given — one cached row
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao, FakeMutationDao())

        // When — one burst inserts a row, updates it, deletes it, and updates the cached one
        store.cachePatcher.patchServerChanges(
            table = "ratings",
            keyColumns = listOf("id"),
            changes =
                listOf(
                    saved(id = 2, rating = 50),
                    saved(id = 2, rating = 60),
                    deleted(id = 2),
                    saved(id = 1, rating = 90),
                ),
        )

        // Then — the new row came and went, and the cached one holds its last value
        assertEquals(
            """[{"id":1,"rating":90}]""",
            queryCacheDao.getRow("ratings", "sig-A")?.payload,
        )
    }

    @Test
    fun a_batch_of_server_changes_updates_each_row_it_names_in_one_write() = runTest {
        // Given — two cached rows
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload(
            "ratings",
            "sig-A",
            """[{"id":1,"rating":80},{"id":2,"rating":70}]""",
        )
        val store = buildStore(queryCacheDao, FakeMutationDao())
        val writesBefore = store.queryCacheDao.writesTo("ratings").second

        // When — both change within one burst
        store.cachePatcher.patchServerChanges(
            table = "ratings",
            keyColumns = listOf("id"),
            changes = listOf(saved(id = 1, rating = 81), saved(id = 2, rating = 71)),
        )

        // Then — both updated, by one write of the payload
        assertEquals(
            """[{"id":1,"rating":81},{"id":2,"rating":71}]""",
            queryCacheDao.getRow("ratings", "sig-A")?.payload,
        )
        assertEquals(1L, store.queryCacheDao.writesTo("ratings").second - writesBefore)
    }

    @Test
    fun a_batch_of_server_changes_that_no_longer_applies_under_the_lock_writes_nothing() = runTest {
        // Given — a cached row, and a batch whose channel was left for another user by the
        // time the patch holds the table's lock
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao, FakeMutationDao())
        val writesBefore = store.queryCacheDao.writesTo("ratings").second

        // When
        store.cachePatcher.patchServerChanges(
            table = "ratings",
            keyColumns = listOf("id"),
            changes = listOf(saved(id = 1, rating = 90)),
            applies = { false },
        )

        // Then — nothing written
        assertEquals(0L, store.queryCacheDao.writesTo("ratings").second - writesBefore)
        assertEquals(
            """[{"id":1,"rating":80}]""",
            queryCacheDao.getRow("ratings", "sig-A")?.payload,
        )
    }

    @Test
    fun an_empty_batch_of_server_changes_patches_nothing() = runTest {
        // Given
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", """[{"id":1,"rating":80}]""")
        val store = buildStore(queryCacheDao, FakeMutationDao())
        val writesBefore = store.queryCacheDao.writesTo("ratings").second

        // When
        store.cachePatcher.patchServerChanges("ratings", listOf("id"), emptyList())

        // Then
        assertEquals(0L, store.queryCacheDao.writesTo("ratings").second - writesBefore)
        assertEquals(
            """[{"id":1,"rating":80}]""",
            queryCacheDao.getRow("ratings", "sig-A")?.payload,
        )
    }

    /** The server's record of the rating [id], saved with [rating]. */
    private fun saved(id: Int, rating: Int): ServerRowChange =
        ServerRowChange(
            record =
                buildJsonObject {
                    put("id", id)
                    put("rating", rating)
                },
            keyFilterJson = byId(id),
            deleted = false,
        )

    /** The server's record of the rating [id], deleted: its key alone. */
    private fun deleted(id: Int): ServerRowChange =
        ServerRowChange(
            record = buildJsonObject { put("id", id) },
            keyFilterJson = byId(id),
            deleted = true,
        )

    private fun byId(id: Int): String =
        encodeFilter(
            FilterDescriptor(
                conditions =
                    listOf(FilterDescriptor.Condition("id", operatorId = "eq", value = "$id"))
            ),
            json,
        )

    // ── Fixtures ──────────────────────────────────────────────────────────────

    /**
     * A store over the DAOs, knowing the shape of each filtered query these tests cache, as reading
     * it would.
     */
    private suspend fun buildStore(
        queryCacheDao: QueryCacheDao,
        mutationDao: MutationDao,
    ): TestStore =
        newTestStore(
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
            )
            .apply {
                recordQueryShape("notes", "notes?type=eq.OPEN") { filter { eq("type", "OPEN") } }
                recordQueryShape("notes", "notes?type=eq.DONE") { filter { eq("type", "DONE") } }
                recordQueryShape("notes", "notes?type=eq.OPEN&order=created_at.desc") {
                    filter { eq("type", "OPEN") }
                    order("created_at", Order.DESCENDING)
                }
            }
}

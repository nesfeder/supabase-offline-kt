@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [OfflineStore.applyLocalMutationToCache] — the online-success overlay path that
 * honors [io.github.nesfeder.supabase.offline.MutationStrategy.QueueIfOffline]'s "UI reflects immediately"
 * promise without requiring a round-trip SELECT.
 *
 * The whole point is: after any successful online mutation, the `object_cache` payloads that
 * drive `selectAsFlow(...)` must be patched so reactive observers emit the new state within
 * flow dispatch latency — no tab switch, no pull-to-refresh.
 */
class OfflineStoreApplyLocalMutationTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── UPSERT ────────────────────────────────────────────────────────────────

    @Test
    fun upsert_patches_existing_row_in_cached_payload() = runTest {
        // Given — cache holds an older row for id 1
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "scores",
            signature = "sig-A",
            payload = """[{"id":1,"score":80}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — the same row is upserted online with a new score
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":92}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
        )

        // Then — cached payload reflects the new score
        val updated = objectDao.getRow("scores", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"score":92}]""", updated.payload)
        // And — mutation_cache is not touched (this is the online-success path)
        assertEquals(0, mutationDao.inserted.size)
    }

    @Test
    fun upsert_appends_new_row_when_primary_key_not_in_cache() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "scores",
            signature = "sig-A",
            payload = """[{"id":1,"score":80}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — a different id is upserted
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":2,"score":70}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
        )

        // Then — the new row is appended
        val updated = objectDao.getRow("scores", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"score":80},{"id":2,"score":70}]""", updated.payload)
    }

    @Test
    fun upsert_with_ignoreDuplicates_leaves_matching_cached_row_untouched() = runTest {
        // Given — cache holds the canonical server row for id 1 (e.g. a score another
        // device already wrote)
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "scores",
            signature = "sig-A",
            payload = """[{"id":1,"score":80}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — an insert-if-missing (ON CONFLICT DO NOTHING) races the existing row. The
        // server preserved its row and returned nothing, so the overlay falls back to the
        // request body — which must NOT be merged over the canonical value.
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":92}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
            ignoreDuplicates = true,
        )

        // Then — cached payload still holds the canonical score
        val updated = objectDao.getRow("scores", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"score":80}]""", updated.payload)
    }

    @Test
    fun upsert_with_ignoreDuplicates_still_appends_genuinely_new_row() = runTest {
        // Given — cache holds a row for id 1 only
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "scores",
            signature = "sig-A",
            payload = """[{"id":1,"score":80}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — an insert-if-missing for an id the cache doesn't hold
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":2,"score":70}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
            ignoreDuplicates = true,
        )

        // Then — the genuinely new row is appended; ignoreDuplicates only guards existing rows
        val updated = objectDao.getRow("scores", "sig-A")
        assertNotNull(updated)
        assertEquals("""[{"id":1,"score":80},{"id":2,"score":70}]""", updated.payload)
    }

    @Test
    fun upsert_across_multiple_cached_signatures_patches_all() = runTest {
        // Given — two live observers on the same table, under different filter signatures
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload("scores", "sig-A", """[{"id":1,"score":80}]""")
        objectDao.seedPayload("scores", "sig-B", """[{"id":1,"score":80}]""")
        val store = buildStore(objectDao, mutationDao)

        // When
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — both signatures got patched
        assertEquals("""[{"id":1,"score":92}]""", objectDao.getRow("scores", "sig-A")?.payload)
        assertEquals("""[{"id":1,"score":92}]""", objectDao.getRow("scores", "sig-B")?.payload)
    }

    @Test
    fun upsert_with_composite_onConflict_patches_targeted_row_in_user_scoped_payload() = runTest {
        // Given — a `scores` cache populated under a user_id-only signature, as a per-user
        // reactive read would cache it. Two rows for two activities; the
        // body shape mirrors what Supabase returns (id + user_id + activity_id + nested payload).
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "scores",
            signature = "scores?user_id=eq.user-uuid",
            payload = """[{"id":"r1","user_id":"user-uuid","activity_id":1,"payload":{"score":80}},""" +
                """{"id":"r2","user_id":"user-uuid","activity_id":2,"payload":{"score":75}}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — re-score for activity 2 via a composite-key UPSERT (onConflict names the
        // table's natural key).
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":"r2","user_id":"user-uuid","activity_id":2,"payload":{"score":92}}]""",
            filterJson = null,
            onConflict = "user_id,activity_id",
        )

        // Then — activity 2's row reflects the new score; activity 1 stays unchanged. The
        // updated payload is what a cold-start `selectAsFlow` collector would observe after
        // a process restart, since the same in-memory map backs the on-disk Room cache.
        val updated = objectDao.getRow("scores", "scores?user_id=eq.user-uuid")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"activity_id\":1"))
        assertTrue(updated.contains("\"score\":80"), "activity 1 score should remain 80, got $updated")
        assertTrue(updated.contains("\"activity_id\":2"))
        assertTrue(updated.contains("\"score\":92"), "activity 2 score should be 92, got $updated")
        assertFalse(updated.contains("\"score\":75"), "stale score 75 must not survive overlay")
    }

    @Test
    fun upsert_with_no_cached_payloads_is_noop() = runTest {
        // Given — empty cache (no live observers yet)
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(objectDao, mutationDao)

        // When
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — nothing written, no crash
        assertEquals(0, objectDao.writes)
        assertEquals(0, mutationDao.inserted.size)
    }

    // ── INSERT ────────────────────────────────────────────────────────────────

    @Test
    fun insert_appends_row_to_cached_payload() = runTest {
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload("activity_logs", "sig-A", """[{"id":"a","notes":"one"}]""")
        val store = buildStore(objectDao, mutationDao)

        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"b","notes":"two"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        assertEquals(
            """[{"id":"a","notes":"one"},{"id":"b","notes":"two"}]""",
            objectDao.getRow("activity_logs", "sig-A")?.payload,
        )
    }

    // ── Query-aware overlay gating ────────────────────────────────────────────

    @Test
    fun insert_does_not_land_in_cached_signature_whose_eq_filter_excludes_it() = runTest {
        // Given — a cached exhaustive `type=eq.RUN` query holding one RUN row
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_logs",
            signature = "activity_logs?type=eq.RUN",
            payload = """[{"id":"r1","type":"RUN"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — a WALK row is inserted online
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w1","type":"WALK"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — the WALK row is NOT appended to the RUN-only query, and the cache is untouched
        assertEquals(
            """[{"id":"r1","type":"RUN"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN")?.payload,
        )
        assertEquals(0, objectDao.writes, "excluded row must not trigger a cache write")
    }

    @Test
    fun insert_lands_in_matching_exhaustive_signature() = runTest {
        // Given — a cached exhaustive `type=eq.RUN` query
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_logs",
            signature = "activity_logs?type=eq.RUN",
            payload = """[{"id":"r1","type":"RUN"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — a matching RUN row is inserted
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"r2","type":"RUN"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — the RUN row is appended
        assertEquals(
            """[{"id":"r1","type":"RUN"},{"id":"r2","type":"RUN"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN")?.payload,
        )
    }

    @Test
    fun insert_fans_out_only_to_matching_signatures_across_multiple_queries() = runTest {
        // Given — two live observers on the same table under mutually-exclusive type filters
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload("activity_logs", "activity_logs?type=eq.RUN", """[{"id":"r1","type":"RUN"}]""")
        objectDao.seedPayload("activity_logs", "activity_logs?type=eq.WALK", """[{"id":"w1","type":"WALK"}]""")
        val store = buildStore(objectDao, mutationDao)

        // When — a WALK row is inserted online
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w2","type":"WALK"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — it lands only in the WALK query; the RUN query is untouched
        assertEquals(
            """[{"id":"r1","type":"RUN"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN")?.payload,
        )
        assertEquals(
            """[{"id":"w1","type":"WALK"},{"id":"w2","type":"WALK"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.WALK")?.payload,
        )
    }

    @Test
    fun insert_still_lands_in_non_exhaustive_ordered_signature() = runTest {
        // Given — an ordered (hence non-exhaustive) `type=eq.RUN` query. We deliberately keep the
        // legacy always-apply behavior here so windowed/ordered flows never under-apply.
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_logs",
            signature = "activity_logs?type=eq.RUN&order=created_at.desc",
            payload = """[{"id":"r1","type":"RUN"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — even a non-matching WALK row is inserted
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":"w1","type":"WALK"}]""",
            filterJson = null,
            defaultToNull = true,
        )

        // Then — it is still appended, because a non-exhaustive signature keeps legacy behavior
        assertEquals(
            """[{"id":"r1","type":"RUN"},{"id":"w1","type":"WALK"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN&order=created_at.desc")?.payload,
        )
    }

    @Test
    fun upsert_append_is_gated_by_exhaustive_signature() = runTest {
        // Given — a cached exhaustive `type=eq.RUN` query
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_logs",
            signature = "activity_logs?type=eq.RUN",
            payload = """[{"id":"r1","type":"RUN"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — a WALK row is upserted (onConflict key doesn't match any cached row → append path)
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":"w1","type":"WALK"}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — the append is suppressed for the excluding query
        assertEquals(
            """[{"id":"r1","type":"RUN"}]""",
            objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN")?.payload,
        )
        assertEquals(0, objectDao.writes, "excluded upsert-append must not trigger a cache write")
    }

    @Test
    fun upsert_merge_into_matching_row_unchanged_under_exhaustive_signature() = runTest {
        // Given — a cached exhaustive `type=eq.RUN` query with an existing row
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_logs",
            signature = "activity_logs?type=eq.RUN",
            payload = """[{"id":"r1","type":"RUN","notes":"old"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        // When — the same row (which belongs in this query) is re-upserted with new data
        store.applyLocalMutationToCache(
            table = "activity_logs",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":"r1","type":"RUN","notes":"new"}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — the merge into the matching row is unaffected by the gate
        val updated = objectDao.getRow("activity_logs", "activity_logs?type=eq.RUN")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"notes\":\"new\""), "matching row should be merged, got $updated")
        assertFalse(updated.contains("\"notes\":\"old\""), "stale value must not survive, got $updated")
    }

    // ── UPDATE ────────────────────────────────────────────────────────────────

    @Test
    fun update_patches_rows_matching_filter() = runTest {
        // Given — two shoes in cache, filter targets only the second
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "shoes",
            signature = "sig-A",
            payload = """[{"id":"s1","retired":false},{"id":"s2","retired":false}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        val filter = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "s2"),
            ),
        )

        // When — update retire=true for id s2
        store.applyLocalMutationToCache(
            table = "shoes",
            operation = MutationOperation.UPDATE,
            bodyJson = buildJsonObject { put("retired", JsonPrimitive(true)) }.toString(),
            filterJson = FilterEngine.encode(filter, json),
        )

        // Then — only s2 is patched; s1 is unchanged
        val updated = objectDao.getRow("shoes", "sig-A")?.payload
        assertNotNull(updated)
        // Use substring checks since the overlay may preserve key order from the original row.
        assertTrue(updated.contains("\"id\":\"s1\"") && updated.contains("\"retired\":false"))
        assertTrue(updated.contains("\"id\":\"s2\"") && updated.contains("\"retired\":true"))
    }

    @Test
    fun update_with_is_null_condition_patches_unread_row_and_skips_seen_row() = runTest {
        // Given — a weekly_report cache with one unread row (seen_at is JSON null) and one
        // already-seen row. Mirrors a first-write-wins "mark seen" update's
        // filter: [eq user_id, eq week_id, seen_at IS NULL].
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.user-uuid",
            payload = """[{"user_id":"user-uuid","week_id":"2026-W26","seen_at":null},""" +
                """{"user_id":"user-uuid","week_id":"2026-W25","seen_at":"2026-06-22T08:00:00Z"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        fun markSeenFilter(weekId: String) = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(column = "user_id", operatorId = "eq", value = "user-uuid"),
                FilterDescriptor.Condition(column = "week_id", operatorId = "eq", value = weekId),
                FilterDescriptor.Condition(column = "seen_at", operatorId = "is", value = "null"),
            ),
        )

        // When — mark the unread week seen, then re-mark the already-seen week
        store.applyLocalMutationToCache(
            table = "weekly_report",
            operation = MutationOperation.UPDATE,
            bodyJson = buildJsonObject { put("seen_at", JsonPrimitive("2026-06-29T09:00:00Z")) }.toString(),
            filterJson = FilterEngine.encode(markSeenFilter("2026-W26"), json),
        )
        store.applyLocalMutationToCache(
            table = "weekly_report",
            operation = MutationOperation.UPDATE,
            bodyJson = buildJsonObject { put("seen_at", JsonPrimitive("2026-06-29T09:05:00Z")) }.toString(),
            filterJson = FilterEngine.encode(markSeenFilter("2026-W25"), json),
        )

        // Then — the unread row is stamped; the seen row keeps its first-open timestamp
        val updated = objectDao.getRow("weekly_report", "weekly_report?user_id=eq.user-uuid")?.payload
        assertNotNull(updated)
        assertTrue(
            updated.contains("\"seen_at\":\"2026-06-29T09:00:00Z\""),
            "unread 2026-W26 row should be stamped, got $updated",
        )
        assertTrue(
            updated.contains("\"seen_at\":\"2026-06-22T08:00:00Z\""),
            "already-seen 2026-W25 row must keep its first-open timestamp, got $updated",
        )
        assertFalse(
            updated.contains("2026-06-29T09:05:00Z"),
            "IS NULL guard must skip the already-seen row, got $updated",
        )
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @Test
    fun delete_removes_rows_matching_filter() = runTest {
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload(
            table = "activity_log_nutrition",
            signature = "sig-A",
            payload = """[{"activity_log_id":"a","nutrition_item_id":"gel1"},{"activity_log_id":"a","nutrition_item_id":"gel2"}]""",
        )
        val store = buildStore(objectDao, mutationDao)

        val filter = FilterDescriptor(
            mode = FilterDescriptor.Mode.AND,
            conditions = listOf(
                FilterDescriptor.Condition(column = "nutrition_item_id", operatorId = "eq", value = "gel1"),
            ),
        )

        store.applyLocalMutationToCache(
            table = "activity_log_nutrition",
            operation = MutationOperation.DELETE,
            bodyJson = "{}",
            filterJson = FilterEngine.encode(filter, json),
        )

        val updated = objectDao.getRow("activity_log_nutrition", "sig-A")?.payload
        assertNotNull(updated)
        assertFalse(updated.contains("gel1"), "gel1 row should be removed")
        assertTrue(updated.contains("gel2"))
    }

    // ── Cross-cutting invariants ──────────────────────────────────────────────

    @Test
    fun identity_mutation_does_not_rewrite_unchanged_payload() = runTest {
        // Given — cache already reflects what the mutation would produce
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload("scores", "sig-A", """[{"id":1,"score":92}]""")
        val store = buildStore(objectDao, mutationDao)

        // When — same payload re-upserted
        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":92}]""",
            filterJson = null,
            onConflict = "id",
        )

        // Then — objectDao.upsert() skipped because the payload string is unchanged
        assertEquals(0, objectDao.writes, "should not touch cache when payload is identical")
    }

    @Test
    fun mutation_cache_stays_empty_on_online_success_path() = runTest {
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        objectDao.seedPayload("scores", "sig-A", """[]""")
        val store = buildStore(objectDao, mutationDao)

        store.applyLocalMutationToCache(
            table = "scores",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"score":80}]""",
            filterJson = null,
            onConflict = "id",
        )

        // The whole point of the helper: only `object_cache` is written, never `mutation_cache`.
        assertEquals(0, mutationDao.inserted.size)
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun buildStore(objectDao: ObjectDao, mutationDao: MutationDao): OfflineStore =
        OfflineStore(
            objectDao = objectDao,
            storageDao = FakeStorageDao(),
            mutationDao = mutationDao,
            dbPathProvider = object : DbPathProvider { override fun dbPath(): Path = "/tmp/test.db".toPath() },
            settings = OfflineCacheSettings(
                maxDbSizeBytes = null,
                evictionBatchSize = 0,
                minRetainedBytes = 0,
            ),
            nowMillis = { 0L },
            dbSizeProvider = { null },
        )
}

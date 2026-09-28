@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [SupabaseOfflineClient.applyServerRecord] / [SupabaseOfflineClient.applyServerDelete]
 * — the realtime primitives that merge a server-authored row into every cached SELECT payload
 * for a table by primary key, without a caller-visible cache signature.
 *
 * These run the real [SupabaseOfflineClientImpl] over a real [OfflineStore] backed by in-memory
 * fake DAOs; the [SupabaseClient][io.github.jan.supabase.SupabaseClient] is a mock-engine stub
 * that these methods never touch (they read/write only the local store).
 */
class ApplyServerRecordTest {

    // ── applyServerRecord ──────────────────────────────────────────────────────

    @Test
    fun applyServerRecord_patches_existing_row_in_place_by_single_key() = runTest {
        // Given — one insights row cached under its per-period signature
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "insights",
            signature = "insights-week-sig",
            payload = """[{"id":"ins-1","user_id":"u-1","period_type":"WEEK","avg_score":80}]""",
        )
        val client = buildClient(objectDao)

        // When — a realtime UPDATE for the same id arrives
        client.applyServerRecord(
            table = "insights",
            record = insightsRecord(id = "ins-1", userId = "u-1", periodType = "WEEK", avgScore = 92),
            primaryKeys = listOf("id"),
        )

        // Then — the cached row is merged in place, single row preserved
        val updated = objectDao.getRow("insights", "insights-week-sig")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"avg_score\":92"), "expected merged score, got $updated")
        assertFalse(updated.contains("\"avg_score\":80"), "stale score must not survive, got $updated")
    }

    @Test
    fun applyServerRecord_appends_when_primary_key_absent_from_cache() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "insights",
            signature = "insights-week-sig",
            payload = """[{"id":"ins-1","user_id":"u-1","period_type":"WEEK","avg_score":80}]""",
        )
        val client = buildClient(objectDao)

        // When — a different id arrives
        client.applyServerRecord(
            table = "insights",
            record = insightsRecord(id = "ins-2", userId = "u-1", periodType = "WEEK", avgScore = 70),
            primaryKeys = listOf("id"),
        )

        // Then — the new row is appended alongside the existing one
        val updated = objectDao.getRow("insights", "insights-week-sig")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"id\":\"ins-1\""))
        assertTrue(updated.contains("\"id\":\"ins-2\""))
    }

    @Test
    fun applyServerRecord_with_composite_key_patches_only_targeted_week() = runTest {
        // Given — two weekly_report weeks under the user_id-only signature (composite PK, no id)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","week_id":"2026-W26","avg_score":50},""" +
                """{"user_id":"u-1","week_id":"2026-W25","avg_score":40}]""",
        )
        val client = buildClient(objectDao)

        // When — a regenerated W26 arrives with a new score
        client.applyServerRecord(
            table = "weekly_report",
            record = weeklyRecord(userId = "u-1", weekId = "2026-W26", avgScore = 99, generatedAt = "2026-06-29T12:00:00Z"),
            primaryKeys = listOf("user_id", "week_id"),
        )

        // Then — W26 is patched, W25 untouched
        val updated = objectDao.getRow("weekly_report", "weekly_report?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"avg_score\":99"), "W26 should be rescored, got $updated")
        assertTrue(updated.contains("\"avg_score\":40"), "W25 must remain, got $updated")
        assertFalse(updated.contains("\"avg_score\":50"), "stale W26 score must not survive, got $updated")
    }

    @Test
    fun applyServerRecord_with_composite_key_appends_new_week_at_end() = runTest {
        // Given — a single (older) week cached
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","week_id":"2026-W25","generated_at":"2026-06-22T12:00:00Z"}]""",
        )
        val client = buildClient(objectDao)

        // When — a newer week arrives via realtime
        client.applyServerRecord(
            table = "weekly_report",
            record = weeklyRecord(userId = "u-1", weekId = "2026-W26", avgScore = 60, generatedAt = "2026-06-29T12:00:00Z"),
            primaryKeys = listOf("user_id", "week_id"),
        )

        // Then — the merge appends the new week LAST (out of generated_at order); a consumer that
        // needs newest-first restores it with its own sort on the decode path.
        val updated = objectDao.getRow("weekly_report", "weekly_report?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertTrue(
            updated.indexOf("2026-W25") < updated.indexOf("2026-W26"),
            "new week is appended after the older one in the raw array, got $updated",
        )
    }

    @Test
    fun applyServerRecord_patches_every_cached_signature_for_the_table() = runTest {
        // Given — the same insights row cached under two different signatures (two live observers)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("insights", "sig-A", """[{"id":"ins-1","avg_score":80}]""")
        objectDao.seedPayload("insights", "sig-B", """[{"id":"ins-1","avg_score":80}]""")
        val client = buildClient(objectDao)

        // When
        client.applyServerRecord(
            table = "insights",
            record = insightsRecord(id = "ins-1", userId = "u-1", periodType = "WEEK", avgScore = 92),
            primaryKeys = listOf("id"),
        )

        // Then — both signatures are patched
        assertTrue(objectDao.getRow("insights", "sig-A")!!.payload.contains("\"avg_score\":92"))
        assertTrue(objectDao.getRow("insights", "sig-B")!!.payload.contains("\"avg_score\":92"))
    }

    @Test
    fun applyServerRecord_is_noop_when_no_cached_payloads() = runTest {
        // Given — nothing observing the table
        val objectDao = FakeObjectDao()
        val client = buildClient(objectDao)

        // When
        client.applyServerRecord(
            table = "insights",
            record = insightsRecord(id = "ins-1", userId = "u-1", periodType = "WEEK", avgScore = 92),
            primaryKeys = listOf("id"),
        )

        // Then — no writes, no crash
        assertEquals(0, objectDao.writes)
    }

    @Test
    fun applyServerRecord_drops_record_missing_a_primary_key_and_warns() = runTest {
        // Given — a weekly cache and a record missing week_id (so the composite PK can't be built)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","week_id":"2026-W25"}]""",
        )
        val warnings = mutableListOf<String>()
        val logger = OfflineLogger { level, message ->
            if (level == OfflineLogLevel.WARNING) warnings += message
        }
        val client = buildClient(objectDao, sdkLogger = SdkLogger(logger, OfflineLogLevel.DEBUG))

        // When — the record lacks week_id
        client.applyServerRecord(
            table = "weekly_report",
            record = buildJsonObject { put("user_id", "u-1") },
            primaryKeys = listOf("user_id", "week_id"),
        )

        // Then — the cache is untouched and a warning was emitted
        assertEquals(0, objectDao.writes, "unlocatable record must not patch the cache")
        assertEquals(
            """[{"user_id":"u-1","week_id":"2026-W25"}]""",
            objectDao.getRow("weekly_report", "weekly_report?user_id=eq.u-1")?.payload,
        )
        assertTrue(warnings.any { it.contains("applyServerRecord") }, "expected a warning, got $warnings")
    }

    @Test
    fun applyServerRecord_live_collector_observes_merged_row() = runTest(UnconfinedTestDispatcher()) {
        // Given — a live collector on a seeded-empty cache slot, mirroring a subscribed selectAsFlow
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("insights", "insights-week-sig", "[]")
        val client = buildClient(objectDao)

        val cacheFlow = objectDao.observe("insights", "insights-week-sig")
            .filterNotNull()
            .map { it.payload }
        val firstMergedEmission = async {
            withTimeoutOrNull(timeMillis = 500) {
                cacheFlow.first { it.contains("ins-1") }
            }
        }

        // When — a realtime record is merged into the cache
        client.applyServerRecord(
            table = "insights",
            record = insightsRecord(id = "ins-1", userId = "u-1", periodType = "WEEK", avgScore = 88),
            primaryKeys = listOf("id"),
        )

        // Then — the collector re-emits with the merged row within flow-dispatch latency
        val emitted = firstMergedEmission.await()
        assertNotNull(emitted, "live collector must observe the merged realtime row")
        assertTrue(emitted.contains("\"avg_score\":88"), "emitted payload should carry the new row, got $emitted")
    }

    // ── applyServerDelete ──────────────────────────────────────────────────────

    @Test
    fun applyServerDelete_removes_row_by_composite_primary_key() = runTest {
        // Given — two weeks cached
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","week_id":"2026-W26"},{"user_id":"u-1","week_id":"2026-W25"}]""",
        )
        val client = buildClient(objectDao)

        // When — a DELETE event for W26 arrives
        client.applyServerDelete(
            table = "weekly_report",
            oldRecord = weeklyRecord(userId = "u-1", weekId = "2026-W26", avgScore = 0, generatedAt = "2026-06-29T12:00:00Z"),
            primaryKeys = listOf("user_id", "week_id"),
        )

        // Then — W26 is gone, W25 remains
        val updated = objectDao.getRow("weekly_report", "weekly_report?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertFalse(updated.contains("2026-W26"), "deleted week must be removed, got $updated")
        assertTrue(updated.contains("2026-W25"), "other week must remain, got $updated")
    }

    @Test
    fun applyServerDelete_drops_record_missing_a_primary_key_and_warns() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "weekly_report",
            signature = "weekly_report?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","week_id":"2026-W25"}]""",
        )
        val warnings = mutableListOf<String>()
        val logger = OfflineLogger { level, message ->
            if (level == OfflineLogLevel.WARNING) warnings += message
        }
        val client = buildClient(objectDao, sdkLogger = SdkLogger(logger, OfflineLogLevel.DEBUG))

        // When — the old record lacks week_id
        client.applyServerDelete(
            table = "weekly_report",
            oldRecord = buildJsonObject { put("user_id", "u-1") },
            primaryKeys = listOf("user_id", "week_id"),
        )

        // Then — nothing removed, a warning emitted
        assertEquals(0, objectDao.writes)
        assertTrue(warnings.any { it.contains("applyServerDelete") }, "expected a warning, got $warnings")
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun insightsRecord(id: String, userId: String, periodType: String, avgScore: Int): JsonObject =
        buildJsonObject {
            put("id", id)
            put("user_id", userId)
            put("period_type", periodType)
            put("avg_score", avgScore)
        }

    private fun weeklyRecord(userId: String, weekId: String, avgScore: Int, generatedAt: String): JsonObject =
        buildJsonObject {
            put("user_id", userId)
            put("week_id", weekId)
            put("avg_score", avgScore)
            put("generated_at", generatedAt)
        }

    private fun buildClient(
        objectDao: FakeObjectDao,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    ): SupabaseOfflineClientImpl {
        val store = OfflineStore(
            objectDao = objectDao,
            storageDao = FakeStorageDao(),
            mutationDao = FakeMutationDao(),
            dbPathProvider = object : DbPathProvider { override fun dbPath(): Path = "/tmp/test.db".toPath() },
            settings = OfflineCacheSettings(
                maxDbSizeBytes = null,
                evictionBatchSize = 0,
                minRetainedBytes = 0,
            ),
            nowMillis = { 0L },
            dbSizeProvider = { null },
        )
        val supabase = createSupabaseClient(
            supabaseUrl = "https://unit-test.supabase.co",
            supabaseKey = "unit-test-anon-key",
        ) {
            httpEngine = MockEngine { respondOk("[]") }
            install(Postgrest)
        }
        return SupabaseOfflineClientImpl(
            supabase = supabase,
            store = store,
            baseDirectoryProvider = { "/tmp".toPath() },
            sdkLogger = sdkLogger,
        )
    }
}

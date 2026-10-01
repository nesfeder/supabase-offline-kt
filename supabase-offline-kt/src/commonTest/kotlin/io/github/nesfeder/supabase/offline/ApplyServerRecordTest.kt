// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.logging.SdkLogger
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/**
 * Tests for [SupabaseOfflineClient.applyServerRecord] / [SupabaseOfflineClient.applyServerDelete] —
 * the realtime primitives that merge a server-authored row into every cached SELECT payload for a
 * table by primary key, without a caller-visible cache signature.
 *
 * These run the real [SupabaseOfflineClientImpl] over a real [OfflineStore] backed by in-memory
 * fake DAOs; the [SupabaseClient][io.github.jan.supabase.SupabaseClient] is a mock-engine stub that
 * these methods never touch (they read/write only the local store).
 */
class ApplyServerRecordTest {

    // ── applyServerRecord ──────────────────────────────────────────────────────

    @Test
    fun applyServerRecord_patches_existing_row_in_place_by_single_key() = runTest {
        // Given — one reports row cached under its per-period signature
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "reports",
            signature = "reports-period-sig",
            payload = """[{"id":"rep-1","user_id":"u-1","kind":"WEEK","total":80}]""",
        )
        val client = buildClient(objectDao)

        // When — a realtime UPDATE for the same id arrives
        client.applyServerRecord(
            table = "reports",
            record = reportRecord(id = "rep-1", userId = "u-1", kind = "WEEK", total = 92),
            primaryKeys = listOf("id"),
        )

        // Then — the cached row is merged in place, single row preserved
        val updated = objectDao.getRow("reports", "reports-period-sig")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"total\":92"), "expected merged rating, got $updated")
        assertFalse(updated.contains("\"total\":80"), "stale rating must not survive, got $updated")
    }

    @Test
    fun applyServerRecord_appends_when_primary_key_absent_from_cache() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "reports",
            signature = "reports-period-sig",
            payload = """[{"id":"rep-1","user_id":"u-1","kind":"WEEK","total":80}]""",
        )
        val client = buildClient(objectDao)

        // When — a different id arrives
        client.applyServerRecord(
            table = "reports",
            record = reportRecord(id = "rep-2", userId = "u-1", kind = "WEEK", total = 70),
            primaryKeys = listOf("id"),
        )

        // Then — the new row is appended alongside the existing one
        val updated = objectDao.getRow("reports", "reports-period-sig")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"id\":\"rep-1\""))
        assertTrue(updated.contains("\"id\":\"rep-2\""))
    }

    @Test
    fun applyServerRecord_with_composite_key_patches_only_targeted_week() = runTest {
        // Given — two summary periods under the user_id-only signature (composite PK, no id)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "summaries",
            signature = "summaries?user_id=eq.u-1",
            payload =
                """[{"user_id":"u-1","period_id":"2026-P26","total":50},""" +
                    """{"user_id":"u-1","period_id":"2026-P25","total":40}]""",
        )
        val client = buildClient(objectDao)

        // When — a regenerated P26 arrives with a new rating
        client.applyServerRecord(
            table = "summaries",
            record =
                summaryRecord(
                    userId = "u-1",
                    periodId = "2026-P26",
                    total = 99,
                    generatedAt = "2026-06-29T12:00:00Z",
                ),
            primaryKeys = listOf("user_id", "period_id"),
        )

        // Then — P26 is patched, P25 untouched
        val updated = objectDao.getRow("summaries", "summaries?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertTrue(updated.contains("\"total\":99"), "P26 should be recomputed, got $updated")
        assertTrue(updated.contains("\"total\":40"), "P25 must remain, got $updated")
        assertFalse(
            updated.contains("\"total\":50"),
            "stale P26 rating must not survive, got $updated",
        )
    }

    @Test
    fun applyServerRecord_with_composite_key_appends_new_week_at_end() = runTest {
        // Given — a single (older) period cached
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "summaries",
            signature = "summaries?user_id=eq.u-1",
            payload =
                """[{"user_id":"u-1","period_id":"2026-P25","generated_at":"2026-06-22T12:00:00Z"}]""",
        )
        val client = buildClient(objectDao)

        // When — a newer period arrives via realtime
        client.applyServerRecord(
            table = "summaries",
            record =
                summaryRecord(
                    userId = "u-1",
                    periodId = "2026-P26",
                    total = 60,
                    generatedAt = "2026-06-29T12:00:00Z",
                ),
            primaryKeys = listOf("user_id", "period_id"),
        )

        // Then — the merge appends the new period LAST (out of generated_at order); a consumer that
        // needs newest-first restores it with its own sort on the decode path.
        val updated = objectDao.getRow("summaries", "summaries?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertTrue(
            updated.indexOf("2026-P25") < updated.indexOf("2026-P26"),
            "new period is appended after the older one in the raw array, got $updated",
        )
    }

    @Test
    fun applyServerRecord_patches_every_cached_signature_for_the_table() = runTest {
        // Given — the same reports row cached under two different signatures (two live observers)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("reports", "sig-A", """[{"id":"rep-1","total":80}]""")
        objectDao.seedPayload("reports", "sig-B", """[{"id":"rep-1","total":80}]""")
        val client = buildClient(objectDao)

        // When
        client.applyServerRecord(
            table = "reports",
            record = reportRecord(id = "rep-1", userId = "u-1", kind = "WEEK", total = 92),
            primaryKeys = listOf("id"),
        )

        // Then — both signatures are patched
        assertTrue(objectDao.getRow("reports", "sig-A")!!.payload.contains("\"total\":92"))
        assertTrue(objectDao.getRow("reports", "sig-B")!!.payload.contains("\"total\":92"))
    }

    @Test
    fun applyServerRecord_is_noop_when_no_cached_payloads() = runTest {
        // Given — nothing observing the table
        val objectDao = FakeObjectDao()
        val client = buildClient(objectDao)

        // When
        client.applyServerRecord(
            table = "reports",
            record = reportRecord(id = "rep-1", userId = "u-1", kind = "WEEK", total = 92),
            primaryKeys = listOf("id"),
        )

        // Then — no writes, no crash
        assertEquals(0, objectDao.writes)
    }

    @Test
    fun applyServerRecord_drops_record_missing_a_primary_key_and_warns() = runTest {
        // Given — a summaries cache and a record missing period_id (so the composite PK can't be
        // built)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "summaries",
            signature = "summaries?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","period_id":"2026-P25"}]""",
        )
        val warnings = mutableListOf<String>()
        val logger = OfflineLogger { level, message ->
            if (level == OfflineLogLevel.WARNING) warnings += message
        }
        val client = buildClient(objectDao, sdkLogger = SdkLogger(logger, OfflineLogLevel.DEBUG))

        // When — the record lacks period_id
        client.applyServerRecord(
            table = "summaries",
            record = buildJsonObject { put("user_id", "u-1") },
            primaryKeys = listOf("user_id", "period_id"),
        )

        // Then — the cache is untouched and a warning was emitted
        assertEquals(0, objectDao.writes, "unlocatable record must not patch the cache")
        assertEquals(
            """[{"user_id":"u-1","period_id":"2026-P25"}]""",
            objectDao.getRow("summaries", "summaries?user_id=eq.u-1")?.payload,
        )
        assertTrue(
            warnings.any { it.contains("applyServerRecord") },
            "expected a warning, got $warnings",
        )
    }

    @Test
    fun applyServerRecord_live_collector_observes_merged_row() =
        runTest(UnconfinedTestDispatcher()) {
            // Given — a live collector on a seeded-empty cache slot, mirroring a subscribed
            // selectAsFlow
            val objectDao = FakeObjectDao()
            objectDao.seedPayload("reports", "reports-period-sig", "[]")
            val client = buildClient(objectDao)

            val cacheFlow =
                objectDao.observe("reports", "reports-period-sig").filterNotNull().map {
                    it.payload
                }
            val firstMergedEmission = async {
                withTimeoutOrNull(timeMillis = 500) {
                    cacheFlow.first { it.contains("rep-1") }
                }
            }

            // When — a realtime record is merged into the cache
            client.applyServerRecord(
                table = "reports",
                record = reportRecord(id = "rep-1", userId = "u-1", kind = "WEEK", total = 88),
                primaryKeys = listOf("id"),
            )

            // Then — the collector re-emits with the merged row within flow-dispatch latency
            val emitted = firstMergedEmission.await()
            assertNotNull(emitted, "live collector must observe the merged realtime row")
            assertTrue(
                emitted.contains("\"total\":88"),
                "emitted payload should carry the new row, got $emitted",
            )
        }

    // ── applyServerDelete ──────────────────────────────────────────────────────

    @Test
    fun applyServerDelete_removes_row_by_composite_primary_key() = runTest {
        // Given — two periods cached
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "summaries",
            signature = "summaries?user_id=eq.u-1",
            payload =
                """[{"user_id":"u-1","period_id":"2026-P26"},{"user_id":"u-1","period_id":"2026-P25"}]""",
        )
        val client = buildClient(objectDao)

        // When — a DELETE event for P26 arrives
        client.applyServerDelete(
            table = "summaries",
            oldRecord =
                summaryRecord(
                    userId = "u-1",
                    periodId = "2026-P26",
                    total = 0,
                    generatedAt = "2026-06-29T12:00:00Z",
                ),
            primaryKeys = listOf("user_id", "period_id"),
        )

        // Then — P26 is gone, P25 remains
        val updated = objectDao.getRow("summaries", "summaries?user_id=eq.u-1")?.payload
        assertNotNull(updated)
        assertFalse(updated.contains("2026-P26"), "deleted period must be removed, got $updated")
        assertTrue(updated.contains("2026-P25"), "other period must remain, got $updated")
    }

    @Test
    fun applyServerDelete_drops_record_missing_a_primary_key_and_warns() = runTest {
        // Given
        val objectDao = FakeObjectDao()
        objectDao.seedPayload(
            table = "summaries",
            signature = "summaries?user_id=eq.u-1",
            payload = """[{"user_id":"u-1","period_id":"2026-P25"}]""",
        )
        val warnings = mutableListOf<String>()
        val logger = OfflineLogger { level, message ->
            if (level == OfflineLogLevel.WARNING) warnings += message
        }
        val client = buildClient(objectDao, sdkLogger = SdkLogger(logger, OfflineLogLevel.DEBUG))

        // When — the old record lacks period_id
        client.applyServerDelete(
            table = "summaries",
            oldRecord = buildJsonObject { put("user_id", "u-1") },
            primaryKeys = listOf("user_id", "period_id"),
        )

        // Then — nothing removed, a warning emitted
        assertEquals(0, objectDao.writes)
        assertTrue(
            warnings.any { it.contains("applyServerDelete") },
            "expected a warning, got $warnings",
        )
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private fun reportRecord(id: String, userId: String, kind: String, total: Int): JsonObject =
        buildJsonObject {
            put("id", id)
            put("user_id", userId)
            put("kind", kind)
            put("total", total)
        }

    private fun summaryRecord(
        userId: String,
        periodId: String,
        total: Int,
        generatedAt: String,
    ): JsonObject = buildJsonObject {
        put("user_id", userId)
        put("period_id", periodId)
        put("total", total)
        put("generated_at", generatedAt)
    }

    private fun buildClient(
        objectDao: FakeObjectDao,
        sdkLogger: SdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    ): SupabaseOfflineClientImpl =
        newTestOfflineClient(
            store = newTestOfflineStore(objectDao = objectDao),
            sdkLogger = sdkLogger,
        )
}

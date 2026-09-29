// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.OfflineJson
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterEngine
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import okio.Path.Companion.toPath

/**
 * The store's cache and queue bookkeeping, ported from the previous standalone engine's store
 * tests: payload round trips, table and signature deletes, storage rows, the shape of every queued
 * row, pending counts (plain and reactive), and retiring synced rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStoreQueueAndCacheTest {

    private val mutationDao = FakeMutationDao()
    private var clock = 1_000L
    private val store = newTestOfflineStore(mutationDao = mutationDao, nowMillis = { clock })

    // ─────────────────────────── Cached payloads ───────────────────────────

    @Test
    fun addAndGetPayload_roundTrip() = runTest {
        // Given
        store.addPayload(TABLE, SIGNATURE, PAYLOAD)

        // When
        val payload = store.getPayload(TABLE, SIGNATURE)

        // Then
        assertEquals(PAYLOAD, payload)
    }

    @Test
    fun getPayloadWithTimestamp_returnsCorrectValues() = runTest {
        // Given
        clock = 42L
        store.addPayload(TABLE, SIGNATURE, PAYLOAD)

        // When
        val stored = store.getPayloadWithTimestamp(TABLE, SIGNATURE)

        // Then
        assertEquals(PAYLOAD to 42L, stored)
    }

    @Test
    fun getPayloadWithTimestamp_missingKey_returnsNull() = runTest {
        // Given — nothing cached

        // When
        val stored = store.getPayloadWithTimestamp(TABLE, SIGNATURE)

        // Then
        assertNull(stored)
    }

    @Test
    fun clearTable_removesPayloads() = runTest {
        // Given
        store.addPayload(TABLE, SIGNATURE, PAYLOAD)
        store.addPayload(TABLE, OTHER_SIGNATURE, PAYLOAD)
        store.addPayload(OTHER_TABLE, SIGNATURE, PAYLOAD)

        // When
        store.clearTable(TABLE)

        // Then — every signature of the table is gone; other tables are untouched
        assertNull(store.getPayload(TABLE, SIGNATURE))
        assertNull(store.getPayload(TABLE, OTHER_SIGNATURE))
        assertEquals(PAYLOAD, store.getPayload(OTHER_TABLE, SIGNATURE))
    }

    @Test
    fun deleteTablePayload_removesOnlyMatchingSignature() = runTest {
        // Given
        store.addPayload(TABLE, SIGNATURE, PAYLOAD)
        store.addPayload(TABLE, OTHER_SIGNATURE, PAYLOAD)

        // When
        store.deleteTablePayload(TABLE, SIGNATURE)

        // Then
        assertNull(store.getPayload(TABLE, SIGNATURE))
        assertEquals(PAYLOAD, store.getPayload(TABLE, OTHER_SIGNATURE))
    }

    @Test
    fun observePayload_emitsNullThenPayload() =
        runTest(UnconfinedTestDispatcher()) {
            // Given
            val emissions = mutableListOf<String?>()
            val collector = launch {
                store.observePayload(TABLE, SIGNATURE).collect { emissions += it }
            }

            // When
            store.addPayload(TABLE, SIGNATURE, PAYLOAD)
            collector.cancel()

            // Then
            assertEquals(listOf(null, PAYLOAD), emissions)
        }

    // ─────────────────────────── Overlays ───────────────────────────

    @Test
    fun applyPendingMutations_delegatesToOverlay() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))

        // When
        val overlaid = store.overlayPendingOnResponse(TABLE, SIGNATURE, "[]")

        // Then
        assertEquals("""[{"id":1}]""", overlaid)
    }

    @Test
    fun applyAllMutationsForOfflineFallback_includesSyncedMutations() = runTest {
        // Given — a write that has already synced
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // When
        val fresh = store.overlayPendingOnResponse(TABLE, SIGNATURE, "[]")
        val offlineFallback = store.overlayCachedPayload(TABLE, "[]")

        // Then — a fresh payload already reflects it; a cached one may predate it, so it is
        // re-applied
        assertEquals("[]", fresh)
        assertEquals("""[{"id":1}]""", offlineFallback)
    }

    // ─────────────────────────── Storage rows ───────────────────────────

    @Test
    fun addGetDeleteStorage_roundTrip() = runTest {
        // Given
        clock = 7L
        store.addStorage(
            bucketId = "avatars",
            path = "u-1/me.png",
            file = "/base/me.png".toPath(),
            size = 12L,
            eTag = "tag",
        )

        // When
        val stored = store.getStorageByKey("avatars", "u-1/me.png")
        store.deleteStorageByKey("avatars", "u-1/me.png")

        // Then — keyed "$bucket::$path", with an absolute local path and the fetch time
        assertEquals(
            StorageRow(
                key = "avatars::u-1/me.png",
                bucketId = "avatars",
                path = "u-1/me.png",
                localPath = "/base/me.png",
                sizeBytes = 12L,
                eTag = "tag",
                lastFetchedEpochMs = 7L,
            ),
            stored,
        )
        assertNull(store.getStorageByKey("avatars", "u-1/me.png"))
    }

    // ─────────────────────────── Queued rows ───────────────────────────

    @Test
    fun enqueueInsert_createsMutationRow() = runTest {
        // Given — an empty queue

        // When
        store.enqueueInsert(
            table = TABLE,
            body = rows("""[{"id":1}]"""),
            filter = ID_FILTER,
            defaultToNull = false,
        )

        // Then
        assertEquals(
            MutationRow(
                id = 1,
                table = TABLE,
                operation = "INSERT",
                bodyJson = """[{"id":1}]""",
                filterJson = ID_FILTER_JSON,
                createdAtMs = 1_000L,
                syncedAtMs = null,
                defaultToNull = false,
                onConflict = null,
                ignoreDuplicates = null,
                deltaJson = null,
            ),
            mutationDao.inserted.single(),
        )
    }

    @Test
    fun enqueueUpsert_createsMutationRow() = runTest {
        // Given — an empty queue

        // When
        store.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"id":1}]"""),
            onConflict = "id",
            ignoreDuplicates = true,
            defaultToNull = false,
        )

        // Then
        val row = mutationDao.inserted.single()
        assertEquals("UPSERT", row.operation)
        assertEquals("id", row.onConflict)
        assertEquals(true, row.ignoreDuplicates)
        assertEquals(false, row.defaultToNull)
        assertNull(row.filterJson)
    }

    @Test
    fun enqueueUpsert_defaultsAreStored() = runTest {
        // Given — an empty queue

        // When
        store.enqueueUpsert(table = TABLE, bodyJson = rows("""[{"id":1}]"""))

        // Then — the builder defaults are written explicitly, not left NULL
        val row = mutationDao.inserted.single()
        assertNull(row.onConflict)
        assertEquals(false, row.ignoreDuplicates)
        assertEquals(true, row.defaultToNull)
    }

    @Test
    fun enqueueUpdateAndDelete_createsMutations() = runTest {
        // Given — an empty queue

        // When
        store.enqueueUpdate(
            table = TABLE,
            body = Json.parseToJsonElement("""{"name":"X"}"""),
            filter = ID_FILTER,
        )
        store.enqueueDelete(table = TABLE, filter = ID_FILTER)

        // Then — an UPDATE carries its body and filter; a DELETE an empty object body and its
        // filter
        val (update, delete) = mutationDao.inserted
        assertEquals("UPDATE", update.operation)
        assertEquals("""{"name":"X"}""", update.bodyJson)
        assertEquals(ID_FILTER_JSON, update.filterJson)
        assertEquals("DELETE", delete.operation)
        assertEquals("{}", delete.bodyJson)
        assertEquals(ID_FILTER_JSON, delete.filterJson)
    }

    @Test
    fun enqueueUpdate_defaultFilterIsNull() = runTest {
        // Given — an empty queue

        // When
        store.enqueueUpdate(table = TABLE, body = Json.parseToJsonElement("""{"name":"X"}"""))

        // Then
        val row = mutationDao.inserted.single()
        assertNull(row.filterJson)
        assertNull(row.deltaJson)
    }

    @Test
    fun pendingCountsAndDeleteMutation() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":2}]"""))
        store.enqueueInsert(table = OTHER_TABLE, body = rows("""[{"id":3}]"""))

        // When
        store.deleteMutation(mutationDao.inserted.first().id)

        // Then
        assertEquals(2, store.pendingMutationCount())
        assertEquals(1, store.pendingMutationCountForTable(TABLE))
        assertEquals(1, store.pendingMutationCountForTable(OTHER_TABLE))
    }

    @Test
    fun a_synced_mutation_is_no_longer_pending() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))

        // When
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // Then — kept, but not pending
        assertEquals(0, store.pendingMutationCount())
        assertEquals(1, mutationDao.inserted.size)
    }

    @Test
    fun clearSyncedMutationsForTable_removesOnlySyncedRows() = runTest {
        // Given — one synced and one pending row for the table, and a synced row elsewhere
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":2}]"""))
        store.enqueueInsert(table = OTHER_TABLE, body = rows("""[{"id":3}]"""))
        val (synced, _, otherTable) = mutationDao.inserted
        store.markMutationSynced(id = synced.id, table = TABLE)
        store.markMutationSynced(id = otherTable.id, table = OTHER_TABLE)

        // When
        store.retireCoveredSyncedMutations(TABLE)

        // Then
        assertEquals(
            listOf("""[{"id":2}]""", """[{"id":3}]"""),
            mutationDao.inserted.map { it.bodyJson },
        )
    }

    @Test
    fun pendingMutationCountFlow_emitsUpdatedCount_andExcludesSyncedMutations() =
        runTest(UnconfinedTestDispatcher()) {
            // Given
            val counts = mutableListOf<Int>()
            val collector = launch { store.pendingMutationCountFlow().collect { counts += it } }

            // When
            store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
            store.enqueueInsert(table = TABLE, body = rows("""[{"id":2}]"""))
            store.markMutationSynced(id = mutationDao.inserted.first().id, table = TABLE)
            collector.cancel()

            // Then
            assertEquals(listOf(0, 1, 2, 1), counts)
        }

    /**
     * The per-table count emits only when that table's pending writes change: a write to another
     * table invalidates the same database query (Room's tracker works per database table, not per
     * `table_name`), but the count is derived from the table's own pending list, de-duplicated, so
     * it no longer repeats its unchanged value.
     */
    @Test
    fun pendingMutationCountForTableFlow_emitsUpdatedCount_andExcludesSyncedWrites() =
        runTest(UnconfinedTestDispatcher()) {
            // Given
            val counts = mutableListOf<Int>()
            val collector = launch {
                store.pendingMutationCountForTableFlow(TABLE).collect { counts += it }
            }

            // When
            store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
            store.enqueueInsert(table = TABLE, body = rows("""[{"id":2}]"""))
            store.enqueueInsert(table = OTHER_TABLE, body = rows("""[{"id":3}]"""))
            store.markMutationSynced(id = mutationDao.inserted.first().id, table = TABLE)
            collector.cancel()

            // Then — the other table's write does not re-emit this table's count
            assertEquals(listOf(0, 1, 2, 1), counts)
        }

    @Test
    fun pendingMutationCountForTableFlow_onlyCountsSpecifiedTable() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""))
        store.enqueueInsert(table = OTHER_TABLE, body = rows("""[{"id":2}]"""))
        store.enqueueInsert(table = OTHER_TABLE, body = rows("""[{"id":3}]"""))

        // When
        val count = store.pendingMutationCountForTableFlow(TABLE).first()

        // Then
        assertEquals(1, count)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
        const val OTHER_TABLE = "projects"
        const val SIGNATURE = "todos?select=*"
        const val OTHER_SIGNATURE = "todos?select=*&done=eq.true"
        const val PAYLOAD = """[{"id":1}]"""

        val ID_FILTER =
            FilterDescriptor(conditions = listOf(FilterDescriptor.Condition("id", "eq", "1")))
        val ID_FILTER_JSON: String = FilterEngine.encode(ID_FILTER, OfflineJson)
    }
}

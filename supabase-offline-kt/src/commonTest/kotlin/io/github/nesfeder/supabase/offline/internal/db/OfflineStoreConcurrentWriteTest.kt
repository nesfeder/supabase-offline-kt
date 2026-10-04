// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests that the object cache's read-modify-write paths are serialized per table.
 *
 * [CachePatcher.patchCachedQueries] snapshots every cached payload for a table, patches each in
 * memory, and writes each back whole. Two of those running at once on the same table — a batch job
 * persists its rows from several parallel workers, and two sync runs can drive that job
 * concurrently — used to interleave freely: the second writer read the payload before the first had
 * written, appended its own row, and wrote the first's row back out. Nothing replays an
 * online-success overlay (it never enters the mutation queue), so the lost row stayed out of every
 * reactive `selectAsFlow` observer until something re-fetched the signature. On screen that was a
 * blank loading placeholder for minutes after its row was already on the server.
 *
 * Every test parks one writer between its read and its write with
 * [FakeQueryCacheDao.onAllForTableSnapshot], so the interleaving is exact rather than
 * timing-dependent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStoreConcurrentWriteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun concurrent_upsert_overlays_on_one_table_keep_every_appended_row() = runTest {
        // Given — one cached signature holding a single row, and a first overlay that will park
        // after it has snapshotted the cache (the read half of its read-modify-write)
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", ratingsPayload(1))
        val firstSnapshotTaken = CompletableDeferred<Unit>()
        val releaseFirstWriter = CompletableDeferred<Unit>()
        var snapshots = 0
        queryCacheDao.onAllForTableSnapshot = {
            snapshots++
            if (snapshots == 1) {
                firstSnapshotTaken.complete(Unit)
                releaseFirstWriter.await()
            }
        }
        val store = buildStore(queryCacheDao, tick = { 0L })

        // When — two online-success inserts overlay concurrently: the first parks holding its
        // pre-write snapshot, the second is launched while it is parked, then the first resumes
        val first = launch { store.insertRating(itemId = 2) }
        firstSnapshotTaken.await()
        val second = launch { store.insertRating(itemId = 3) }
        runCurrent()
        releaseFirstWriter.complete(Unit)
        first.join()
        second.join()

        // Then — both appended rows survive: the second writer must have read the first's write
        // rather than the snapshot the first was still holding
        assertEquals(listOf(1, 2, 3), queryCacheDao.itemIds("ratings", "sig-A"))
    }

    @Test
    fun refresh_landing_mid_overlay_is_not_rolled_back_by_that_overlay() = runTest {
        // Given — a cached signature and an overlay parked after its snapshot
        val queryCacheDao = FakeQueryCacheDao()
        queryCacheDao.seedPayload("ratings", "sig-A", ratingsPayload(1))
        var tick = 10L
        val store = buildStore(queryCacheDao, tick = { tick })
        val snapshotTaken = CompletableDeferred<Unit>()
        val releaseOverlay = CompletableDeferred<Unit>()
        queryCacheDao.onAllForTableSnapshot = {
            snapshotTaken.complete(Unit)
            releaseOverlay.await()
        }
        val overlay = launch { store.insertRating(itemId = 2) }
        snapshotTaken.await()

        // When — a refresh whose request started AFTER the overlay recorded its write (so the
        // stale-response watermark admits it) lands while the overlay is still parked. Its
        // payload is the server's view: the overlaid row is already there, and so is a row
        // another device wrote in the meantime.
        tick = 20L
        val requestStartedAtTick = store.freshness.tickNow()
        val refresh = launch {
            store.queryCache.cacheResponse(
                table = "ratings",
                cacheKey = "sig-A",
                payload = ratingsPayload(1, 2, 9),
                requestStartedAtTick = requestStartedAtTick,
            )
        }
        runCurrent()
        releaseOverlay.complete(Unit)
        overlay.join()
        refresh.join()

        // Then — the fresher server payload is what the cache holds; the overlay's stale
        // snapshot did not write row 9 back out
        assertEquals(listOf(1, 2, 9), queryCacheDao.itemIds("ratings", "sig-A"))
    }

    private suspend fun TestStore.insertRating(itemId: Int) {
        cachePatcher.patchCachedQueries(
            table = "ratings",
            operation = MutationOperation.UPSERT,
            bodyJson = ratingsPayload(itemId),
            filterJson = null,
            defaultToNull = false,
            onConflict = "user_id,item_id",
            ignoreDuplicates = true,
        )
    }

    private fun ratingsPayload(vararg itemIds: Int): String =
        itemIds.joinToString(prefix = "[", postfix = "]") { id ->
            """{"item_id":$id,"user_id":"u"}"""
        }

    private fun FakeQueryCacheDao.itemIds(table: String, cacheKey: String): List<Int> {
        val row = getRow(table, cacheKey)
        assertNotNull(row)
        return json.parseToJsonElement(row.payload).jsonArray.map { element ->
            element.jsonObject.getValue("item_id").jsonPrimitive.int
        }
    }

    private fun buildStore(
        queryCacheDao: FakeQueryCacheDao,
        tick: () -> Long,
    ): TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            monotonicTick = tick,
        )
}

@file:OptIn(ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * Tests that the object cache's read-modify-write paths are serialized per table.
 *
 * [OfflineStore.patchCachedQueries] snapshots every cached payload for a table, patches
 * each in memory, and writes each back whole. Two of those running at once on the same table
 * — a batch job persists its rows from several parallel workers, and two sync
 * runs can drive that job concurrently — used to interleave freely: the second writer read the
 * payload before the first had written, appended its own row, and wrote the first's row back
 * out. Nothing replays an online-success overlay (it never enters the mutation queue), so the
 * lost row stayed out of every reactive `selectAsFlow` observer until something re-fetched the
 * signature. On screen that was a blank loading placeholder for minutes after
 * its row was already on the server.
 *
 * Every test parks one writer between its read and its write with
 * [FakeObjectDao.onAllForTableSnapshot], so the interleaving is exact rather than timing-dependent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OfflineStoreConcurrentWriteTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun concurrent_upsert_overlays_on_one_table_keep_every_appended_row() = runTest {
        // Given — one cached signature holding a single row, and a first overlay that will park
        // after it has snapshotted the cache (the read half of its read-modify-write)
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("ratings", "sig-A", ratingsPayload(1))
        val firstSnapshotTaken = CompletableDeferred<Unit>()
        val releaseFirstWriter = CompletableDeferred<Unit>()
        var snapshots = 0
        objectDao.onAllForTableSnapshot = {
            snapshots++
            if (snapshots == 1) {
                firstSnapshotTaken.complete(Unit)
                releaseFirstWriter.await()
            }
        }
        val store = buildStore(objectDao, tick = { 0L })

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
        assertEquals(listOf(1, 2, 3), objectDao.itemIds("ratings", "sig-A"))
    }

    @Test
    fun refresh_landing_mid_overlay_is_not_rolled_back_by_that_overlay() = runTest {
        // Given — a cached signature and an overlay parked after its snapshot
        val objectDao = FakeObjectDao()
        objectDao.seedPayload("ratings", "sig-A", ratingsPayload(1))
        var tick = 10L
        val store = buildStore(objectDao, tick = { tick })
        val snapshotTaken = CompletableDeferred<Unit>()
        val releaseOverlay = CompletableDeferred<Unit>()
        objectDao.onAllForTableSnapshot = {
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
        val requestStartedAtTick = store.monotonicTickNow()
        val refresh = launch {
            store.addPayload(
                table = "ratings",
                signature = "sig-A",
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
        assertEquals(listOf(1, 2, 9), objectDao.itemIds("ratings", "sig-A"))
    }

    private suspend fun OfflineStore.insertRating(itemId: Int) {
        patchCachedQueries(
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

    private fun FakeObjectDao.itemIds(table: String, signature: String): List<Int> {
        val row = getRow(table, signature)
        assertNotNull(row)
        return json.parseToJsonElement(row.payload).jsonArray.map { element ->
            element.jsonObject.getValue("item_id").jsonPrimitive.int
        }
    }

    private fun buildStore(
        objectDao: FakeObjectDao,
        tick: () -> Long,
    ): OfflineStore = newTestOfflineStore(
        objectDao = objectDao,
        monotonicTick = tick,
    )
}

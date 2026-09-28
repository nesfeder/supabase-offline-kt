@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeStorageDao
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Part B substrate + materialize-on-write semantics that replace the old fabricated empty-array
 * cache seed. Covers: the in-memory overlay substrate ([OfflineStore.cachePayloadWithOverlayFlow])
 * making a queued-offline INSERT visible without a Room write; materialize-on-write making an
 * online-success mutation visible to a live cache-less subscriber (the fix for the "save then
 * stale UI" bug, re-expressed without the seed); and the self-GC guarantee that nothing is
 * written when no subscriber is live.
 */
class OfflineStoreEmptyCacheReactivityTest {

    @Test
    fun substrate_reports_no_data_when_no_cache_and_no_mutations() = runTest {
        // Given — an empty cache and no queued mutations
        val store = buildStore(FakeObjectDao(), FakeMutationDao())

        // When — the overlay substrate is observed
        val emitted = store.cachePayloadWithOverlayFlow("shoes", "sig-A").first()

        // Then — it reports the typed no-data state (null), never a fabricated empty array
        assertNull(emitted, "cache-less, mutation-less signature is no-data, not empty []")
    }

    @Test
    fun queued_offline_insert_is_visible_via_substrate_without_writing_a_room_row() = runTest {
        // Given — an empty cache and a queued offline INSERT for the table
        val objectDao = FakeObjectDao()
        val mutationDao = FakeMutationDao()
        val store = buildStore(objectDao, mutationDao)
        store.enqueueInsert(
            table = "shoes",
            body = buildJsonArray { add(buildJsonObject { put("id", "shoe-1"); put("user_id", "u-1") }) },
        )

        // When — the overlay substrate is observed
        val emitted = store.cachePayloadWithOverlayFlow("shoes", "sig-A").first()

        // Then — the optimistic row surfaces from the in-memory `[]` substrate, and nothing was
        // persisted to Room for the signature
        assertNotNull(emitted, "a queued INSERT must be visible even with no cached payload")
        assertTrue(emitted.contains("shoe-1"))
        assertNull(objectDao.getRow("shoes", "sig-A"), "the substrate must not persist a Room row")
        assertEquals(0, objectDao.writes, "the substrate must not write to the object cache")
    }

    @Test
    fun live_cache_less_subscriber_sees_a_materialized_online_upsert() = runTest(UnconfinedTestDispatcher()) {
        // Given — a live subscriber on a signature whose object cache is empty
        val objectDao = FakeObjectDao()
        val store = buildStore(objectDao, FakeMutationDao())
        val table = "activity_log_shoes"
        val signature = "sig-A"
        val cacheFlow = store.cachePayloadWithOverlayFlow(table, signature)
        val firstRealRow = async {
            withTimeoutOrNull(timeMillis = 500) { cacheFlow.first { it != null && it.contains("shoe-1") } }
        }

        // When — an online-success upsert fires while the subscriber is live but cache-less; the
        // client passes the live signature so materialize-on-write can create the row
        store.applyLocalMutationToCache(
            table = table,
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"activity_log_id":"log-1","shoe_id":"shoe-1","user_id":"u-1"}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "activity_log_id",
            liveSignatures = setOf(signature),
        )

        // Then — the collector observes the materialized row (honoring the "UI reflects immediately"
        // contract for first-time saves), and a real row now exists in Room
        val emitted = firstRealRow.await()
        assertNotNull(emitted, "live collector must observe the materialized upsert even on an empty cache")
        assertTrue(emitted.contains("shoe-1"))
        assertNotNull(objectDao.getRow(table, signature), "materialize-on-write must persist the real row")
    }

    @Test
    fun online_success_mutation_writes_nothing_when_no_subscriber_is_live() = runTest {
        // Given — an empty cache and no live subscribers
        val objectDao = FakeObjectDao()
        val store = buildStore(objectDao, FakeMutationDao())

        // When — an online-success upsert fires on an uncached table
        store.applyLocalMutationToCache(
            table = "activity_log_shoes",
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"activity_log_id":"log-1","shoe_id":"shoe-1","user_id":"u-1"}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "activity_log_id",
        )

        // Then — nothing is materialized (self-GC: no live subscriber → no write)
        assertEquals(0, objectDao.writes, "no live subscriber means no materialized row")
    }

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

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.TestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * Eviction holds no table lock, so it can delete a list between a patch's read of the table and its
 * write. The patch writes over the rows it read ([CachePatcher]) and never brings back the deleted
 * list, which, with no shape record left for it, would have admitted every row after a restart.
 */
class PatchAfterEvictionTest {

    private val queryCacheDao = FakeQueryCacheDao()

    /** A cache with room for one of the two lists, so a pass evicts the older. */
    private val store: TestStore =
        newTestStore(
            queryCacheDao = queryCacheDao,
            queryCacheLimits =
                QueryCacheLimits(
                    maxPayloadBytes = 40,
                    evictionHeadroomBytes = 0,
                ),
        )

    /** Evicts once, inside the next read of [TABLE]'s payloads. */
    private fun evictDuringNextPatchRead() {
        var evicted = false
        queryCacheDao.onAllForTableSnapshot = { table ->
            if (table == TABLE && !evicted) {
                evicted = true
                store.queryCache.evictOverBudget()
            }
        }
    }

    private suspend fun seedBothLists() {
        queryCacheDao.seedPayload(TABLE, OLDER, """[{"id":1,"done":false}]""", updatedAt = 1)
        queryCacheDao.seedPayload(TABLE, NEWER, """[{"id":2,"done":false}]""", updatedAt = 2)
        store.recordQueryShape(TABLE, OLDER, persist = true) { filter { eq("id", "1") } }
        store.recordQueryShape(TABLE, NEWER, persist = true) {}
    }

    private fun shapeRecordKeys(): List<String> =
        queryCacheDao
            .rowsOf(RESERVED_TABLE_NAME)
            .map { row -> row.cacheKey }
            .filter { cacheKey -> cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX) }

    @Test
    fun a_list_evicted_between_a_patchs_read_and_its_write_stays_gone() = runTest {
        // Given — two lists with their shapes, and an eviction pass due inside the next patch
        seedBothLists()
        evictDuringNextPatchRead()

        // When — an accepted upsert of the older list's row, and of a new row, is patched in
        store.cachePatcher.patchCachedQueries(
            table = TABLE,
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":1,"done":true},{"id":3,"done":true}]""",
            filterJson = null,
            defaultToNull = false,
            onConflict = "id",
        )

        // Then — the evicted list, which the upsert changed, is not back, nor its shape record;
        // the other list gained the new row (row 1, held by another list before, is outside its
        // window, as the overlay reads it)
        assertNull(queryCacheDao.getRow(TABLE, OLDER))
        assertEquals(
            """[{"id":2,"done":false},{"id":3,"done":true}]""",
            queryCacheDao.getRow(TABLE, NEWER)?.payload,
        )
        assertEquals(listOf("$QUERY_SHAPE_CACHE_KEY_PREFIX$TABLE\u001F$NEWER"), shapeRecordKeys())
    }

    @Test
    fun a_shape_record_is_written_only_beside_a_payload() = runTest {
        // Given — a query with no payload, as after an eviction, whose shape a read records
        store.recordQueryShape(TABLE, OLDER, persist = true) { filter { eq("id", "1") } }

        // Then — no record outlives the payload
        assertEquals(emptyList(), shapeRecordKeys())

        // When — a payload is cached for the query, and the shape recorded again
        queryCacheDao.seedPayload(TABLE, OLDER, """[{"id":1,"done":false}]""", updatedAt = 1)
        store.recordQueryShape(TABLE, OLDER, persist = true) { filter { eq("id", "1") } }

        // Then — the record is written beside it
        assertEquals(listOf("$QUERY_SHAPE_CACHE_KEY_PREFIX$TABLE\u001F$OLDER"), shapeRecordKeys())
    }

    @Test
    fun a_list_evicted_before_it_is_marked_stale_stays_gone() = runTest {
        // Given
        seedBothLists()
        evictDuringNextPatchRead()

        // When — a write the cache cannot patch marks every list of the table stale
        store.cachePatcher.markEveryQueryStale(TABLE, startedAtTick = store.freshness.tickNow())

        // Then — the evicted list is not back; the other is stale
        assertNull(queryCacheDao.getRow(TABLE, OLDER))
        assertEquals(true, queryCacheDao.getRow(TABLE, NEWER)?.updatedAt?.let(::isStaleStamp))
    }

    private companion object {
        const val TABLE = "tasks"
        const val OLDER = "tasks?id=eq.1"
        const val NEWER = "tasks"
    }
}

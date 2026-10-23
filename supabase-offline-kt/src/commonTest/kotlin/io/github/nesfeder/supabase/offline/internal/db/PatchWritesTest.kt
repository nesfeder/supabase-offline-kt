// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * A patch writes every payload it changes or creates in one transaction, and keeps what it wrote
 * parsed, so the next patch over the table parses nothing. Before this, a patch wrote each payload
 * on its own, and every patch over forty lists of 50 KB parsed all forty again: 40 parses, then 40
 * more.
 */
class PatchWritesTest {

    private val queryCacheDao = FakeQueryCacheDao()

    /** The live queries of each table, as the store is told them. */
    private val live = mutableMapOf<String, Set<String>>()

    private val store =
        newTestStore(
            queryCacheDao = queryCacheDao,
            liveCacheKeysOf = { table -> live[table].orEmpty() },
        )

    @Test
    fun the_second_of_two_patches_over_forty_lists_parses_nothing() = runTest {
        // Given forty cached lists of about 50 KB each, over 2 MB in all
        seedLists(40, rowsEach = 100, noteLength = 480)
        assertTrue(queryCacheDao.rowsOf(TABLE).sumOf { row -> row.payload.length } > 2_000_000)

        // When a new row is upserted, which every list admits
        upsertNewRow(900_000)

        // Then every list was parsed once
        assertEquals(40, store.queryCache.payloadParses)

        // When another new row is upserted
        upsertNewRow(900_001)

        // Then nothing was parsed again: what the first patch wrote was kept as its rows
        assertEquals(40, store.queryCache.payloadParses)
        // And both rows reached every list
        queryCacheDao.rowsOf(TABLE).forEach { row ->
            assertTrue(
                row.payload.endsWith("""{"id":900000,$NEW},{"id":900001,$NEW}]"""),
                row.cacheKey,
            )
        }
    }

    @Test
    fun marking_lists_stale_parses_nothing_a_patch_left_parsed() = runTest {
        // Given five lists a patch has changed
        seedLists(5, rowsEach = 10, noteLength = 40)
        upsertNewRow(100)
        assertEquals(5, store.queryCache.payloadParses)

        // When a DELETE the cache cannot patch, of the rows of list L1, marks the lists it reaches
        store.cachePatcher.markQueriesAWriteMayReach(
            TABLE,
            MutationOperation.DELETE,
            "{}",
            narrowing =
                FilterDescriptor(
                    conditions =
                        listOf(FilterDescriptor.Condition("list", operatorId = "eq", value = "L1"))
                ),
            startedAtTick = store.freshness.tickNow(),
        )

        // Then the rows it read were the ones kept, not parsed again, and only L1's list is stale
        assertEquals(5, store.queryCache.payloadParses)
        assertTrue(isStaleStamp(assertNotNull(queryCacheDao.getRow(TABLE, listKey(1))).updatedAt))
        assertFalse(isStaleStamp(assertNotNull(queryCacheDao.getRow(TABLE, listKey(0))).updatedAt))
    }

    @Test
    fun a_patch_whose_second_write_fails_lands_none_of_its_writes() = runTest {
        // Given three lists a new row reaches, and a disk that fails the second list's write
        seedLists(3, rowsEach = 1, noteLength = 4)
        val before = queryCacheDao.rowsOf(TABLE).map { row -> row.payload }
        queryCacheDao.failNextUpsertOf { row -> row.cacheKey == listKey(1) }

        // When the row is patched in
        assertFailsWith<IllegalStateException> { upsertNewRow(7) }

        // Then the first list's write, which went through, was rolled back with the rest
        assertEquals(before, queryCacheDao.rowsOf(TABLE).map { row -> row.payload })
    }

    @Test
    fun a_created_live_payload_lands_with_the_changed_lists_or_not_at_all() = runTest {
        // Given two lists, and a live query of everything with no payload yet
        seedLists(2, rowsEach = 1, noteLength = 4)
        live[TABLE] = setOf(LIVE)
        store.recordQueryShape(TABLE, LIVE) {}
        val before = queryCacheDao.rowsOf(TABLE).map { row -> row.payload }

        // When a new row reaches both lists and the live query, and the created payload's write
        // fails
        queryCacheDao.failNextUpsertOf { row -> row.cacheKey == LIVE }
        assertFailsWith<IllegalStateException> { upsertNewRow(7) }

        // Then neither list changed and no payload was created
        assertEquals(before, queryCacheDao.rowsOf(TABLE).map { row -> row.payload })
        assertNull(queryCacheDao.getRow(TABLE, LIVE))

        // When the write goes through
        upsertNewRow(7)

        // Then all three landed together, the created payload with its shape record
        assertEquals("""[{"id":7,$NEW}]""", queryCacheDao.getRow(TABLE, LIVE)?.payload)
        listOf(0, 1).forEach { list ->
            assertTrue(
                queryCacheDao.getRow(TABLE, listKey(list))!!.payload.endsWith("{\"id\":7,$NEW}]")
            )
        }
        assertNotNull(
            queryCacheDao.getRow(
                RESERVED_TABLE_NAME,
                "$QUERY_SHAPE_CACHE_KEY_PREFIX$TABLE\u001F$LIVE",
            )
        )
    }

    @Test
    fun what_a_patch_adds_to_a_list_counts_toward_the_bound() = runTest {
        // Given a bounded cache whose one list, written as a response, is counted
        var clock = 1L
        val bounded =
            newTestStore(
                queryCacheDao = queryCacheDao,
                queryCacheLimits =
                    QueryCacheLimits(maxPayloadBytes = 1_000L, evictionHeadroomBytes = 0L),
                nowMillis = { clock++ },
            )
        val note = "n".repeat(500)
        bounded.queryCache.cacheResponse(
            TABLE,
            listKey(0),
            """[{"id":1,"list":"L0","note":"$note"}]""",
        )

        // When a patch grows the list past the bound, and a small response of another query lands
        bounded.cachePatcher.patchCachedQueries(
            table = TABLE,
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":2,"list":"L0","note":"$note"}]""",
            filterJson = null,
            onConflict = "id",
        )
        assertTrue(assertNotNull(queryCacheDao.getRow(TABLE, listKey(0))).payload.length > 1_000)
        bounded.queryCache.cacheResponse(TABLE, listKey(1), """[{"id":3,"list":"L1"}]""")

        // Then the growth counted: a pass ran and evicted the grown list, the older of the two
        assertNull(queryCacheDao.getRow(TABLE, listKey(0)))
        assertNotNull(queryCacheDao.getRow(TABLE, listKey(1)))
    }

    /** Seeds [count] lists, `L0` the oldest, of [rowsEach] rows with a note of [noteLength]. */
    private fun seedLists(count: Int, rowsEach: Int, noteLength: Int) {
        var id = 0
        repeat(count) { list ->
            val rows =
                List(rowsEach) {
                    """{"id":${id++},"list":"L$list","note":"${"n".repeat(noteLength)}"}"""
                }
            queryCacheDao.seedPayload(
                TABLE,
                listKey(list),
                rows.joinToString(",", "[", "]"),
                updatedAt = list.toLong(),
            )
        }
    }

    /** Patches an accepted upsert of a row with [id] that no list holds. */
    private suspend fun upsertNewRow(id: Int) {
        store.cachePatcher.patchCachedQueries(
            table = TABLE,
            operation = MutationOperation.UPSERT,
            bodyJson = """[{"id":$id,$NEW}]""",
            filterJson = null,
            onConflict = "id",
        )
    }

    private fun listKey(list: Int): String = "items?list=eq.L$list"

    private companion object {
        const val TABLE = "items"
        const val LIVE = "items"

        /** The columns of a new row after its id. */
        const val NEW = """"list":"L0","note":"new""""
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.recordQueryShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * How a write the server accepted is patched into the cached queries: the server holds every row it
 * made, so a row a refresh already holds is not added twice; an upsert row with no key to place it
 * by is not guessed at, its queries marked stale instead; a write the patch cannot apply is logged,
 * once for each write; and a patch that runs as a query's first answer is written already knows
 * which rows that query admits.
 */
class AcceptedWritePatchTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val logger = RecordingLogSink()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, nowMillis = { NOW }).apply {
            engineLogger = logger.asEngineLogger(EngineLogLevel.WARNING)
        }

    /** The queries the store asked to fetch again, by table. */
    private val markedStale = mutableListOf<Pair<String, Set<String>>>()

    init {
        store.onQueriesMarkedStale = { table, cacheKeys -> markedStale += table to cacheKeys }
    }

    @Test
    fun a_patch_let_in_as_a_first_answer_is_written_sees_that_answers_shape() = runTest {
        // Given — list A's first answer being written, held inside the table's lock
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        queryCacheDao.onUpsert = { row ->
            if (row.cacheKey == LIST_A && !release.isCompleted) {
                reached.complete(Unit)
                release.await()
            }
        }
        val listA =
            queryShapeOf(
                Columns.ALL,
                SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply {
                    filter { eq("list", "A") }
                },
            )
        val writer = launch {
            store.queryCache.cacheResponse(TASKS, LIST_A, LIST_A_ROWS, shape = listA)
        }
        reached.await()

        // When — an accepted insert of a row of list B waits on the lock, and the lock's release
        // resumes it in place
        val patch =
            launch(Dispatchers.Unconfined) {
                store.cachePatcher.patchCachedQueries(
                    table = TASKS,
                    operation = MutationOperation.INSERT,
                    bodyJson = """[{"id":2,"list":"B"}]""",
                    filterJson = null,
                    liveCacheKeys = emptySet(),
                )
            }
        release.complete(Unit)
        writer.join()
        patch.join()

        // Then — list A holds its own row alone: the patch saw which rows it admits
        assertEquals(LIST_A_ROWS, queryCacheDao.getRow(TASKS, LIST_A)?.payload)
    }

    @Test
    fun an_accepted_insert_a_refresh_already_holds_is_not_added_twice() = runTest {
        // Given — a refresh that landed after the insert reached the server holds its row
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, """[{"id":7,"title":"new"}]""", updatedAt = NOW)
        store.recordQueryShape(TABLE, CACHE_KEY) {}

        // When — the insert's patch arrives
        store.cachePatcher.patchCachedQueries(
            table = TABLE,
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":7,"title":"new"}]""",
            filterJson = null,
        )

        // Then — one row
        assertEquals(
            """[{"id":7,"title":"new"}]""",
            queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload,
        )
    }

    @Test
    fun an_accepted_upsert_row_without_a_key_is_not_guessed_into_a_list_which_is_fetched_again() =
        runTest {
            // Given — a list of settings, a table keyed by `key` the engine has not been told of
            queryCacheDao.seedPayload(
                TABLE,
                CACHE_KEY,
                """[{"key":"theme","value":"dark"}]""",
                updatedAt = NOW,
            )
            store.recordQueryShape(TABLE, CACHE_KEY) {}

            // When — an upsert of the same setting, with no conflict columns
            store.cachePatcher.patchCachedQueries(
                table = TABLE,
                operation = MutationOperation.UPSERT,
                bodyJson = """[{"key":"theme","value":"light"}]""",
                filterJson = null,
            )

            // Then — no second row by guess; the list is stale and fetched again
            val row = queryCacheDao.getRow(TABLE, CACHE_KEY)
            assertEquals("""[{"key":"theme","value":"dark"}]""", row?.payload)
            assertTrue(row != null && row.updatedAt < 0, "${row?.updatedAt}")
            assertEquals(listOf(TABLE to setOf(CACHE_KEY)), markedStale)
        }

    @Test
    fun each_accepted_write_the_patch_cannot_apply_is_logged() = runTest {
        // Given — a cached list
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, """[{"id":1}]""", updatedAt = NOW)

        // When — two writes whose bodies the patch cannot read
        repeat(2) {
            store.cachePatcher.patchCachedQueries(
                table = TABLE,
                operation = MutationOperation.INSERT,
                bodyJson = "not json",
                filterJson = null,
            )
        }

        // Then — both logged, without their bodies
        val warnings = logger.entriesAt(EngineLogLevel.WARNING).map { entry -> entry.message }
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.none { message -> "not json" in message })
    }

    private companion object {
        const val TABLE = "settings"
        const val CACHE_KEY = "settings"
        const val NOW = 1_000_000L
        const val TASKS = "tasks"
        const val LIST_A = "tasks?list=eq.A"
        const val LIST_A_ROWS = """[{"id":1,"list":"A"}]"""
    }
}

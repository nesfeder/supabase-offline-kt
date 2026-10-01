// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
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

    private val objectDao = FakeObjectDao()
    private val logger = RecordingOfflineLogger()
    private val store =
        newTestOfflineStore(objectDao = objectDao, nowMillis = { NOW }).apply {
            engineLogger = logger.asEngineLogger(OfflineLogLevel.WARNING)
        }

    /** The queries the store asked to fetch again, by table. */
    private val markedStale = mutableListOf<Pair<String, Set<String>>>()

    init {
        store.onQueriesMarkedStale = { table, signatures -> markedStale += table to signatures }
    }

    @Test
    fun a_patch_let_in_as_a_first_answer_is_written_sees_that_answers_shape() = runTest {
        // Given — list A's first answer being written, held inside the table's lock
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        objectDao.onUpsert = { row ->
            if (row.signature == LIST_A && !release.isCompleted) {
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
        val writer = launch { store.addPayload(TASKS, LIST_A, LIST_A_ROWS, shape = listA) }
        reached.await()

        // When — an accepted insert of a row of list B waits on the lock, and the lock's release
        // resumes it in place
        val patch =
            launch(Dispatchers.Unconfined) {
                store.patchCachedQueries(
                    table = TASKS,
                    operation = MutationOperation.INSERT,
                    bodyJson = """[{"id":2,"list":"B"}]""",
                    filterJson = null,
                    liveSignatures = emptySet(),
                )
            }
        release.complete(Unit)
        writer.join()
        patch.join()

        // Then — list A holds its own row alone: the patch saw which rows it admits
        assertEquals(LIST_A_ROWS, objectDao.getRow(TASKS, LIST_A)?.payload)
    }

    @Test
    fun an_accepted_insert_a_refresh_already_holds_is_not_added_twice() = runTest {
        // Given — a refresh that landed after the insert reached the server holds its row
        objectDao.seedPayload(TABLE, SIGNATURE, """[{"id":7,"title":"new"}]""", updatedAt = NOW)
        store.recordQueryShape(TABLE, SIGNATURE) {}

        // When — the insert's patch arrives
        store.patchCachedQueries(
            table = TABLE,
            operation = MutationOperation.INSERT,
            bodyJson = """[{"id":7,"title":"new"}]""",
            filterJson = null,
        )

        // Then — one row
        assertEquals("""[{"id":7,"title":"new"}]""", objectDao.getRow(TABLE, SIGNATURE)?.payload)
    }

    @Test
    fun an_accepted_upsert_row_without_a_key_is_not_guessed_into_a_list_which_is_fetched_again() =
        runTest {
            // Given — a list of settings, a table keyed by `key` the engine has not been told of
            objectDao.seedPayload(
                TABLE,
                SIGNATURE,
                """[{"key":"theme","value":"dark"}]""",
                updatedAt = NOW,
            )
            store.recordQueryShape(TABLE, SIGNATURE) {}

            // When — an upsert of the same setting, with no conflict columns
            store.patchCachedQueries(
                table = TABLE,
                operation = MutationOperation.UPSERT,
                bodyJson = """[{"key":"theme","value":"light"}]""",
                filterJson = null,
            )

            // Then — no second row by guess; the list is stale and fetched again
            val row = objectDao.getRow(TABLE, SIGNATURE)
            assertEquals("""[{"key":"theme","value":"dark"}]""", row?.payload)
            assertTrue(row != null && row.updatedAt < 0, "${row?.updatedAt}")
            assertEquals(listOf(TABLE to setOf(SIGNATURE)), markedStale)
        }

    @Test
    fun each_accepted_write_the_patch_cannot_apply_is_logged() = runTest {
        // Given — a cached list
        objectDao.seedPayload(TABLE, SIGNATURE, """[{"id":1}]""", updatedAt = NOW)

        // When — two writes whose bodies the patch cannot read
        repeat(2) {
            store.patchCachedQueries(
                table = TABLE,
                operation = MutationOperation.INSERT,
                bodyJson = "not json",
                filterJson = null,
            )
        }

        // Then — both logged, without their bodies
        val warnings = logger.entriesAt(OfflineLogLevel.WARNING).map { entry -> entry.message }
        assertEquals(2, warnings.size, "$warnings")
        assertTrue(warnings.none { message -> "not json" in message })
    }

    private companion object {
        const val TABLE = "settings"
        const val SIGNATURE = "settings"
        const val NOW = 1_000_000L
        const val TASKS = "tasks"
        const val LIST_A = "tasks?list=eq.A"
        const val LIST_A_ROWS = """[{"id":1,"list":"A"}]"""
    }
}

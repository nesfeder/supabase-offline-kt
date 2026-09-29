// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A replayed write is kept, marked synced, so the overlay can go on showing it on cached queries
 * fetched before it replayed. It must never be overlaid onto a query fetched after it replayed:
 * that query already holds the server's version, so the overlay would show an inserted row twice,
 * or put back the user's older value over an edit another device made since.
 */
class SyncedOverlayScopeTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private var tick = 0L
    private var clock = 1_000_000L
    private val store =
        newTestOfflineStore(
            objectDao = objectDao,
            mutationDao = mutationDao,
            monotonicTick = { tick },
            nowMillis = { clock },
        )

    @Test
    fun a_refreshed_query_does_not_show_a_retained_synced_insert_twice() = runTest {
        // Given — two cached queries, an offline insert of x, then its replay
        objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L"}]""")
        objectDao.seedPayload(TABLE, ALL, """[{"id":"a","list_id":"L"}]""")
        tick = 10L
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"x","list_id":"L"}]"""))
        tick = 20L
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // When — only ALL refreshes, and the server's rows include x
        tick = 30L
        val started = store.monotonicTickNow()
        tick = 40L
        store.addPayload(
            TABLE,
            ALL,
            """[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""",
            requestStartedAtTick = started,
        )
        store.retireCoveredSyncedMutations(TABLE, started)

        // Then — ALL shows x once, and L (fetched before the replay) still shows it
        assertEquals(
            """[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""",
            store.overlaidPayloadFlow(TABLE, ALL).first(),
        )
        assertEquals(
            """[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""",
            store.overlaidPayloadFlow(TABLE, LIST_L).first(),
        )
    }

    @Test
    fun a_refreshed_query_is_not_overwritten_by_a_retained_synced_update() = runTest {
        // Given — two cached queries, an offline rename to A, then its replay
        objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L","title":"orig"}]""")
        objectDao.seedPayload(TABLE, ALL, """[{"id":"a","list_id":"L","title":"orig"}]""")
        tick = 10L
        store.enqueueUpdate(table = TABLE, body = element("""{"title":"A"}"""), filter = byId("a"))
        tick = 20L
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // When — another device renamed it to C; ALL refreshes and brings back C
        tick = 30L
        val started = store.monotonicTickNow()
        tick = 40L
        store.addPayload(
            TABLE,
            ALL,
            """[{"id":"a","list_id":"L","title":"C"}]""",
            requestStartedAtTick = started,
        )
        store.retireCoveredSyncedMutations(TABLE, started)

        // Then — ALL shows the server's C
        assertEquals(
            """[{"id":"a","list_id":"L","title":"C"}]""",
            store.overlaidPayloadFlow(TABLE, ALL).first(),
        )
    }

    @Test
    fun a_one_shot_read_of_a_refreshed_query_is_not_overwritten_by_a_retained_synced_update() =
        runTest {
            // Given — as above: an offline rename to A replayed, then ALL refreshed with another
            // device's C
            objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L","title":"orig"}]""")
            objectDao.seedPayload(TABLE, ALL, """[{"id":"a","list_id":"L","title":"orig"}]""")
            tick = 10L
            store.enqueueUpdate(
                table = TABLE,
                body = element("""{"title":"A"}"""),
                filter = byId("a"),
            )
            tick = 20L
            store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)
            tick = 30L
            val started = store.monotonicTickNow()
            tick = 40L
            store.addPayload(
                TABLE,
                ALL,
                """[{"id":"a","list_id":"L","title":"C"}]""",
                requestStartedAtTick = started,
            )

            // When — a one-shot read falls back to the cache for each query
            val all =
                store.overlayCachedPayload(TABLE, ALL, """[{"id":"a","list_id":"L","title":"C"}]""")
            val listL =
                store.overlayCachedPayload(
                    TABLE,
                    LIST_L,
                    """[{"id":"a","list_id":"L","title":"orig"}]""",
                )

            // Then — ALL keeps the server's C; L, fetched before the replay, still shows A
            assertEquals("""[{"id":"a","list_id":"L","title":"C"}]""", all)
            assertEquals("""[{"id":"a","list_id":"L","title":"A"}]""", listL)
        }

    @Test
    fun a_synced_write_is_retired_after_a_day_even_if_a_query_that_needs_it_is_never_refreshed() =
        runTest {
            // Given — a detail query cached once and never refreshed, and a replayed insert it
            // shows
            objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L"}]""")
            store.enqueueInsert(table = TABLE, body = rows("""[{"id":"x","list_id":"L"}]"""))
            store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)
            store.retireCoveredSyncedMutations(TABLE)
            val keptWhileNeeded = mutationDao.inserted.size

            // When — a day passes
            clock += ONE_DAY_MS + 1
            store.retireAgedSyncedMutations()

            // Then — kept while the query needed it, retired once it aged out
            assertEquals(1, keptWhileNeeded)
            assertEquals(0, mutationDao.inserted.size)
            assertEquals(
                """[{"id":"a","list_id":"L"}]""",
                store.overlaidPayloadFlow(TABLE, LIST_L).first(),
            )
        }

    @Test
    fun a_refreshed_query_keeps_the_servers_version_after_a_restart() = runTest {
        // Given — a keyless insert and an update replayed; ALL refreshed afterwards with the
        // server's
        // rows, while another query stays unrefreshed and so keeps the synced mutations around
        objectDao.seedPayload(TABLE, ORPHAN, """[{"id":"a","title":"orig"}]""")
        objectDao.seedPayload(TABLE, ALL, """[{"id":"a","title":"orig"}]""")
        tick = 10L
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"keyless"}]"""))
        store.enqueueUpdate(table = TABLE, body = element("""{"title":"A"}"""), filter = byId("a"))
        tick = 20L
        mutationDao.inserted.forEach { mutation ->
            store.markMutationSynced(id = mutation.id, table = TABLE)
        }
        tick = 30L
        val started = store.monotonicTickNow()
        tick = 40L
        store.addPayload(
            TABLE,
            ALL,
            """[{"id":"a","title":"C"},{"id":"srv","title":"keyless"}]""",
            requestStartedAtTick = started,
        )
        store.retireCoveredSyncedMutations(TABLE, started)
        val beforeRestart = store.overlaidPayloadFlow(TABLE, ALL).first()

        // When — the app starts again an hour later
        clock += 60 * 60 * 1000
        val afterRestart =
            newTestOfflineStore(
                    objectDao = objectDao,
                    mutationDao = mutationDao,
                    nowMillis = { clock },
                )
                .overlaidPayloadFlow(TABLE, ALL)
                .first()

        // Then — ALL still shows the server's version: C, and the keyless row once
        assertEquals("""[{"id":"a","title":"C"},{"id":"srv","title":"keyless"}]""", beforeRestart)
        assertEquals(beforeRestart, afterRestart)
    }

    @Test
    fun a_pass_retires_synced_mutations_older_than_a_day() = runTest {
        // Given — a replayed update a never-refreshed query still shows, a day old
        objectDao.seedPayload(TABLE, ORPHAN, """[{"id":"a","title":"orig"}]""")
        store.enqueueUpdate(table = TABLE, body = element("""{"title":"A"}"""), filter = byId("a"))
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)
        clock += ONE_DAY_MS + 1

        // When — a pass runs, with nothing left to send
        MutationQueueReplayer(store = store, supabase = RecordingPostgrestServer().supabaseClient())
            .sync()

        // Then
        assertEquals(0, mutationDao.inserted.size)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private fun byId(id: String): FilterDescriptor =
        FilterDescriptor(
            conditions =
                listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id))
        )

    private companion object {
        const val TABLE = "tasks"
        const val LIST_L = "tasks?list_id=eq.L"
        const val ALL = "tasks"
        const val ORPHAN = "tasks?id=eq.a"
        const val ONE_DAY_MS = 24L * 60 * 60 * 1000
    }
}

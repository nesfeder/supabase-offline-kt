// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

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

/**
 * A replayed mutation stays in the queue, marked synced, for as long as a cached query still needs
 * its overlay: a cached payload fetched before the replay does not contain the write. Retirement is
 * scoped to what a refresh actually covered — one refreshed query retires nothing another cached
 * query still depends on — and a table nothing caches or observes retires its synced writes as soon
 * as they replay, so write-only tables do not grow the queue forever.
 */
class SyncedRetirementScopeTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private var tick = 0L
    private val store =
        newTestOfflineStore(
            objectDao = objectDao,
            mutationDao = mutationDao,
            monotonicTick = { tick },
        )

    @Test
    fun refreshing_one_query_keeps_a_synced_write_another_cached_query_still_needs() = runTest {
        // Given — two cached queries over one table, both fetched before the user's offline insert
        objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L"}]""")
        objectDao.seedPayload(TABLE, ALL, """[{"id":"a","list_id":"L"}]""")
        tick = 10L
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"x","list_id":"L"}]"""))
        tick = 20L
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // When — only the "all tasks" query refreshes, and brings back server state including X
        tick = 30L
        val requestStartedAtTick = store.monotonicTickNow()
        tick = 40L
        store.addPayload(
            table = TABLE,
            signature = ALL,
            payload = """[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""",
            requestStartedAtTick = requestStartedAtTick,
        )
        store.clearSyncedMutationsForTable(TABLE, requestStartedAtTick)

        // Then — list L's cached payload predates X, so its overlay still shows the user's write
        val listL = store.cachePayloadWithOverlayFlow(TABLE, LIST_L).first()
        assertEquals("""[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""", listL)
    }

    @Test
    fun a_synced_write_retires_once_every_cached_query_has_refreshed_past_it() = runTest {
        // Given — the same two cached queries and a synced insert
        objectDao.seedPayload(TABLE, LIST_L, """[{"id":"a","list_id":"L"}]""")
        objectDao.seedPayload(TABLE, ALL, """[{"id":"a","list_id":"L"}]""")
        tick = 10L
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"x","list_id":"L"}]"""))
        tick = 20L
        store.markMutationSynced(id = mutationDao.inserted.single().id, table = TABLE)

        // When — both queries refresh after the replay
        for (signature in listOf(ALL, LIST_L)) {
            tick += 10
            val requestStartedAtTick = store.monotonicTickNow()
            tick += 10
            store.addPayload(
                table = TABLE,
                signature = signature,
                payload = """[{"id":"a","list_id":"L"},{"id":"x","list_id":"L"}]""",
                requestStartedAtTick = requestStartedAtTick,
            )
            store.clearSyncedMutationsForTable(TABLE, requestStartedAtTick)
        }

        // Then — nothing needs the overlay any more
        assertEquals(emptyList(), mutationDao.inserted)
    }

    @Test
    fun a_write_only_table_does_not_keep_its_synced_writes() = runTest {
        // Given — a table nothing reads (feedback, analytics events)
        store.enqueueInsert(table = EVENTS, body = rows("""[{"kind":"opened"}]"""))
        store.enqueueInsert(table = EVENTS, body = rows("""[{"kind":"closed"}]"""))
        val server = RecordingPostgrestServer()

        // When
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — no cached query needs them, so they do not accumulate
        assertEquals(2, synced)
        assertEquals(0, mutationDao.countAll())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "tasks"
        const val EVENTS = "events"
        const val LIST_L = "tasks?list_id=eq.L"
        const val ALL = "tasks"
    }
}

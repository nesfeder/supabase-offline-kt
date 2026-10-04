// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Under `QueueIfOffline`, a write made while online waits in the queue when any earlier write of
 * the same identity is pending, in any table, so it cannot overtake them. Sent straight away, it
 * would land first and the older offline write would replay over it — the user's latest edit lost,
 * and the screen flipped back to the older value the moment the online write returned.
 */
class OnlineWriteJoinsQueueTest {

    private val server = RecordingPostgrestServer()
    private val queryCacheDao = FakeQueryCacheDao()
    private val store = newTestOfflineStore(queryCacheDao = queryCacheDao)
    private val builder =
        newTestOfflineTable(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
        )

    private suspend fun rename(title: String): MutationOutcome =
        builder.update(patch(title), MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

    @Test
    fun an_online_edit_behind_a_pending_offline_edit_reaches_the_server_last() = runTest {
        // Given — offline, the user renamed the task to A
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, """[{"id":1,"title":"original"}]""")
        server.answer = { connectivityFailure() }
        rename("A")

        // When — back online, the user renames it to B, then the queue replays
        server.answer = { okJson("[]") }
        rename("B")
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
            .syncAllTables()

        // Then — the server receives A, then B, and ends on B
        val patches = server.requests.filter { it.method == "PATCH" }.map { it.body }
        assertEquals(listOf("""{"title":"A"}""", """{"title":"B"}"""), patches.takeLast(2))
        assertEquals("""{"title":"B"}""", patches.last())
    }

    @Test
    fun the_screen_shows_the_latest_edit_while_the_older_one_is_still_queued() = runTest {
        // Given — a cached task, renamed to A offline
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, """[{"id":1,"title":"original"}]""")
        server.answer = { connectivityFailure() }
        rename("A")

        // When — online again, the user renames it to B before any sync has run
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size
        val outcome = rename("B")

        // Then — the edit is queued behind A without reaching the server, and the overlay shows B
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(
            requestsBefore,
            server.requests.size,
            "a write that joins the queue is not sent",
        )
        assertEquals(
            """[{"id":1,"title":"B"}]""",
            store.overlaidPayloadFlow(TABLE, CACHE_KEY).first(),
        )
    }

    @Test
    fun an_online_delete_behind_a_pending_insert_of_the_same_row_does_not_resurrect_it() = runTest {
        // Given — offline, the user created row 7
        server.answer = { connectivityFailure() }
        builder.insert(Json.parseToJsonElement("""[{"id":7,"title":"temp"}]""") as JsonArray)

        // When — online, the user deletes it again, then the queue replays
        server.answer = { okJson("[]") }
        builder.delete { filter { eq("id", 7) } }
        newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())
            .syncAllTables()

        // Then — the insert reaches the server before the delete
        val writes = server.requests.filter { it.method != "GET" }.drop(1).map { it.method }
        assertEquals(listOf("POST", "DELETE"), writes)
    }

    @Test
    fun an_online_write_waits_behind_a_pending_write_in_another_table() = runTest {
        // Given — another table has a pending write, this one has none
        server.answer = { connectivityFailure() }
        newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from("other"),
                store = store,
            )
            .update(patch("x"), MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

        // When
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size
        val outcome = rename("B")

        // Then — it waits its turn: the other table's write may be the row this one depends on
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(requestsBefore, server.requests.size)
    }

    @Test
    fun an_online_write_with_nothing_pending_is_applied_at_once_and_leaves_no_queue_row() =
        runTest {
            // Given — nothing pending
            queryCacheDao.seedPayload(TABLE, CACHE_KEY, """[{"id":1,"title":"original"}]""")

            // When
            val outcome = rename("B")

            // Then — applied, overlaid, and gone from the queue
            assertEquals(MutationOutcome.Applied::class, outcome::class)
            assertEquals(0, store.allPendingMutations().size)
            assertEquals(
                """[{"id":1,"title":"B"}]""",
                queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload,
            )
        }

    @Test
    fun a_write_that_joins_the_queue_is_sent_by_a_pass_the_engine_starts_itself() = runTest {
        // Given — a client whose background work runs on the test scheduler, with a pending edit
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                store = store,
                coordinatorScope = backgroundScope,
            )
        server.answer = { connectivityFailure() }
        client.from(TABLE).update(patch("A")) { filter { eq("id", 1) } }

        // When — online again, a new edit joins the queue; nobody calls sync
        server.answer = { okJson("[]") }
        val outcome = client.from(TABLE).update(patch("B")) { filter { eq("id", 1) } }
        store.pendingMutationCountFlow().first { pending -> pending == 0 }

        // Then — the engine replayed both, in order
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(
            listOf("""{"title":"A"}""", """{"title":"B"}"""),
            server.requests.filter { it.method == "PATCH" }.map { it.body }.takeLast(2),
        )
    }

    private fun patch(title: String): JsonElement =
        Json.parseToJsonElement("""{"title":"$title"}""")

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?id=eq.1"
    }
}

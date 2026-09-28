package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Under `QueueIfOffline`, a write made while online joins the queue when its table already has
 * pending writes, so it cannot overtake them. Sent straight away, it would land first and the older
 * offline write would replay over it — the user's latest edit lost, and the screen flipped back to
 * the older value the moment the online write returned.
 */
class OnlineWriteJoinsQueueTest {

    private val server = RecordingPostgrestServer()
    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val builder = OfflinePostgrestQueryBuilderImpl(
        delegate = server.supabaseClient().postgrest.from(TABLE),
        store = store,
    )

    private suspend fun rename(title: String): MutationOutcome =
        builder.updateJson(patch(title), MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

    @Test
    fun an_online_edit_behind_a_pending_offline_edit_reaches_the_server_last() = runTest {
        // Given — offline, the user renamed the task to A
        objectDao.seedPayload(TABLE, SIGNATURE, """[{"id":1,"title":"original"}]""")
        server.answer = { connectivityFailure() }
        rename("A")

        // When — back online, the user renames it to B, then the queue replays
        server.answer = { okJson("[]") }
        rename("B")
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — the server receives A, then B, and ends on B
        val patches = server.requests.filter { it.method == "PATCH" }.map { it.body }
        assertEquals(listOf("""{"title":"A"}""", """{"title":"B"}"""), patches.takeLast(2))
        assertEquals("""{"title":"B"}""", patches.last())
    }

    @Test
    fun the_screen_shows_the_latest_edit_while_the_older_one_is_still_queued() = runTest {
        // Given — a cached task, renamed to A offline
        objectDao.seedPayload(TABLE, SIGNATURE, """[{"id":1,"title":"original"}]""")
        server.answer = { connectivityFailure() }
        rename("A")

        // When — online again, the user renames it to B before any sync has run
        server.answer = { okJson("[]") }
        val outcome = rename("B")

        // Then — the edit is queued behind A, and the overlay shows B
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals("""[{"id":1,"title":"B"}]""", store.cachePayloadWithOverlayFlow(TABLE, SIGNATURE).first())
    }

    @Test
    fun an_online_delete_behind_a_pending_insert_of_the_same_row_does_not_resurrect_it() = runTest {
        // Given — offline, the user created row 7
        server.answer = { connectivityFailure() }
        builder.insertJson(Json.parseToJsonElement("""[{"id":7,"title":"temp"}]""") as JsonArray)

        // When — online, the user deletes it again, then the queue replays
        server.answer = { okJson("[]") }
        builder.delete { filter { eq("id", 7) } }
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — the insert reaches the server before the delete
        val writes = server.requests.filter { it.method != "GET" }.drop(1).map { it.method }
        assertEquals(listOf("POST", "DELETE"), writes)
    }

    @Test
    fun an_online_write_to_a_table_without_pending_writes_is_applied_at_once() = runTest {
        // Given — another table has a pending write, this one has none
        server.answer = { connectivityFailure() }
        OfflinePostgrestQueryBuilderImpl(delegate = server.supabaseClient().postgrest.from("other"), store = store)
            .updateJson(patch("x"), MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

        // When
        server.answer = { okJson("[]") }
        val outcome = rename("B")

        // Then
        assertEquals(MutationOutcome.Applied::class, outcome::class)
    }

    private fun patch(title: String): JsonElement = Json.parseToJsonElement("""{"title":"$title"}""")

    private companion object {
        const val TABLE = "todos"
        const val SIGNATURE = "todos?id=eq.1"
    }
}

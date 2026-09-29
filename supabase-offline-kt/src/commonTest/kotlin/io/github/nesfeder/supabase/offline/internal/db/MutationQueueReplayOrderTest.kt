package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer.ForeignKey
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Replay order across tables.
 *
 * The queue is one global list in insertion order. A sweep replays it front to back and stops at
 * the first write that cannot go through for a reason that may pass (offline, a server error), so
 * a later write can never overtake an earlier one — not in its own table, and not in a table it
 * depends on through a foreign key or a row-level-security policy.
 */
class MutationQueueReplayOrderTest {

    private val mutationDao = FakeMutationDao()
    private var clock = 1_000L
    private val store = newTestOfflineStore(mutationDao = mutationDao, nowMillis = { clock++ })

    @Test
    fun a_child_queued_after_its_parent_in_another_table_reaches_the_server() = runTest {
        // Given — photos reference tasks; offline the user edits an existing photo, creates a
        // task, then attaches a photo to the new task
        val server = FakeRelationalPostgrestServer(foreignKeys = mapOf(PHOTOS to ForeignKey("task_id", TASKS)))
        server.seed(TASKS, row("id" to "t0"))
        server.seed(PHOTOS, row("id" to "p0", "task_id" to "t0", "caption" to "old"))
        store.enqueueUpdate(table = PHOTOS, body = row("caption" to "new"), filter = idFilter("p0"))
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1")))
        store.enqueueInsert(table = PHOTOS, body = rows(row("id" to "p1", "task_id" to "t1")))
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When
        val synced = syncer.syncAllTables()

        // Then — all three arrive; the child was not replayed ahead of its parent and dropped
        assertEquals(3, synced)
        assertEquals(setOf("t0", "t1"), server.rows(TASKS).map { it.string("id") }.toSet())
        assertEquals(setOf("p0", "p1"), server.rows(PHOTOS).map { it.string("id") }.toSet())
        assertEquals("new", server.rows(PHOTOS).single { it.string("id") == "p0" }.string("caption"))
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun replay_follows_insertion_order_even_when_the_clock_stepped_backwards() = runTest {
        // Given — an insert, then the device clock is set back, then an edit of the same row
        clock = 5_000L
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1", "title" to "draft")))
        clock = 1_000L
        store.enqueueUpdate(table = TASKS, body = row("title" to "final"), filter = idFilter("t1"))
        val server = RecordingPostgrestServer()
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When
        syncer.syncAllTables()

        // Then — the insert goes first, so the edit lands on the row instead of on nothing
        assertEquals(listOf("POST", "PATCH"), server.requests.map { it.method })
    }

    @Test
    fun a_connectivity_failure_stops_the_whole_sweep() = runTest {
        // Given — a write to one table, then a write to another; the first cannot reach the server
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1")))
        store.enqueueInsert(table = PHOTOS, body = rows(row("id" to "p1")))
        val server = RecordingPostgrestServer(
            answer = { request -> if (request.table == TASKS) connectivityFailure() else okJson("[]") },
        )
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When
        val synced = syncer.syncAllTables()

        // Then — nothing after the failure is sent, and both writes stay queued
        assertEquals(0, synced)
        assertEquals(listOf(TASKS), server.requests.map { it.table })
        assertEquals(listOf(TASKS, PHOTOS), store.allPendingMutations().map { it.table })
    }

    @Test
    fun a_server_error_stops_the_whole_sweep_instead_of_skipping_ahead() = runTest {
        // Given — the server fails the first table's write with a 500; a later write waits behind it
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1")))
        store.enqueueInsert(table = PHOTOS, body = rows(row("id" to "p1")))
        val server = RecordingPostgrestServer(
            answer = { request ->
                if (request.table == TASKS) postgrestError(HttpStatusCode.InternalServerError) else okJson("[]")
            },
        )
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When
        val synced = syncer.syncAllTables()

        // Then — the later write does not overtake the one that failed
        assertEquals(0, synced)
        assertEquals(listOf(TASKS), server.requests.map { it.table })
        assertEquals(2, store.pendingMutationCount())
    }

    @Test
    fun a_conflict_behind_an_earlier_pending_write_in_another_table_is_held() = runTest {
        // Given — the parent task is still queued; only the photo table is synced
        val server = FakeRelationalPostgrestServer(foreignKeys = mapOf(PHOTOS to ForeignKey("task_id", TASKS)))
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1")))
        store.enqueueInsert(table = PHOTOS, body = rows(row("id" to "p1", "task_id" to "t1")))
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — the photo replays first and its foreign key fails with 409
        syncer.syncTable(table = PHOTOS)

        // Then — the photo is held for a later sweep, not dropped
        assertEquals(listOf(TASKS, PHOTOS), store.allPendingMutations().map { it.table })

        // And — the next full sweep delivers both, parent first
        val synced = syncer.syncAllTables()
        assertEquals(2, synced)
        assertEquals(listOf("p1"), server.rows(PHOTOS).map { it.string("id") })
    }

    @Test
    fun a_forbidden_write_behind_an_earlier_pending_write_in_another_table_is_held() = runTest {
        // Given — a row-level-security policy that admits a child only once its parent exists
        store.enqueueInsert(table = TASKS, body = rows(row("id" to "t1")))
        store.enqueueInsert(table = PHOTOS, body = rows(row("id" to "p1", "task_id" to "t1")))
        val server = RecordingPostgrestServer(answer = { postgrestError(HttpStatusCode.Forbidden) })
        val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When
        syncer.syncTable(table = PHOTOS)

        // Then
        assertEquals(listOf(TASKS, PHOTOS), store.allPendingMutations().map { it.table })
    }

    private fun row(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        pairs.forEach { (key, value) -> put(key, value) }
    }

    private fun rows(vararg objects: JsonObject): JsonArray = JsonArray(objects.toList())

    private fun idFilter(id: String): FilterDescriptor = FilterDescriptor(
        conditions = listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id)),
    )

    private fun JsonObject.string(key: String): String = (getValue(key) as JsonPrimitive).content

    private companion object {
        const val TASKS = "tasks"
        const val PHOTOS = "task_photos"
    }
}

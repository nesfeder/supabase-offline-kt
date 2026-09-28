package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.NumericDeltaColumn
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A write whose response was lost stays queued and replays, so replaying it must not apply it
 * twice. Which replays are idempotent:
 *
 * - an INSERT whose rows carry the table's primary key — it replays as an upsert that ignores
 *   duplicates, so it is not inserted twice (or refused as a duplicate of itself);
 * - an UPSERT, an UPDATE of absolute values and a DELETE — as sent.
 *
 * Two are not, and are pinned here as such: an INSERT whose key the server generates is inserted
 * twice, and an UPDATE with numeric delta columns applies its delta twice.
 */
class MutationQueueIdempotentReplayTest {

    private val store = newTestOfflineStore()

    @Test
    fun an_insert_whose_response_was_lost_is_not_duplicated_by_its_replay() = runTest {
        // Given — the server commits the insert, then the connection drops before the response
        val server = FakeRelationalPostgrestServer()
        server.loseResponseAfterCommit = { request -> request.method == "POST" }
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
        )
        val outcome = builder.insertJson(rows("""[{"id":"t1","title":"Buy milk"}]"""), MutationStrategy.QueueIfOffline)
        assertIs<MutationOutcome.Queued>(outcome)

        // When — the queue replays
        val synced = OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — the replay lands as a no-op on the row the lost request already wrote
        assertEquals(1, synced)
        assertEquals(1, server.rows(TABLE).size)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun an_insert_carrying_its_primary_key_replays_as_an_upsert_that_ignores_duplicates() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"t1","title":"Buy milk"}]"""))
        val server = RecordingPostgrestServer()

        // When
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals(mapOf("on_conflict" to "id", "columns" to "id,title"), request.query)
        assertEquals("resolution=ignore-duplicates,return=minimal", request.prefer)
        assertEquals("""[{"id":"t1","title":"Buy milk"}]""", request.body)
    }

    @Test
    fun an_insert_without_its_primary_key_replays_as_a_plain_insert() = runTest {
        // Given — the server generates the key, so nothing can make a replay idempotent
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"Buy milk"}]"""))
        val server = RecordingPostgrestServer()

        // When
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        val request = server.requests.single()
        assertEquals(mapOf("columns" to "title"), request.query)
        assertEquals("return=minimal", request.prefer)
    }

    @Test
    fun a_multi_row_insert_where_one_row_lacks_the_key_replays_as_a_plain_insert() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"t1"},{"title":"no key"}]"""))
        val server = RecordingPostgrestServer()

        // When
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertEquals("return=minimal", server.requests.single().prefer)
    }

    @Test
    fun an_idempotent_replay_keeps_default_to_null_false() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"t1"}]"""), defaultToNull = false)
        val server = RecordingPostgrestServer()

        // When
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — missing columns still take their database default
        assertEquals("resolution=ignore-duplicates,missing=default,return=minimal", server.requests.single().prefer)
    }

    @Test
    fun an_upsert_whose_response_was_lost_replays_to_the_same_row() = runTest {
        // Given
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","title":"old"}"""))
        server.loseResponseAfterCommit = { request -> request.method == "POST" }

        // When — the upsert commits, its response is lost, and the queue replays it
        val outcome = builderOn(server).upsertJson(rows("""[{"id":"t1","title":"new"}]"""))
        val synced = OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, synced)
        assertEquals(listOf(row("""{"id":"t1","title":"new"}""")), server.rows(TABLE))
    }

    @Test
    fun an_update_whose_response_was_lost_replays_to_the_same_value() = runTest {
        // Given
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","title":"old"}"""))
        server.loseResponseAfterCommit = { request -> request.method == "PATCH" }

        // When
        val outcome = builderOn(server).updateJson(Json.parseToJsonElement("""{"title":"new"}""")) { filter { eq("id", "t1") } }
        val synced = OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, synced)
        assertEquals(listOf(row("""{"id":"t1","title":"new"}""")), server.rows(TABLE))
    }

    @Test
    fun a_delete_whose_response_was_lost_replays_as_a_no_op() = runTest {
        // Given
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","title":"gone"}"""), row("""{"id":"t2","title":"kept"}"""))
        server.loseResponseAfterCommit = { request -> request.method == "DELETE" }

        // When
        val outcome = builderOn(server).delete { filter { eq("id", "t1") } }
        val synced = OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, synced)
        assertEquals(listOf(row("""{"id":"t2","title":"kept"}""")), server.rows(TABLE))
    }

    @Test
    fun an_insert_whose_key_the_server_generates_is_inserted_twice_when_its_response_was_lost() = runTest {
        // Given — not idempotent: nothing in the write identifies the row it created
        val server = FakeRelationalPostgrestServer()
        server.loseResponseAfterCommit = { request -> request.method == "POST" }

        // When
        builderOn(server).insertJson(rows("""[{"title":"Buy milk"}]"""))
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — pinned as it is: two rows
        assertEquals(2, server.rows(TABLE).size)
    }

    @Test
    fun a_delta_update_whose_response_was_lost_applies_its_delta_twice() = runTest {
        // Given — not idempotent: the replay rebases the delta onto a value that already carries it
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","done_count":10}"""))
        server.loseResponseAfterCommit = { request -> request.method == "PATCH" }

        // When — 10 becomes 15 on the server, the response is lost, and the queue replays +5
        builderOn(server).updateJson(
            body = Json.parseToJsonElement("""{"done_count":15}"""),
            deltaColumns = listOf(NumericDeltaColumn(column = "done_count", snapshotValue = 10.0)),
        ) { filter { eq("id", "t1") } }
        OfflineMutationSyncer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — pinned as it is: 20, not 15
        val doneCount = (server.rows(TABLE).single()["done_count"] as JsonPrimitive).content.toDouble()
        assertEquals(20.0, doneCount)
    }

    private fun builderOn(server: FakeRelationalPostgrestServer): OfflinePostgrestQueryBuilderImpl =
        OfflinePostgrestQueryBuilderImpl(delegate = server.supabaseClient().postgrest.from(TABLE), store = store)

    private fun row(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

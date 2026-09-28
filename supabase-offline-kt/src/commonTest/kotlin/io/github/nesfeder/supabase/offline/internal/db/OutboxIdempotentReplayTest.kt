package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A queued INSERT whose rows carry the table's primary key replays as an upsert that ignores
 * duplicates, so a write that reached the server but whose response was lost is not inserted twice
 * (or, on a table with a unique key, refused as a duplicate of itself).
 */
class OutboxIdempotentReplayTest {

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

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A write whose response was lost stays queued and replays, so replaying it must not apply it twice
 * — while a genuine duplicate must still be refused. Which replays are idempotent:
 *
 * - an INSERT whose rows carry the table's primary key and whose send may have reached the server
 *   replays as an upsert that ignores duplicates and counts the rows it wrote; one that was never
 *   sent, or never connected, replays as a plain INSERT, so a duplicate id is refused;
 * - an UPSERT, an UPDATE of absolute values and a DELETE — as sent.
 *
 * One is not, and is pinned here as such: an INSERT whose key the server generates is inserted
 * twice.
 */
class MutationQueueIdempotentReplayTest {

    private val store = newTestOfflineStore()

    @Test
    fun an_insert_whose_response_was_lost_is_not_duplicated_by_its_replay() = runTest {
        // Given — the server commits the insert, then the connection drops before the response
        val server = FakeRelationalPostgrestServer()
        server.loseResponseAfterCommit = { request -> request.method == "POST" }
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )
        val outcome =
            builder.insertJson(
                rows("""[{"id":"t1","title":"Buy milk"}]"""),
                MutationStrategy.QueueIfOffline,
            )
        assertIs<MutationOutcome.Queued>(outcome)

        // When — the queue replays
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — the replay lands as a no-op on the row the lost request already wrote
        assertEquals(1, synced)
        assertEquals(1, server.rows(TABLE).size)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun an_insert_that_was_never_sent_replays_as_a_plain_insert() = runTest {
        // Given — queued without ever reaching the server
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"t1","title":"Buy milk"}]"""))
        val server = RecordingPostgrestServer()

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — nothing on the server can be its own, so a duplicate must be refused
        val request = server.requests.single()
        assertEquals("POST", request.method)
        assertEquals(mapOf("columns" to "id,title"), request.query)
        assertEquals("return=minimal", request.prefer)
    }

    @Test
    fun a_genuine_duplicate_id_is_refused_and_lands_on_the_rejected_list() = runTest {
        // Given — another row already has the id this never-sent insert uses
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","title":"someone else's"}"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":"t1","title":"Buy milk"}]"""))

        // When
        val report = MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — the server's own refusal reaches the rejected list; the other row is untouched
        assertEquals("23505", report.rejected.single().serverError?.code)
        assertEquals(listOf(row("""{"id":"t1","title":"someone else's"}""")), server.rows(TABLE))
    }

    @Test
    fun an_insert_whose_send_may_have_reached_the_server_replays_as_an_upsert_that_counts_its_rows() =
        runTest {
            // Given — the send failed without saying whether the server got it
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            builderOn(server).insertJson(rows("""[{"id":"t1","title":"Buy milk"}]"""))
            server.answer = { okJson("[]") }

            // When
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

            // Then
            val replay = server.requests.last()
            assertEquals("id", replay.query["on_conflict"])
            val preferences = replay.prefer.orEmpty().split(',')
            assertTrue("resolution=ignore-duplicates" in preferences, "got $preferences")
            assertTrue("count=exact" in preferences, "got $preferences")
        }

    @Test
    fun an_insert_whose_send_never_connected_replays_as_a_plain_insert() = runTest {
        // Given — the connection was never made, so the server cannot have it
        val server =
            RecordingPostgrestServer(
                answer = { throw ConnectTimeoutException("connect timed out", null) }
            )
        builderOn(server).insertJson(rows("""[{"id":"t1","title":"Buy milk"}]"""))
        server.answer = { okJson("[]") }

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertEquals("return=minimal", server.requests.last().prefer)
    }

    @Test
    fun an_upsert_replay_with_no_matching_unique_key_is_sent_again_as_a_plain_insert() = runTest {
        // Given — a table whose key is not the id alone, and an insert whose response was lost
        val server = FakeRelationalPostgrestServer()
        server.tablesWithoutUniqueKey = setOf(TABLE)
        server.loseResponseAfterCommit = { request -> request.method == "POST" }
        builderOn(server).insertJson(rows("""[{"id":"t1","title":"Buy milk"}]"""))

        // When — the replay's upsert has no constraint to match (42P10)
        val report = MutationQueueReplayer(store = store, supabase = server.supabaseClient()).sync()

        // Then — sent again as the plain INSERT it is, rather than rejected
        assertEquals(1, report.syncedCount)
        assertEquals(emptyList(), report.rejected)
        assertEquals(listOf(null, "id", null), server.requests.map { it.query["on_conflict"] })
    }

    @Test
    fun an_insert_without_its_primary_key_replays_as_a_plain_insert() = runTest {
        // Given — the server generates the key, so nothing can make a replay idempotent
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"Buy milk"}]"""))
        val server = RecordingPostgrestServer()

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

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
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertEquals("return=minimal", server.requests.single().prefer)
    }

    @Test
    fun an_insert_whose_send_was_under_way_when_the_app_died_replays_as_an_upsert() = runTest {
        // Given — the send started and the process died before any answer
        val queryCacheDao = FakeQueryCacheDao()
        val mutationDao = FakeMutationDao()
        val beforeTheCrash =
            newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
        val id =
            beforeTheCrash.enqueueInsert(
                table = TABLE,
                body = rows("""[{"id":"t1","title":"Buy milk"}]"""),
            )
        beforeTheCrash.markSendUnconfirmed(id)

        // When — the next launch replays it
        val server = RecordingPostgrestServer()
        val afterTheCrash =
            newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
        MutationQueueReplayer(store = afterTheCrash, supabase = server.supabaseClient())
            .syncAllTables()

        // Then — the server may already hold it, so the replay tolerates that
        assertTrue(
            "resolution=ignore-duplicates" in server.requests.single().prefer.orEmpty().split(',')
        )
    }

    @Test
    fun a_replay_whose_rows_were_already_on_the_server_is_synced_and_says_so() = runTest {
        // Given — the insert committed and its response was lost
        val logger = RecordingOfflineLogger()
        val server = FakeRelationalPostgrestServer()
        server.loseResponseAfterCommit = { request -> request.method == "POST" }
        builderOn(server).insertJson(rows("""[{"id":"t1","title":"Buy milk"}]"""))
        val replayer =
            MutationQueueReplayer(
                store = store,
                supabase = server.supabaseClient(),
                engineLogger = logger.asEngineLogger(),
            )

        // When
        val report = replayer.sync()

        // Then — synced once, and the count the server returned is logged without the row's values
        assertEquals(1, report.syncedCount)
        assertEquals(1, server.rows(TABLE).size)
        val info = logger.entriesAt(OfflineLogLevel.INFO).map { it.message }
        assertTrue(info.any { "1 of 1 rows were already on the server" in it }, "got $info")
        assertTrue(info.none { "Buy milk" in it })
    }

    @Test
    fun an_idempotent_replay_keeps_default_to_null_false() = runTest {
        // Given — an insert that may have reached the server, made with defaultToNull = false
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        builderOn(server).insertJson(rows("""[{"id":"t1"}]""")) { defaultToNull = false }
        server.answer = { okJson("[]") }

        // When
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — missing columns still take their database default
        assertTrue("missing=default" in server.requests.last().prefer.orEmpty().split(','))
    }

    @Test
    fun an_upsert_whose_response_was_lost_replays_to_the_same_row() = runTest {
        // Given
        val server = FakeRelationalPostgrestServer()
        server.seed(TABLE, row("""{"id":"t1","title":"old"}"""))
        server.loseResponseAfterCommit = { request -> request.method == "POST" }

        // When — the upsert commits, its response is lost, and the queue replays it
        val outcome = builderOn(server).upsertJson(rows("""[{"id":"t1","title":"new"}]"""))
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

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
        val outcome =
            builderOn(server).updateJson(Json.parseToJsonElement("""{"title":"new"}""")) {
                filter { eq("id", "t1") }
            }
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, synced)
        assertEquals(listOf(row("""{"id":"t1","title":"new"}""")), server.rows(TABLE))
    }

    @Test
    fun a_delete_whose_response_was_lost_replays_as_a_no_op() = runTest {
        // Given
        val server = FakeRelationalPostgrestServer()
        server.seed(
            TABLE,
            row("""{"id":"t1","title":"gone"}"""),
            row("""{"id":"t2","title":"kept"}"""),
        )
        server.loseResponseAfterCommit = { request -> request.method == "DELETE" }

        // When
        val outcome = builderOn(server).delete { filter { eq("id", "t1") } }
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, synced)
        assertEquals(listOf(row("""{"id":"t2","title":"kept"}""")), server.rows(TABLE))
    }

    @Test
    fun an_insert_whose_key_the_server_generates_is_inserted_twice_when_its_response_was_lost() =
        runTest {
            // Given — not idempotent: nothing in the write identifies the row it created
            val server = FakeRelationalPostgrestServer()
            server.loseResponseAfterCommit = { request -> request.method == "POST" }

            // When
            builderOn(server).insertJson(rows("""[{"title":"Buy milk"}]"""))
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

            // Then — pinned as it is: two rows
            assertEquals(2, server.rows(TABLE).size)
        }

    private fun builderOn(server: FakeRelationalPostgrestServer): OfflinePostgrestQueryBuilderImpl =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
        )

    private fun builderOn(server: RecordingPostgrestServer): OfflinePostgrestQueryBuilderImpl =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
        )

    private fun row(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

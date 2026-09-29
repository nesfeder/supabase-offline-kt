package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.mutationqueue.DefaultPrimaryKeys
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationOperation as MutationQueueOperation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PendingMutation
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.mutationqueue.toPendingMutation
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every queued mutation has a stable id — the mutation row's autoincrement id — and a replay pass
 * reports what it did.
 *
 * The id serves because SQLite's `AUTOINCREMENT` never reuses a value, even after rows are deleted
 * or the table is emptied, it survives process death with the row, it already orders the queue,
 * and it costs no schema change.
 */
class MutationIdAndSyncReportTest {

    private val mutationDao = FakeMutationDao()
    private var clock = 7_000L
    private val store = newTestOfflineStore(mutationDao = mutationDao, nowMillis = { clock })
    private val server = RecordingPostgrestServer()
    private val syncer = MutationQueueReplayer(store = store, supabase = server.supabaseClient())

    @Test
    fun ids_are_assigned_in_order_and_survive_a_restart() = runTest {
        // Given
        val first = store.enqueueInsert(table = TABLE, body = rows("""[{"id":"a"}]"""))
        val second = store.enqueueDelete(table = TABLE, filter = idFilter("a"))

        // When — a new process opens the same queue
        val restarted = newTestOfflineStore(mutationDao = mutationDao)

        // Then
        assertTrue(second > first)
        assertEquals(listOf(first, second), restarted.allPendingMutations().map { it.id })
    }

    @Test
    fun an_id_is_never_reused_after_the_queue_empties() = runTest {
        // Given — a queue that was emptied
        val first = store.enqueueInsert(table = TABLE, body = rows("""[{"id":"a"}]"""))
        store.clearAll()

        // When
        val next = store.enqueueInsert(table = TABLE, body = rows("""[{"id":"b"}]"""))

        // Then — a rejection or a handle held for the old write can never name the new one
        assertTrue(next > first)
    }

    @Test
    fun a_pending_mutation_carries_its_id_operation_body_and_row_keys() = runTest {
        // Given
        val insertId = store.enqueueInsert(table = TABLE, body = rows("""[{"id":"a","title":"x"},{"title":"no key"}]"""))
        val updateId = store.enqueueUpdate(table = TABLE, body = Json.parseToJsonElement("""{"done":true}"""), filter = idFilter("b"))

        // When
        val pending = store.pendingMutationsFlow(null).first().mapNotNull { row ->
            row.toPendingMutation(store.json, DefaultPrimaryKeys)
        }

        // Then
        assertEquals(
            listOf(
                PendingMutation(
                    id = insertId,
                    table = TABLE,
                    operation = MutationQueueOperation.Insert,
                    createdAt = Instant.fromEpochMilliseconds(7_000L),
                    rowKeys = listOf(key("a")),
                    body = rows("""[{"id":"a","title":"x"},{"title":"no key"}]"""),
                ),
                PendingMutation(
                    id = updateId,
                    table = TABLE,
                    operation = MutationQueueOperation.Update,
                    createdAt = Instant.fromEpochMilliseconds(7_000L),
                    rowKeys = listOf(key("b")),
                    body = Json.parseToJsonElement("""{"done":true}"""),
                ),
            ),
            pending,
        )
    }

    @Test
    fun a_report_counts_what_synced_what_was_rejected_and_what_remains() = runTest {
        // Given — one write goes through, one is refused, one meets a server outage
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"ok"}]"""))
        val refusedId = store.enqueueInsert(table = TABLE, body = rows("""[{"title":"refused"}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"outage"}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"waiting"}]"""))
        server.answer = { request ->
            when {
                "refused" in request.body -> postgrestError(HttpStatusCode.BadRequest)
                "outage" in request.body -> postgrestError(HttpStatusCode.ServiceUnavailable)
                else -> okJson("[]")
            }
        }

        // When
        val report = syncer.sync()

        // Then
        assertEquals(1, report.syncedCount)
        assertEquals(listOf(refusedId), report.rejected.map { it.id })
        assertEquals(2, report.remainingCount)
        assertIs<SyncStop.TransientFailure>(report.stop)
    }

    @Test
    fun an_offline_pass_returns_after_one_attempt() = runTest {
        // Given — the device is offline with three writes queued
        repeat(3) { index -> store.enqueueInsert(table = TABLE, body = rows("""[{"n":$index}]""")) }
        server.answer = { connectivityFailure() }

        // When
        val report = syncer.sync()

        // Then — one request, then back, with everything still pending
        assertEquals(1, server.requests.size)
        assertEquals(0, report.syncedCount)
        assertEquals(3, report.remainingCount)
        assertIs<SyncStop.TransientFailure>(report.stop)
    }

    @Test
    fun a_pass_over_an_empty_queue_reports_nothing_left() = runTest {
        // Given / When
        val report = syncer.sync()

        // Then
        assertEquals(0, report.syncedCount)
        assertEquals(0, report.remainingCount)
        assertEquals(null, report.stop)
    }

    private fun key(id: String): JsonObject = JsonObject(mapOf("id" to JsonPrimitive(id)))

    private fun idFilter(id: String): FilterDescriptor =
        FilterDescriptor(conditions = listOf(FilterDescriptor.Condition(column = "id", operatorId = "eq", value = id)))

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

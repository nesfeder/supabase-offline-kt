// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.assertNotQueueable
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The queue-or-rethrow decision each mutation verb makes when its request fails.
 *
 * - Under `QueueIfOffline`, a **connectivity** failure queues the write and reports it queued.
 * - Under `QueueIfOffline`, a **server** error (the request reached PostgREST and was rejected) is
 *   rethrown and nothing is queued — replaying a rejected write would only be rejected again.
 * - Under `OnlineOnly`, **every** failure is rethrown and nothing is queued.
 * - A success is applied and queues nothing.
 *
 * The verbs run over the real PostgREST handle against a [RecordingPostgrestServer], so the failure
 * classes are the exceptions supabase-kt really raises: `HttpRequestException` for a transport
 * failure, `PostgrestRestException` for a rejection.
 */
class OfflineMutationQueueDecisionTest {

    private val server = RecordingPostgrestServer()
    private val mutationDao = FakeMutationDao()
    private val logger = RecordingLogSink()
    private val builder =
        newTestOfflineTable(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = newTestStore(mutationDao = mutationDao),
            engineLogger = logger.asEngineLogger(),
        )

    private suspend fun insert(strategy: MutationStrategy): MutationOutcome =
        builder.insert(ROWS, strategy)

    private suspend fun upsert(strategy: MutationStrategy): MutationOutcome =
        builder.upsert(ROWS, strategy) { onConflict = "id" }

    private suspend fun update(strategy: MutationStrategy): MutationOutcome =
        builder.update(PATCH, strategy) { filter { eq("id", 1) } }

    private suspend fun delete(strategy: MutationStrategy): MutationOutcome =
        builder.delete(strategy) { filter { eq("id", 1) } }

    private fun failWithConnectivity() {
        server.answer = { connectivityFailure() }
    }

    private fun failWithServerError() {
        server.answer = { postgrestError(HttpStatusCode.Forbidden) }
    }

    // ─────────────────────────── INSERT ───────────────────────────

    @Test
    fun insert_success_is_applied_and_queues_nothing() = runTest {
        // Given — the default server accepts every write

        // When
        val outcome = insert(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun insert_connectivity_failure_under_QueueIfOffline_queues() = runTest {
        // Given
        failWithConnectivity()

        // When
        val outcome = insert(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(MutationOperation.INSERT.storedName, mutationDao.inserted.single().operation)
    }

    @Test
    fun insert_server_error_under_QueueIfOffline_rethrows_and_queues_nothing() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { insert(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun insert_connectivity_failure_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> { insert(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun insert_server_error_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { insert(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    // ─────────────────────────── UPSERT ───────────────────────────

    @Test
    fun upsert_success_is_applied_and_queues_nothing() = runTest {
        // Given — the default server accepts every write

        // When
        val outcome = upsert(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun upsert_connectivity_failure_under_QueueIfOffline_queues() = runTest {
        // Given
        failWithConnectivity()

        // When
        val outcome = upsert(MutationStrategy.QueueIfOffline)

        // Then — queued with its onConflict
        assertIs<MutationOutcome.Queued>(outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.UPSERT.storedName, queued.operation)
        assertEquals("id", queued.onConflict)
    }

    @Test
    fun upsert_server_error_under_QueueIfOffline_rethrows_and_queues_nothing() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { upsert(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun upsert_connectivity_failure_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> { upsert(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun upsert_server_error_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { upsert(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    // ─────────────────────────── UPDATE ───────────────────────────

    @Test
    fun update_success_is_applied_and_queues_nothing() = runTest {
        // Given — the default server accepts every write

        // When
        val outcome = update(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun update_connectivity_failure_under_QueueIfOffline_queues_with_its_filter() = runTest {
        // Given
        failWithConnectivity()

        // When
        val outcome = update(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.UPDATE.storedName, queued.operation)
        assertEquals(ID_FILTER_JSON, queued.filterJson)
    }

    @Test
    fun update_server_error_under_QueueIfOffline_rethrows_and_queues_nothing() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { update(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun update_connectivity_failure_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> { update(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun update_server_error_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { update(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    // ─────────────────────────── DELETE ───────────────────────────

    @Test
    fun delete_success_is_applied_and_queues_nothing() = runTest {
        // Given — the default server accepts every write

        // When
        val outcome = delete(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun delete_connectivity_failure_under_QueueIfOffline_queues_with_its_filter() = runTest {
        // Given
        failWithConnectivity()

        // When
        val outcome = delete(MutationStrategy.QueueIfOffline)

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.DELETE.storedName, queued.operation)
        assertEquals(ID_FILTER_JSON, queued.filterJson)
    }

    @Test
    fun delete_server_error_under_QueueIfOffline_rethrows_and_queues_nothing() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { delete(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun delete_connectivity_failure_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> { delete(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun delete_server_error_under_OnlineOnly_rethrows() = runTest {
        // Given
        failWithServerError()

        // When / Then
        assertFailsWith<PostgrestRestException> { delete(MutationStrategy.OnlineOnly) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    // ─────────────────────────── Edges ───────────────────────────

    /**
     * Inverted: an UPDATE with no filter used to be queued and then refused and dropped at replay,
     * so the caller was told "queued" for a write that would never be sent. It is now refused at
     * enqueue, like any other write the queue cannot replay faithfully: sent at once, and a
     * connectivity failure throws `MutationNotQueueableException` with that failure as its cause.
     */
    @Test
    fun an_unfiltered_update_is_not_queued_and_reports_why() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertNotQueueable<HttpRequestException> {
            builder.update(PATCH, MutationStrategy.QueueIfOffline)
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    /**
     * Inverted for the same reason as the UPDATE case above: an unfiltered DELETE is refused at
     * enqueue instead of being queued and dropped at replay.
     */
    @Test
    fun an_unfiltered_delete_is_not_queued_and_reports_why() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertNotQueueable<HttpRequestException> { builder.delete(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_filter_the_queue_cannot_capture_is_not_queued_and_reports_why() = runTest {
        // Given — a connectivity failure, and a logical group the stored filter cannot represent
        failWithConnectivity()

        // When / Then
        assertNotQueueable<HttpRequestException> {
            builder.update(PATCH, MutationStrategy.QueueIfOffline) {
                filter {
                    or {
                        eq("id", 1)
                        eq("id", 2)
                    }
                }
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_queued_write_never_logs_its_body_above_debug() = runTest {
        // Given
        failWithConnectivity()

        // When
        insert(MutationStrategy.QueueIfOffline)

        // Then — the queue is reported at INFO, the row data only at DEBUG
        assertTrue(
            logger.entriesAt(EngineLogLevel.INFO).any {
                it.message == "INSERT $TABLE queued offline (network unavailable)"
            }
        )
        assertTrue(
            logger.entries
                .filter { it.level != EngineLogLevel.DEBUG }
                .none { it.message.contains(SECRET_VALUE) },
            "row data must never be logged above DEBUG",
        )
    }

    private companion object {
        const val TABLE = "todos"
        const val SECRET_VALUE = "private-row-value"
        val ROWS: JsonArray =
            Json.parseToJsonElement("""[{"id":1,"title":"$SECRET_VALUE"}]""") as JsonArray
        val PATCH = Json.parseToJsonElement("""{"title":"$SECRET_VALUE"}""")
        const val ID_FILTER_JSON =
            """{"conditions":[{"column":"id","operatorId":"eq","value":"1"}]}"""
    }
}

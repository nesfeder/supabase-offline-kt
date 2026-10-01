// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
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
    private val logger = RecordingOfflineLogger()
    private val builder =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = newTestOfflineStore(mutationDao = mutationDao),
            engineLogger = logger.asEngineLogger(),
        )

    private suspend fun insert(strategy: MutationStrategy): MutationOutcome =
        builder.insertJson(ROWS, strategy)

    private suspend fun upsert(strategy: MutationStrategy): MutationOutcome =
        builder.upsertJson(ROWS, strategy) { onConflict = "id" }

    private suspend fun update(strategy: MutationStrategy): MutationOutcome =
        builder.updateJson(PATCH, strategy) { filter { eq("id", 1) } }

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
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(MutationOperation.INSERT, mutationDao.inserted.single().operation)
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
        assertEquals(MutationOutcome.Queued, outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.UPSERT, queued.operation)
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
        assertEquals(MutationOutcome.Queued, outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.UPDATE, queued.operation)
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
        assertEquals(MutationOutcome.Queued, outcome)
        val queued = mutationDao.inserted.single()
        assertEquals(MutationOperation.DELETE, queued.operation)
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
     * enqueue, like any other write the queue cannot replay faithfully, and the original failure is
     * rethrown.
     */
    @Test
    fun an_unfiltered_update_is_refused_for_queueing_and_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> {
            builder.updateJson(PATCH, MutationStrategy.QueueIfOffline)
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    /**
     * Inverted for the same reason as the UPDATE case above: an unfiltered DELETE is refused at
     * enqueue instead of being queued and dropped at replay.
     */
    @Test
    fun an_unfiltered_delete_is_refused_for_queueing_and_rethrows() = runTest {
        // Given
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> { builder.delete(MutationStrategy.QueueIfOffline) }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_filter_the_queue_cannot_capture_refuses_to_queue_and_rethrows_the_failure() = runTest {
        // Given — a connectivity failure, and a logical group the stored filter cannot represent
        failWithConnectivity()

        // When / Then
        assertFailsWith<HttpRequestException> {
            builder.updateJson(PATCH, MutationStrategy.QueueIfOffline) {
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
            logger.entriesAt(OfflineLogLevel.INFO).any {
                it.message == "INSERT $TABLE queued offline (network unavailable)"
            }
        )
        assertTrue(
            logger.entries
                .filter { it.level != OfflineLogLevel.DEBUG }
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

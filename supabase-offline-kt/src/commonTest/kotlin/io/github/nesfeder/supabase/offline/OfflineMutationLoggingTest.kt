// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * What each mutation verb logs, ported from the previous standalone engine's builder tests. A
 * success logs its request and response at DEBUG only, and a write queued while offline logs one
 * INFO line that names the verb and the table and carries no row data.
 */
class OfflineMutationLoggingTest {

    private val server = RecordingPostgrestServer(answer = { okJson(RESPONSE) })
    private val logger = RecordingLogSink()
    private val builder =
        newTestOfflineTable(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = newTestOfflineStore(),
            engineLogger = logger.asEngineLogger(),
        )

    private fun messagesAt(level: EngineLogLevel): List<String> =
        logger.entriesAt(level).map { it.message }

    // ─────────────────────────── Success ───────────────────────────

    @Test
    fun insert_success_logsDebugWithRequestAndResponseJson() = runTest {
        // Given — the default server accepts the write

        // When
        builder.insert(ROWS)

        // Then
        assertEquals(
            listOf("INSERT $TABLE request body: $ROWS", "INSERT $TABLE response: $RESPONSE"),
            messagesAt(EngineLogLevel.DEBUG),
        )
        assertEquals(emptyList(), messagesAt(EngineLogLevel.INFO))
    }

    @Test
    fun upsert_success_logsDebugWithJson() = runTest {
        // Given — the default server accepts the write

        // When
        builder.upsert(ROWS) { onConflict = "id" }

        // Then
        assertEquals(
            listOf("UPSERT $TABLE request body: $ROWS", "UPSERT $TABLE response: $RESPONSE"),
            messagesAt(EngineLogLevel.DEBUG),
        )
        assertEquals(emptyList(), messagesAt(EngineLogLevel.INFO))
    }

    @Test
    fun update_success_logsDebugWithJson() = runTest {
        // Given — the default server accepts the write

        // When
        builder.update(PATCH) { filter { eq("id", 1) } }

        // Then
        assertEquals(
            listOf("UPDATE $TABLE request body: $PATCH", "UPDATE $TABLE response: $RESPONSE"),
            messagesAt(EngineLogLevel.DEBUG),
        )
        assertEquals(emptyList(), messagesAt(EngineLogLevel.INFO))
    }

    @Test
    fun delete_success_logsDebugWithResponseJson() = runTest {
        // Given — the default server accepts the write

        // When
        builder.delete { filter { eq("id", 1) } }

        // Then
        assertEquals(
            listOf("DELETE $TABLE attempting network", "DELETE $TABLE response: $RESPONSE"),
            messagesAt(EngineLogLevel.DEBUG),
        )
        assertEquals(emptyList(), messagesAt(EngineLogLevel.INFO))
    }

    // ─────────────────────────── Queued offline ───────────────────────────

    @Test
    fun insert_queuedOffline_logsInfoQueued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        builder.insert(ROWS, MutationStrategy.QueueIfOffline)

        // Then
        assertEquals(
            listOf("INSERT $TABLE queued offline (network unavailable)"),
            messagesAt(EngineLogLevel.INFO),
        )
    }

    @Test
    fun upsert_queuedOffline_logsInfoQueued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        builder.upsert(ROWS, MutationStrategy.QueueIfOffline) { onConflict = "id" }

        // Then
        assertEquals(
            listOf("UPSERT $TABLE queued offline (network unavailable)"),
            messagesAt(EngineLogLevel.INFO),
        )
    }

    @Test
    fun update_queuedOffline_logsInfoQueued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        builder.update(PATCH, MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

        // Then
        assertEquals(
            listOf("UPDATE $TABLE queued offline (network unavailable)"),
            messagesAt(EngineLogLevel.INFO),
        )
    }

    @Test
    fun delete_queuedOffline_logsInfoQueued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When
        builder.delete(MutationStrategy.QueueIfOffline) { filter { eq("id", 1) } }

        // Then
        assertEquals(
            listOf("DELETE $TABLE queued offline (network unavailable)"),
            messagesAt(EngineLogLevel.INFO),
        )
    }

    private companion object {
        const val TABLE = "todos"
        const val RESPONSE = """[{"id":1,"created":true}]"""
        val ROWS: JsonArray =
            Json.parseToJsonElement("""[{"id":1,"title":"Buy milk"}]""") as JsonArray
        val PATCH = Json.parseToJsonElement("""{"title":"Buy oat milk"}""")
    }
}

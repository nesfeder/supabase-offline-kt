package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a replay logs, ported from the previous standalone engine's syncer tests: INFO when a
 * table starts and finishes, DEBUG per replayed write, and a WARNING when a write is dropped as
 * permanently rejected or the table pauses for connectivity. No message carries row data.
 */
class OfflineMutationSyncerLoggingTest {

    private val server = RecordingPostgrestServer()
    private val logger = RecordingOfflineLogger()
    private val store = newTestOfflineStore()
    private val syncer = OfflineMutationSyncer(
        store = store,
        supabase = server.supabaseClient(),
        sdkLogger = logger.asSdkLogger(),
    )

    private fun messagesAt(level: OfflineLogLevel): List<String> = logger.entriesAt(level).map { it.message }

    @Test
    fun syncTable_success_logsInfoStartAndComplete() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = ROWS)
        store.enqueueInsert(table = TABLE, body = ROWS)

        // When
        syncer.syncTable(table = TABLE)

        // Then
        assertEquals(
            listOf("Syncing 2 pending mutations for $TABLE", "Sync complete: 2 mutations synced for $TABLE"),
            messagesAt(OfflineLogLevel.INFO),
        )
    }

    @Test
    fun syncTable_success_logsDebugPerMutation() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = ROWS)

        // When
        syncer.syncTable(table = TABLE)

        // Then
        assertEquals(listOf("Synced INSERT mutation for $TABLE"), messagesAt(OfflineLogLevel.DEBUG))
    }

    @Test
    fun syncTable_permanentFailure_logsWarning() = runTest {
        // Given — the server rejects the write for good
        store.enqueueInsert(table = TABLE, body = ROWS)
        server.answer = { postgrestError(HttpStatusCode.Conflict) }

        // When
        syncer.syncTable(table = TABLE)

        // Then
        assertEquals(
            listOf("Permanent failure for INSERT on $TABLE, dropping mutation"),
            messagesAt(OfflineLogLevel.WARNING),
        )
    }

    @Test
    fun syncTable_networkFailure_logsWarningPaused() = runTest {
        // Given — the network is down
        store.enqueueInsert(table = TABLE, body = ROWS)
        server.answer = { connectivityFailure() }

        // When
        syncer.syncTable(table = TABLE)

        // Then
        assertEquals(listOf("Network unavailable, pausing sync for $TABLE"), messagesAt(OfflineLogLevel.WARNING))
    }

    private companion object {
        const val TABLE = "todos"
        val ROWS: JsonArray = Json.parseToJsonElement("""[{"id":1,"title":"Buy milk"}]""") as JsonArray
    }
}

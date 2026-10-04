// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * What a replay logs, ported from the previous standalone engine's replayer tests: INFO when a
 * table starts and finishes, DEBUG per replayed write, and a WARNING when a write is dropped as
 * permanently rejected or the table pauses for connectivity. No message carries row data.
 */
class MutationQueueReplayerLoggingTest {

    private val server = RecordingPostgrestServer()
    private val logger = RecordingLogSink()
    private val store = newTestOfflineStore()
    private val replayer =
        newTestMutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            engineLogger = logger.asEngineLogger(),
        )

    private fun messagesAt(level: EngineLogLevel): List<String> =
        logger.entriesAt(level).map { it.message }

    @Test
    fun syncTable_success_logsInfoStartAndComplete() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = ROWS)
        store.enqueueInsert(table = TABLE, body = ROWS)

        // When
        replayer.syncTable(table = TABLE)

        // Then
        assertEquals(
            listOf(
                "Syncing 2 pending mutations for $TABLE",
                "Sync complete: 2 mutations synced for $TABLE",
            ),
            messagesAt(EngineLogLevel.INFO),
        )
    }

    @Test
    fun syncTable_success_logsDebugPerMutation() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = ROWS)

        // When
        replayer.syncTable(table = TABLE)

        // Then
        assertEquals(listOf("Synced INSERT mutation for $TABLE"), messagesAt(EngineLogLevel.DEBUG))
    }

    @Test
    fun syncTable_permanentFailure_logsWarning() = runTest {
        // Given — the server rejects the write for good
        store.enqueueInsert(table = TABLE, body = ROWS)
        server.answer = { postgrestError(HttpStatusCode.Conflict) }

        // When
        replayer.syncTable(table = TABLE)

        // Then
        assertEquals(
            listOf("Permanent failure for INSERT on $TABLE (409); moved to the rejected list"),
            messagesAt(EngineLogLevel.WARNING),
        )
    }

    @Test
    fun syncTable_networkFailure_logsWarningPaused() = runTest {
        // Given — the network is down
        store.enqueueInsert(table = TABLE, body = ROWS)
        server.answer = { connectivityFailure() }

        // When
        replayer.syncTable(table = TABLE)

        // Then
        assertEquals(
            listOf("Network unavailable, pausing sync for $TABLE"),
            messagesAt(EngineLogLevel.WARNING),
        )
    }

    @Test
    fun a_queued_write_whose_body_no_longer_parses_is_logged_before_it_leaves_the_queue() =
        runTest {
            // Given — a stored INSERT body that is not a JSON array, carrying a private value
            val mutationDao = FakeMutationDao()
            val storeWithCorruptRow = newTestOfflineStore(mutationDao = mutationDao)
            mutationDao.insert(
                MutationRow(
                    table = TABLE,
                    operation = MutationOperation.INSERT,
                    bodyJson = "not-json $PRIVATE_VALUE",
                    filterJson = null,
                    createdAtMs = 0L,
                )
            )
            val corruptSyncer =
                newTestMutationQueueReplayer(
                    store = storeWithCorruptRow,
                    supabase = server.supabaseClient(),
                    engineLogger = logger.asEngineLogger(),
                )

            // When
            corruptSyncer.syncAllTables()

            // Then — a warning names the table and the problem, and carries none of the payload
            val warnings = messagesAt(EngineLogLevel.WARNING)
            assertTrue(
                warnings.any { it.contains(TABLE) && it.contains("no longer parses") },
                "got $warnings",
            )
            assertTrue(
                logger.entries.none { it.message.contains(PRIVATE_VALUE) },
                "the payload must never be logged",
            )
            assertTrue(server.requests.isEmpty())
        }

    private companion object {
        const val TABLE = "todos"
        const val PRIVATE_VALUE = "private-row-value"
        val ROWS: JsonArray =
            Json.parseToJsonElement("""[{"id":1,"title":"Buy milk"}]""") as JsonArray
    }
}

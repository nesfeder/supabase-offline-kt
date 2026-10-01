// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A queued write's outcome carries the write as the mutation queue holds it, so the id it hands the
 * caller is the one the queue lists, and later reports, for the same write.
 */
class QueuedOutcomeTest {

    @Test
    fun a_queued_update_carries_the_pending_mutation_the_queue_lists() = runTest {
        // Given — offline, with no background pass to send it
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val offline =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )

        // When
        val outcome =
            offline.from("todos").update(buildJsonObject { put("done", true) }) {
                filter { eq("id", "t1") }
            }

        // Then — the outcome's write is the queue's, field for field
        val queued = assertIs<MutationOutcome.Queued>(outcome).pendingMutation
        assertEquals(listOf(queued), offline.pendingMutations().first())
        assertEquals("todos", queued.table)
        assertEquals(MutationOperation.Update, queued.operation)
        assertEquals(listOf(buildJsonObject { put("id", "t1") }), queued.rowKeys)
        assertEquals(buildJsonObject { put("done", true) }, queued.body)
        assertTrue(outcome.isQueued)
        assertNull(outcome.resultOrNull())
    }

    @Test
    fun a_queued_insert_carries_the_keys_of_its_rows() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val offline =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = idleCoroutineScope(),
            )
        val rows = buildJsonArray {
            add(buildJsonObject { put("id", "a") })
            add(buildJsonObject { put("title", "no key") })
        }

        // When
        val outcome = offline.from("todos").insert(rows)

        // Then — one key for the row that carries it; none for the one that does not
        val queued = assertIs<MutationOutcome.Queued>(outcome).pendingMutation
        assertEquals(MutationOperation.Insert, queued.operation)
        assertEquals(listOf(buildJsonObject { put("id", "a") }), queued.rowKeys)
        assertEquals(listOf(queued), offline.pendingMutations().first())
    }

    @Test
    fun an_applied_write_is_not_queued_and_carries_the_server_response() = runTest {
        // Given — online
        val server = RecordingPostgrestServer()
        val offline = newTestOfflineClient(supabase = server.supabaseClient())

        // When
        val outcome = offline.from("todos").insert(buildJsonArray { add(buildJsonObject {}) })

        // Then
        val applied = assertIs<MutationOutcome.Applied>(outcome)
        assertFalse(outcome.isQueued)
        assertEquals(applied.result, outcome.resultOrNull())
        offline.close()
    }
}

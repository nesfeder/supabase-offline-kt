// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.assertClientClosedError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `syncStatus` is one value an app draws a sync banner from: the network, the current user's queued
 * and refused writes, whether a pass runs, when the queue was last emptied and why the last pass
 * stopped. Its counts follow the queue shortly after each change, so these tests wait for the value
 * they expect, on real threads, as an app's status does.
 */
class SyncStatusTest {

    /** Waits, at most ten seconds, for a status [accepted] takes. */
    private suspend fun StateFlow<SyncStatus>.awaiting(
        accepted: (SyncStatus) -> Boolean
    ): SyncStatus = withContext(Dispatchers.Default) { withTimeout(10.seconds) { first(accepted) } }

    @Test
    fun the_status_follows_the_queue_and_says_when_it_was_emptied() = runTest {
        // Given — two writes queued offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        client.from(TABLE).insert(row(2))
        val queued = status.awaiting { it.pendingMutationCount == 2 }

        // When — the network is back and the app syncs
        server.answer = { okJson("[]") }
        client.syncPendingMutations()
        val drained = status.awaiting { it.pendingMutationCount == 0 && it.lastSyncedAt != null }

        // Then
        assertNull(queued.lastSyncedAt)
        assertNull(drained.lastSyncError)
        client.close()
    }

    @Test
    fun the_status_counts_the_current_users_refused_writes() = runTest {
        // Given — a write queued offline that the server refuses for good
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        server.answer = { postgrestError(HttpStatusCode.UnprocessableEntity) }
        val id = client.syncPendingMutations().rejected.single().id

        // When
        val refused = status.awaiting { it.rejectedMutationCount == 1 }
        client.dismissRejectedMutation(id)
        val dismissed = status.awaiting { it.rejectedMutationCount == 0 }

        // Then
        assertEquals(0, refused.pendingMutationCount)
        assertEquals(0, dismissed.rejectedMutationCount)
        client.close()
    }

    @Test
    fun a_write_moving_between_the_queue_and_the_rejected_list_is_counted_once() = runTest {
        // Given — a write the server refuses for good every time it is sent, on the rejected list,
        // and a collector recording every status it is handed
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        server.answer = { postgrestError(HttpStatusCode.UnprocessableEntity) }
        var id = client.syncPendingMutations().rejected.single().id
        val seen = mutableListOf<SyncStatus>()
        val recorder = launch(Dispatchers.Default) { status.collect { value -> seen += value } }

        // When — it is retried, and refused again on the pass the retry asks for, many times
        repeat(RETRIES) {
            val retried = id
            client.retryRejectedMutation(retried)
            id =
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        client
                            .rejectedMutations()
                            .first { entries ->
                                entries.singleOrNull()?.id.let { it != null && it != retried }
                            }
                            .single()
                            .id
                    }
                }
        }
        status.awaiting { it.rejectedMutationCount == 1 && it.pendingMutationCount == 0 }
        recorder.cancelAndJoin()

        // Then — every status counted the write in exactly one place, once it was counted at all
        val counted = seen.map { value -> value.pendingMutationCount + value.rejectedMutationCount }
        assertEquals(listOf(), counted.dropWhile { it == 0 }.filter { it != 1 }, "counts: $counted")
        client.close()
    }

    @Test
    fun the_status_says_a_pass_is_running_while_one_is() = runTest {
        // Given — a write queued offline, and a server that holds its replay
        val held = CompletableDeferred<Unit>()
        var online = false
        val server =
            RecordingPostgrestServer(
                answer = {
                    if (!online) connectivityFailure()
                    held.await()
                    okJson("[]")
                }
            )
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val status = client.syncStatus
        client.from(TABLE).insert(row(1))
        online = true

        // When
        val sync = async(Dispatchers.Default) { client.syncPendingMutations() }
        val running = status.awaiting { it.isSyncing }
        held.complete(Unit)
        sync.await()
        val done = status.awaiting { !it.isSyncing && it.pendingMutationCount == 0 }

        // Then
        assertEquals(true, running.isSyncing)
        assertNotNull(done.lastSyncedAt)
        client.close()
    }

    @Test
    fun the_status_cannot_be_read_once_the_client_is_closed() = runTest {
        // Given
        val client = newTestOfflineClient()

        // When
        client.close()

        // Then
        assertClientClosedError(runCatching { client.syncStatus }.exceptionOrNull(), "syncStatus")
    }

    private fun row(id: Int): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("title", "note $id")
            }
        )
    }

    private companion object {
        const val TABLE = "notes"
        const val RETRIES = 20
    }
}

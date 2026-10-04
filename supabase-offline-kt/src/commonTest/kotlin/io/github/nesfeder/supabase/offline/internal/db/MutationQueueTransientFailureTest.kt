// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A failure that may pass — the network, a server error, rate limiting, a request timeout — never
 * deletes a queued write, however many times it recurs. Only the server refusing the write itself
 * (a permanent 4xx) takes it out of the queue.
 */
class MutationQueueTransientFailureTest {

    private val store = newTestStore()

    @Test
    fun a_write_that_keeps_meeting_503_is_never_dropped() = runTest {
        assertSurvives(HttpStatusCode.ServiceUnavailable)
    }

    @Test
    fun a_write_that_keeps_meeting_500_is_never_dropped() = runTest {
        assertSurvives(HttpStatusCode.InternalServerError)
    }

    @Test
    fun a_write_that_keeps_meeting_429_is_never_dropped() = runTest {
        assertSurvives(HttpStatusCode.TooManyRequests)
    }

    @Test
    fun a_write_that_keeps_meeting_408_is_never_dropped() = runTest {
        assertSurvives(HttpStatusCode.RequestTimeout)
    }

    @Test
    fun a_write_that_keeps_meeting_connectivity_failures_is_never_dropped() = runTest {
        // Given
        store.mutationQueue.enqueueInsert(table = TABLE, body = ROWS)
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val replayer =
            newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — far more sweeps than any retry budget
        repeat(SWEEPS) { replayer.syncAllTables() }

        // Then
        assertEquals(SWEEPS, server.requests.size)
        assertEquals(1, store.pendingMutationCount())
    }

    private suspend fun assertSurvives(status: HttpStatusCode) {
        // Given — a queued write and a server that keeps answering [status]
        store.mutationQueue.enqueueInsert(table = TABLE, body = ROWS)
        val server = RecordingPostgrestServer(answer = { postgrestError(status) })
        val replayer =
            newTestMutationQueueReplayer(store = store, supabase = server.supabaseClient())

        // When — both entry points, far more often than the old five-strike budget
        repeat(SWEEPS) {
            replayer.syncAllTables()
            try {
                replayer.syncTable(table = TABLE)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // The table entry point reports a server error to its caller; the write stays.
            }
        }

        // Then — every sweep tried it, and it is still queued
        assertEquals(SWEEPS * 2, server.requests.size)
        assertEquals(1, store.pendingMutationCount(), "a $status must never drop the write")
    }

    private companion object {
        const val TABLE = "todos"
        const val SWEEPS = 6
        val ROWS: JsonArray = Json.parseToJsonElement("""[{"title":"Buy milk"}]""") as JsonArray
    }
}

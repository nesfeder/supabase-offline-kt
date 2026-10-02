// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `discardPendingMutation` is the way out of a write the server keeps failing. When that write is
 * on the wire at the moment, the discard waits for the send to end and then removes it, instead of
 * leaving it queued; and what waited behind it is sent, with no call from the app.
 */
class DiscardWhileSendingTest {

    @Test
    fun a_write_being_sent_is_discarded_once_its_send_ends() = runTest {
        // Given — a write sent at once, held at the server; everything on this test's scheduler
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val held = CompletableDeferred<Unit>()
        val server =
            RecordingPostgrestServer(
                answer = {
                    held.await()
                    postgrestError(HttpStatusCode.ServiceUnavailable, code = "XX503")
                },
                engineDispatcher = dispatcher,
            )
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = scope)
        val write = scope.async { client.from(TABLE).insert(row(1)) }
        runCurrent()
        val id = client.pendingMutations().first().single().id

        // When — the app discards it while it is on the wire, then the server answers 503
        val discard = scope.async { client.discardPendingMutation(id) }
        runCurrent()
        val discardedWhileOnTheWire = discard.isCompleted
        held.complete(Unit)
        runCurrent()
        discard.await()

        // Then — it waited, and the write the 503 left queued is gone
        assertFalse(discardedWhileOnTheWire)
        assertIs<MutationOutcome.Queued>(write.await())
        assertEquals(0, client.pendingMutationCount())
        scope.cancel()
    }

    @Test
    fun discarding_the_write_at_the_head_lets_the_writes_behind_it_go() = runTest {
        // Given — a write the server always fails, and one behind it
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server =
            RecordingPostgrestServer(
                answer = { connectivityFailure() },
                engineDispatcher = dispatcher,
            )
        val client =
            newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = scope)
        client.from(TABLE).insert(row(1))
        client.from(TABLE).insert(row(2))
        server.answer = { request ->
            if (""""id":1""" in request.body)
                postgrestError(HttpStatusCode.InternalServerError, code = "XX000")
            else okJson("[]")
        }
        client.syncPendingMutations()
        val head = client.pendingMutations().first().first().id

        // When — the app discards it, and does nothing else; any backoff goes by
        client.discardPendingMutation(head)
        runCurrent()
        advanceTimeBy(2.minutes)
        runCurrent()

        // Then — the write behind it was sent
        assertEquals(0, client.pendingMutationCount())
        assertEquals(row(2).toString(), server.requests.last { it.method == "POST" }.body)
        scope.cancel()
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
    }
}

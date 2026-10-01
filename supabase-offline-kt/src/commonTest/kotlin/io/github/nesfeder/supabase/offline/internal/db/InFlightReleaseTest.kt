// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A pass marks each mutation in flight while it sends it, and releases the mark however the send
 * ends. A pass whose local store fails around a send — a full disk while it records the send — or
 * whose caller is cancelled while the request is on the wire, which it then finishes, stops with
 * the mutation still queued when it was not applied, and the next pass, and the next write at the
 * head, send it.
 */
class InFlightReleaseTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(mutationDao = mutationDao)
    private val server = RecordingPostgrestServer()
    private val replayer =
        MutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            currentIdentity = { QueueIdentity.Anonymous },
        )

    @Test
    fun a_store_failure_during_a_pass_does_not_stop_the_passes_after_it() = runTest {
        // Given — a queued keyed INSERT, and a disk that fails the write recording its send, once
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        mutationDao.failNextMarkSendUnconfirmed = true

        // When — one pass meets the failure, and the next runs once the disk recovers
        val failed = runCatching { replayer.sync() }
        val next = replayer.sync()

        // Then — the first sent nothing and kept the write; the next sent it
        assertTrue(
            failed.isFailure || failed.getOrThrow().stop is SyncStop.TransientFailure,
            "got $failed",
        )
        assertEquals(1, next.syncedCount)
        assertEquals(null, next.stop)
        assertEquals(1, server.requests.size)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun a_store_failure_during_a_pass_does_not_hold_back_later_writes_at_the_head() = runTest {
        // Given — a pass that met a failing disk
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        mutationDao.failNextMarkSendUnconfirmed = true
        runCatching { replayer.sync() }
        replayer.sync()

        // When — the app writes again
        val builder =
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
                queueIdentity = { QueueIdentity.Anonymous },
                headSender = replayer,
            )
        val later = builder.insertJson(rows("""[{"id":"k2","title":"Eggs"}]"""))

        // Then — it is sent at once, not queued behind a send that is no longer happening
        assertIs<MutationOutcome.Applied>(later)
        assertEquals(2, server.requests.size)
    }

    @Test
    fun a_pass_cancelled_while_its_request_is_on_the_wire_does_not_stop_the_next() = runTest {
        // Given — a queued write whose first send is on the wire when its caller gives up; the pass
        // finishes that request, which then fails without an answer
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val onTheWire = CompletableDeferred<Unit>()
        val unanswered = CompletableDeferred<Unit>()
        server.answer = {
            onTheWire.complete(Unit)
            unanswered.await()
            connectivityFailure()
        }
        val cancelled = async { replayer.sync() }
        onTheWire.await()
        cancelled.cancel()
        unanswered.complete(Unit)
        cancelled.join()

        // When — the next pass runs
        server.answer = { okJson("[]") }
        val next = replayer.sync()

        // Then — it sends the write rather than stopping at a send that is no longer happening
        assertEquals(1, next.syncedCount)
        assertEquals(null, next.stop)
    }

    @Test
    fun a_keyed_insert_on_the_wire_when_its_pass_is_cancelled_is_recorded_and_not_sent_again() =
        runTest {
            // Given — a pass cancelled while its keyed INSERT is on the wire; the server then
            // accepts it
            cancelPassThenAccept("""[{"id":"k1","title":"Buy milk"}]""")

            // When
            val next = replayer.sync()

            // Then — the pass finished the request and recorded it, so nothing is sent again
            assertEquals(0, store.pendingMutationCount())
            assertEquals(0, next.syncedCount)
            assertEquals(1, server.requests.count { it.method == "POST" })
        }

    @Test
    fun a_keyless_insert_on_the_wire_when_its_pass_is_cancelled_is_recorded_and_not_sent_again() =
        runTest {
            // Given — the same, for an INSERT a resend would duplicate
            cancelPassThenAccept("""[{"title":"Buy milk"}]""")

            // When
            val next = replayer.sync()

            // Then
            assertEquals(0, store.pendingMutationCount())
            assertEquals(0, next.syncedCount)
            assertEquals(1, server.requests.count { it.method == "POST" })
        }

    /**
     * Queues an INSERT of [body], cancels the pass sending it while its request is on the wire, and
     * only then lets the server accept it.
     */
    private suspend fun TestScope.cancelPassThenAccept(body: String) {
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows(body))
        }
        val onTheWire = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<Unit>()
        server.answer = {
            onTheWire.complete(Unit)
            accepted.await()
            okJson("[]")
        }
        val cancelled = async { replayer.sync() }
        onTheWire.await()
        cancelled.cancel()
        accepted.complete(Unit)
        cancelled.join()
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}

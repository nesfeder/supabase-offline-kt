// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A pass marks each mutation in flight while it sends it, and releases the mark however the send
 * ends. A pass whose local store fails around a send — a full disk while it records the send — or
 * whose caller is cancelled while the request is on the wire stops there with the mutation still
 * queued, and the next pass, and the next write at the head, send it.
 */
class InFlightReleaseTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
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
        objectDao.failNextUpsertOf { row ->
            row.table == RESERVED_TABLE_NAME && row.signature == UNCONFIRMED_SENDS_SIGNATURE
        }

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
        objectDao.failNextUpsertOf { row ->
            row.table == RESERVED_TABLE_NAME && row.signature == UNCONFIRMED_SENDS_SIGNATURE
        }
        runCatching { replayer.sync() }
        replayer.sync()

        // When — the app writes again
        val builder =
            OfflinePostgrestQueryBuilderImpl(
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
        // Given — a queued write whose first send hangs until its caller gives up
        store.enqueueOwnedBy(QueueIdentity.Anonymous.storedValue) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"k1","title":"Buy milk"}]"""))
        }
        val onTheWire = CompletableDeferred<Unit>()
        server.answer = {
            onTheWire.complete(Unit)
            awaitCancellation()
        }
        val cancelled = async { replayer.sync() }
        onTheWire.await()
        cancelled.cancelAndJoin()

        // When — the next pass runs
        server.answer = { okJson("[]") }
        val next = replayer.sync()

        // Then — it sends the write rather than stopping at a send that is no longer happening
        assertEquals(1, next.syncedCount)
        assertEquals(null, next.stop)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}

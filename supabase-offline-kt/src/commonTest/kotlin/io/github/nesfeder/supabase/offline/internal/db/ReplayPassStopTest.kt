// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A replay pass that is cancelled — by `close()`, or a clear of local data — finishes a request it
 * has already sent, but sends none it has not: cancelled after it chose a mutation and before the
 * request left, it stops there, and the mutation stays queued, known never to have been sent.
 */
class ReplayPassStopTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()

    @Test
    fun a_pass_cancelled_before_its_request_leaves_sends_nothing() = runTest {
        // Given — a keyed insert queued offline, and a pass that is cancelled when the send's start
        // is recorded: after the mutation was claimed, before the request is built
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("user-a")
        store.enqueueOwnedBy("user:user-a") {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"a1"}]"""))
        }
        val replayer = MutationQueueReplayer(store = store, supabase = supabase)
        var pass: Job? = null
        objectDao.onUpsert = { row ->
            if (row.signature == UNCONFIRMED_SENDS_SIGNATURE && "mutationIds" in row.payload)
                pass?.cancel()
        }

        // When
        pass = launch(start = CoroutineStart.LAZY) { replayer.sync() }
        pass.join()

        // Then — nothing reached the server; the insert is queued with no record of a send
        assertTrue(pass.isCancelled)
        assertTrue(server.requests.isEmpty(), "sent: ${server.requests}")
        val pending = store.allPendingMutations().single()
        assertFalse(store.hasUnconfirmedSend(pending.id))

        // When — the next pass
        objectDao.onUpsert = null
        val report = replayer.sync()

        // Then — sent once, as the plain insert a write never sent is
        assertEquals(1, report.syncedCount)
        assertEquals(listOf("return=minimal"), server.requests.map { it.prefer })
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "todos"
    }
}

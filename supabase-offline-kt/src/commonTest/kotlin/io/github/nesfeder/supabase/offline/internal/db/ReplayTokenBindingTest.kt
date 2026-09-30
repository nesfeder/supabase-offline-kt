// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * A queued write is sent with the token of the identity that owns it, or not at all. The replayer
 * checks the owner against the session before it sends, but supabase-kt reads the token later, as
 * it builds the request; a session change in between must stop the send, never carry the write out
 * under the next user's token. Each case injects the change at that exact point: when the send's
 * start is recorded, after the check and before the request is built.
 */
class ReplayTokenBindingTest {

    private val objectDao = FakeObjectDao()
    private val store = newTestOfflineStore(objectDao = objectDao)
    private val server = RecordingPostgrestServer()

    /** Runs [change] once, when the send of a keyed INSERT is recorded as started. */
    private fun changeTheSessionWhenASendStarts(change: suspend () -> Unit) {
        var changed = false
        objectDao.onUpsert = { row ->
            if (!changed && row.signature == UNCONFIRMED_SENDS_SIGNATURE && "mutationIds" in row.payload) {
                changed = true
                change()
            }
        }
    }

    /** Queues a keyed INSERT for [owner] without sending it, as a write made offline is. */
    private suspend fun queueFor(owner: String, id: String) {
        store.enqueueOwnedBy(owner) { enqueueInsert(table = TABLE, body = rows("""[{"id":"$id"}]""")) }
    }

    private fun postsCarrying(subject: String): List<String> =
        server.requests
            .filter { it.method == "POST" && it.authorization == "Bearer ${testJwt(subject)}" }
            .map { it.body }

    @Test
    fun a_sign_in_between_the_check_and_the_request_stops_the_pass() = runTest {
        // Given — user A's write queued offline, then the network back
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("user-a")
        queueFor("user:user-a", id = "a1")
        val replayer = MutationQueueReplayer(store = store, supabase = supabase)
        changeTheSessionWhenASendStarts { supabase.signInForTest("user-b") }

        // When — a pass as A; B signs in after A was checked, before the request is built
        val report = replayer.sync()

        // Then — nothing went out under B's token; the write is still A's and still queued
        assertEquals(emptyList(), postsCarrying("user-b"))
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertEquals(0, report.syncedCount)
        assertEquals(listOf("user:user-a"), store.allPendingMutations().map { it.ownerOf() })

        // When — A signs in again and a pass runs
        supabase.signInForTest("user-a")
        val later = replayer.sync()

        // Then — it goes out once, with A's token
        assertEquals(1, later.syncedCount)
        assertEquals(listOf("""[{"id":"a1"}]"""), postsCarrying("user-a"))
        assertEquals(emptyList(), store.allPendingMutations())
    }

    @Test
    fun a_sign_out_between_the_check_and_the_request_stops_the_pass() = runTest {
        // Given — user A's write queued offline
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("user-a")
        queueFor("user:user-a", id = "a1")
        val replayer = MutationQueueReplayer(store = store, supabase = supabase)
        changeTheSessionWhenASendStarts { supabase.auth.clearSession() }

        // When — A signs out after the check, before the request is built
        val report = replayer.sync()

        // Then — A's write did not go out on the anon key; it waits for A
        assertTrue(server.requests.isEmpty(), "sent: ${server.requests}")
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun a_sign_in_during_a_write_sent_at_the_head_leaves_it_queued_for_its_owner() = runTest {
        // Given — user A online, nothing queued
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("user-a")
        val builder =
            OfflinePostgrestQueryBuilderImpl(delegate = supabase.postgrest.from(TABLE), store = store)
        changeTheSessionWhenASendStarts { supabase.signInForTest("user-b") }

        // When — A writes; B signs in after the head was claimed, before the request is built
        val outcome = builder.insertJson(rows("""[{"id":"a1"}]"""))

        // Then — the write stays queued as A's, and nothing went out under B's token
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(emptyList(), postsCarrying("user-b"))
        assertEquals(listOf("user:user-a"), store.allPendingMutations().map { it.ownerOf() })

        // When — A is back
        supabase.signInForTest("user-a")
        val report = MutationQueueReplayer(store = store, supabase = supabase).sync()

        // Then — sent once, as A
        assertEquals(1, report.syncedCount)
        assertEquals(listOf("""[{"id":"a1"}]"""), postsCarrying("user-a"))
    }

    @Test
    fun a_signed_out_write_is_not_sent_with_a_user_who_signs_in_meanwhile() = runTest {
        // Given — a write made signed out, queued offline
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        queueFor("anonymous", id = "n1")
        val replayer = MutationQueueReplayer(store = store, supabase = supabase)
        changeTheSessionWhenASendStarts { supabase.signInForTest("user-b") }

        // When — a pass signed out; B signs in before the request is built
        val report = replayer.sync()

        // Then — the anonymous write did not go out under B's token
        assertEquals(emptyList(), postsCarrying("user-b"))
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertEquals(1, store.allPendingMutations().size)
    }

    private suspend fun MutationRow.ownerOf(): String? =
        (store.mutationQueueState.owners() as MutationOwners.Known).byId[id]

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}

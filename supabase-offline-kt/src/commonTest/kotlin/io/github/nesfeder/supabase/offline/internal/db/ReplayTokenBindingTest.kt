// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(mutationDao = mutationDao)
    private val server = RecordingPostgrestServer()

    /** Runs [change] once, when the send of a keyed INSERT is recorded as started. */
    private fun changeTheSessionWhenASendStarts(change: suspend () -> Unit) {
        var changed = false
        mutationDao.onMarkSendUnconfirmed = {
            if (!changed) {
                changed = true
                change()
            }
        }
    }

    /** The `Prefer` header of each write sent. */
    private fun writePrefers(): List<String?> =
        server.requests.filter { it.method == "POST" }.map { it.prefer }

    /** Queues a keyed INSERT for [owner] without sending it, as a write made offline is. */
    private suspend fun queueFor(owner: String, id: String) {
        store.enqueueOwnedBy(owner) {
            enqueueInsert(table = TABLE, body = rows("""[{"id":"$id"}]"""))
        }
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

        // Then — nothing went out under B's token; the write is still A's and still queued, and
        // the send this attempt recorded is cleared: nothing left the device
        assertEquals(emptyList(), postsCarrying("user-b"))
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertEquals(0, report.syncedCount)
        assertEquals(listOf("user:user-a"), store.allPendingMutations().map { it.owner })
        assertFalse(store.hasUnconfirmedSend(store.allPendingMutations().single().id))

        // When — A signs in again and a pass runs
        supabase.signInForTest("user-a")
        val later = replayer.sync()

        // Then — it goes out once, with A's token, as the plain INSERT a write never sent is
        assertEquals(1, later.syncedCount)
        assertEquals(listOf("""[{"id":"a1"}]"""), postsCarrying("user-a"))
        assertEquals(listOf<String?>("return=minimal"), writePrefers())
        assertEquals(emptyList(), store.allPendingMutations())
    }

    @Test
    fun a_stop_keeps_the_record_an_earlier_send_made() = runTest {
        // Given — user A's write, whose earlier send may have reached the server
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.signInForTest("user-a")
        queueFor("user:user-a", id = "a1")
        val id = store.allPendingMutations().single().id
        store.markSendUnconfirmed(id)
        val replayer = MutationQueueReplayer(store = store, supabase = supabase)
        changeTheSessionWhenASendStarts { supabase.signInForTest("user-b") }

        // When — a pass as A is stopped by B's sign-in before its request is built
        val report = replayer.sync()

        // Then — the earlier send's record is kept
        assertEquals(SyncStop.SessionChanged, report.stop)
        assertTrue(store.hasUnconfirmedSend(id))

        // When — A is back
        supabase.signInForTest("user-a")
        replayer.sync()

        // Then — it replays as the upsert that takes a row already there as its own
        assertTrue(
            "resolution=ignore-duplicates" in writePrefers().single().orEmpty().split(','),
            "${writePrefers()}",
        )
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
            OfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )
        changeTheSessionWhenASendStarts { supabase.signInForTest("user-b") }

        // When — A writes; B signs in after the head was claimed, before the request is built
        val outcome = builder.insert(rows("""[{"id":"a1"}]"""))

        // Then — the write stays queued as A's, nothing went out under B's token, and the send
        // this attempt recorded is cleared
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(emptyList(), postsCarrying("user-b"))
        assertEquals(listOf("user:user-a"), store.allPendingMutations().map { it.owner })
        assertFalse(store.hasUnconfirmedSend(store.allPendingMutations().single().id))

        // When — A is back
        supabase.signInForTest("user-a")
        val report = MutationQueueReplayer(store = store, supabase = supabase).sync()

        // Then — sent once, as A, as the plain INSERT
        assertEquals(1, report.syncedCount)
        assertEquals(listOf("""[{"id":"a1"}]"""), postsCarrying("user-a"))
        assertEquals(listOf<String?>("return=minimal"), writePrefers())
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

    @Test
    fun a_token_naming_no_user_holds_its_users_queue_and_warns_once() = runTest {
        // Given — A's session, whose token names no user, and A's write queued
        val logger = RecordingLogSink()
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        supabase.auth.importSession(
            UserSession(
                accessToken = "opaque-token",
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
                user = UserInfo(aud = "authenticated", id = "user-a"),
            ),
            autoRefresh = false,
        )
        queueFor("user:user-a", id = "a1")
        val replayer =
            MutationQueueReplayer(
                store = store,
                supabase = supabase,
                engineLogger = logger.asEngineLogger(),
            )

        // When — three passes
        val stops = List(3) { replayer.sync().stop }

        // Then — nothing sent and the write still queued, and one WARN says why
        assertTrue(stops.all { it == SyncStop.SessionChanged }, "$stops")
        assertTrue(server.requests.isEmpty(), "${server.requests}")
        assertEquals(1, store.allPendingMutations().size)
        assertEquals(
            1,
            logger.entriesAt(EngineLogLevel.WARNING).count { "token" in it.message },
        )
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}

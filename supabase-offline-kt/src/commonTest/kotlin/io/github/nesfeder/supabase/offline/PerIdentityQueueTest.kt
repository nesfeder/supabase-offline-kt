// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestMutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The queue is kept per identity: each write records who made it, a pass replays only the current
 * identity's writes, and another identity's writes neither block it nor are sent by it. They go out
 * when their identity is current again — the anonymous identity included, after signing out.
 */
class PerIdentityQueueTest {

    private val store = newTestOfflineStore()
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity = QueueIdentity.Anonymous
    private val builder =
        newTestOfflineTable(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { session },
        )
    private val replayer =
        newTestMutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            currentIdentity = { session },
        )

    private suspend fun writeOffline(title: String): MutationOutcome {
        server.answer = { connectivityFailure() }
        return builder.insert(rows("""[{"title":"$title"}]"""))
    }

    private fun sentBodies(): List<String> =
        server.requests.filter { it.method == "POST" }.map { it.body }

    @Test
    fun a_signed_in_user_can_queue_while_anonymous_writes_are_held() = runTest {
        // Given — an anonymous write queued offline, then user A signs in and writes offline
        writeOffline("anonymous note")
        session = USER_A

        // When
        val outcome = writeOffline("A's note")

        // Then — A's write is queued, not refused because the anonymous one is pending
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(2, store.allPendingMutations().size)
    }

    @Test
    fun a_pass_replays_only_the_current_identitys_writes_and_is_not_blocked_by_others() = runTest {
        // Given — an anonymous write, then one of A's
        writeOffline("anonymous note")
        session = USER_A
        writeOffline("A's note")
        val sentBefore = sentBodies().size

        // When — A is signed in and the queue replays
        server.answer = { okJson("[]") }
        val report = replayer.sync()

        // Then — only A's write went out; the anonymous one waits
        assertEquals(1, report.syncedCount)
        assertEquals(listOf("""[{"title":"A's note"}]"""), sentBodies().drop(sentBefore))
        assertEquals(
            listOf("""[{"title":"anonymous note"}]"""),
            store.allPendingMutations().map { it.bodyJson },
        )
    }

    @Test
    fun anonymous_writes_replay_once_signed_out_again() = runTest {
        // Given — an anonymous write, held while A was signed in
        writeOffline("anonymous note")
        session = USER_A
        server.answer = { okJson("[]") }
        replayer.sync()

        // When — A signs out
        session = QueueIdentity.Anonymous
        val report = replayer.sync()

        // Then
        assertEquals(1, report.syncedCount)
        assertEquals(0, store.allPendingMutations().size)
    }

    @Test
    fun a_write_issued_by_one_user_is_not_queued_as_the_next_user() = runTest {
        // Given — user A's write is in flight
        session = USER_A
        val parked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        server.answer = {
            parked.complete(Unit)
            release.await()
            connectivityFailure()
        }
        val write = async { runCatching { builder.insert(rows("""[{"title":"A's note"}]""")) } }
        parked.await()

        // When — the app switches to user B (no clear), then A's request fails offline
        session = USER_B
        release.complete(Unit)
        write.await()
        server.answer = { okJson("[]") }
        val asB = replayer.sync()
        session = USER_A
        val asA = replayer.sync()

        // Then — it never goes out under B, and does under A
        assertEquals(0, asB.syncedCount)
        assertEquals(1, asA.syncedCount)
    }

    @Test
    fun counts_and_the_overlay_show_only_the_current_identitys_writes() = runTest {
        // Given — a store that follows the session, one offline write each from nobody and from A
        val sessionChanges = MutableStateFlow<QueueIdentity>(session)
        store.identityProvider = { session.storedValue }
        store.identityChanges = sessionChanges
        writeOffline("anonymous note")
        session = USER_A
        sessionChanges.value = session
        writeOffline("A's note")
        val countsAsA = store.pendingMutationCountFlow().first()

        // When — A signs out
        session = QueueIdentity.Anonymous
        sessionChanges.value = session

        // Then — each sees only its own
        assertEquals(1, countsAsA)
        assertEquals(1, store.pendingMutationCountFlow().first())
        assertEquals(1, store.pendingMutationCount())
        assertEquals(
            listOf("""[{"title":"anonymous note"}]"""),
            store.pendingMutationsFlow(null).first().map { it.bodyJson },
        )
        assertEquals(
            """[{"title":"anonymous note"}]""",
            store.overlaidPayloadFlow(TABLE, TABLE).first(),
        )
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}

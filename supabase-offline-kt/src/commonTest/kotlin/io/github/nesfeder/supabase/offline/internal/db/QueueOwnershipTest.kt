// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.internal.mutationqueue.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.mutationqueue.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Each queued write records the identity that made it, and a pass sends only the current identity's
 * writes — so an app that switches accounts without clearing local data never replays one user's
 * offline writes as another user, and neither user's queue blocks the other's.
 */
class QueueOwnershipTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity? = USER_A
    private val syncer =
        MutationQueueReplayer(
            store = store,
            supabase = server.supabaseClient(),
            currentIdentity = { session },
        )
    private val builder =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
            queueIdentity = { session },
        )

    private suspend fun writeOffline(title: String): MutationOutcome {
        server.answer = { connectivityFailure() }
        return builder.insertJson(rows("""[{"title":"$title"}]"""), MutationStrategy.QueueIfOffline)
    }

    @Test
    fun a_pending_write_from_one_user_is_not_sent_under_another() = runTest {
        // Given — user A writes offline, then the app switches to user B without clearing
        writeOffline("A's note")
        session = USER_B
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size

        // When
        val report = syncer.sync()

        // Then — nothing is sent: B has nothing pending, and A's write waits for A
        assertEquals(requestsBefore, server.requests.size)
        assertEquals(null, report.stop)
        assertEquals(0, report.syncedCount)
        assertEquals(0, report.remainingCount)
        assertEquals(1, store.allPendingMutations().size)
    }

    @Test
    fun the_owners_writes_replay_once_they_sign_back_in() = runTest {
        // Given
        writeOffline("A's note")
        session = USER_B
        server.answer = { okJson("[]") }
        syncer.sync()

        // When — user A is back
        session = USER_A
        val report = syncer.sync()

        // Then
        assertEquals(1, report.syncedCount)
        assertEquals("""[{"title":"A's note"}]""", server.requests.last().body)
    }

    @Test
    fun another_user_queues_beside_a_pending_queue_that_is_not_theirs() = runTest {
        // Given — user A's write is pending
        writeOffline("A's note")

        // When — user B writes offline
        session = USER_B
        val outcome = writeOffline("B's note")

        // Then — B's write is queued as B's, and A's stays A's
        assertEquals(MutationOutcome.Queued, outcome)
        val pending = store.allPendingMutations()
        assertEquals(listOf(USER_A.storedValue, USER_B.storedValue), pending.map { it.owner })
    }

    @Test
    fun each_user_replays_only_their_own_writes() = runTest {
        // Given — one write from each of A and B
        writeOffline("A's note")
        session = USER_B
        writeOffline("B's note")
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size

        // When — B syncs, then A
        val asB = syncer.sync()
        session = USER_A
        val asA = syncer.sync()

        // Then
        assertEquals(1, asB.syncedCount)
        assertEquals(1, asA.syncedCount)
        assertEquals(
            listOf("""[{"title":"B's note"}]""", """[{"title":"A's note"}]"""),
            server.requests.drop(requestsBefore).map { it.body },
        )
    }

    @Test
    fun a_queue_written_before_owners_were_recorded_is_adopted_by_the_first_identity_to_replay() =
        runTest {
            // Given — a pending write with no recorded owner, as an older build leaves it
            store.enqueueInsert(table = TABLE, body = rows("""[{"title":"legacy"}]"""))

            // When — A's pass sees it but cannot reach the server; then B and A sync online
            server.answer = { connectivityFailure() }
            syncer.sync()
            server.answer = { okJson("[]") }
            session = USER_B
            val asB = syncer.sync()
            session = USER_A
            val asA = syncer.sync()

            // Then — it became A's at A's first pass: B never sent it, A did
            assertEquals(0, asB.syncedCount)
            assertEquals(1, asA.syncedCount)
            assertEquals("""[{"title":"legacy"}]""", server.requests.last().body)
        }

    @Test
    fun a_pass_whose_identity_cannot_be_told_sends_nothing() = runTest {
        // Given
        writeOffline("A's note")
        session = null
        server.answer = { okJson("[]") }
        val requestsBefore = server.requests.size

        // When
        val report = syncer.sync()

        // Then
        assertEquals(requestsBefore, server.requests.size)
        assertEquals(SyncStop.IdentityUnknown, report.stop)
    }

    @Test
    fun a_rejection_cannot_be_retried_by_another_user() = runTest {
        // Given — user A's write was rejected
        writeOffline("A's note")
        server.answer = { okJson("[]") }
        val id = store.allPendingMutations().single().id
        store.rejectMutation(store.allPendingMutations().single(), "refused", serverError = null)

        // When / Then
        assertEquals(null, store.retryRejectedMutation(id, USER_B.storedValue))
        assertTrue(store.retryRejectedMutation(id, USER_A.storedValue) != null)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}

package io.github.nesfeder.supabase.offline.internal.db

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.internal.outbox.SyncStop
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The queue records the identity that queued into it, and a pass made under another identity sends
 * nothing — so an app that switches accounts without clearing local data never replays one user's
 * offline writes as another user.
 */
class QueueOwnershipTest {

    private val objectDao = FakeObjectDao()
    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(objectDao = objectDao, mutationDao = mutationDao)
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity? = USER_A
    private val syncer = OfflineMutationSyncer(
        store = store,
        supabase = server.supabaseClient(),
        currentIdentity = { session },
    )
    private val builder = OfflinePostgrestQueryBuilderImpl(
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

        // Then — nothing is sent, and the pass says why
        assertEquals(requestsBefore, server.requests.size)
        assertEquals(SyncStop.QueueOwnedByAnotherIdentity, report.stop)
        assertEquals(1, report.remainingCount)
        assertEquals(1, store.pendingMutationCount())
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
    fun another_user_cannot_queue_behind_a_pending_queue_that_is_not_theirs() = runTest {
        // Given — user A's write is pending
        writeOffline("A's note")

        // When — user B writes offline
        session = USER_B

        // Then — B's write is refused rather than attributed to A or sent as A
        assertFailsWith<HttpRequestException> { writeOffline("B's note") }
        assertEquals(listOf("""[{"title":"A's note"}]"""), store.allPendingMutations().map { it.bodyJson })
    }

    @Test
    fun a_drained_queue_passes_to_the_next_user() = runTest {
        // Given — user A's queue replayed completely
        writeOffline("A's note")
        server.answer = { okJson("[]") }
        syncer.sync()

        // When — user B writes offline and later syncs
        session = USER_B
        val outcome = writeOffline("B's note")
        server.answer = { okJson("[]") }
        val report = syncer.sync()

        // Then
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, report.syncedCount)
        assertEquals(USER_B.storedValue, store.outboxState.queueOwner())
    }

    @Test
    fun a_queue_written_before_owners_were_recorded_is_adopted() = runTest {
        // Given — a pending write with no recorded owner, as an older build leaves it
        store.enqueueInsert(table = TABLE, body = rows("""[{"title":"legacy"}]"""))

        // When
        val report = syncer.sync()

        // Then — it replays under the current session, as it always did
        assertEquals(1, report.syncedCount)
        assertEquals(USER_A.storedValue, store.outboxState.queueOwner())
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
        assertEquals(SyncStop.QueueOwnedByAnotherIdentity, report.stop)
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

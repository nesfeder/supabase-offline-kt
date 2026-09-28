package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.db.OfflineMutationSyncer
import io.github.nesfeder.supabase.offline.internal.outbox.QueueIdentity
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The queue is kept per identity: each write records who made it, a pass replays only the current
 * identity's writes, and another identity's writes neither block it nor are sent by it. They go out
 * when their identity is current again — the anonymous identity included, after signing out.
 */
class PerIdentityQueueTest {

    private val store = newTestOfflineStore()
    private val server = RecordingPostgrestServer()
    private var session: QueueIdentity = QueueIdentity.Anonymous
    private val builder = OfflinePostgrestQueryBuilderImpl(
        delegate = server.supabaseClient().postgrest.from(TABLE),
        store = store,
        queueIdentity = { session },
    )
    private val syncer = OfflineMutationSyncer(store = store, supabase = server.supabaseClient(), currentIdentity = { session })

    private suspend fun writeOffline(title: String): MutationOutcome {
        server.answer = { connectivityFailure() }
        return builder.insertJson(rows("""[{"title":"$title"}]"""))
    }

    private fun sentBodies(): List<String> = server.requests.filter { it.method == "POST" }.map { it.body }

    @Test
    fun a_signed_in_user_can_queue_while_anonymous_writes_are_held() = runTest {
        // Given — an anonymous write queued offline, then user A signs in and writes offline
        writeOffline("anonymous note")
        session = USER_A

        // When
        val outcome = writeOffline("A's note")

        // Then — A's write is queued, not refused because the anonymous one is pending
        assertEquals(MutationOutcome.Queued, outcome)
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
        val report = syncer.sync()

        // Then — only A's write went out; the anonymous one waits
        assertEquals(1, report.syncedCount)
        assertEquals(listOf("""[{"title":"A's note"}]"""), sentBodies().drop(sentBefore))
        assertEquals(listOf("""[{"title":"anonymous note"}]"""), store.allPendingMutations().map { it.bodyJson })
    }

    @Test
    fun anonymous_writes_replay_once_signed_out_again() = runTest {
        // Given — an anonymous write, held while A was signed in
        writeOffline("anonymous note")
        session = USER_A
        server.answer = { okJson("[]") }
        syncer.sync()

        // When — A signs out
        session = QueueIdentity.Anonymous
        val report = syncer.sync()

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
        val write = async { runCatching { builder.insertJson(rows("""[{"title":"A's note"}]""")) } }
        parked.await()

        // When — the app switches to user B (no clear), then A's request fails offline
        session = USER_B
        release.complete(Unit)
        write.await()
        server.answer = { okJson("[]") }
        val asB = syncer.sync()
        session = USER_A
        val asA = syncer.sync()

        // Then — it never goes out under B, and does under A
        assertEquals(0, asB.syncedCount)
        assertEquals(1, asA.syncedCount)
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
        val USER_A = QueueIdentity.User("user-a")
        val USER_B = QueueIdentity.User("user-b")
    }
}

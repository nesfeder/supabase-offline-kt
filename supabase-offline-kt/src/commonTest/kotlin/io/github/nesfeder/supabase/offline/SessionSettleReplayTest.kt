@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.auth
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * When the session settles into an identity that has pending mutations — signing in, or a restore
 * or refresh finishing — the engine starts a pass for them, however long settling took. It reads the
 * session status as a state that keeps only its latest value, so it must act on every new session it
 * sees, including a new session of the identity it saw last: a sign-out and sign-in that happen
 * before it looks again reach it as exactly that.
 */
class SessionSettleReplayTest {

    private val server = RecordingPostgrestServer()
    private val store = newTestOfflineStore()

    @Test
    fun a_users_queued_mutations_go_out_when_that_user_signs_in_again() = runTest {
        // Given — user A's edit queued offline, then A signs out; the network stays down until A is
        // back, so nothing but the sign-in can send it. The client's work runs on real threads.
        val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        val client = newTestOfflineClient(supabase = supabase, store = store, coordinatorScope = clientScope)
        supabase.signInForTest("user-a")
        server.answer = { connectivityFailure() }
        client.from(TABLE).updateJson(Json.parseToJsonElement("""{"title":"A's edit"}""")) { filter { eq("id", 1) } }
        supabase.auth.clearSession()

        // When — the network returns and A signs in again; nothing else asks for a pass
        server.answer = { okJson("[]") }
        supabase.signInForTest("user-a")
        val drained = withContext(Dispatchers.Default) {
            withTimeoutOrNull(DRAIN_TIMEOUT) { store.pendingMutationCountFlow().first { pending -> pending == 0 } }
        }
        clientScope.cancel()

        // Then — the settle started a pass that sent A's edit
        assertEquals(0, drained, "A's edit was never sent")
        assertEquals("""{"title":"A's edit"}""", server.requests.last { it.method == "PATCH" }.body)
    }

    @Test
    fun a_sign_out_and_sign_in_the_engine_did_not_see_happen_still_send_the_users_mutations() = runTest {
        // Given — the client's work runs on a scheduler only this test advances. The engine has seen
        // user A signed in with nothing queued; then A's edit is queued offline.
        val clientScheduler = TestCoroutineScheduler()
        val clientScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(clientScheduler))
        val supabase = newTestSupabaseClientWithAuth(server.engine)
        val client = newTestOfflineClient(supabase = supabase, store = store, coordinatorScope = clientScope)
        supabase.signInForTest("user-a")
        clientScheduler.runCurrent()
        server.answer = { connectivityFailure() }
        client.from(TABLE).updateJson(Json.parseToJsonElement("""{"title":"A's edit"}""")) { filter { eq("id", 1) } }

        // When — A signs out and back in before the engine looks again, as on a busy device: all it
        // can see is a new session for the same user
        supabase.auth.clearSession()
        server.answer = { okJson("[]") }
        supabase.signInForTest("user-a")
        val drained = runUntilDrained(clientScheduler)
        clientScope.cancel()

        // Then — that new session started a pass that sent A's edit
        assertEquals(0, drained, "A's edit was never sent")
        assertEquals("""{"title":"A's edit"}""", server.requests.last { it.method == "PATCH" }.body)
    }

    /**
     * Runs the client's work on [scheduler] as it arrives — work resumed from the HTTP engine's
     * threads included — until nothing is pending.
     *
     * @return `0` once the queue is empty, or `null` if it is not within [DRAIN_TIMEOUT].
     */
    private suspend fun runUntilDrained(scheduler: TestCoroutineScheduler): Int? = withContext(Dispatchers.Default) {
        withTimeoutOrNull(DRAIN_TIMEOUT) {
            while (store.pendingMutationCount() != 0) {
                scheduler.runCurrent()
                delay(5.milliseconds)
            }
            0
        }
    }

    private companion object {
        const val TABLE = "tasks"
        val DRAIN_TIMEOUT = 10.seconds
    }
}

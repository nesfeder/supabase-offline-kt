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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * When the session settles into an identity that has pending mutations — signing in, or a restore
 * or refresh finishing — the engine starts a pass for them, however long settling took.
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
            withTimeoutOrNull(10.seconds) { store.pendingMutationCountFlow().first { pending -> pending == 0 } }
        }
        clientScope.cancel()

        // Then — the settle started a pass that sent A's edit
        assertEquals(0, drained, "A's edit was never sent")
        assertEquals("""{"title":"A's edit"}""", server.requests.last { it.method == "PATCH" }.body)
    }

    private companion object {
        const val TABLE = "tasks"
    }
}

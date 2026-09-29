package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Passes the engine starts on its own are coalesced — at most one runs and one waits — and back off
 * after a pass that stopped on a failure, so a burst of writes queued behind a write the server keeps
 * refusing does not hammer the server with one request per write.
 */
class BackgroundPassCoalescingTest {

    @Test
    fun twenty_writes_behind_a_failing_write_do_not_start_twenty_passes() = runTest {
        // Given — a write the server answers 500 every time (SQLSTATE 54000), queued first
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val store = newTestOfflineStore()
        val client = newTestOfflineClient(supabase = server.supabaseClient(), store = store, coordinatorScope = backgroundScope)
        client.from(TABLE).updateJson(Json.parseToJsonElement("""{"title":"poison"}""")) { filter { eq("id", 0) } }
        server.answer = { request ->
            if (request.body.contains("poison")) postgrestError(HttpStatusCode.InternalServerError, code = "54000") else okJson("[]")
        }

        // When — twenty writes join the queue behind it
        repeat(20) { index ->
            client.from(TABLE).updateJson(Json.parseToJsonElement("""{"title":"w$index"}""")) { filter { eq("id", index + 1) } }
        }
        withContext(Dispatchers.Default) { delay(2_000) }

        // Then — a couple of attempts at the failing write, not twenty
        val poisonRequests = server.requests.count { it.body.contains("poison") } - 1
        assertTrue(poisonRequests <= 2, "the failing write was sent $poisonRequests more times")
    }

    private companion object {
        const val TABLE = "tasks"
    }
}

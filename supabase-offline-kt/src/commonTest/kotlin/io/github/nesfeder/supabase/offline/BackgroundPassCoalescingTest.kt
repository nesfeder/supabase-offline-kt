// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Passes the engine starts on its own are coalesced — at most one runs and one waits — and back off
 * after a pass that stopped on a failure, so a burst of writes queued behind a write the server
 * keeps refusing does not hammer the server with one request per write.
 *
 * The client's passes and the engine's requests run on the test scheduler as ordinary work, so
 * waiting until it is idle waits for every pass the burst started, and a count taken then is final.
 */
class BackgroundPassCoalescingTest {

    @Test
    fun twenty_writes_behind_a_failing_write_do_not_start_twenty_passes() = runTest {
        // Given — a write the server answers 500 every time (SQLSTATE 54000), queued first; the
        // client's passes and the server's answers all run on this test's scheduler
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchersSeen = mutableSetOf<ContinuationInterceptor?>()
        val server =
            RecordingPostgrestServer(
                answer = { connectivityFailure() },
                engineDispatcher = testDispatcher,
            )
        val store = newTestOfflineStore()
        val clientScope = CoroutineScope(SupervisorJob() + testDispatcher)
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                store = store,
                coordinatorScope = clientScope,
            )
        client.from(TABLE).update(Json.parseToJsonElement("""{"title":"poison"}""")) {
            filter { eq("id", 0) }
        }
        server.answer = { request ->
            dispatchersSeen += currentCoroutineContext()[ContinuationInterceptor]
            if (request.body.contains("poison"))
                postgrestError(HttpStatusCode.InternalServerError, code = "54000")
            else okJson("[]")
        }

        // When — twenty writes join the queue behind it, and every pass they start has run
        repeat(20) { index ->
            client.from(TABLE).update(Json.parseToJsonElement("""{"title":"w$index"}""")) {
                filter { eq("id", index + 1) }
            }
        }
        advanceUntilIdle()
        clientScope.cancel()

        // Then — the engine did retry the failing write, a couple of times at most, not twenty
        val retries = server.requests.count { it.body.contains("poison") } - 1
        assertTrue(retries in 1..2, "the failing write was retried $retries times")
        assertEquals(
            setOf<ContinuationInterceptor?>(testDispatcher),
            dispatchersSeen,
            "a request left the test scheduler",
        )
    }

    private companion object {
        const val TABLE = "tasks"
    }
}

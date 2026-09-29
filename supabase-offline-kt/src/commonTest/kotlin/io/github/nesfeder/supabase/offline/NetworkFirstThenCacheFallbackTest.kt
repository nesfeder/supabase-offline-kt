package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `FlowReadStrategy.NetworkFirstThenCache` falls back to a warm cache when the network is
 * unavailable, as the one-shot `NetworkFirst` read does, and at once, without waiting out
 * supabase-kt's retries. Cold, or on a server's own error, it still fails.
 */
class NetworkFirstThenCacheFallbackTest {

    /** What the server does with the next request: answer the list, fail the transport, or a status. */
    private var answer: () -> HttpStatusCode? = { HttpStatusCode.OK }

    private val server = RecordingPostgrestServer(
        answer = {
            when (val status = answer()) {
                null -> connectivityFailure()
                HttpStatusCode.OK -> okJson(ROWS)
                else -> postgrestError(status)
            }
        },
    )

    @Test
    fun with_a_warm_cache_and_no_network_it_shows_the_cache_at_once_and_stays_live() = runTest {
        // Given — upstream's default retries on the test's clock, the list cached, then no connection
        val onTheTestClock = RecordingPostgrestServer(
            answer = { if (answer() == null) connectivityFailure() else okJson(ROWS) },
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val items = newTestOfflineClient(supabase = newTestSupabaseClient(engine = onTheTestClock.engine), store = newTestOfflineStore(), coordinatorScope = idleCoroutineScope()).from(TABLE)
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        answer = { null }
        val sentBefore = onTheTestClock.requests.size
        val shown = mutableListOf<OfflineReadResult>()

        // When — the read starts, then a row is written while it is live
        var firstShownAt = -1L
        coroutineScope {
            val reader = launch {
                items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).collect { read ->
                    if (shown.isEmpty()) firstShownAt = currentTime
                    shown += read
                }
            }
            testScheduler.runCurrent()
            items.insertJson(Json.parseToJsonElement("""[{"id":2,"title":"b"}]""") as JsonArray)
            testScheduler.runCurrent()
            reader.cancel()
        }

        // Then — the cached list first, idle, from one attempt and no wait; then the write
        assertTrue(shown.isNotEmpty(), "the read showed nothing")
        assertEquals(ROWS, shown.first().result?.data)
        assertEquals(RefreshState.Idle, shown.first().refresh)
        val attempts = onTheTestClock.requests.count { request -> request.method == "GET" } - sentBefore
        assertTrue(firstShownAt == 0L && attempts == 1, "showed the cache at ${firstShownAt}ms after $attempts attempts")
        assertTrue(shown.last().result?.data.orEmpty().contains("\"id\":2"), "the write never showed: ${shown.last()}")
    }

    @Test
    fun with_a_cold_cache_and_no_network_it_fails() = runTest {
        // Given
        val items = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = idleCoroutineScope()).from(TABLE)
        answer = { null }

        // When
        val failure = runCatching { items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).first() }.exceptionOrNull()

        // Then
        assertTrue(failure?.isNetworkOrConnectivityFailure() == true, "gave $failure")
    }

    @Test
    fun with_a_warm_cache_a_servers_own_error_still_fails_it() = runTest {
        // Given — the list cached, then a server that refuses the read
        val items = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = idleCoroutineScope()).from(TABLE)
        items.select(readStrategy = ReadStrategy.NetworkOnly)
        answer = { HttpStatusCode.Forbidden }

        // When
        val failure = runCatching { items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).first() }.exceptionOrNull()

        // Then — only a transport failure falls back
        assertIs<PostgrestRestException>(failure)
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1,"title":"a"}]"""
    }
}

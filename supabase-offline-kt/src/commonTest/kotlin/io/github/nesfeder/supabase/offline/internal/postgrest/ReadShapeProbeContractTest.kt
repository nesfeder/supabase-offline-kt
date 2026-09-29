@file:OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.get
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the upstream behavior the read-shape check depends on: supabase-kt writes the shape a SELECT
 * asks for only into its method and its `Accept` and `Prefer` headers. Also pins that the check
 * sends nothing, and stops nothing but a PostgREST read made under a capture.
 */
class ReadShapeProbeContractTest {

    private val server = RecordingPostgrestServer()
    private val handle = PostgrestQueryBuilderWrapperImpl(server.supabaseClient().postgrest.from("todos"))

    @Test
    fun each_read_shape_is_told_without_sending_the_read() = runTest {
        // Given
        val reads: List<Pair<ReadShape, SelectRequestBuilder.() -> Unit>> = listOf(
            ReadShape.RowList to { filter { eq("id", 1) } },
            ReadShape.RowList to { filter { eq("id", 1) }; order("id", Order.ASCENDING); limit(5) },
            ReadShape.Uncacheable("single object") to { single() },
            ReadShape.Uncacheable("single object") to { maybeSingle() },
            ReadShape.Uncacheable("head") to { head = true },
            ReadShape.Uncacheable("count") to { count(Count.EXACT) },
            ReadShape.Uncacheable("another media type") to { csv() },
            ReadShape.Uncacheable("another media type") to { geojson() },
            ReadShape.Uncacheable("rows with nulls stripped") to { stripNulls() },
            ReadShape.Uncacheable("query plan") to { explain() },
        )

        // When
        val shapes = reads.map { (_, request) -> handle.shapeOf(Columns.ALL, request) }

        // Then — each told apart, and nothing reached the server
        assertEquals(reads.map { (expected, _) -> expected }, shapes)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_request_that_is_not_a_postgrest_read_passes_the_check() = runTest {
        // Given — a capture, and a request of another service in the same coroutine
        val supabase = server.supabaseClient()
        supabase.installReadShapeCheck()
        val capture = ReadRequestCapture()

        // When
        withContext(capture) { supabase.httpClient.httpClient.get("$TEST_SUPABASE_URL/auth/v1/user") }

        // Then — it reached the server, and the capture saw no read
        assertEquals(listOf("user"), server.requests.map { it.table })
        assertNull(capture.shape)
    }

    @Test
    fun a_postgrest_write_passes_the_check() = runTest {
        // Given
        val supabase = server.supabaseClient()
        supabase.installReadShapeCheck()
        val capture = ReadRequestCapture()

        // When
        withContext(capture) { supabase.postgrest.from("todos").update(Json.parseToJsonElement("""{"title":"x"}""")) { filter { eq("id", 1) } } }

        // Then — writes are not the check's business
        assertEquals(1, server.requests.size)
        assertNull(capture.shape)
    }

    @Test
    fun the_check_is_installed_once_however_many_callers_race_to_install_it() = runTest {
        // Given — a client nothing has installed the check on yet
        val supabase = server.supabaseClient()

        // When — many first uses at once, released together
        val start = CompletableDeferred<Unit>()
        val racers = (1..64).map {
            async(Dispatchers.Default) {
                start.await()
                supabase.installReadShapeCheck()
            }
        }
        start.complete(Unit)
        val installs = racers.awaitAll()

        // Then — exactly one of them put the interceptor on the pipeline
        assertEquals(1, installs.count { installed -> installed })
    }

    @Test
    fun an_apps_retry_plugin_does_not_retry_the_check() = runTest {
        // Given — an app that retries every failed request three times, 400 ms apart, and a list the
        // cache holds; the server answers on the test scheduler, so only a wait moves the test's clock
        val onTheTestClock = RecordingPostgrestServer(
            answer = { okJson("""[{"id":1}]""") },
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )
        val supabase = newTestSupabaseClient(engine = onTheTestClock.engine) {
            install(Postgrest) { maxRetries = 0 }
            httpConfig {
                install(HttpRequestRetry) {
                    retryOnException(maxRetries = 3, retryOnTimeout = true)
                    constantDelay(millis = 400)
                }
            }
        }
        val builder = OfflinePostgrestQueryBuilderImpl(delegate = supabase.postgrest.from("todos"), store = newTestOfflineStore())
        builder.select(readStrategy = ReadStrategy.NetworkOnly)

        // When — a read the cache answers, timed on the test's clock
        val startedAt = testScheduler.currentTime
        builder.select(readStrategy = ReadStrategy.CacheOnly())
        val waited = (testScheduler.currentTime - startedAt).milliseconds

        // Then — the check that stops before the network was never retried, and sent nothing
        assertTrue(waited < 400.milliseconds, "the read waited $waited for retries")
        assertEquals(1, onTheTestClock.requests.size)
    }
}

@file:OptIn(SupabaseInternal::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.request.post
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pins the upstream behaviour the dry-run probe depends on, so a supabase-kt change breaks this
 * test rather than silently letting a dry run into the queue: `dryRun()` is visible only as
 * `tx=rollback` in the `Prefer` header of the outgoing PostgREST request. Also pins that the probe
 * touches nothing but PostgREST writes — supabase-kt refreshes an expired session inline, in the
 * same coroutine, and that request must go through.
 */
class PreferHeaderProbeContractTest {

    private val server = RecordingPostgrestServer()
    private val handle = PostgrestQueryBuilderWrapperImpl(server.supabaseClient().postgrest.from("todos"))

    @Test
    fun a_dry_run_is_told_apart_from_a_write_without_sending_either() = runTest {
        // Given
        val body = Json.parseToJsonElement("""{"title":"x"}""")

        // When
        val dryRun = handle.classify { handle.update(body) { filter { eq("id", 1) }; dryRun() } }
        val write = handle.classify { handle.update(body) { filter { eq("id", 1) } } }

        // Then — both classified, and nothing reached the server
        assertEquals(DryRunVerdict.DryRun, dryRun)
        assertEquals(DryRunVerdict.NotDryRun, write)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_request_that_is_not_a_postgrest_write_passes_a_short_circuiting_probe() = runTest {
        // Given — a probe that stops PostgREST writes, and a session refresh in the same coroutine
        val supabase = server.supabaseClient()
        supabase.installDryRunCheck()
        val probe = PreferHeaderProbe()

        // When
        withContext(probe) { supabase.httpClient.httpClient.post("$TEST_SUPABASE_URL/auth/v1/token") }

        // Then — it reached the server, and the probe did not record it as a write
        assertEquals(listOf("token"), server.requests.map { it.table })
        assertFalse(probe.observed)
    }

    @Test
    fun the_probe_is_installed_once_however_many_callers_race_to_install_it() = runTest {
        // Given — a client nothing has installed the probe on yet
        val supabase = server.supabaseClient()

        // When — many first uses at once, released together
        val start = CompletableDeferred<Unit>()
        val racers = (1..64).map {
            async(Dispatchers.Default) {
                start.await()
                supabase.installDryRunCheck()
            }
        }
        start.complete(Unit)
        val installs = racers.awaitAll()

        // Then — exactly one of them put the interceptor on the pipeline
        assertEquals(1, installs.count { installed -> installed })
    }

    @Test
    fun a_postgrest_read_passes_a_short_circuiting_probe() = runTest {
        // Given
        val supabase = server.supabaseClient()
        supabase.installDryRunCheck()
        val probe = PreferHeaderProbe()

        // When
        withContext(probe) { supabase.postgrest.from("todos").select { filter { eq("id", 1) } } }

        // Then — reads are not the probe's business
        assertEquals(1, server.requests.size)
        assertFalse(probe.observed)
    }

    @Test
    fun an_apps_retry_plugin_does_not_retry_the_dry_run_check() = runTest {
        // Given — an app that retries every failed request three times, 400 ms apart; the server
        // answers on the test scheduler, so only a wait the write itself makes moves the test's clock
        val onTheTestClock = RecordingPostgrestServer(engineDispatcher = StandardTestDispatcher(testScheduler))
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

        // When — one QueueIfOffline write, timed on the test's clock, which a retry's wait would move
        val startedAt = testScheduler.currentTime
        builder.updateJson(Json.parseToJsonElement("""{"title":"x"}""")) { filter { eq("id", 1) } }
        val waited = (testScheduler.currentTime - startedAt).milliseconds

        // Then — the check that stops before the network was never retried
        assertTrue(waited < 400.milliseconds, "the write waited $waited for retries")
        assertEquals(1, onTheTestClock.requests.size)
    }
}

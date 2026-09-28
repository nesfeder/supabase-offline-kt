@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.ktor.client.request.post
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
        val dryRun = handle.requestsRollback { handle.update(body) { filter { eq("id", 1) }; dryRun() } }
        val write = handle.requestsRollback { handle.update(body) { filter { eq("id", 1) } } }

        // Then — both classified, and nothing reached the server
        assertEquals(true, dryRun)
        assertEquals(false, write)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun a_request_that_is_not_a_postgrest_write_passes_a_short_circuiting_probe() = runTest {
        // Given — a probe that stops PostgREST writes, and a session refresh in the same coroutine
        val supabase = server.supabaseClient()
        supabase.installPreferHeaderProbe()
        val probe = PreferHeaderProbe(shortCircuit = true)

        // When
        withContext(probe) { supabase.httpClient.httpClient.post("$TEST_SUPABASE_URL/auth/v1/token") }

        // Then — it reached the server, and the probe did not record it as a write
        assertEquals(listOf("token"), server.requests.map { it.table })
        assertFalse(probe.observed)
    }
}

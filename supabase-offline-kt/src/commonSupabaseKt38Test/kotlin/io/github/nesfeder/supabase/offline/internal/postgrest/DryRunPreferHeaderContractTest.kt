// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Pins the upstream behavior the dry-run probe depends on, so a supabase-kt change breaks this test
 * rather than silently letting a dry run into the queue: supabase-kt 3.8's `dryRun()` is visible
 * only as `tx=rollback` in the `Prefer` header of the outgoing PostgREST request.
 */
class DryRunPreferHeaderContractTest {

    private val server = RecordingPostgrestServer()
    private val handle = SupabaseRemoteTable(server.supabaseClient().postgrest.from("todos"))

    @Test
    fun a_dry_run_is_told_apart_from_a_write_without_sending_either() = runTest {
        // Given
        val body = Json.parseToJsonElement("""{"title":"x"}""")

        // When
        val dryRun = handle.classify {
            handle.update(body) {
                filter { eq("id", 1) }
                dryRun()
            }
        }
        val write = handle.classify { handle.update(body) { filter { eq("id", 1) } } }

        // Then — both classified, and nothing reached the server
        assertEquals(DryRunVerdict.DryRun, dryRun)
        assertEquals(DryRunVerdict.NotDryRun, write)
        assertTrue(server.requests.isEmpty())
    }
}

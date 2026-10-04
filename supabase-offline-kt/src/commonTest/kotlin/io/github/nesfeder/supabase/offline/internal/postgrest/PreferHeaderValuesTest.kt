// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class, SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.nesfeder.supabase.offline.MutationNotQueueableException
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The dry-run check reads every `Prefer` value of a write, not the first. supabase-kt sends its own
 * `Prefer` first and appends the request's own `headers` after it, so a `tx=rollback` an app adds
 * there is a second value; read as a write, it would be queued and replayed for real.
 */
class PreferHeaderValuesTest {

    private val server = RecordingPostgrestServer()

    @Test
    fun a_rollback_in_a_second_prefer_value_is_told() = runTest {
        // Given
        val supabase = server.supabaseClient()
        supabase.installDryRunCheck()
        val capture = PreferHeaderCapture()

        // When — a PostgREST write carrying two Prefer values, the rollback second
        runCatching {
            withContext(capture) {
                supabase.httpClient.httpClient.post("$TEST_SUPABASE_URL/rest/v1/todos") {
                    header(PREFER, "return=minimal")
                    header(PREFER, ROLLBACK)
                }
            }
        }

        // Then — stopped before the network, and read as a dry run
        assertTrue(capture.hasObservedWrite)
        assertTrue(capture.requestedRollback, "read the Prefer header as ${capture.prefer}")
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun a_rollback_comma_joined_in_one_prefer_value_is_told() = runTest {
        // Given
        val supabase = server.supabaseClient()
        supabase.installDryRunCheck()
        val capture = PreferHeaderCapture()

        // When — one Prefer value naming both
        runCatching {
            withContext(capture) {
                supabase.httpClient.httpClient.post("$TEST_SUPABASE_URL/rest/v1/todos") {
                    header(PREFER, "return=minimal,$ROLLBACK")
                }
            }
        }

        // Then
        assertTrue(capture.requestedRollback, "read the Prefer header as ${capture.prefer}")
        assertEquals(emptyList(), server.requests)
    }

    @Test
    fun a_write_whose_request_headers_ask_for_a_rollback_is_never_queued() = runTest {
        // Given — offline
        server.answer = { connectivityFailure() }
        val client = newTestOfflineClient(supabase = server.supabaseClient())

        // When — a write that adds the rollback through its request's own headers
        assertFailsWith<MutationNotQueueableException> {
            client.from(TABLE).update(Json.parseToJsonElement("""{"title":"x"}""")) {
                filter { eq("id", 1) }
                headers.append(PREFER, ROLLBACK)
            }
        }

        // Then — a dry run is never queued, so it cannot be replayed as a real write
        assertEquals(0, client.pendingMutationCount())
        client.close()
    }

    private companion object {
        const val TABLE = "todos"
        const val PREFER = "Prefer"
        const val ROLLBACK = "tx=rollback"
    }
}

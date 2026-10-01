// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Smoke coverage for the public test seam: the in-memory client must behave like the real offline
 * stack for the two things a consumer's tests lean on — serving a cached read once the network is
 * gone, and queueing a write made while offline.
 */
class InMemorySupabaseOfflineClientTest {

    /** Flipped by a test to take the scripted server offline. */
    private var online = true

    @Test
    fun a_row_read_online_is_served_from_memory_after_the_network_drops() = runTest {
        // Given — one read while online, which the client caches
        val client = buildInMemorySupabaseOfflineClient(scriptedSupabase(), backgroundScope)
        client.from(TABLE).select()

        // When — the same read after the network drops
        online = false
        val result = client.from(TABLE).select()

        // Then — the cached row comes back instead of the failure
        assertEquals(SERVER_ROWS, result.data)
    }

    @Test
    fun a_write_made_offline_is_queued_in_memory() = runTest {
        // Given — a client whose network is down
        online = false
        val client = buildInMemorySupabaseOfflineClient(scriptedSupabase(), backgroundScope)

        // When
        val outcome =
            client
                .from(TABLE)
                .insertJson(JsonArray(listOf(buildJsonObject { put("title", "Buy milk") })))

        // Then — the write is queued rather than lost
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(1, client.pendingMutationCount())
    }

    /**
     * A Supabase client whose PostgREST server answers every request with [SERVER_ROWS] while
     * [online], and fails at the transport level otherwise. Retries are off so a failure surfaces
     * on the first attempt.
     */
    private fun scriptedSupabase(): SupabaseClient =
        createSupabaseClient(
            supabaseUrl = "https://unit-test.supabase.co",
            supabaseKey = "unit-test-anon-key",
        ) {
            install(Postgrest) { maxRetries = 0 }
            httpEngine = MockEngine {
                if (!online) throw IOException("network down")
                respond(
                    content = SERVER_ROWS,
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            }
        }

    private companion object {
        const val TABLE = "todos"
        const val SERVER_ROWS = """[{"id":1,"title":"Buy milk"}]"""
    }
}

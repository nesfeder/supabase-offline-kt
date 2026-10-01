// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * A captured filter value is stored exactly as the caller wrote it, so a queued write replays
 * against the rows it was written for. Trimming whitespace or stripping quotes made `eq("code",
 * "'X'")` replay as `eq.X` and `eq("name", " Bob")` as `eq.Bob` — a different row set.
 */
class FilterCaptureFidelityTest {

    @Test
    fun strict_capture_keeps_quotes_and_surrounding_whitespace() {
        // Given
        val request: PostgrestRequestBuilder.() -> Unit = {
            filter {
                eq("code", "'X'")
                eq("name", " Bob ")
                eq("label", "\"quoted\"")
            }
        }

        // When
        val descriptor = assertNotNull(captureFilterExactly(SCHEMA, CONVERSION, request))

        // Then
        assertEquals(
            listOf("'X'", " Bob ", "\"quoted\""),
            descriptor.conditions.map { it.value },
        )
    }

    @Test
    fun lenient_capture_keeps_quotes_and_surrounding_whitespace() {
        // Given
        val builder =
            SelectRequestBuilder(SCHEMA, CONVERSION).apply {
                filter {
                    eq("code", "'X'")
                    eq("name", " Bob ")
                }
            }

        // When
        val descriptor = assertNotNull(captureFilter(builder))

        // Then
        assertEquals(listOf("'X'", " Bob "), descriptor.conditions.map { it.value })
    }

    @Test
    fun a_queued_update_replays_the_filter_values_it_was_written_with() = runTest {
        // Given — the write is queued while offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val store = newTestOfflineStore()
        val builder =
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )
        builder.update(
            Json.parseToJsonElement("""{"done":true}"""),
            MutationStrategy.QueueIfOffline,
        ) {
            filter {
                eq("code", "'X'")
                eq("name", " Bob")
            }
        }
        val online = server.requests.single()

        // When — the connection returns and the queue replays
        server.answer = { okJson("[]") }
        MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — the replay carries the very filter the original request did
        val replay = server.requests.last()
        assertEquals("PATCH", replay.method)
        assertEquals(online.query, replay.query)
        assertEquals(mapOf("code" to "eq.'X'", "name" to "eq. Bob"), replay.query)
    }

    private companion object {
        const val TABLE = "todos"
        const val SCHEMA = "public"
        val CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE
    }
}

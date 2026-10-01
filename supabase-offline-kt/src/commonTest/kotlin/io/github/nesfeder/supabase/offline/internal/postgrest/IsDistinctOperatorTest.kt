// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseExperimental::class)

package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.annotations.SupabaseExperimental
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.MutationOutcome
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.internal.db.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `FilterOperator.ISDISTINCT` arrived in supabase-kt 3.8.0. The engine captures it and replays it
 * through supabase-kt's generic `filter(column, operator, value)` call, so a write filtered on it
 * queues offline and replays with exactly the filter the caller wrote. What the engine does not do
 * is evaluate it locally: the overlay treats it as "cannot evaluate" (never a match), and a cached
 * query filtered on it is not treated as exhaustive.
 *
 * These cases were inverted when replay support landed; before it, the operator was refused for
 * queueing and skipped everywhere.
 */
class IsDistinctOperatorTest {

    private fun builderFilteredOnIsDistinct(): SelectRequestBuilder =
        SelectRequestBuilder(SCHEMA, CONVERSION).apply {
            filter { filter("status", FilterOperator.ISDISTINCT, "done") }
        }

    @Test
    fun strict_capture_captures_an_isdistinct_filter() {
        // Given
        val request: PostgrestRequestBuilder.() -> Unit = {
            filter { filter("status", FilterOperator.ISDISTINCT, "done") }
        }

        // When
        val descriptor = captureFilterExactly(SCHEMA, CONVERSION, request)

        // Then
        assertEquals(listOf(ISDISTINCT_DONE), descriptor?.conditions)
    }

    @Test
    fun lenient_capture_captures_an_isdistinct_condition() {
        // Given
        val builder = builderFilteredOnIsDistinct()

        // When
        val descriptor = captureFilter(builder)

        // Then
        assertEquals(listOf(ISDISTINCT_DONE), descriptor?.conditions)
    }

    @Test
    fun a_stored_isdistinct_condition_replays_but_is_never_matched_locally() {
        // Given — a condition as it would be stored under its identifier
        val descriptor = FilterDescriptor(conditions = listOf(ISDISTINCT_DONE))
        val row = buildJsonObject { put("status", "open") }
        val replayed = SelectRequestBuilder(SCHEMA, CONVERSION)

        // When
        val match = rowMatch(row, descriptor)
        val applied = FilterEngine.replayOnto(replayed, descriptor)

        // Then — replayed through the generic filter call, exactly as the caller wrote it
        assertEquals(FilterOperator.ISDISTINCT, ISDISTINCT_DONE.operator)
        assertEquals(1, applied)
        assertEquals(mapOf("status" to listOf("isdistinct.done")), replayed.params)
        assertTrue(FilterEngine.replaysFully(descriptor))
        // And — the overlay does not evaluate it, so it never claims a match
        assertEquals(RowMatch.UNKNOWN, match)
    }

    @Test
    fun a_cached_query_filtered_on_isdistinct_admits_any_row() {
        // Given / When
        val builder =
            SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply {
                filter { filter("status", FilterOperator.ISDISTINCT, "done") }
            }
        val admission = queryShapeOf(Columns.ALL, builder).admission

        // Then — the condition is not evaluated, so it never hides a row
        assertEquals(
            RowMatch.UNKNOWN,
            rowMatch(buildJsonObject { put("status", "done") }, admission.conditions.single()),
        )
        assertTrue(admission.admits(buildJsonObject { put("status", "done") }))
    }

    @Test
    fun a_write_filtered_on_isdistinct_is_queued_offline_and_replays_its_filter() = runTest {
        // Given — the network is down
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val mutationDao = FakeMutationDao()
        val store = newTestOfflineStore(mutationDao = mutationDao)
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = server.supabaseClient().postgrest.from("todos"),
                store = store,
            )

        // When — the write is made offline, then the queue replays online
        val outcome =
            builder.updateJson(
                Json.parseToJsonElement("""{"done":true}"""),
                MutationStrategy.QueueIfOffline,
            ) {
                filter { filter("status", FilterOperator.ISDISTINCT, "done") }
            }
        server.answer = { okJson("[]") }
        val synced =
            MutationQueueReplayer(store = store, supabase = server.supabaseClient()).syncAllTables()

        // Then — it was queued, and the replay carries the caller's own filter
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(1, synced)
        val replay = server.requests.last()
        assertEquals("PATCH", replay.method)
        assertEquals(mapOf("status" to "isdistinct.done"), replay.query)
    }

    private companion object {
        const val SCHEMA = "public"
        val CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE
        val ISDISTINCT_DONE =
            FilterDescriptor.Condition(column = "status", operatorId = "isdistinct", value = "done")
    }
}

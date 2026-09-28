package io.github.nesfeder.supabase.offline.internal.postgrest

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.MutationStrategy
import io.github.nesfeder.supabase.offline.OfflinePostgrestQueryBuilderImpl
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `FilterOperator.ISDISTINCT` arrived in supabase-kt 3.8.0. Before that release its identifier,
 * `isdistinct`, was not an operator the engine knew, and the engine has no offline semantics for
 * it, so it stays unrecognised everywhere: strict capture refuses to queue a write filtered on it,
 * lenient capture skips it, the overlay never matches it, replay never applies it, and a cached
 * query filtered on it is not treated as exhaustive. These cases pin that.
 */
class IsDistinctOperatorTest {

    private fun builderFilteredOnIsDistinct(): SelectRequestBuilder =
        SelectRequestBuilder(SCHEMA, CONVERSION).apply {
            filter { filter("status", FilterOperator.ISDISTINCT, "done") }
        }

    @Test
    fun strict_capture_refuses_an_isdistinct_filter() {
        // Given
        val request: PostgrestRequestBuilder.() -> Unit = { filter { filter("status", FilterOperator.ISDISTINCT, "done") } }

        // When / Then
        assertFailsWith<UnsupportedOfflineFilterException> {
            buildDescriptorStrictFromRequest(SCHEMA, CONVERSION, request)
        }
    }

    @Test
    fun lenient_capture_skips_an_isdistinct_condition() {
        // Given
        val builder = builderFilteredOnIsDistinct()

        // When
        val descriptor = buildDescriptorFromBuilder(builder)

        // Then — no condition survives, so there is no descriptor
        assertNull(descriptor)
    }

    @Test
    fun a_stored_isdistinct_condition_is_never_matched_and_never_replayed() {
        // Given — a condition as it would be stored under its identifier
        val condition = FilterDescriptor.Condition(column = "status", operatorId = "isdistinct", value = "done")
        val descriptor = FilterDescriptor(conditions = listOf(condition))
        val row = buildJsonObject { put("status", "open") }

        // When
        val matches = FilterEngine.matchesRow(row, descriptor)
        val applied = FilterEngine.applyTo(SelectRequestBuilder(SCHEMA, CONVERSION), descriptor)

        // Then
        assertNull(condition.operator)
        assertFalse(matches)
        assertEquals(0, applied)
        assertFalse(FilterEngine.replaysFully(descriptor))
    }

    @Test
    fun a_cached_query_filtered_on_isdistinct_is_not_exhaustive() {
        // Given / When
        val filters = parseSignatureFilters("todos?status=isdistinct.done")

        // Then — the admit gate falls back to appending rather than hiding a row
        assertFalse(filters?.exhaustive ?: true)
        assertNull(filters?.descriptor)
    }

    @Test
    fun a_write_filtered_on_isdistinct_is_refused_for_queueing_when_offline() = runTest {
        // Given — the network is down
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val mutationDao = FakeMutationDao()
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from("todos"),
            store = newTestOfflineStore(mutationDao = mutationDao),
        )

        // When / Then — the original failure is rethrown and nothing is queued
        assertFailsWith<HttpRequestException> {
            builder.updateJson(Json.parseToJsonElement("""{"done":true}"""), MutationStrategy.QueueIfOffline) {
                filter { filter("status", FilterOperator.ISDISTINCT, "done") }
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    private companion object {
        const val SCHEMA = "public"
        val CONVERSION = PropertyConversionMethod.CAMEL_CASE_TO_SNAKE_CASE
    }
}

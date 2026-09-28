package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Nothing that can identify a person reaches a log at INFO or above: no filter value, row payload,
 * object path or token. Apps forward those levels to crash reporters. Table names, operators,
 * counts and durations are fine; the values stay at DEBUG.
 *
 * Every refusal to queue an offline write is driven through the client, the way an app reaches
 * it, for each filter shape the queue cannot capture: a negated operator, a logical group and a
 * referenced-table column. Each log at INFO and above is searched for the filter value.
 */
class LogPrivacyTest {

    /** The filter shapes the offline queue refuses, each carrying [EMAIL]. */
    private val refusedFilters: Map<String, PostgrestFilterBuilder.() -> Unit> = mapOf(
        "negated operator" to { filterNot("email", FilterOperator.EQ, EMAIL) },
        "logical group" to {
            or {
                eq("email", EMAIL)
                eq("id", 7)
            }
        },
        "referenced-table column" to { eq("profile.email", EMAIL) },
    )

    /** Runs [write] offline through a fresh client and returns every message logged at INFO or above. */
    private suspend fun visibleLogsOfARefusal(write: suspend (OfflinePostgrestQueryBuilder) -> Unit): List<String> {
        val logger = RecordingOfflineLogger()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client = newTestOfflineClient(supabase = server.supabaseClient(), sdkLogger = logger.asSdkLogger())
        assertFailsWith<Exception> { write(client.from("profiles")) }
        return logger.entries.filter { it.level != OfflineLogLevel.DEBUG }.map { it.message }
    }

    @Test
    fun a_refused_offline_update_never_logs_its_filter_value() = runTest {
        refusedFilters.forEach { (shape, refusedFilter) ->
            // Given / When — offline, with a filter the queue cannot capture exactly
            val visible = visibleLogsOfARefusal { builder ->
                builder.updateJson(buildJsonObject { put("display_name", "Jane") }) { filter(refusedFilter) }
            }

            // Then — the refusal is logged, without the value
            assertTrue(visible.isNotEmpty(), "expected the refusal ($shape) to be logged")
            assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log ($shape): $visible")
        }
    }

    @Test
    fun a_refused_offline_delete_never_logs_its_filter_value() = runTest {
        refusedFilters.forEach { (shape, refusedFilter) ->
            // Given / When
            val visible = visibleLogsOfARefusal { builder -> builder.delete { filter(refusedFilter) } }

            // Then
            assertTrue(visible.isNotEmpty(), "expected the refusal ($shape) to be logged")
            assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log ($shape): $visible")
        }
    }

    private companion object {
        const val EMAIL = "jane.doe@example.com"
    }
}

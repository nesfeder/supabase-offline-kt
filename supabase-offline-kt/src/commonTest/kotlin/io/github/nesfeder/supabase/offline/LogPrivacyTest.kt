package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
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
 */
class LogPrivacyTest {

    private val logger = RecordingOfflineLogger()

    private fun visibleMessages(): List<String> =
        logger.entries.filter { it.level != OfflineLogLevel.DEBUG }.map { it.message }

    @Test
    fun a_refused_offline_update_never_logs_its_filter_value() = runTest {
        // Given — offline, with a filter the queue cannot capture exactly
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from("profiles"),
            store = newTestOfflineStore(),
            sdkLogger = logger.asSdkLogger(),
        )

        // When
        assertFailsWith<Exception> {
            builder.updateJson(buildJsonObject { put("display_name", "Jane") }) {
                filter { filterNot("email", FilterOperator.EQ, EMAIL) }
            }
        }

        // Then — the refusal is logged, without the value
        val visible = visibleMessages()
        assertTrue(visible.isNotEmpty(), "expected the refusal to be logged")
        assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log: $visible")
    }

    @Test
    fun a_refused_offline_delete_never_logs_its_filter_value() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val builder = OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from("profiles"),
            store = newTestOfflineStore(),
            sdkLogger = logger.asSdkLogger(),
        )

        // When
        assertFailsWith<Exception> {
            builder.delete { filter { filterNot("email", FilterOperator.EQ, EMAIL) } }
        }

        // Then
        val visible = visibleMessages()
        assertTrue(visible.isNotEmpty(), "expected the refusal to be logged")
        assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log: $visible")
    }

    private companion object {
        const val EMAIL = "jane.doe@example.com"
    }
}

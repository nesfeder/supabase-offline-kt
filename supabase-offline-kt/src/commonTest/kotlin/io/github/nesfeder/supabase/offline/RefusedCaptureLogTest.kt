// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * A write whose filter the mutation queue cannot record exactly is sent directly, and fails
 * offline. The refusal is logged at ERROR without the filter's values — which can be personal data
 * — and the detail, values included, only at DEBUG.
 */
class RefusedCaptureLogTest {

    private val logger = RecordingOfflineLogger()
    private val server = RecordingPostgrestServer(answer = { connectivityFailure() })
    private val builder =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = newTestOfflineStore(),
            engineLogger = logger.asEngineLogger(),
        )

    @Test
    fun a_refused_offline_update_logs_no_filter_value_at_info_or_above() = runTest {
        // When
        assertFailsWith<Exception> {
            builder.updateJson(Json.parseToJsonElement("""{"title":"x"}""")) {
                filter { filterNot("email", FilterOperator.EQ, EMAIL) }
            }
        }

        // Then
        val visible = logger.entries.filter { it.level >= OfflineLogLevel.INFO }.map { it.message }
        assertTrue(visible.any { "cannot be captured exactly" in it }, "got $visible")
        assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log: $visible")
        assertTrue(logger.entriesAt(OfflineLogLevel.DEBUG).any { EMAIL in it.message })
    }

    @Test
    fun a_refused_offline_delete_logs_no_filter_value_at_info_or_above() = runTest {
        // When
        assertFailsWith<Exception> {
            builder.delete { filter { filterNot("email", FilterOperator.EQ, EMAIL) } }
        }

        // Then
        val visible = logger.entries.filter { it.level >= OfflineLogLevel.INFO }.map { it.message }
        assertTrue(visible.none { EMAIL in it }, "a filter value reached a visible log: $visible")
    }

    private companion object {
        const val TABLE = "profiles"
        const val EMAIL = "jane.doe@example.com"
    }
}

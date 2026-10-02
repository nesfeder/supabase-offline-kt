// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nothing that can identify a person reaches a log at INFO or above: no filter value, row payload,
 * object path or token. Apps forward those levels to crash reporters. Table names, operators,
 * counts and durations are fine; the values stay at DEBUG.
 *
 * Every refusal to queue an offline write is driven through the client, the way an app reaches it:
 * each filter shape the queue cannot capture or replay in full, a write with no filter, a dry run,
 * and a replay the server rejects or fails with a message quoting the value. Each log at INFO and
 * above is searched for the value.
 */
class LogPrivacyTest {

    /** The filter shapes the offline queue refuses, each carrying [EMAIL]. */
    private val refusedFilters: Map<String, PostgrestFilterBuilder.() -> Unit> =
        mapOf(
            "negated operator" to { filterNot("email", FilterOperator.EQ, EMAIL) },
            "logical group" to
                {
                    or {
                        eq("email", EMAIL)
                        eq("id", 7)
                    }
                },
            "referenced-table column" to { eq("profile.email", EMAIL) },
            "range bound with a comma" to { sl("window", "$EMAIL,a" to "b") },
        )

    /**
     * Runs [write] offline through a fresh client and returns every message logged at INFO or
     * above.
     */
    private suspend fun visibleLogsOfARefusal(
        write: suspend (OfflinePostgrestQueryBuilder) -> Unit
    ): List<String> = visibleLogsOf { client, _ ->
        assertFailsWith<Exception> { write(client.from("profiles")) }
    }

    /**
     * Runs [scenario] against a fresh client on an offline server and returns its INFO-and-above
     * messages.
     */
    private suspend fun visibleLogsOf(
        scenario: suspend (Offline, RecordingPostgrestServer) -> Unit
    ): List<String> {
        val logger = RecordingLogSink()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                engineLogger = logger.asEngineLogger(),
            )
        scenario(client, server)
        return logger.entries.filter { it.level != EngineLogLevel.DEBUG }.map { it.message }
    }

    /** Queues an INSERT carrying [EMAIL] offline, then replays it against [serverAnswer]. */
    private suspend fun visibleLogsOfAReplay(
        serverAnswer: suspend MockRequestHandleScope.() -> HttpResponseData
    ): List<String> = visibleLogsOf { client, server ->
        client
            .from("profiles")
            .insert(buildJsonArray { add(buildJsonObject { put("email", EMAIL) }) })
        server.answer = { serverAnswer() }
        client.syncPendingMutations().syncedCount
    }

    @Test
    fun a_refused_write_without_a_filter_never_logs_its_body() = runTest {
        // Given / When — offline, an UPDATE with no filter, which would reach every row
        val visible = visibleLogsOfARefusal { builder ->
            builder.update(buildJsonObject { put("email", EMAIL) }) {}
        }

        // Then
        assertTrue(visible.isNotEmpty(), "expected the refusal to be logged")
        assertTrue(visible.none { EMAIL in it }, "a body value reached a visible log: $visible")
    }

    @Test
    fun an_offline_dry_run_never_logs_its_body() = runTest {
        // Given / When — a dry run is never queued; offline it fails
        val visible = visibleLogsOfARefusal { builder ->
            builder.insert(buildJsonArray { add(buildJsonObject { put("email", EMAIL) }) }) {
                dryRun()
            }
        }

        // Then
        assertTrue(visible.none { EMAIL in it }, "a body value reached a visible log: $visible")
    }

    @Test
    fun a_replay_the_server_rejects_never_logs_its_message_or_the_body() = runTest {
        // Given / When — the server refuses the queued INSERT, quoting the value, as a unique
        // violation does
        val visible = visibleLogsOfAReplay {
            postgrestError(
                HttpStatusCode.Conflict,
                code = "23505",
                message = "duplicate key value violates unique constraint for $EMAIL",
                details = "Key (email)=($EMAIL) already exists.",
            )
        }

        // Then — the rejection is logged, without the value
        assertTrue(
            visible.any { "rejected" in it },
            "expected the rejection to be logged: $visible",
        )
        assertTrue(visible.none { EMAIL in it }, "a value reached a visible log: $visible")
    }

    @Test
    fun a_replay_the_server_fails_never_logs_its_message() = runTest {
        // Given / When — a server error quoting the value, which keeps the mutation queued
        val visible = visibleLogsOfAReplay {
            postgrestError(
                HttpStatusCode.InternalServerError,
                code = "XX000",
                message = "failed on $EMAIL",
            )
        }

        // Then
        assertTrue(visible.none { EMAIL in it }, "a value reached a visible log: $visible")
    }

    @Test
    fun a_refused_offline_update_never_logs_its_filter_value() = runTest {
        refusedFilters.forEach { (shape, refusedFilter) ->
            // Given / When — offline, with a filter the queue cannot capture exactly
            val visible = visibleLogsOfARefusal { builder ->
                builder.update(buildJsonObject { put("display_name", "Jane") }) {
                    filter(refusedFilter)
                }
            }

            // Then — the refusal is logged, without the value
            assertTrue(visible.isNotEmpty(), "expected the refusal ($shape) to be logged")
            assertTrue(
                visible.none { EMAIL in it },
                "a filter value reached a visible log ($shape): $visible",
            )
        }
    }

    @Test
    fun a_refused_offline_delete_never_logs_its_filter_value() = runTest {
        refusedFilters.forEach { (shape, refusedFilter) ->
            // Given / When
            val visible = visibleLogsOfARefusal { builder ->
                builder.delete { filter(refusedFilter) }
            }

            // Then
            assertTrue(visible.isNotEmpty(), "expected the refusal ($shape) to be logged")
            assertTrue(
                visible.none { EMAIL in it },
                "a filter value reached a visible log ($shape): $visible",
            )
        }
    }

    private companion object {
        const val EMAIL = "jane.doe@example.com"
    }
}

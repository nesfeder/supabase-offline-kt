// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.headers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A write that asks PostgREST to roll its transaction back (`Prefer: tx=rollback`, what a dry run
 * sends) is committed by a server whose `db-tx-end` setting does not allow the override, as on
 * hosted projects. The library cannot stop that, but it says so: one warning per client, naming
 * nothing else. The header is set by hand here, as an app's own plugin would set it.
 */
class CommittedDryRunTest {

    private val logs = RecordingLogSink()

    @Test
    fun a_write_that_asked_for_a_rollback_and_was_committed_is_reported_once() = runTest {
        // Given — every write asks for a rollback, and the server commits them
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val client = clientAskingForRollback(server)

        // When
        client.from(TABLE).insert(row(1))
        client.from(TABLE).insert(row(2))

        // Then — one warning, for the client
        assertEquals(2, server.requests.count { it.method == "POST" })
        assertEquals(1, committedReports().size, "${logs.entries}")
        client.close()
    }

    @Test
    fun a_write_the_server_rolled_back_is_not_reported() = runTest {
        // Given — the server applies the rollback, and says so
        val server =
            RecordingPostgrestServer(
                answer = {
                    okJson("[]", extraHeaders = mapOf("Preference-Applied" to "tx=rollback"))
                }
            )
        val client = clientAskingForRollback(server)

        // When
        client.from(TABLE).insert(row(1))

        // Then
        assertEquals(emptyList(), committedReports())
        client.close()
    }

    @Test
    fun a_rollback_listed_in_a_second_preference_applied_value_is_not_reported() = runTest {
        // Given — the server applies the rollback, and lists it after another preference
        val server =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        content = "[]",
                        headers =
                            headers {
                                append(HttpHeaders.ContentType, "application/json")
                                append(PREFERENCE_APPLIED, "return=minimal")
                                append(PREFERENCE_APPLIED, ROLLBACK)
                            },
                    )
                }
            )
        val client = clientAskingForRollback(server)

        // When
        client.from(TABLE).insert(row(1))

        // Then
        assertEquals(emptyList(), committedReports())
        client.close()
    }

    @Test
    fun a_write_that_asked_for_no_rollback_is_not_reported() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                engineLogger = logs.asEngineLogger(),
            )

        // When
        client.from(TABLE).insert(row(1))

        // Then
        assertEquals(emptyList(), committedReports())
        client.close()
    }

    /**
     * A client over [server] whose every write carries `Prefer: tx=rollback` on its way to the
     * engine, set by an interceptor installed before the offline client's own.
     */
    @OptIn(SupabaseInternal::class)
    private fun clientAskingForRollback(server: RecordingPostgrestServer): OfflineClient {
        val supabase: SupabaseClient = server.supabaseClient()
        supabase.httpClient.httpClient.plugin(HttpSend).intercept { request ->
            if (request.method != HttpMethod.Get) request.headers.append(PREFER, ROLLBACK)
            execute(request)
        }
        return newTestOfflineClient(supabase = supabase, engineLogger = logs.asEngineLogger())
    }

    private fun committedReports(): List<RecordingLogSink.Entry> =
        logs.entries.filter { entry ->
            entry.level == EngineLogLevel.WARNING && "dry run was committed" in entry.message
        }

    private fun row(id: Int): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("id", id)
                    put("title", "t$id")
                }
            )
        )

    private companion object {
        const val TABLE = "tasks"
        const val PREFER = "Prefer"
        const val ROLLBACK = "tx=rollback"
        const val PREFERENCE_APPLIED = "Preference-Applied"
    }
}

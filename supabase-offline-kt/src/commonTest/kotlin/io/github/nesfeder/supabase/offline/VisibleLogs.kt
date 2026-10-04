// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.assertFailsWith

/**
 * Runs [write] offline through a fresh client, expecting it to fail, and returns every message
 * logged at INFO or above.
 */
internal suspend fun visibleLogsOfARefusal(
    write: suspend (OfflinePostgrestQueryBuilder) -> Unit
): List<String> = visibleLogsOf { client, _ ->
    assertFailsWith<Exception> { write(client.from("profiles")) }
}

/**
 * Runs [scenario] against a fresh client on an offline server and returns its INFO-and-above
 * messages.
 */
internal suspend fun visibleLogsOf(
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

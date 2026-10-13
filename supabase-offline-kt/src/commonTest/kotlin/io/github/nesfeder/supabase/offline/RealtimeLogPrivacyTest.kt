// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTableFilter
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Nothing of a change the feed reports, of a watched table's filter, or of a failure's text reaches
 * a log at INFO or above: a record the cache cannot place is logged without its values, and a
 * channel that ended is logged by its failure's type.
 */
class RealtimeLogPrivacyTest {

    @Test
    fun nothing_of_a_change_a_filter_or_a_failure_reaches_a_log_at_info_or_above() =
        runTest(timeout = 2.minutes) {
            // Given — a client whose logs are recorded, following a table with a filter
            val logs = RecordingLogSink()
            val feed = FakeServerChangeFeed()
            val server = RecordingPostgrestServer(answer = { okJson("[]") })
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    engineLogger = logs.asEngineLogger(),
                    watchedTables =
                        listOf(
                            WatchedTable(
                                "profiles",
                                filter = WatchedTableFilter("email", FilterOperator.EQ, EMAIL),
                            )
                        ),
                    serverChangeFeed = feed,
                )
            val follow = feed.awaitFollow(0)
            follow.join()

            // When — a change without its key, which is dropped, then a channel that ends
            // quoting a token
            follow.saved("profiles", buildJsonObject { put("email", EMAIL) })
            follow.fail(IllegalStateException("token $TOKEN rejected"))
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    while (logs.entries.none { entry -> "channel ended" in entry.message }) delay(
                        20
                    )
                }
            }
            client.close()

            // Then
            val visible =
                logs.entries
                    .filter { entry -> entry.level >= EngineLogLevel.INFO }
                    .map { it.message }
            assertTrue(
                visible.any { "dropping record" in it },
                "expected the drop logged: $visible",
            )
            assertTrue(visible.none { EMAIL in it }, "a value reached a visible log: $visible")
            assertTrue(visible.none { TOKEN in it }, "a token reached a visible log: $visible")
        }

    private companion object {
        const val EMAIL = "someone@example.com"
        const val TOKEN = "eyJ-secret-token"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.offline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `inMemory(realtime = fakeRealtime)` with `realtime { table(…) }`: the client joins the fake's
 * channel as it would Realtime's, the fake says what is followed, a reported change reaches the
 * cached queries, a report nothing follows is dropped, and a channel the fake ends is joined again.
 */
class FakeRealtimeTest {

    private val server = ScriptedServer()
    private val fakeRealtime = FakeRealtime()

    @Test
    fun the_client_joins_at_once_and_reported_changes_reach_the_cached_queries() =
        runTest(timeout = 2.minutes) {
            // Given — a client following the table, with one list of it cached
            val supabase = watching { realtime { table(TABLE) } }
            supabase.offline.from(TABLE).select()
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }
            supabase.offline.realtimeStatus.awaitValue { status ->
                status == RealtimeStatus.Subscribed
            }

            // When — a row is inserted, updated, then the first row deleted
            fakeRealtime.reportInsert(TABLE, row(2, "Tea"))
            fakeRealtime.reportUpdate(TABLE, row(2, "Tea, green"))
            fakeRealtime.reportDelete(TABLE, buildJsonObject { put("id", 1) })

            // Then — the cached list shows the updated row alone
            supabase.awaitCachedRead { data -> "Tea, green" in data && "Buy milk" !in data }
            assertEquals(0, fakeRealtime.droppedReportCount)
            supabase.close()
        }

    @Test
    fun a_table_of_another_schema_is_followed_under_its_qualified_name() =
        runTest(timeout = 2.minutes) {
            // Given
            val supabase = watching { realtime { table("audit", schema = "app") } }

            // When / Then — keyed as the configuration keys it, and a report names the schema
            fakeRealtime.subscribedTables.awaitValue { tables -> tables == setOf("app.audit") }
            fakeRealtime.reportInsert("audit", row(1, "x"))
            assertEquals(1, fakeRealtime.droppedReportCount)
            fakeRealtime.reportInsert("audit", row(1, "x"), schema = "app")
            assertEquals(1, fakeRealtime.droppedReportCount)
            supabase.close()
        }

    @Test
    fun a_report_nothing_follows_is_dropped() = runTest {
        // Given — a client that names no table, and so joins nothing
        val supabase = watching {}

        // When
        fakeRealtime.reportInsert(TABLE, row(2, "Tea"))

        // Then
        assertTrue(fakeRealtime.subscribedTables.value.isEmpty())
        assertEquals(1, fakeRealtime.droppedReportCount)
        assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
        supabase.close()
    }

    @Test
    fun a_failed_channel_stalls_the_status_and_is_joined_again() =
        runTest(timeout = 2.minutes) {
            // Given — joined
            val supabase = watching { realtime { table(TABLE) } }
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }

            // When — the server refuses the channel
            val cause = IllegalStateException("not in the publication")
            fakeRealtime.failChannel(cause)

            // Then — stalled with the cause, nothing followed, then joined anew
            val stalled =
                assertIs<RealtimeStatus.Stalled>(
                    supabase.offline.realtimeStatus.awaitValue { status ->
                        status is RealtimeStatus.Stalled
                    }
                )
            assertEquals(cause.message, stalled.cause.message)
            fakeRealtime.subscribedTables.awaitValue { tables -> tables.isEmpty() }
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }
            supabase.offline.realtimeStatus.awaitValue { status ->
                status == RealtimeStatus.Subscribed
            }
            supabase.close()
        }

    @Test
    fun a_dropped_channel_is_joined_again() =
        runTest(timeout = 2.minutes) {
            // Given — joined
            val supabase = watching { realtime { table(TABLE) } }
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }

            // When — the socket is lost
            fakeRealtime.dropChannel()

            // Then — stalled, then joined anew
            supabase.offline.realtimeStatus.awaitValue { status ->
                status is RealtimeStatus.Stalled
            }
            fakeRealtime.subscribedTables.awaitValue { tables -> tables.isEmpty() }
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }
            supabase.close()
        }

    @Test
    fun the_switch_leaves_the_channel_and_joins_it_again() =
        runTest(timeout = 2.minutes) {
            // Given — joined
            val supabase = watching { realtime { table(TABLE) } }
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }

            // When / Then
            supabase.offline.disableRealtime()
            fakeRealtime.subscribedTables.awaitValue { tables -> tables.isEmpty() }
            assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
            supabase.offline.enableRealtime()
            fakeRealtime.subscribedTables.awaitValue { tables -> TABLE in tables }
            supabase.close()
            assertEquals(RealtimeStatus.Off, supabase.offline.realtimeStatus.value)
        }

    /** A client of the scripted server, in memory, following what [configure] names. */
    private fun watching(configure: Offline.Config.() -> Unit): SupabaseClient =
        server.supabaseClient {
            inMemory(realtime = fakeRealtime)
            configure()
        }

    /** Waits, on real threads, for a value of this flow that satisfies [settled]. */
    private suspend fun <T> StateFlow<T>.awaitValue(settled: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { first(settled) } }

    /** Waits, on real threads, until a cache-only read of the table satisfies [settled]. */
    private suspend fun SupabaseClient.awaitCachedRead(settled: (String) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (true) {
                    val data =
                        offline.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data
                    if (settled(data)) return@withTimeout
                    delay(20)
                }
            }
        }
    }

    private fun row(id: Int, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
    }

    private companion object {
        const val TABLE = "todos"
    }
}

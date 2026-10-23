// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.internal.realtime.toRealtimeTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `realtimeTableStatus` says where each named table stands: a table the server refuses reads
 * stalled alone while the others stay subscribed and keep their changes, `realtimeStatus` sums them
 * up, and with no table named the map is empty.
 */
class RealtimeTableStatusTest {

    private val feed = FakeServerChangeFeed()

    @Test
    fun a_refused_table_reads_stalled_alone_and_the_others_keep_their_changes() =
        runTest(timeout = 2.minutes) {
            // Given — two tables followed, a list of each cached, and both channels joined
            val cache = FakeQueryCacheDao()
            cache.seedPayload(TASKS, "$TASKS?select=*", """[{"id":"t1"}]""")
            cache.seedPayload(NOTES, "$NOTES?select=*", """[{"id":"n1"}]""")
            val client =
                newTestOfflineClient(
                    supabase = RecordingPostgrestServer(answer = { okJson("[]") }).supabaseClient(),
                    queryCacheDao = cache,
                    watchedTables = listOf(WatchedTable(TASKS), WatchedTable(NOTES)),
                    serverChangeFeed = feed,
                )
            feed.awaitFollow(1)
            val tasks = feed.follows.value.single { it.tables == listOf(WatchedTable(TASKS)) }
            val notes = feed.follows.value.single { it.tables == listOf(WatchedTable(NOTES)) }
            assertEquals(
                listOf(TASKS_NAMED, NOTES_NAMED),
                client.realtimeTableStatus.value.keys.toList(),
            )
            tasks.join()
            notes.join()
            client.realtimeTableStatus.awaitValue { statuses ->
                statuses.values.all { status -> status == RealtimeStatus.Subscribed }
            }
            assertEquals(RealtimeStatus.Subscribed, client.realtimeStatus.value)

            // When — the server refuses the tasks binding
            tasks.fail(IllegalStateException("column renamed"))

            // Then — tasks alone is stalled, and the summed status says so
            val statuses =
                client.realtimeTableStatus.awaitValue { current ->
                    current[TASKS_NAMED] is RealtimeStatus.Stalled
                }
            assertEquals(RealtimeStatus.Subscribed, statuses[NOTES_NAMED])
            assertIs<RealtimeStatus.Stalled>(
                client.realtimeStatus.awaitValue { status -> status is RealtimeStatus.Stalled }
            )

            // And — a change of notes still reaches its cached list
            notes.saved(NOTES, buildJsonObject { put("id", "n2") })
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    cache.observe(NOTES, "$NOTES?select=*").first { row ->
                        row?.payload == """[{"id":"n1"},{"id":"n2"}]"""
                    }
                }
            }

            // When — closed
            client.close()

            // Then — every table is off
            assertTrue(client.realtimeTableStatus.value.values.all { it == RealtimeStatus.Off })
            assertEquals(RealtimeStatus.Off, client.realtimeStatus.value)
        }

    @Test
    fun with_no_table_named_the_map_is_empty() =
        runTest(timeout = 2.minutes) {
            // Given / When
            val client: OfflineClient = newTestOfflineClient()

            // Then
            assertEquals(emptyMap(), client.realtimeTableStatus.value)
            assertEquals(RealtimeStatus.Off, client.realtimeStatus.value)
            client.close()
        }

    /** Waits, on real threads, for a value of this flow that satisfies [settled]. */
    private suspend fun <T> StateFlow<T>.awaitValue(settled: (T) -> Boolean): T =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { first(settled) } }

    private companion object {
        const val TASKS = "tasks"
        const val NOTES = "notes"
        val TASKS_NAMED: RealtimeTable = WatchedTable(TASKS).toRealtimeTable()
        val NOTES_NAMED: RealtimeTable = WatchedTable(NOTES).toRealtimeTable()
    }
}

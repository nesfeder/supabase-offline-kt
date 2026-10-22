// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.onRealThreads
import io.github.nesfeder.supabase.offline.internal.db.LiveReadConsistency.seededServer
import io.github.nesfeder.supabase.offline.internal.db.RecordedLiveList
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Realtime replays nothing that happened while the channel was not joined, so each join, the first
 * included, has the watched table's live readers fetch again, once each, and marks its cached
 * one-shot queries stale so their next read asks the server. Other tables are left alone.
 */
class RealtimeReconnectRecoveryTest {

    private val feed = FakeServerChangeFeed()
    private val server = seededServer("a" to "Milk")
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            nowMillis = { Clock.System.now().toEpochMilliseconds() },
            watchedTables = listOf(WatchedTable(TABLE)),
            serverChangeFeed = feed,
        )

    @Test
    fun each_join_has_the_tables_live_readers_fetch_again_once() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a live list showing the server's row, then a row only the server has
                val list = RecordedLiveList(client, TABLE)
                list.await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                server.seed(TABLE, row("b", "Eggs"))
                val readsBefore = server.readsOf(TABLE)

                // When — the channel joins
                val first = feed.awaitFollow(0)
                first.join()

                // Then — the list asked the server once more, and shows both rows
                list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
                assertEquals(readsBefore + 1, server.readsOf(TABLE))

                // When — the channel ends and is joined again, another row only the server has
                server.seed(TABLE, row("c", "Bread"))
                first.fail(IllegalStateException("closed by the server"))
                val second = feed.awaitFollow(1)
                second.join()

                // Then — once more
                list.await { seen ->
                    seen.titles == listOf("Milk", "Eggs", "Bread") && !seen.isFromCache
                }
                assertEquals(readsBefore + 2, server.readsOf(TABLE))
                list.stop()
            }
            client.close()
        }

    @Test
    fun a_join_marks_a_cached_one_shot_query_stale_so_its_next_read_asks_the_server() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a one-shot query cached and served from the cache under a long maxAge
                client.from(TABLE).select(readStrategy = ReadStrategy.CacheFirst(maxAge = 1.days))
                val cached =
                    client
                        .from(TABLE)
                        .select(readStrategy = ReadStrategy.CacheFirst(maxAge = 1.days))
                assertTrue(cached.isFromCache)
                val readsBefore = server.readsOf(TABLE)
                server.seed(TABLE, row("b", "Eggs"))

                // When — the channel joins
                feed.awaitFollow(0).join()

                // Then — the next such read goes to the server, once the mark has landed
                withTimeout(10.seconds) {
                    while (true) {
                        val read =
                            client
                                .from(TABLE)
                                .select(readStrategy = ReadStrategy.CacheFirst(maxAge = 1.days))
                        if (!read.isFromCache) break
                        delay(20)
                    }
                }
                assertEquals(readsBefore + 1, server.readsOf(TABLE))
            }
            client.close()
        }

    @Test
    fun an_unwatched_table_and_a_cache_only_reader_are_left_alone() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a live list of another table, and a cache-only live list of the
                // watched one
                server.seed(OTHER, row("n1", "Note"))
                val other = RecordedLiveList(client, OTHER)
                other.await { seen -> seen.titles == listOf("Note") && !seen.isFromCache }
                client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
                val cacheOnlyReads = server.readsOf(TABLE)
                val otherReads = server.readsOf(OTHER)
                val cacheOnly = CacheOnlyList(client, TABLE)
                cacheOnly.await { titles -> titles == listOf("Milk") }

                // When — the channel joins, and a moment passes
                feed.awaitFollow(0).join()
                delay(300)

                // Then — neither asked the server
                assertEquals(otherReads, server.readsOf(OTHER))
                assertEquals(cacheOnlyReads, server.readsOf(TABLE))
                other.stop()
                cacheOnly.stop()
            }
            client.close()
        }

    @Test
    fun a_joins_re_read_keeps_asking_while_other_users_deletes_arrive_and_lands_when_they_stop() =
        runTest(timeout = 2.minutes) {
            onRealThreads {
                // Given — a live list showing the server's row, a row only the server has, and a
                // server that takes a while to answer
                val list = RecordedLiveList(client, TABLE)
                list.await { seen -> seen.titles == listOf("Milk") && !seen.isFromCache }
                server.seed(TABLE, row("b", "Eggs"))
                server.answerDelayMillis = 200
                val readsBefore = server.readsOf(TABLE)

                // When — the channel joins while other users' deletes arrive faster than the server
                // answers (Realtime sends every delete to every subscriber), each a change to the
                // table that an answer already in flight cannot describe
                val follow = feed.awaitFollow(0)
                val deletes = launch {
                    var n = 0
                    while (true) {
                        follow.deleted(TABLE, row("theirs-${n++}", "Theirs"))
                        delay(50)
                    }
                }
                follow.join()
                delay(2_500)

                // Then — every answer so far was refused, and the read kept asking instead of
                // settling on the rows it had
                val seenWhileDeleting = list.count
                assertTrue(server.readsOf(TABLE) >= readsBefore + 3, "the re-read kept asking")

                // When — the deletes stop
                deletes.cancelAndJoin()

                // Then — the next answer lands, and nothing settled before it
                list.await { seen -> seen.titles == listOf("Milk", "Eggs") && !seen.isFromCache }
                val seen = list.stop()
                assertTrue(
                    seen.take(seenWhileDeleting).none { it.titles?.contains("Eggs") == true },
                    "no answer landed while the deletes kept arriving",
                )
                // A stale mark shows as the cache for a moment before the loop reports Refreshing,
                // so what is pinned is where the read stood when the deletes stopped
                assertEquals(
                    RefreshState.Refreshing,
                    seen[seenWhileDeleting - 1].refresh,
                    "the read was still refreshing, not settled on the cache",
                )
            }
            client.close()
        }

    /** A cache-only live list of [table]: the titles of each emission, in order. */
    private class CacheOnlyList(client: Offline, table: String) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val recorded = MutableStateFlow<List<List<String>?>>(emptyList())
        private val job = scope.launch {
            client.from(table).selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly).collect {
                snapshot ->
                recorded.update { seen -> seen + snapshot.result?.data?.let(::titlesOf) }
            }
        }

        /** Waits until an emission's titles satisfy [settled]. */
        suspend fun await(settled: (List<String>?) -> Boolean) {
            withTimeout(10.seconds) { recorded.first { seen -> seen.any(settled) } }
        }

        suspend fun stop() {
            job.cancelAndJoin()
            scope.cancel()
        }

        private fun titlesOf(data: String): List<String> =
            Json.parseToJsonElement(data).jsonArray.mapNotNull { row ->
                row.jsonObject["title"]?.jsonPrimitive?.content
            }
    }

    private fun row(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
    }

    private fun FakeRelationalPostgrestServer.readsOf(table: String): Int =
        requests.count { request ->
            request.method == "GET" && request.table == table
        }

    private companion object {
        const val TABLE = "todos"
        const val OTHER = "notes"
    }
}

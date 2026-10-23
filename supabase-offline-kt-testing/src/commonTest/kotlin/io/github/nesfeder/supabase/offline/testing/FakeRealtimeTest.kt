// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RealtimeFilter
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.offline
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `inMemory(realtime = fakeRealtime)` with `realtime { table(…) }`: the client joins the fake's
 * channel as it would Realtime's, the fake says what is followed, a reported change reaches the
 * cached queries, a report nothing follows is dropped, and a channel the fake ends is joined again;
 * a table the fake fails stalls alone, while the other tables keep receiving.
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
    fun a_failed_table_stalls_alone_and_the_other_keeps_receiving() =
        runTest(timeout = 2.minutes) {
            // Given — two tables followed and joined, with one list of the first cached
            val supabase = watching {
                realtime {
                    table(TABLE)
                    table(OTHER)
                }
            }
            supabase.offline.from(TABLE).select()
            fakeRealtime.subscribedTables.awaitValue { tables ->
                TABLE in tables && OTHER in tables
            }
            supabase.offline.realtimeStatus.awaitValue { status ->
                status == RealtimeStatus.Subscribed
            }

            // When — the server refuses the other table's binding
            val cause = IllegalStateException("column renamed")
            fakeRealtime.failTable(OTHER, cause)

            // Then — that table alone is stalled, with the cause, and the summed status says so
            val statuses =
                supabase.offline.realtimeTableStatus.awaitValue { current ->
                    current.entries.any { (table, status) ->
                        table.table == OTHER && status is RealtimeStatus.Stalled
                    }
                }
            val stalled =
                assertIs<RealtimeStatus.Stalled>(
                    statuses.entries.single { it.key.table == OTHER }.value
                )
            assertEquals(cause.message, stalled.cause.message)
            assertEquals(
                RealtimeStatus.Subscribed,
                statuses.entries.single { it.key.table == TABLE }.value,
            )
            supabase.offline.realtimeStatus.awaitValue { status ->
                status is RealtimeStatus.Stalled
            }

            // And — the first table keeps receiving, and the refused one is joined again
            fakeRealtime.reportInsert(TABLE, row(2, "Tea"))
            supabase.awaitCachedRead { data -> "Tea" in data }
            supabase.offline.realtimeTableStatus.awaitValue { current ->
                current.values.all { status -> status == RealtimeStatus.Subscribed }
            }
            assertEquals(0, fakeRealtime.droppedReportCount)
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

    @Test
    fun the_join_count_moves_once_the_join_is_handled_and_a_live_reader_shows_each_change() =
        runTest(timeout = 2.minutes) {
            // Given — the server's rows, which the engine answers every read with, and a client
            // following the table with a live reader of it
            val serverRows = MutableStateFlow(listOf(row(1, "Buy milk")))
            val supabase =
                createSupabaseClient("https://unit-test.supabase.co", "unit-test-anon-key") {
                    httpEngine = MockEngine {
                        respond(
                            JsonArray(serverRows.value).toString(),
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }
                    install(Postgrest) { maxRetries = 0 }
                    install(Offline) {
                        inMemory(realtime = fakeRealtime)
                        realtime { table(TABLE) }
                    }
                }
            val shown = MutableStateFlow("")
            backgroundScope.launch(Dispatchers.Default) {
                supabase.offline.from(TABLE).selectAsFlow().collect { snapshot ->
                    snapshot.result?.let { result -> shown.value = result.data }
                }
            }

            // When — the client has handled the join
            fakeRealtime.joinCount.awaitValue { count -> count >= 1 }

            // Then — it reports the channel subscribed by then
            assertEquals(RealtimeStatus.Subscribed, supabase.offline.realtimeStatus.value)

            // When — a row is inserted on the server, and its change reported
            serverRows.value += row(2, "Tea")
            fakeRealtime.reportInsert(TABLE, row(2, "Tea"))

            // Then — the live reader shows it
            shown.awaitValue { data -> "Tea" in data && "Buy milk" in data }

            // When — the socket is lost, and the row renamed before the client joins again
            fakeRealtime.dropChannel()
            serverRows.value = listOf(row(1, "Buy milk"), row(2, "Green tea"))
            fakeRealtime.joinCount.awaitValue { count -> count >= 2 }

            // Then — the join's read brings the rename, which no change reported
            shown.awaitValue { data -> "Green tea" in data }
            assertEquals(0, fakeRealtime.droppedReportCount)
            supabase.close()
        }

    @Test
    fun a_filtered_table_hears_the_saved_rows_its_filter_admits_and_every_delete() =
        runTest(timeout = 2.minutes) {
            // Given — a client following one owner's rows, with a list of the table cached
            val supabase = watching {
                realtime { table(TABLE, filter = RealtimeFilter("owner", FilterOperator.EQ, "a")) }
            }
            supabase.offline.from(TABLE).select()
            fakeRealtime.joinCount.awaitValue { count -> count >= 1 }

            // When — another owner's row is inserted, one of the owner's inserted then moved to
            // another owner, and the cached row, which names no owner, deleted
            fakeRealtime.reportInsert(TABLE, owned(2, "Tea", owner = "b"))
            fakeRealtime.reportInsert(TABLE, owned(3, "Bread", owner = "a"))
            fakeRealtime.reportUpdate(TABLE, owned(3, "Bread, sold", owner = "b"))
            fakeRealtime.reportDelete(TABLE, buildJsonObject { put("id", 1) })

            // Then — the two the filter turns away were dropped and counted, the rest applied
            assertEquals(2, fakeRealtime.droppedReportCount)
            supabase.awaitCachedRead { data -> "Bread" in data && "Buy milk" !in data }
            val cached =
                supabase.offline.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())
            assertFalse("Tea" in cached.data, cached.data)
            assertFalse("sold" in cached.data, cached.data)
            supabase.close()
        }

    @Test
    fun each_filter_operator_admits_what_the_server_would_send() =
        runTest(timeout = 2.minutes) {
            // Given — for each operator, records it admits and records it turns away; numbers are
            // compared as numbers (10 is greater than 9, though "10" sorts before "9")
            val cases =
                listOf(
                    RealtimeFilter("n", FilterOperator.EQ, 5) to
                        listOf(number(5) to true, number(5.0) to true, number(6) to false),
                    RealtimeFilter("n", FilterOperator.NEQ, 5) to
                        listOf(number(4) to true, number(5) to false),
                    RealtimeFilter("n", FilterOperator.GT, 9) to
                        listOf(number(10) to true, number(9) to false),
                    RealtimeFilter("n", FilterOperator.GTE, 9) to
                        listOf(number(9) to true, number(8) to false),
                    RealtimeFilter("n", FilterOperator.LT, 3) to
                        listOf(number(2) to true, number(3) to false),
                    RealtimeFilter("n", FilterOperator.LTE, 3) to
                        listOf(number(3) to true, number(4) to false),
                    RealtimeFilter("status", FilterOperator.IN, listOf("open", "doing")) to
                        listOf(text("doing") to true, text("done") to false),
                    RealtimeFilter("status", FilterOperator.GT, "b") to
                        listOf(text("c") to true, text("a") to false),
                    RealtimeFilter("done", FilterOperator.EQ, false) to
                        listOf(
                            buildJsonObject { put("done", false) } to true,
                            buildJsonObject { put("done", true) } to false,
                            buildJsonObject { put("done", JsonNull) } to false,
                            buildJsonObject { put("id", 1) } to false,
                        ),
                )

            for ((filter, records) in cases) {
                // When — a client follows the table through that filter, and each is inserted
                val fake = FakeRealtime()
                val supabase = server.supabaseClient {
                    inMemory(realtime = fake)
                    realtime { table(TABLE, filter = filter) }
                }
                fake.joinCount.awaitValue { count -> count >= 1 }
                for ((record, admitted) in records) {
                    val droppedBefore = fake.droppedReportCount
                    fake.reportInsert(TABLE, record)

                    // Then — only what the filter turns away is dropped
                    val dropped = fake.droppedReportCount - droppedBefore
                    assertEquals(if (admitted) 0 else 1, dropped, "$filter on $record")
                }
                supabase.close()
            }
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

    private fun owned(id: Int, title: String, owner: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
        put("owner", owner)
    }

    private fun number(n: Number): JsonObject = buildJsonObject { put("n", n) }

    private fun text(status: String): JsonObject = buildJsonObject { put("status", status) }

    private companion object {
        const val TABLE = "todos"
        const val OTHER = "notes"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The changes a feed reports reach the cached queries through the same path as an app's
 * `applyServerRecord` and `applyServerDelete`, in the order they were sent, a table's waiting
 * changes as one patch, in the table's own schema; and an echo of the user's own write meets the
 * overlay as a replayed write's answer does.
 */
class RealtimeApplyOrderingTest {

    private val feed = FakeServerChangeFeed()

    @Test
    fun changes_reach_the_cached_queries_in_the_order_they_were_sent() =
        runTest(timeout = 2.minutes) {
            // Given — one cached list of the table, and the channel joined
            val cache = FakeQueryCacheDao()
            cache.seedPayload(TABLE, CACHE_KEY, """[{"id":"t1","title":"Milk"}]""")
            val client = newWatchingClient(cache, listOf(WatchedTable(TABLE)))
            val follow = feed.awaitFollow(0)
            follow.join()

            // When — an insert, an update of the new row, and a delete of the old one
            follow.saved(TABLE, row("t2", "Eggs"))
            follow.saved(TABLE, row("t2", "Eggs, large"))
            follow.deleted(TABLE, buildJsonObject { put("id", "t1") })

            // Then — the list holds the new row as last updated, and the old one is gone
            cache.awaitPayload(TABLE, """[{"id":"t2","title":"Eggs, large"}]""")
            client.close()
        }

    @Test
    fun a_batch_that_inserts_updates_and_deletes_one_row_ends_with_the_row_gone() =
        runTest(timeout = 2.minutes) {
            // Given — one cached list of the table, and the channel joined
            val cache = FakeQueryCacheDao()
            cache.seedPayload(TABLE, CACHE_KEY, ROWS)
            val client = newWatchingClient(cache, listOf(WatchedTable(TABLE)))
            val follow = feed.awaitFollow(0)
            follow.join()

            // When — a row is inserted, updated and deleted within one burst, and the old row is
            // then renamed, so the list shows when the burst has been applied
            follow.saved(TABLE, row("t2", "Eggs"))
            follow.saved(TABLE, row("t2", "Eggs, large"))
            follow.deleted(TABLE, buildJsonObject { put("id", "t2") })
            follow.saved(TABLE, row("t1", "Oat milk"))

            // Then — the old row renamed, and no trace of the new one
            cache.awaitPayload(TABLE, """[{"id":"t1","title":"Oat milk"}]""")
            client.close()
        }

    @Test
    fun a_batch_of_updates_to_two_rows_ends_with_both_updated() =
        runTest(timeout = 2.minutes) {
            // Given — two cached rows
            val cache = FakeQueryCacheDao()
            cache.seedPayload(
                TABLE,
                CACHE_KEY,
                """[{"id":"t1","title":"Milk"},{"id":"t2","title":"Eggs"}]""",
            )
            val client = newWatchingClient(cache, listOf(WatchedTable(TABLE)))
            val follow = feed.awaitFollow(0)
            follow.join()

            // When — both change within one burst
            follow.saved(TABLE, row("t1", "Oat milk"))
            follow.saved(TABLE, row("t2", "Duck eggs"))

            // Then
            cache.awaitPayload(
                TABLE,
                """[{"id":"t1","title":"Oat milk"},{"id":"t2","title":"Duck eggs"}]""",
            )
            client.close()
        }

    @Test
    fun a_table_of_another_schema_reaches_its_own_cached_queries() =
        runTest(timeout = 2.minutes) {
            // Given — the table cached under its schema-qualified name
            val cache = FakeQueryCacheDao()
            cache.seedPayload("app.audit", CACHE_KEY, """[{"id":"a1","what":"old"}]""")
            val client = newWatchingClient(cache, listOf(WatchedTable("audit", schema = "app")))
            val follow = feed.awaitFollow(0)
            follow.join()

            // When
            follow.saved(
                "audit",
                buildJsonObject {
                    put("id", "a1")
                    put("what", "new")
                },
                schema = "app",
            )

            // Then
            cache.awaitPayload("app.audit", """[{"id":"a1","what":"new"}]""")
            client.close()
        }

    @Test
    fun an_echo_of_a_write_still_queued_shows_the_write_until_it_is_sent() =
        runTest(timeout = 2.minutes) {
            // Given — the list cached, then an update the server keeps failing to take, so it
            // waits in the queue
            val server = RecordingPostgrestServer(answer = { okJson(ROWS) })
            val client = newWatchingClient(FakeQueryCacheDao(), listOf(WatchedTable(TABLE)), server)
            client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            server.answer = { request ->
                if (request.method == "PATCH") postgrestError(HttpStatusCode.ServiceUnavailable)
                else okJson(ROWS)
            }
            val outcome =
                client.from(TABLE).update(buildJsonObject { put("title", "Milk (mine)") }) {
                    filter { eq("id", "t1") }
                }
            assertIs<MutationOutcome.Queued>(outcome)

            // When — the server reports the row changed by someone else meanwhile
            val follow = feed.awaitFollow(0)
            follow.join()
            follow.saved(TABLE, row("t1", "Milk (theirs)"))

            // Then — the cached read still shows the queued write, laid over the server's row
            client.awaitCachedRead { data -> "Milk (mine)" in data && "theirs" !in data }

            // When — the server takes the write
            server.answer = { okJson("""[{"id":"t1","title":"Milk (mine)"}]""") }
            val report = client.syncPendingMutations()

            // Then — sent, and the list shows what was written
            assertEquals(1, report.syncedCount)
            client.awaitCachedRead { data -> "Milk (mine)" in data && "theirs" !in data }
            client.close()
        }

    @Test
    fun an_echo_of_a_write_already_applied_changes_nothing() =
        runTest(timeout = 2.minutes) {
            // Given — the list cached, and an update the server applied at once
            val server = RecordingPostgrestServer(answer = { okJson(ROWS) })
            val cache = FakeQueryCacheDao()
            val client = newWatchingClient(cache, listOf(WatchedTable(TABLE)), server)
            client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            server.answer = { request ->
                if (request.method == "PATCH") okJson("""[{"id":"t1","title":"Milk (mine)"}]""")
                else okJson(ROWS)
            }
            val outcome =
                client.from(TABLE).update(buildJsonObject { put("title", "Milk (mine)") }) {
                    filter { eq("id", "t1") }
                }
            assertIs<MutationOutcome.Applied>(outcome)
            client.awaitCachedRead { data -> "Milk (mine)" in data }
            val before = cache.rowsOf(TABLE).single().payload

            // When — the server echoes the same row
            val follow = feed.awaitFollow(0)
            follow.join()
            follow.saved(TABLE, row("t1", "Milk (mine)"))

            // Then — the same cached rows
            client.awaitCachedRead { data -> "Milk (mine)" in data }
            assertEquals(before, cache.rowsOf(TABLE).single().payload)
            client.close()
        }

    @Test
    fun a_delete_echo_of_a_queued_delete_leaves_the_row_gone() =
        runTest(timeout = 2.minutes) {
            // Given — the list cached, then a delete the server keeps failing to take
            val server = RecordingPostgrestServer(answer = { okJson(ROWS) })
            val client = newWatchingClient(FakeQueryCacheDao(), listOf(WatchedTable(TABLE)), server)
            client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            server.answer = { request ->
                if (request.method == "DELETE") postgrestError(HttpStatusCode.ServiceUnavailable)
                else okJson(ROWS)
            }
            val outcome = client.from(TABLE).delete { filter { eq("id", "t1") } }
            assertIs<MutationOutcome.Queued>(outcome)
            client.awaitCachedRead { data -> "t1" !in data }

            // When — the server reports the row deleted, and then takes the write
            val follow = feed.awaitFollow(0)
            follow.join()
            follow.deleted(TABLE, buildJsonObject { put("id", "t1") })
            server.answer = { okJson("[]") }
            val report = client.syncPendingMutations()

            // Then — gone throughout
            assertEquals(1, report.syncedCount)
            client.awaitCachedRead { data -> "t1" !in data }
            client.close()
        }

    private fun newWatchingClient(
        cache: FakeQueryCacheDao,
        tables: List<WatchedTable>,
        server: RecordingPostgrestServer = RecordingPostgrestServer(answer = { okJson(ROWS) }),
    ): OfflineClient =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = cache,
            watchedTables = tables,
            serverChangeFeed = feed,
        )

    /** Waits until the cached payload under [table] and [CACHE_KEY] reads [expected]. */
    private suspend fun FakeQueryCacheDao.awaitPayload(table: String, expected: String) {
        val payload =
            withContext(Dispatchers.Default) {
                withTimeout(10.seconds) {
                    observe(table, CACHE_KEY).first { row -> row?.payload == expected }
                }
            }
        assertEquals(expected, payload?.payload)
    }

    /** Waits until a cache-only read of the table satisfies [settled]. */
    private suspend fun OfflineClient.awaitCachedRead(settled: (String) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (true) {
                    val data = from(TABLE).select(readStrategy = ReadStrategy.CacheOnly()).data
                    if (settled(data)) return@withTimeout
                    delay(20)
                }
            }
        }
    }

    private fun row(id: String, title: String): JsonObject = buildJsonObject {
        put("id", id)
        put("title", title)
    }

    private companion object {
        const val TABLE = "tasks"
        const val CACHE_KEY = "tasks?select=*"
        const val ROWS = """[{"id":"t1","title":"Milk"}]"""
    }
}

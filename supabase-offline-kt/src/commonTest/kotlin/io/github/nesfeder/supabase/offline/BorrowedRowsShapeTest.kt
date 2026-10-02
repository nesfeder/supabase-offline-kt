// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Columns
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A row cached for one query joins another, when a write makes it start matching, only if it was
 * read with the same columns: a row from a query with another select list lacks that list's
 * aliases, embeds or columns. Such a query is marked stale instead, and fetched again.
 */
class BorrowedRowsShapeTest {

    /** The server's answer to each query, by its select list and filter. */
    private val answers =
        mapOf(
            ("*" to null) to
                """[{"id":"x","title":"x title","done":false,"status":"inactive","created_at":"t0"}]""",
            ("*" to "done=eq.false") to
                """[{"id":"x","title":"x title","done":false,"status":"inactive","created_at":"t0"}]""",
            ("*" to "done=eq.true") to
                """[{"id":"y","title":"y title","done":true,"status":"active","created_at":"t0"}]""",
            (ALIASED to "done=eq.true") to """[{"id":"y","headline":"y title","done":true}]""",
            (PICKER to null) to """[{"id":"x","title":"x title","status":"inactive"}]""",
            ("*" to "status=eq.active") to
                """[{"id":"y","title":"y title","done":true,"status":"active","created_at":"t0"}]""",
        )

    /** Whether the server can be reached. */
    private var online = true

    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                when {
                    request.method != "GET" -> okJson("")
                    request.query["select"] == EMBEDDING -> okJson(embeddingAnswerTo(request.query))
                    else -> {
                        val filter =
                            request.query.entries
                                .filter { (name, _) -> name != "select" }
                                .joinToString("&") { (name, value) -> "$name=$value" }
                                .ifEmpty { null }
                        okJson(answers[request.query["select"] to filter] ?: "[]")
                    }
                }
            }
        )

    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = newTestOfflineStore(nowMillis = { NOW }),
            coordinatorScope = idleCoroutineScope(),
        )
    private val items = client.from(TABLE)

    private fun readsOf(select: String, filter: String): Int =
        server.requests.count { request ->
            request.method == "GET" &&
                request.query["select"] == select &&
                request.query.entries.any { (name, value) -> "$name=$value" == filter }
        }

    private suspend fun cache(columns: Columns, condition: Pair<String, Any>? = null) {
        items.select(columns = columns, readStrategy = ReadStrategy.NetworkOnly) {
            condition?.let { (column, value) -> filter { eq(column, value) } }
        }
    }

    private suspend fun cached(columns: Columns, condition: Pair<String, Any>): String =
        items
            .select(columns = columns, readStrategy = ReadStrategy.CacheOnly()) {
                filter { eq(condition.first, condition.second) }
            }
            .data

    /** Whether a read with an hour's cache duration of the query asked the server. */
    private suspend fun askedTheServer(columns: Columns, condition: Pair<String, Any>): Boolean {
        val key = "${condition.first}=eq.${condition.second}"
        val before = readsOf(columns.value, key)
        items.select(
            columns = columns,
            readStrategy = ReadStrategy.CacheFirst(maxAge = 1.hours),
        ) {
            filter { eq(condition.first, condition.second) }
        }
        return readsOf(columns.value, key) > before
    }

    private suspend fun update(body: String, id: String) {
        items.update(Json.parseToJsonElement(body) as JsonObject) { filter { eq("id", id) } }
    }

    /** The answer to a read of [EMBEDDING]: one comment per row when it limits them to one. */
    private fun embeddingAnswerTo(query: Map<String, String>): String {
        val onePerRow = query["comments.limit"] == "1"
        return when (query["done"]) {
            "eq.false" -> if (onePerRow) OPEN_ONE_COMMENT else OPEN_ALL_COMMENTS
            "eq.true" -> if (onePerRow) DONE_ONE_COMMENT else DONE_ALL_COMMENTS
            else -> "[]"
        }
    }

    /**
     * Caches the list of tasks [done] or not, with their comments: one each when [onePerRow], all
     * otherwise.
     */
    private suspend fun cacheEmbedding(done: Boolean, onePerRow: Boolean) {
        items.select(columns = Columns.raw(EMBEDDING), readStrategy = ReadStrategy.NetworkOnly) {
            filter { eq("done", done) }
            if (onePerRow) limit(1, referencedTable = "comments")
        }
    }

    /** The list of tasks done, as cached, with its comments as [cacheEmbedding] read them. */
    private suspend fun cachedDone(onePerRow: Boolean): String =
        items
            .select(columns = Columns.raw(EMBEDDING), readStrategy = ReadStrategy.CacheOnly()) {
                filter { eq("done", true) }
                if (onePerRow) limit(1, referencedTable = "comments")
            }
            .data

    @Test
    fun a_row_read_with_one_embedded_row_does_not_join_a_list_that_reads_them_all() = runTest {
        // Given — x cached by a list that reads one comment per task, and the list of tasks done,
        // with the same columns, that reads them all
        cacheEmbedding(done = false, onePerRow = true)
        cacheEmbedding(done = true, onePerRow = false)

        // When — x is marked done
        update("""{"done":true}""", "x")

        // Then — it is not shown there with one comment of its two; the list is fetched again
        assertEquals(DONE_ALL_COMMENTS, cachedDone(onePerRow = false))
        assertEquals(true, askedTheServer(Columns.raw(EMBEDDING), "done" to true))
    }

    @Test
    fun a_queued_update_does_not_bring_in_a_row_read_with_one_embedded_row() = runTest {
        // Given — the same two lists, offline
        cacheEmbedding(done = false, onePerRow = true)
        cacheEmbedding(done = true, onePerRow = false)
        online = false

        // When — x is marked done, queued
        update("""{"done":true}""", "x")

        // Then — the list of tasks done does not show it with one comment of its two
        assertEquals(DONE_ALL_COMMENTS, cachedDone(onePerRow = false))
    }

    @Test
    fun a_row_joins_a_list_that_gives_its_embedded_table_the_same_limit() = runTest {
        // Given — both lists read one comment per task
        cacheEmbedding(done = false, onePerRow = true)
        cacheEmbedding(done = true, onePerRow = true)

        // When — x is marked done
        update("""{"done":true}""", "x")

        // Then — it joins, as read
        assertEquals(DONE_ONE_COMMENT_WITH_X, cachedDone(onePerRow = true))
    }

    @Test
    fun a_row_does_not_join_a_list_that_renames_its_columns() = runTest {
        // Given — row x cached by a select-all query, and a list that renames `title`
        cache(Columns.ALL)
        cache(Columns.raw(ALIASED), "done" to true)

        // When — x starts matching the renamed list
        update("""{"done":true}""", "x")

        // Then — x is not shown there without `headline`; the list is fetched again instead
        assertEquals(
            """[{"id":"y","headline":"y title","done":true}]""",
            cached(Columns.raw(ALIASED), "done" to true),
        )
        assertEquals(true, askedTheServer(Columns.raw(ALIASED), "done" to true))
    }

    @Test
    fun a_row_read_with_fewer_columns_does_not_join_a_select_all_list() = runTest {
        // Given — row x cached by a picker reading three columns, and a select-all list
        cache(Columns.raw(PICKER))
        cache(Columns.ALL, "status" to "active")

        // When — x starts matching the list
        update("""{"status":"active"}""", "x")

        // Then — x is not shown there missing `done` and `created_at`; the list is fetched again
        assertEquals(
            """[{"id":"y","title":"y title","done":true,"status":"active","created_at":"t0"}]""",
            cached(Columns.ALL, "status" to "active"),
        )
        assertEquals(true, askedTheServer(Columns.ALL, "status" to "active"))
    }

    @Test
    fun a_row_read_with_the_same_columns_joins_and_the_list_stays_fresh() = runTest {
        // Given — two select-all lists
        cache(Columns.ALL, "done" to false)
        cache(Columns.ALL, "done" to true)

        // When — x moves from one to the other
        update("""{"done":true}""", "x")

        // Then — it is shown whole in the list it joined, which is not fetched again
        assertEquals(
            """[{"id":"y","title":"y title","done":true,"status":"active","created_at":"t0"},""" +
                """{"id":"x","title":"x title","done":true,"status":"inactive","created_at":"t0"}]""",
            cached(Columns.ALL, "done" to true),
        )
        assertEquals(false, askedTheServer(Columns.ALL, "done" to true))
    }

    @Test
    fun no_payload_is_made_for_a_live_list_from_a_row_read_with_other_columns() = runTest {
        // Given — row x cached by a select-all query, and the renamed list observed, never fetched
        cache(Columns.ALL)
        val reader = launch {
            items
                .selectAsFlow(
                    columns = Columns.raw(ALIASED),
                    readStrategy = FlowReadStrategy.CacheOnly,
                ) {
                    filter { eq("done", true) }
                }
                .collect {}
        }
        runCurrent()

        // When — x starts matching it
        update("""{"done":true}""", "x")
        runCurrent()
        reader.cancel()

        // Then — no payload of x without `headline` was made for it
        assertFailsWith<CacheMissException> { cached(Columns.raw(ALIASED), "done" to true) }
    }

    private companion object {
        const val TABLE = "items"
        const val ALIASED = "id,headline:title,done"
        const val PICKER = "id,title,status"
        const val EMBEDDING = "id,done,comments(body)"
        const val OPEN_ALL_COMMENTS =
            """[{"id":"x","done":false,"comments":[{"body":"first"},{"body":"second"}]}]"""
        const val OPEN_ONE_COMMENT = """[{"id":"x","done":false,"comments":[{"body":"first"}]}]"""
        const val DONE_ALL_COMMENTS =
            """[{"id":"y","done":true,"comments":[{"body":"third"},{"body":"fourth"}]}]"""
        const val DONE_ONE_COMMENT = """[{"id":"y","done":true,"comments":[{"body":"third"}]}]"""
        const val DONE_ONE_COMMENT_WITH_X =
            """[{"id":"y","done":true,"comments":[{"body":"third"}]},""" +
                """{"id":"x","done":true,"comments":[{"body":"first"}]}]"""

        /**
         * The store's clock: every write is stamped now, so an hour's cache duration keeps it
         * fresh.
         */
        val NOW: Long = Clock.System.now().toEpochMilliseconds()
    }
}

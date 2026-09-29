package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.FakeRelationalPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A locally written row appears in exactly the cached lists whose filters admit it, whatever their
 * order or limit, through every way a row reaches the cache: an online write, a queued write, an
 * upsert, a server record and a replay. A row that stops matching leaves a list; one that starts
 * matching joins it once.
 */
class ListMembershipTest {

    private val server = FakeRelationalPostgrestServer()
    private val client = newTestOfflineClient(supabase = server.supabaseClient(), coordinatorScope = idleCoroutineScope())
    private val items = client.from(TABLE)

    /** Caches list [list] (ordered and limited) from the server. */
    private suspend fun cacheList(list: String) {
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listQuery(list))
    }

    /** The ids a one-shot cache read of list [list] shows. */
    private suspend fun cachedIds(list: String): List<String> =
        idsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = listQuery(list)).data)

    /** The ids a reactive cache read of list [list] shows first. */
    private suspend fun liveIds(list: String): List<String> =
        idsOf(checkNotNull(items.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = listQuery(list)).first().result).data)

    private fun goOffline() {
        server.recorder.answer = { connectivityFailure() }
    }

    private fun seedTwoLists() {
        server.seed(TABLE, row("a1", list = "A", position = 1), row("a2", list = "A", position = 2), row("b1", list = "B", position = 1))
    }

    @Test
    fun an_online_insert_into_list_b_lands_in_list_b_only() = runTest {
        // Given
        seedTwoLists()
        cacheList("A")
        cacheList("B")

        // When
        items.insertJson(JsonArray(listOf(row("new-b", list = "B", position = 5))))

        // Then
        assertEquals(listOf("a1", "a2"), cachedIds("A"))
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
    }

    @Test
    fun a_queued_insert_into_list_b_shows_in_list_b_only() = runTest {
        // Given
        seedTwoLists()
        cacheList("A")
        cacheList("B")
        goOffline()

        // When
        val outcome = items.insertJson(JsonArray(listOf(row("new-b", list = "B", position = 5))))

        // Then
        assertEquals(MutationOutcome.Queued, outcome)
        assertEquals(listOf("a1", "a2"), cachedIds("A"))
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
        assertEquals(listOf("a1", "a2"), liveIds("A"))
        assertEquals(listOf("b1", "new-b"), liveIds("B"))
    }

    @Test
    fun an_online_upsert_into_list_b_lands_in_list_b_only() = runTest {
        // Given
        seedTwoLists()
        cacheList("A")
        cacheList("B")

        // When
        items.upsertJson(JsonArray(listOf(row("new-b", list = "B", position = 5)))) { onConflict = "id" }

        // Then
        assertEquals(listOf("a1", "a2"), cachedIds("A"))
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
    }

    @Test
    fun a_server_record_for_list_b_lands_in_list_b_only() = runTest {
        // Given
        seedTwoLists()
        cacheList("A")
        cacheList("B")

        // When
        client.applyServerRecord(TABLE, row("new-b", list = "B", position = 5))

        // Then
        assertEquals(listOf("a1", "a2"), cachedIds("A"))
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
    }

    @Test
    fun a_replayed_insert_into_list_b_shows_in_list_b_only() = runTest {
        // Given — both lists cached, then an insert queued offline
        seedTwoLists()
        cacheList("A")
        cacheList("B")
        val online = server.recorder.answer
        goOffline()
        items.insertJson(JsonArray(listOf(row("new-b", list = "B", position = 5))))

        // When — back online, the queue replays
        server.recorder.answer = online
        val synced = client.syncAllPendingMutations()

        // Then
        assertEquals(1, synced)
        assertEquals(listOf("a1", "a2"), cachedIds("A"))
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
    }

    @Test
    fun an_online_insert_does_not_create_a_list_it_does_not_belong_to() = runTest {
        // Given — list B cached, list A observed but never fetched
        seedTwoLists()
        cacheList("B")
        val observedA = items.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = listQuery("A"))
        var aCreated = false

        // When — an insert into B while A is observed
        coroutineScope {
            val reader = launch { observedA.collect { read -> if (read.result != null) aCreated = true } }
            testScheduler.runCurrent()
            items.insertJson(JsonArray(listOf(row("new-b", list = "B", position = 5))))
            testScheduler.runCurrent()
            reader.cancel()
        }

        // Then
        assertEquals(false, aCreated, "an insert into list B created a cached payload for list A")
        assertEquals(listOf("b1", "new-b"), cachedIds("B"))
    }

    @Test
    fun an_online_update_moves_a_row_between_lists_once() = runTest {
        // Given — an open list and a done list, both cached
        server.seed(TABLE, row("x", list = "A", position = 1, done = false), row("y", list = "A", position = 2, done = true))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = doneList(false))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = doneList(true))

        // When — x is marked done
        items.updateJson(buildJsonObject { put("done", true) }) { filter { eq("id", "x") } }

        // Then — it leaves the open list and joins the done list once
        assertEquals(emptyList(), idsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = doneList(false)).data))
        assertEquals(listOf("y", "x"), idsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = doneList(true)).data))
    }

    @Test
    fun a_queued_update_moves_a_queued_row_between_lists_once() = runTest {
        // Given — both lists cached, then offline a new open row
        server.seed(TABLE, row("z", list = "A", position = 3, done = false), row("y", list = "A", position = 2, done = true))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = doneList(false))
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = doneList(true))
        goOffline()
        items.insertJson(JsonArray(listOf(row("x", list = "A", position = 1, done = false))))

        // When — it is marked done while still offline
        items.updateJson(buildJsonObject { put("done", true) }) { filter { eq("id", "x") } }

        // Then
        assertEquals(listOf("z"), idsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = doneList(false)).data))
        assertEquals(listOf("y", "x"), idsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = doneList(true)).data))
    }

    @Test
    fun an_online_delete_with_a_filter_the_cache_cannot_represent_leaves_other_rows_alone() = runTest {
        // Given — list A holds an open row and a done one
        server.seed(TABLE, row("a1", list = "A", position = 1, done = false), row("a2", list = "A", position = 2, done = true))
        cacheList("A")

        // When — the done rows of list A are deleted online, through an `or` group
        items.delete { filter { eq("list_id", "A"); or { eq("done", true); eq("archived", true) } } }

        // Then — the open row is still there
        assertEquals(true, "a1" in cachedIds("A"), "a row the delete never reached left the cache")
    }

    @Test
    fun an_online_update_with_a_negated_condition_leaves_excluded_rows_alone() = runTest {
        // Given — list A holds an open row and a done one
        server.seed(TABLE, row("a1", list = "A", position = 1, done = false), row("a2", list = "A", position = 2, done = true))
        cacheList("A")

        // When — every row of list A that is not done is renamed
        items.updateJson(buildJsonObject { put("title", "renamed") }) {
            filter {
                eq("list_id", "A")
                filterNot("done", FilterOperator.EQ, true)
            }
        }

        // Then — the done row keeps its title
        val done = rowsOf(items.select(readStrategy = ReadStrategy.CacheOnly(), request = listQuery("A")).data).single { it.idOf() == "a2" }
        assertEquals(JsonPrimitive("a2 title"), done["title"])
    }

    @Test
    fun a_live_list_fetches_again_after_an_online_write_the_cache_cannot_patch() = runTest {
        // Given — a list observed live, its first refresh done; the client's work, the reader and
        // every request run on this test's scheduler
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val liveServer = FakeRelationalPostgrestServer(engineDispatcher = testDispatcher)
        liveServer.seed(TABLE, row("a1", list = "A", position = 1, done = false), row("a2", list = "A", position = 2, done = true))
        val liveItems = newTestOfflineClient(supabase = liveServer.supabaseClient(), coordinatorScope = scope).from(TABLE)
        val shown = mutableListOf<List<String>>()
        scope.launch { liveItems.selectAsFlow(request = listQuery("A")).collect { read -> read.result?.let { shown += idsOf(it.data) } } }
        advanceUntilIdle()
        val readsBefore = liveServer.requests.count { request -> request.method == "GET" }

        // When — rows are deleted through an `or` group
        liveItems.delete { filter { eq("list_id", "A"); or { eq("done", true); eq("archived", true) } } }
        advanceUntilIdle()
        scope.cancel()

        // Then — the live list read the table again, and never lost a row the delete did not reach
        assertEquals(1, readsBefore)
        assertEquals(readsBefore + 1, liveServer.requests.count { request -> request.method == "GET" })
        assertEquals(listOf("a1", "a2"), shown.last())
        assertEquals(true, shown.all { ids -> "a1" in ids })
    }

    private fun listQuery(list: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", list) }
        order("position", Order.ASCENDING)
        limit(20)
    }

    private fun doneList(done: Boolean): SelectRequestBuilder.() -> Unit = {
        filter { eq("done", done) }
        order("position", Order.ASCENDING)
    }

    private fun row(id: String, list: String, position: Int, done: Boolean = false): JsonObject = buildJsonObject {
        put("id", id)
        put("list_id", list)
        put("position", position)
        put("done", done)
        put("title", "$id title")
    }

    private fun rowsOf(payload: String): List<JsonObject> = (Json.parseToJsonElement(payload) as JsonArray).map { it.jsonObject }

    private fun idsOf(payload: String): List<String> = rowsOf(payload).map { row -> row.idOf() }

    private fun JsonObject.idOf(): String = (getValue("id") as JsonPrimitive).content

    private companion object {
        const val TABLE = "items"
    }
}

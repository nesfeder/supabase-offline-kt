// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.result.PostgrestResult
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.postgrest.PENDING_ROW_KEYS_HEADER
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Which rows a queued write changed, named in the emission that shows them: an insert, an update
 * and a delete each name their own row and no other, a write that syncs leaves the set, the set
 * never disagrees with the rows of its own emission, and a one-shot read names the same rows.
 */
class PendingRowKeysTest {

    /** A client on this test's scheduler, and the server behind it. */
    private class Rig(val client: OfflineClient, val scope: CoroutineScope) {
        /** Whether the server can be reached. */
        var online = true

        /** The rows the server answers a read with. */
        var serverRows = ROWS
    }

    private fun TestScope.rig(autoSync: Boolean = true): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        lateinit var rig: Rig
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    if (!rig.online) connectivityFailure()
                    okJson(if (request.method == "GET") rig.serverRows else "[]")
                },
                engineDispatcher = dispatcher,
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                coordinatorScope = scope,
                config = Offline.Config().apply { this.autoSync = autoSync },
            )
        rig = Rig(client, scope)
        return rig
    }

    /** Collects [flow] in [scope], keeping every emission. */
    private fun <T> collected(scope: CoroutineScope, flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        scope.launch { flow.collect { values += it } }
        return values
    }

    /** A live read of rows 1 to 3, then offline, then [write]: the read's last emission. */
    private suspend fun TestScope.afterOneQueuedWrite(
        write: suspend OfflinePostgrestQueryBuilder.() -> Unit
    ): QuerySnapshot {
        val rig = rig()
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        rig.online = false
        rig.client.from(TABLE).write()
        runCurrent()
        rig.scope.cancel()
        return shown.last()
    }

    @Test
    fun a_queued_insert_names_its_row_and_no_other() = runTest {
        // Given / When
        val shown = afterOneQueuedWrite { insert(row(4)) }

        // Then
        assertEquals(listOf(1, 2, 3, 4), idsOf(shown.result))
        assertEquals(setOf(key(4)), shown.pendingRowKeys)
        assertTrue(shown.hasPendingMutations)
    }

    @Test
    fun a_queued_update_names_its_row_and_no_other() = runTest {
        // Given / When
        val shown = afterOneQueuedWrite { update(edited()) { filter { eq("id", 2) } } }

        // Then
        assertEquals(listOf(1, 2, 3), idsOf(shown.result))
        assertEquals("edited", titlesOf(shown.result)[2])
        assertEquals(setOf(key(2)), shown.pendingRowKeys)
    }

    @Test
    fun a_queued_delete_names_the_row_it_removed_and_no_other() = runTest {
        // Given / When
        val shown = afterOneQueuedWrite { delete { filter { eq("id", 3) } } }

        // Then — the row is gone from the rows, and its key stays in the set
        assertEquals(listOf(1, 2), idsOf(shown.result))
        assertEquals(setOf(key(3)), shown.pendingRowKeys)
        assertTrue(shown.hasPendingMutations)
    }

    @Test
    fun a_row_a_queued_update_moves_out_of_the_query_keeps_its_key() = runTest {
        // Given — a live read of list a, then offline
        val rig = rig()
        val shown =
            collected(rig.scope, rig.client.from(TABLE).selectAsFlow { filter { eq("list", "a") } })
        runCurrent()
        rig.online = false

        // When — row 1 moves to list b
        rig.client.from(TABLE).update(JsonObject(mapOf("list" to JsonPrimitive("b")))) {
            filter { eq("id", 1) }
        }
        runCurrent()

        // Then
        assertEquals(listOf(2, 3), idsOf(shown.last().result))
        assertEquals(setOf(key(1)), shown.last().pendingRowKeys)
        rig.scope.cancel()
    }

    @Test
    fun a_queued_row_without_its_key_marks_the_snapshot_pending_and_names_no_row() = runTest {
        // Given / When — a row the server would number
        val shown = afterOneQueuedWrite {
            insert(buildJsonArray { add(buildJsonObject { put("title", "unnumbered") }) })
        }

        // Then
        assertEquals(4, shown.result?.let { rowsOf(it).size })
        assertTrue(shown.hasPendingMutations)
        assertEquals(emptySet(), shown.pendingRowKeys)
    }

    @Test
    fun a_queued_insert_in_a_list_whose_select_leaves_the_key_out_is_named() = runTest {
        // Given — a live read of titles only, whose server rows carry no key, then offline
        val rig = rig()
        rig.serverRows = """[{"title":"note 1"},{"title":"note 2"}]"""
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow(Columns.list("title")))
        runCurrent()
        rig.online = false

        // When — an insert that writes its key
        rig.client.from(TABLE).insert(row(4, title = "new"))
        runCurrent()

        // Then — the queued row carries every column it wrote, so its key is named; the server
        // rows add none
        assertEquals(3, shown.last().result?.let { rowsOf(it).size })
        assertTrue(shown.last().hasPendingMutations)
        assertEquals(setOf(key(4)), shown.last().pendingRowKeys)
        rig.scope.cancel()
    }

    @Test
    fun a_write_that_syncs_leaves_the_set() = runTest {
        // Given — a live read, and an update queued offline
        val rig = rig()
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        rig.online = false
        rig.client.from(TABLE).update(edited()) { filter { eq("id", 2) } }
        runCurrent()
        val queued = shown.last()

        // When — back online, the write is sent and the server holds it
        rig.online = true
        rig.serverRows = ROWS.replace("note 2", "edited")
        rig.client.syncPendingMutations()
        runCurrent()

        // Then — the row still shows the change, and no key names it
        assertEquals(setOf(key(2)), queued.pendingRowKeys)
        assertEquals("edited", titlesOf(shown.last().result)[2])
        assertEquals(emptySet(), shown.last().pendingRowKeys)
        assertFalse(shown.last().hasPendingMutations)
        rig.scope.cancel()
    }

    @Test
    fun no_emission_names_a_key_without_its_row_or_shows_a_queued_row_without_its_key() = runTest {
        // Given — a live read of rows 1 to 3, then offline
        val rig = rig()
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        rig.online = false

        // When — an insert, an update and a delete queued, then sent once the server is back
        val builder = rig.client.from(TABLE)
        builder.insert(row(4, title = LOCAL))
        runCurrent()
        builder.update(JsonObject(mapOf("title" to JsonPrimitive(LOCAL)))) {
            filter { eq("id", 2) }
        }
        runCurrent()
        builder.delete { filter { eq("id", 3) } }
        runCurrent()
        val whileQueued = shown.size
        rig.online = true
        rig.serverRows =
            """[{"id":1,"title":"note 1"},{"id":2,"title":"$LOCAL"},{"id":4,"title":"$LOCAL"}]"""
        rig.client.syncPendingMutations()
        runCurrent()

        // Then — in every emission each key names a row it shows, or the row the delete removed,
        // which it does not show; while the writes wait, each row they wrote is named
        shown.forEachIndexed { index, snapshot ->
            val ids = idsOf(snapshot.result)
            val named =
                snapshot.pendingRowKeys.map { key -> key.getValue("id").jsonPrimitive.content }
            assertTrue(snapshot.pendingRowKeys.isEmpty() || snapshot.hasPendingMutations, "$index")
            named.forEach { id ->
                if (id == "3") assertFalse(3 in ids, "$index")
                else assertTrue(id.toInt() in ids, "$index")
            }
            if (index < whileQueued) {
                val written = ids.filter { id -> titlesOf(snapshot.result)[id] == LOCAL }
                assertEquals(written.toSet(), named.filter { it != "3" }.map(String::toInt).toSet())
            }
        }
        assertEquals(setOf(key(4), key(2), key(3)), shown[whileQueued - 1].pendingRowKeys)
        assertEquals(emptySet(), shown.last().pendingRowKeys)
        rig.scope.cancel()
    }

    @Test
    fun a_one_shot_read_names_the_rows_its_queued_writes_changed() = runTest {
        // Given — rows 1 to 3 cached; writes that are not sent by themselves
        val rig = rig(autoSync = false)
        rig.client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        rig.online = false
        rig.client.from(TABLE).update(edited()) { filter { eq("id", 2) } }
        rig.client.from(TABLE).insert(row(4))

        // When — the cache answers offline, and the server online with the writes still queued
        val fromCache = rig.client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly())
        rig.online = true
        val fromServer = rig.client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // Then
        assertTrue(fromCache.isFromCache)
        assertEquals(setOf(key(2), key(4)), fromCache.pendingRowKeys)
        assertFalse(fromServer.isFromCache)
        assertEquals(setOf(key(2), key(4)), fromServer.pendingRowKeys)
        rig.scope.cancel()
    }

    @Test
    fun a_result_supabase_kt_returned_names_the_rows_only_its_own_headers_name() = runTest {
        // Given — a server that sends a header of the stamp's name, and one that cannot be read
        val stamp = """[{"id":9}]"""
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { okJson(ROWS, extraHeaders = mapOf(PENDING_ROW_KEYS_HEADER to stamp)) },
                engineDispatcher = dispatcher,
            )
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        val postgrest = server.supabaseClient().postgrest

        // When
        val read = client.from(TABLE).select()
        val upstream = postgrest.from(TABLE).select()
        val unreadable =
            PostgrestResult(ROWS, headersOf(PENDING_ROW_KEYS_HEADER, "not json"), postgrest)

        // Then — the library's own result names none; supabase-kt's names what the header says
        assertEquals(emptySet(), read.pendingRowKeys)
        assertEquals(setOf(key(9)), upstream.pendingRowKeys)
        assertEquals(emptySet(), unreadable.pendingRowKeys)
        client.close()
    }

    private fun edited(): JsonObject = JsonObject(mapOf("title" to JsonPrimitive("edited")))

    private fun row(id: Int, title: String = "note $id"): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("list", "a")
                put("title", title)
            }
        )
    }

    private fun key(id: Int): JsonObject = buildJsonObject { put("id", id) }

    private fun rowsOf(result: PostgrestResult): JsonArray =
        Json.parseToJsonElement(result.data).jsonArray

    private fun idsOf(result: PostgrestResult?): List<Int> =
        result?.let(::rowsOf).orEmpty().mapNotNull { row ->
            row.jsonObject["id"]?.jsonPrimitive?.content?.toInt()
        }

    private fun titlesOf(result: PostgrestResult?): Map<Int, String?> =
        result?.let(::rowsOf).orEmpty().associate { row ->
            row.jsonObject.getValue("id").jsonPrimitive.content.toInt() to
                row.jsonObject["title"]?.jsonPrimitive?.content
        }

    private companion object {
        const val TABLE = "notes"
        const val LOCAL = "written here"
        const val ROWS =
            """[{"id":1,"list":"a","title":"note 1"},""" +
                """{"id":2,"list":"a","title":"note 2"},""" +
                """{"id":3,"list":"a","title":"note 3"}]"""
    }
}

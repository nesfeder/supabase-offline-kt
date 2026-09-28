package io.github.nesfeder.supabase.offline.internal.db

import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a queued mutation puts on the wire when it replays.
 *
 * The syncer runs over the real PostgREST handle (no test double between it and supabase-kt), and a
 * [RecordingPostgrestServer] records each request: method, table, query parameters, the `Prefer`
 * header and the body. These cases pin that a replayed write carries everything that was queued —
 * its filter, `onConflict`, `ignoreDuplicates` and `defaultToNull` — and that a queued row whose
 * body no longer parses is dropped without a request.
 */
class OfflineMutationReplayWireTest {

    private val server = RecordingPostgrestServer()
    private val mutationDao = FakeMutationDao()
    private var clock = 0L
    private val store = newTestOfflineStore(mutationDao = mutationDao, nowMillis = { clock++ })
    private val syncer = OfflineMutationSyncer(store = store, supabase = server.supabaseClient())

    /**
     * Changed with idempotent replay: the rows carry their primary key, so the stored body is posted
     * as an upsert on that key that ignores duplicates rather than as a plain insert.
     */
    @Test
    fun a_replayed_insert_posts_the_stored_body() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1,"title":"Buy milk"}]"""))

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then
        assertEquals(1, synced)
        assertEquals(
            listOf(
                write(
                    method = "POST",
                    query = mapOf("on_conflict" to "id", "columns" to "id,title"),
                    prefer = "resolution=ignore-duplicates,return=minimal",
                    body = """[{"id":1,"title":"Buy milk"}]""",
                ),
            ),
            server.requests,
        )
    }

    @Test
    fun a_replayed_insert_keeps_default_to_null_false() = runTest {
        // Given
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""), defaultToNull = false)

        // When
        syncer.syncTable(table = TABLE)

        // Then — missing columns take their database default, as the caller asked (the key it
        // carries makes the replay an upsert that ignores duplicates)
        assertEquals("resolution=ignore-duplicates,missing=default,return=minimal", server.requests.single().prefer)
    }

    @Test
    fun a_replayed_upsert_carries_on_conflict_and_merges_duplicates() = runTest {
        // Given
        store.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"list_id":"l-1","user_id":"u-2","role":"editor"}]"""),
            onConflict = "list_id,user_id",
            ignoreDuplicates = false,
        )

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then — upserted on the natural key, merging into an existing row
        assertEquals(1, synced)
        assertEquals(
            listOf(
                write(
                    method = "POST",
                    query = mapOf("on_conflict" to "list_id,user_id", "columns" to "list_id,user_id,role"),
                    prefer = "resolution=merge-duplicates,return=minimal",
                    body = """[{"list_id":"l-1","user_id":"u-2","role":"editor"}]""",
                ),
            ),
            server.requests,
        )
    }

    @Test
    fun a_replayed_upsert_keeps_ignore_duplicates_and_default_to_null_false() = runTest {
        // Given
        store.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"id":1}]"""),
            onConflict = "id",
            ignoreDuplicates = true,
            defaultToNull = false,
        )

        // When
        syncer.syncTable(table = TABLE)

        // Then
        val request = server.requests.single()
        assertEquals("id", request.query["on_conflict"])
        assertEquals("resolution=ignore-duplicates,missing=default,return=minimal", request.prefer)
    }

    @Test
    fun a_replayed_update_carries_its_stored_filter() = runTest {
        // Given
        store.enqueueUpdate(table = TABLE, body = element("""{"title":"Renamed"}"""), filter = ID_AND_OWNER)

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then — it targets exactly the captured rows, never the whole table
        assertEquals(1, synced)
        assertEquals(
            listOf(
                write(
                    method = "PATCH",
                    query = mapOf("id" to "eq.2", "user_id" to "eq.u-1"),
                    prefer = "return=minimal",
                    body = """{"title":"Renamed"}""",
                ),
            ),
            server.requests,
        )
    }

    @Test
    fun a_replayed_delete_carries_its_stored_filter() = runTest {
        // Given
        store.enqueueDelete(table = TABLE, filter = IDS_OR_ARCHIVED)

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then — it deletes exactly the captured rows, never the whole table
        assertEquals(1, synced)
        assertEquals(
            listOf(
                write(
                    method = "DELETE",
                    query = mapOf("or" to "(id.in.(7,8),status.eq.archived)"),
                    prefer = "return=minimal",
                    body = "",
                ),
            ),
            server.requests,
        )
    }

    /** Ported from the standalone engine; UPDATE and DELETE now carry the filter replay requires. */
    @Test
    fun syncTable_invokesRequestLambdas_andPropagatesOptions() = runTest {
        // Given — one queued write per verb, in this order
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":1}]"""), defaultToNull = false)
        store.enqueueUpsert(
            table = TABLE,
            bodyJson = rows("""[{"id":2}]"""),
            onConflict = "id",
            ignoreDuplicates = true,
            defaultToNull = false,
        )
        store.enqueueUpdate(table = TABLE, body = element("""{"name":"X"}"""), filter = ID_AND_OWNER)
        store.enqueueDelete(table = TABLE, filter = ID_AND_OWNER)

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then — all four replayed in order with their options. Nothing caches or observes the table,
        // so none of them is kept once synced (inverted: they used to stay until a later read).
        assertEquals(4, synced)
        assertEquals(listOf("POST", "POST", "PATCH", "DELETE"), server.requests.map { it.method })
        assertEquals("resolution=ignore-duplicates,missing=default,return=minimal", server.requests[0].prefer)
        assertEquals("id", server.requests[1].query["on_conflict"])
        assertEquals("resolution=ignore-duplicates,missing=default,return=minimal", server.requests[1].prefer)
        assertEquals(mapOf("id" to "eq.2", "user_id" to "eq.u-1"), server.requests[2].query)
        assertEquals(mapOf("id" to "eq.2", "user_id" to "eq.u-1"), server.requests[3].query)
        assertEquals(0, mutationDao.allForTable(TABLE).size)
        assertEquals(0, store.pendingMutationCount())
    }

    @Test
    fun syncTable_insert_malformedBody_deletesMutation() = runTest {
        // Given — a stored INSERT body that is not a JSON array
        mutationDao.insert(queuedRow(MutationOperation.INSERT, bodyJson = "not-json-array"))

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then — dropped without a request
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun syncTable_upsert_malformedBody_deletesMutation() = runTest {
        // Given
        mutationDao.insert(queuedRow(MutationOperation.UPSERT, bodyJson = "not-json-array"))

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun syncTable_update_malformedBody_deletesMutation() = runTest {
        // Given — a stored UPDATE body that is not JSON at all
        mutationDao.insert(queuedRow(MutationOperation.UPDATE, bodyJson = "", filterJson = ID_AND_OWNER_JSON))

        // When
        val synced = syncer.syncTable(table = TABLE)

        // Then
        assertEquals(0, synced)
        assertEquals(0, mutationDao.countAll())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun syncAllTables_noPending_returnsZero() = runTest {
        // Given — an empty queue

        // When
        val synced = syncer.syncAllTables()

        // Then
        assertEquals(0, synced)
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun syncAllTables_ignoresAlreadySyncedMutations() = runTest {
        // Given — a write that already replayed, not yet retired
        mutationDao.insert(queuedRow(MutationOperation.INSERT, bodyJson = """[{"id":1}]""", syncedAtMs = 5L))

        // When
        val synced = syncer.syncAllTables()

        // Then — it is not sent again
        assertEquals(0, synced)
        assertTrue(server.requests.isEmpty())
    }

    /**
     * Inverted: a server error on one table used to be isolated to that table, and the next table
     * replayed. With one queue across tables the pass stops there instead, so a later write — which may
     * depend on the failed one — never overtakes it.
     */
    @Test
    fun syncAllTables_serverErrorOnOneTable_stopsThePassWithLaterTablesQueued() = runTest {
        // Given — one write per table; the server fails the first table with a 500
        store.enqueueInsert(table = FAILING_TABLE, body = rows("""[{"id":1}]"""))
        store.enqueueInsert(table = TABLE, body = rows("""[{"id":2}]"""))
        server.answer = { request ->
            if (request.table == FAILING_TABLE) postgrestError(HttpStatusCode.InternalServerError) else okJson("[]")
        }

        // When
        val synced = syncer.syncAllTables()

        // Then — nothing after the failure is sent; both writes stay queued for a retry
        assertEquals(0, synced)
        assertEquals(listOf(FAILING_TABLE), server.requests.map { it.table })
        assertEquals(listOf(FAILING_TABLE, TABLE), store.allPendingMutations().map { it.table })
    }

    private fun write(method: String, query: Map<String, String>, prefer: String, body: String): RecordedRequest =
        RecordedRequest(
            method = method,
            table = TABLE,
            query = query,
            prefer = prefer,
            contentProfile = "public",
            acceptProfile = null,
            body = body,
        )

    private fun queuedRow(
        operation: String,
        bodyJson: String,
        filterJson: String? = null,
        syncedAtMs: Long? = null,
    ): MutationRow = MutationRow(
        table = TABLE,
        operation = operation,
        bodyJson = bodyJson,
        filterJson = filterJson,
        createdAtMs = clock++,
        syncedAtMs = syncedAtMs,
    )

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private fun element(json: String): JsonElement = Json.parseToJsonElement(json)

    private companion object {
        const val TABLE = "todos"
        const val FAILING_TABLE = "archive"

        val ID_AND_OWNER = FilterDescriptor(
            conditions = listOf(
                FilterDescriptor.Condition(column = "id", operatorId = "eq", value = "2"),
                FilterDescriptor.Condition(column = "user_id", operatorId = "eq", value = "u-1"),
            ),
        )

        const val ID_AND_OWNER_JSON =
            """{"conditions":[{"column":"id","operatorId":"eq","value":"2"},""" +
                """{"column":"user_id","operatorId":"eq","value":"u-1"}]}"""

        val IDS_OR_ARCHIVED = FilterDescriptor(
            mode = FilterDescriptor.Mode.OR,
            conditions = listOf(
                FilterDescriptor.Condition(column = "id", operatorId = "in", value = "(7,8)"),
                FilterDescriptor.Condition(column = "status", operatorId = "eq", value = "archived"),
            ),
        )
    }
}

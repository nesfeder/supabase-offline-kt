// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.PropertyConversionMethod
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.cache.QUERY_SHAPE_RECORD_VERSION
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.postgrest.queryShapeOf
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.UNLIMITED_TEST_CACHE_SETTINGS
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A cached query's shape — its columns and filters, captured from its request — is kept beside its
 * payload, so the rows a query admits are known after a restart. A query cached before shapes were
 * kept, or one whose cache key two different queries share, has no known shape: its conditions are
 * unknown, and unknown admits. A record this build cannot read is deleted, so the next read records
 * the shape it knows.
 */
class QueryShapeRecordsTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                when (request.method) {
                    "GET" ->
                        okJson(
                            """[{"id":"${request.query["list_id"]?.removePrefix("eq.") ?: "x"}1","list_id":"${request.query["list_id"]?.removePrefix("eq.") ?: "x"}"}]"""
                        )
                    else -> okJson("")
                }
            }
        )

    /** Every stamp the store writes is later than the one before. */
    private var clock = 0L

    /** The query cache of the last [launch]'s client. */
    private lateinit var queryCache: QueryCache

    /** A client over the same database, as each launch of the app builds one. */
    private fun launch(
        queryCacheLimits: QueryCacheLimits = UNLIMITED_TEST_CACHE_SETTINGS
    ): Offline {
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                queryCacheDao = queryCacheDao,
                mutationDao = mutationDao,
                queryCacheLimits = queryCacheLimits,
                nowMillis = { ++clock },
                coordinatorScope = idleCoroutineScope(),
            )
        queryCache = client.queryCache
        return client
    }

    private fun list(name: String): SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", name) }
    }

    private fun cachedIds(list: String): List<String> =
        queryCacheDao
            .rowsOf(TABLE)
            .single { row -> row.cacheKey == "$TABLE?list_id=eq.$list" }
            .payload
            .let { payload ->
                Json.parseToJsonElement(payload).jsonArray.map { element ->
                    element.jsonObject.getValue("id").jsonPrimitive.content
                }
            }

    private fun row(id: String, list: String) =
        Json.parseToJsonElement("""[{"id":"$id","list_id":"$list"}]""") as JsonArray

    private fun cacheKeyOf(list: String): String = "$TABLE?list_id=eq.$list"

    /** The signature of the shape record of the list [list] under [RESERVED_TABLE_NAME]. */
    private fun recordKeyOf(list: String): String =
        "$QUERY_SHAPE_CACHE_KEY_PREFIX$TABLE\u001F${cacheKeyOf(list)}"

    /** The shape record on disk of the list [list], or `null` for none. */
    private fun recordOf(list: String): JsonObject? =
        queryCacheDao.getRow(RESERVED_TABLE_NAME, recordKeyOf(list))?.payload?.let { payload ->
            Json.parseToJsonElement(payload).jsonObject
        }

    /** The filter value a record's one condition holds, or `null` for a record with no shape. */
    private fun conditionValueOf(record: JsonObject?): String? =
        (record?.get("shape") as? JsonObject)
            ?.getValue("conditions")
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?.getValue("value")
            ?.jsonPrimitive
            ?.content

    @Test
    fun a_cached_query_keeps_its_shape_across_a_restart() = runTest {
        // Given — two lists cached by one launch
        val first = launch()
        first.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))
        first.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = list("B"))

        // When — the next launch writes a row of list B before reading either list
        launch().from(TABLE).insert(row("B2", "B"))

        // Then — the row lands in list B only
        assertEquals(listOf("A1"), cachedIds("A"))
        assertEquals(listOf("B1", "B2"), cachedIds("B"))
    }

    @Test
    fun a_query_cached_without_a_shape_admits_every_row_until_it_is_read() = runTest {
        // Given — list A cached by a build that kept no shapes
        queryCacheDao.seedPayload(
            table = TABLE,
            cacheKey = "$TABLE?list_id=eq.A",
            payload = """[{"id":"A1","list_id":"A"}]""",
            updatedAt = 1,
        )
        val client = launch()

        // When — a row of list B is written before list A is read, and another after
        client.from(TABLE).insert(row("B2", "B"))
        client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly(), request = list("A"))
        client.from(TABLE).insert(row("B3", "B"))

        // Then — unknown conditions admitted the first; the shape the read recorded kept out the
        // second
        assertEquals(listOf("A1", "B2"), cachedIds("A"))
    }

    @Test
    fun two_queries_sharing_a_cache_key_are_trusted_for_neither() = runTest {
        // Given — list A cached with its shape, and another query recorded under the same key
        val client = launch()
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))
        val other =
            queryShapeOf(
                Columns.ALL,
                SelectRequestBuilder("public", PropertyConversionMethod.NONE).apply {
                    filter { eq("list_id", "B") }
                },
            )
        queryCache.queryShapes.remember(TABLE, cacheKeyOf("A"), other, persist = true)

        // When
        val shape = queryCache.queryShapes.shapeOf(TABLE, cacheKeyOf("A"))

        // Then — unknown, so that key's rows are never judged by the wrong query's filters, in
        // memory and on disk alike
        assertNull(shape)
        assertEquals(
            QUERY_SHAPE_RECORD_VERSION,
            recordOf("A")?.getValue("version")?.jsonPrimitive?.int,
        )
        assertNull(conditionValueOf(recordOf("A")))
    }

    @Test
    fun a_value_holding_a_separator_keys_apart_from_the_query_it_reads_as() = runTest {
        // Given — one value holding `&` and `=`, and the two filters it reads as
        val client = launch()
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter { eq("list_id", "A&note=eq.x") }
        }
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                eq("list_id", "A")
                eq("note", "x")
            }
        }

        // Then — two cached lists, each with the shape of its own query
        val keys = queryCacheDao.rowsOf(TABLE).map { row -> row.cacheKey }.sorted()
        assertEquals(2, keys.size, "$keys")
        assertEquals("$TABLE?list_id=eq.A&note=eq.x", keys[0])
        assertTrue(keys[1].startsWith("$TABLE?list_id=eq.A&note=eq.x|digest="), keys[1])
        assertEquals(
            listOf("A", "A&note=eq.x"),
            keys.map { key ->
                queryCache.queryShapes.shapeOf(TABLE, key)?.conditions?.first()?.value
            },
        )
    }

    @Test
    fun a_record_of_another_version_is_replaced_by_the_shape_the_next_read_records() = runTest {
        // Given — lists A and B cached, A's record written by a build with another record format,
        // B's unreadable
        queryCacheDao.seedPayload(TABLE, cacheKeyOf("A"), """[{"id":"A1","list_id":"A"}]""", 1)
        queryCacheDao.seedPayload(TABLE, cacheKeyOf("B"), """[{"id":"B1","list_id":"B"}]""", 1)
        queryCacheDao.seedPayload(
            RESERVED_TABLE_NAME,
            recordKeyOf("A"),
            """{"version":${QUERY_SHAPE_RECORD_VERSION + 1},"shape":{"select":"*","conditions":[],"hasOtherFilters":false}}""",
        )
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, recordKeyOf("B"), "not a record")
        val client = launch()

        // When — each list is read with its shape, then a row of list C is written
        client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly(), request = list("A"))
        client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly(), request = list("B"))
        client.from(TABLE).insert(row("C1", "C"))

        // Then — the shapes the reads recorded kept the row out of both lists, and each record on
        // disk is this build's; a record read as a shared key's would have admitted it for good
        assertEquals(listOf("A1"), cachedIds("A"))
        assertEquals(listOf("B1"), cachedIds("B"))
        listOf("A", "B").forEach { list ->
            val record = recordOf(list)
            assertEquals(
                QUERY_SHAPE_RECORD_VERSION,
                record?.getValue("version")?.jsonPrimitive?.int,
            )
            assertEquals(list, conditionValueOf(record))
        }
    }

    @Test
    fun an_unreadable_record_is_deleted_when_the_tables_records_are_read() = runTest {
        // Given — list A cached with a record this build cannot read
        queryCacheDao.seedPayload(TABLE, cacheKeyOf("A"), """[{"id":"A1","list_id":"A"}]""", 1)
        queryCacheDao.seedPayload(RESERVED_TABLE_NAME, recordKeyOf("A"), "not a record")
        val client = launch()

        // When — a write is patched into the table before list A is read, and another after
        client.from(TABLE).insert(row("B2", "B"))
        val recordAfterPatch = recordOf("A")
        client.from(TABLE).select(readStrategy = ReadStrategy.CacheOnly(), request = list("A"))
        client.from(TABLE).insert(row("B3", "B"))

        // Then — the patch deleted the record and, the shape unknown, admitted the first row, as
        // for a query cached without one; the read recorded the shape, which kept out the second
        assertNull(recordAfterPatch)
        assertEquals(listOf("A1", "B2"), cachedIds("A"))
        assertEquals("A", conditionValueOf(recordOf("A")))
    }

    @Test
    fun a_query_evicted_from_the_cache_leaves_no_shape_behind() = runTest {
        // Given — a cache with room for one list, and two lists read into it
        val client =
            launch(
                QueryCacheLimits(
                    maxPayloadBytes = 70,
                    evictionBatchSize = 1,
                    evictionHeadroomBytes = 0,
                )
            )
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))

        // When — the second list pushes the first out
        client
            .from(TABLE)
            .select(readStrategy = ReadStrategy.NetworkOnly, request = list("BBBBBBBBBBBBBB"))
        queryCache.evictOverBudget()

        // Then — one payload, and one shape record, for it
        val payloads = queryCacheDao.rowsOf(TABLE).map { row -> row.cacheKey }
        val shapes =
            queryCacheDao
                .rowsOf(RESERVED_TABLE_NAME)
                .map { row -> row.cacheKey }
                .filter { cacheKey -> cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX) }
        assertEquals(1, payloads.size)
        assertEquals(
            listOf("$QUERY_SHAPE_CACHE_KEY_PREFIX$TABLE\u001F${payloads.single()}"),
            shapes,
        )
    }

    @Test
    fun clearing_local_data_drops_every_shape() = runTest {
        // Given
        val client = launch()
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly, request = list("A"))

        // When
        client.clearLocalData()

        // Then
        assertTrue(
            queryCacheDao.rowsOf(RESERVED_TABLE_NAME).none { row ->
                row.cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX)
            }
        )
    }

    private companion object {
        const val TABLE = "items"
    }
}

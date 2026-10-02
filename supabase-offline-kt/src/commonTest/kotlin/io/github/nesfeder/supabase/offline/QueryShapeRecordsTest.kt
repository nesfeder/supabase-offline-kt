// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import io.github.nesfeder.supabase.offline.internal.cache.QueryCacheLimits
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A cached query's shape — its columns and filters, captured from its request — is kept beside its
 * payload, so the rows a query admits are known after a restart. A query cached before shapes were
 * kept, or one whose cache key two different queries share, has no known shape: its conditions are
 * unknown, and unknown admits.
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

    /** The store of the last [launch]. */
    private lateinit var store: OfflineStore

    /** A client over the same database, as each launch of the app builds one. */
    private fun launch(queryCacheLimits: QueryCacheLimits? = null): Offline {
        store =
            if (queryCacheLimits == null) {
                newTestOfflineStore(
                    queryCacheDao = queryCacheDao,
                    mutationDao = mutationDao,
                    nowMillis = { ++clock },
                )
            } else {
                newTestOfflineStore(
                    queryCacheDao = queryCacheDao,
                    mutationDao = mutationDao,
                    queryCacheLimits = queryCacheLimits,
                    nowMillis = { ++clock },
                )
            }
        return newTestOfflineClient(
            supabase = server.supabaseClient(),
            store = store,
            coordinatorScope = idleCoroutineScope(),
        )
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
        // Given — one value holding `&` and two filters, which key the same
        val client = launch()
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter { eq("list_id", "A&note=eq.x") }
        }
        assertEquals(
            listOf("$TABLE?list_id=eq.A&note=eq.x"),
            queryCacheDao.rowsOf(TABLE).map { row -> row.cacheKey },
        )
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
            filter {
                eq("list_id", "A")
                eq("note", "x")
            }
        }

        // When
        val shape = store.queryShapes.shapeOf(TABLE, "$TABLE?list_id=eq.A&note=eq.x")

        // Then — unknown, so that key's rows are never judged by the wrong query's filters
        assertEquals(null, shape)
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
        store.evictOverBudget()

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

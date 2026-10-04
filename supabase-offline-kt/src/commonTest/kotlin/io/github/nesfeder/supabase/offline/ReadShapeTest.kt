// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * Reads whose response is not a JSON array of rows — `single()`, `maybeSingle()` (supabase-kt 3.8
 * on), `head`, a `count`, `csv()`, `geojson()`, `stripNulls()` and `explain()` — bypass the cache:
 * they go to the network, their response is not stored, they never share the slot of the list read
 * with the same filters, and offline they fail as a network-only read does.
 */
class ReadShapeTest {

    private var online = true
    private val server =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                val accept = request.accept.orEmpty()
                when {
                    request.method == "HEAD" ->
                        respond("", HttpStatusCode.OK, headersOf("Content-Range", "*/2"))
                    accept.startsWith(OBJECT) -> okJson(ONE_ROW)
                    // The client adds its own media types after the one the read asks for
                    accept.startsWith(CSV) ->
                        respond(
                            CSV_BODY,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, CSV),
                        )
                    accept.startsWith(GEOJSON) -> okJson(GEOJSON_BODY)
                    accept.contains("nulls=stripped") -> okJson(STRIPPED_ROWS)
                    accept.startsWith(PLAN) ->
                        respond(
                            PLAN_BODY,
                            HttpStatusCode.OK,
                            headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
                        )
                    request.prefer.orEmpty().contains("count=") ->
                        okJson(ROWS, mapOf("Content-Range" to "0-1/2"))
                    else -> okJson(ROWS)
                }
            }
        )
    private val queryCacheDao = FakeQueryCacheDao()
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
            coordinatorScope = idleCoroutineScope(),
        )
    private val items = client.from(TABLE)

    /** Each read shape the cache cannot hold, with what the server answers it. */
    private val shapes: List<Triple<String, SelectRequestBuilder.() -> Unit, String>> =
        listOf<Triple<String, SelectRequestBuilder.() -> Unit, String>>(
            Triple(
                "single",
                {
                    filter { eq("list_id", "A") }
                    single()
                },
                ONE_ROW,
            ),
            Triple(
                "head",
                {
                    filter { eq("list_id", "A") }
                    head = true
                    count(Count.EXACT)
                },
                "",
            ),
            Triple(
                "count",
                {
                    filter { eq("list_id", "A") }
                    count(Count.EXACT)
                },
                ROWS,
            ),
            Triple(
                "csv",
                {
                    filter { eq("list_id", "A") }
                    csv()
                },
                CSV_BODY,
            ),
            Triple(
                "geojson",
                {
                    filter { eq("list_id", "A") }
                    geojson()
                },
                GEOJSON_BODY,
            ),
            Triple(
                "stripNulls",
                {
                    filter { eq("list_id", "A") }
                    stripNulls()
                },
                STRIPPED_ROWS,
            ),
            Triple(
                "explain",
                {
                    filter { eq("list_id", "A") }
                    explain()
                },
                PLAN_BODY,
            ),
        ) + maybeSingleReadShapes(oneRow = ONE_ROW)

    private val listOfA: SelectRequestBuilder.() -> Unit = { filter { eq("list_id", "A") } }

    private suspend fun cachedList(): String =
        items.select(readStrategy = ReadStrategy.CacheOnly(), request = listOfA).data

    @Test
    fun each_shape_is_answered_by_the_network_and_never_stored_over_the_list() = runTest {
        // Given — the list read of the same filters, cached
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)

        shapes.forEach { (name, request, answer) ->
            // When
            val read = items.select(readStrategy = ReadStrategy.NetworkOnly, request = request)

            // Then — the server's answer as it came, and the cached list untouched
            assertEquals(answer, read.data, name)
            assertEquals(ROWS, cachedList(), "$name wrote over the cached list")
            assertEquals(1, queryCacheDao.rowsOf(TABLE).size, "$name was stored")
        }
    }

    @Test
    fun each_shape_goes_to_the_network_even_when_asked_for_the_cache() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)

        listOf(ReadStrategy.CacheFirst(), ReadStrategy.NetworkFirst()).forEach { strategy ->
            shapes.forEach { (name, request, answer) ->
                // When
                val read = items.select(readStrategy = strategy, request = request)

                // Then — never the cached list in another shape's place
                assertEquals(answer, read.data, "$name via $strategy")
            }
        }
    }

    @Test
    fun offline_each_shape_fails_as_a_network_only_read_does() = runTest {
        // Given — the list cached, then no connection
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)
        online = false

        listOf(ReadStrategy.NetworkOnly, ReadStrategy.CacheFirst(), ReadStrategy.NetworkFirst())
            .forEach { strategy ->
                shapes.forEach { (name, request, _) ->
                    // When
                    val failure = runCatching {
                        items.select(readStrategy = strategy, request = request)
                    }
                        .exceptionOrNull()

                    // Then — the connectivity failure, never the list in another shape's place
                    assertTrue(
                        failure?.isNetworkOrConnectivityFailure() == true,
                        "$name via $strategy gave $failure",
                    )
                }
            }
    }

    @Test
    fun a_cache_only_read_of_each_shape_sends_nothing_and_says_the_cache_cannot_answer_it() =
        runTest {
            // Given — the list of the same filters cached
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)
            val sentBefore = server.requests.size

            shapes.forEach { (name, request, _) ->
                // When
                val oneShot = runCatching {
                    items.select(readStrategy = ReadStrategy.CacheOnly(), request = request)
                }
                    .exceptionOrNull()
                val reactive = runCatching {
                    items
                        .selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = request)
                        .first()
                }
                    .exceptionOrNull()

                // Then — a clear error, never the list in the shape's place, and nothing sent
                listOf(oneShot, reactive).forEach { failure ->
                    assertIs<CacheMissException>(failure, "$name")
                    assertTrue(
                        failure.message.orEmpty().contains("the cache holds lists of rows"),
                        "$name: ${failure.message}",
                    )
                }
            }
            assertEquals(sentBefore, server.requests.size)
        }

    @Test
    fun a_reactive_read_of_each_shape_emits_the_network_answer_once() = runTest {
        listOf(FlowReadStrategy.CacheFirstThenNetwork, FlowReadStrategy.NetworkFirstThenCache)
            .forEach { strategy ->
                shapes.forEach { (name, request, answer) ->
                    // When
                    val emissions =
                        items.selectAsFlow(readStrategy = strategy, request = request).toList()

                    // Then
                    assertEquals(
                        listOf(answer),
                        emissions.map { read -> read.result?.data },
                        "$name via $strategy",
                    )
                    assertEquals(RefreshState.Idle, emissions.single().refresh)
                }
            }
        assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE), "a shape read was stored")
    }

    @Test
    fun offline_a_reactive_read_of_each_shape_fails() = runTest {
        // Given
        online = false

        listOf(FlowReadStrategy.CacheFirstThenNetwork, FlowReadStrategy.NetworkFirstThenCache)
            .forEach { strategy ->
                shapes.forEach { (name, request, _) ->
                    // When
                    val failure = runCatching {
                        items.selectAsFlow(readStrategy = strategy, request = request).toList()
                    }
                        .exceptionOrNull()

                    // Then
                    assertTrue(
                        failure?.isNetworkOrConnectivityFailure() == true,
                        "$name via $strategy gave $failure",
                    )
                }
            }
    }

    /**
     * The list of the filters cached, then a client over the same cache that cannot build a request
     * with a token: a session that expired while the network is down, a session required while
     * nobody is signed in, and a token provider that throws. In each, a shape the cache cannot hold
     * is never answered with that list.
     */
    @Test
    fun a_client_that_cannot_resolve_a_token_never_answers_a_shape_from_the_list() = runTest {
        // Given — the list cached, and a server that is down
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)
        online = false
        val clients =
            listOf(
                "expired offline" to
                    suspend {
                        newTestSupabaseClient(engine = server.engine) {
                                install(Postgrest)
                                install(Auth) {
                                    sessionManager = MemorySessionManager()
                                    codeVerifierCache = MemoryCodeVerifierCache()
                                    autoLoadFromStorage = false
                                    alwaysAutoRefresh = true
                                    enableLifecycleCallbacks = false
                                }
                            }
                            .also { supabase ->
                                supabase.auth.sessionStatus.first { status ->
                                    status !is SessionStatus.Initializing
                                }
                                supabase.signInForTest("u-1", expiresInSeconds = -60)
                            }
                    },
                "session required, signed out" to
                    suspend {
                        newTestSupabaseClient(engine = server.engine) {
                                install(Postgrest) { requireValidSession = true }
                                install(Auth) {
                                    sessionManager = MemorySessionManager()
                                    codeVerifierCache = MemoryCodeVerifierCache()
                                    autoLoadFromStorage = false
                                    enableLifecycleCallbacks = false
                                }
                            }
                            .also { supabase ->
                                supabase.auth.sessionStatus.first { status ->
                                    status !is SessionStatus.Initializing
                                }
                            }
                    },
                "token provider throws" to
                    suspend {
                        newTestSupabaseClient(engine = server.engine) {
                            accessToken = { error("the app's token provider failed") }
                            install(Postgrest)
                        }
                    },
            )

        clients.forEach { (label, build) ->
            val cached =
                newTestOfflineClient(
                        supabase = build(),
                        queryCacheDao = queryCacheDao,
                        coordinatorScope = idleCoroutineScope(),
                    )
                    .from(TABLE)
            shapes
                .filter { (name, _, _) -> name in listOf("single", "maybeSingle", "head", "count") }
                .forEach { (name, request, _) ->
                    // When
                    val reads =
                        listOf(ReadStrategy.CacheOnly(), ReadStrategy.CacheFirst()).map { strategy
                            ->
                            strategy to
                                runCatching {
                                    cached.select(readStrategy = strategy, request = request).data
                                }
                        } +
                            (FlowReadStrategy.CacheOnly to
                                runCatching {
                                    cached
                                        .selectAsFlow(
                                            readStrategy = FlowReadStrategy.CacheOnly,
                                            request = request,
                                        )
                                        .first()
                                        .result
                                        ?.data
                                })

                    // Then — each fails; none returns the cached list
                    reads.forEach { (strategy, read) ->
                        assertTrue(
                            read.isFailure,
                            "$label, $name via $strategy gave ${read.getOrNull()}",
                        )
                    }
                }
        }
    }

    private companion object {
        const val TABLE = "items"
        const val OBJECT = "application/vnd.pgrst.object+json"
        const val CSV = "text/csv"
        const val GEOJSON = "application/geo+json"
        const val PLAN = "application/vnd.pgrst.plan"
        const val ROWS =
            """[{"id":1,"list_id":"A","note":null},{"id":2,"list_id":"A","note":"x"}]"""
        const val ONE_ROW = """{"id":1,"list_id":"A","note":null}"""
        const val STRIPPED_ROWS = """[{"id":1,"list_id":"A"},{"id":2,"list_id":"A","note":"x"}]"""
        const val CSV_BODY = "id,list_id\n1,A\n2,A"
        const val GEOJSON_BODY = """{"type":"FeatureCollection","features":[]}"""
        const val PLAN_BODY = "Seq Scan on items"
    }
}

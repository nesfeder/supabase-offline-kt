@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Reads whose response is not a JSON array of rows — `single()`, `maybeSingle()`, `head`, a
 * `count`, `csv()`, `geojson()`, `stripNulls()` and `explain()` — bypass the cache: they go to the
 * network, their response is not stored, they never share the slot of the list read with the same
 * filters, and offline they fail as a network-only read does.
 */
class ReadShapeTest {

    private var online = true
    private var noRowForOne = false
    private val server = RecordingPostgrestServer(
        answer = { request ->
            if (!online) connectivityFailure()
            val accept = request.accept.orEmpty()
            when {
                request.method == "HEAD" -> respond("", HttpStatusCode.OK, headersOf("Content-Range", "*/2"))
                accept.startsWith(OBJECT) && noRowForOne ->
                    postgrestError(HttpStatusCode.NotAcceptable, code = "PGRST116", message = "no rows")
                accept.startsWith(OBJECT) -> okJson(ONE_ROW)
                // The client adds its own media types after the one the read asks for
                accept.startsWith(CSV) -> respond(CSV_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, CSV))
                accept.startsWith(GEOJSON) -> okJson(GEOJSON_BODY)
                accept.contains("nulls=stripped") -> okJson(STRIPPED_ROWS)
                accept.startsWith(PLAN) -> respond(PLAN_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
                request.prefer.orEmpty().contains("count=") -> okJson(ROWS, mapOf("Content-Range" to "0-1/2"))
                else -> okJson(ROWS)
            }
        },
    )
    private val objectDao = FakeObjectDao()
    private val client = newTestOfflineClient(supabase = server.supabaseClient(), store = newTestOfflineStore(objectDao = objectDao), coordinatorScope = idleCoroutineScope())
    private val items = client.from(TABLE)

    /** Each read shape the cache cannot hold, with what the server answers it. */
    private val shapes: List<Triple<String, SelectRequestBuilder.() -> Unit, String>> = listOf(
        Triple("single", { filter { eq("list_id", "A") }; single() }, ONE_ROW),
        Triple("maybeSingle", { filter { eq("list_id", "A") }; maybeSingle() }, ONE_ROW),
        Triple("head", { filter { eq("list_id", "A") }; head = true; count(Count.EXACT) }, ""),
        Triple("count", { filter { eq("list_id", "A") }; count(Count.EXACT) }, ROWS),
        Triple("csv", { filter { eq("list_id", "A") }; csv() }, CSV_BODY),
        Triple("geojson", { filter { eq("list_id", "A") }; geojson() }, GEOJSON_BODY),
        Triple("stripNulls", { filter { eq("list_id", "A") }; stripNulls() }, STRIPPED_ROWS),
        Triple("explain", { filter { eq("list_id", "A") }; explain() }, PLAN_BODY),
    )

    private val listOfA: SelectRequestBuilder.() -> Unit = { filter { eq("list_id", "A") } }

    private suspend fun cachedList(): String = items.select(readStrategy = ReadStrategy.CacheOnly(), request = listOfA).data

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
            assertEquals(1, objectDao.rowsOf(TABLE).size, "$name was stored")
        }
    }

    @Test
    fun each_shape_goes_to_the_network_even_when_asked_for_the_cache() = runTest {
        // Given
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = listOfA)

        listOf(ReadStrategy.CacheOnly(), ReadStrategy.CacheFirst(), ReadStrategy.NetworkFirst()).forEach { strategy ->
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

        listOf(ReadStrategy.NetworkOnly, ReadStrategy.CacheOnly(), ReadStrategy.CacheFirst(), ReadStrategy.NetworkFirst()).forEach { strategy ->
            shapes.forEach { (name, request, _) ->
                // When
                val failure = runCatching { items.select(readStrategy = strategy, request = request) }.exceptionOrNull()

                // Then — the connectivity failure, never the list in another shape's place
                assertTrue(failure?.isNetworkOrConnectivityFailure() == true, "$name via $strategy gave $failure")
            }
        }
    }

    @Test
    fun a_reactive_read_of_each_shape_emits_the_network_answer_once() = runTest {
        FlowReadStrategy.entries.forEach { strategy ->
            shapes.forEach { (name, request, answer) ->
                // When
                val emissions = items.selectAsFlow(readStrategy = strategy, request = request).toList()

                // Then
                assertEquals(listOf(answer), emissions.map { read -> read.result?.data }, "$name via $strategy")
                assertEquals(RefreshState.Idle, emissions.single().refresh)
            }
        }
        assertEquals(emptyList(), objectDao.rowsOf(TABLE), "a shape read was stored")
    }

    @Test
    fun offline_a_reactive_read_of_each_shape_fails() = runTest {
        // Given
        online = false

        FlowReadStrategy.entries.forEach { strategy ->
            shapes.forEach { (name, request, _) ->
                // When
                val failure = runCatching { items.selectAsFlow(readStrategy = strategy, request = request).toList() }.exceptionOrNull()

                // Then
                assertTrue(failure?.isNetworkOrConnectivityFailure() == true, "$name via $strategy gave $failure")
            }
        }
    }

    @Test
    fun maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring() = runTest {
        // Given — a session still restoring, and no row for the filter
        noRowForOne = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restoring = newTestSupabaseClient(engine = server.engine) {
            coroutineDispatcher = dispatcher
            install(Postgrest) { maxRetries = 0 }
            install(Auth) {
                sessionManager = FakeHeldSessionManager(UserSession(accessToken = testJwt("u-1"), refreshToken = "r", expiresIn = 3600, tokenType = "bearer"))
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = true
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        }
        val reader = newTestOfflineClient(supabase = restoring, store = newTestOfflineStore(objectDao = objectDao), coordinatorScope = idleCoroutineScope())

        // When
        val read = reader.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("id", 404) }; maybeSingle() }

        // Then — upstream's empty result, from one request, with no wait for the session
        assertEquals("", read.data)
        assertEquals(1, server.requests.size)
        assertEquals(0L, currentTime)
        if (objectDao.rowsOf(TABLE).isNotEmpty()) fail("the empty result was stored")
    }

    private companion object {
        const val TABLE = "items"
        const val OBJECT = "application/vnd.pgrst.object+json"
        const val CSV = "text/csv"
        const val GEOJSON = "application/geo+json"
        const val PLAN = "application/vnd.pgrst.plan"
        const val ROWS = """[{"id":1,"list_id":"A","note":null},{"id":2,"list_id":"A","note":"x"}]"""
        const val ONE_ROW = """{"id":1,"list_id":"A","note":null}"""
        const val STRIPPED_ROWS = """[{"id":1,"list_id":"A"},{"id":2,"list_id":"A","note":"x"}]"""
        const val CSV_BODY = "id,list_id\n1,A\n2,A"
        const val GEOJSON_BODY = """{"type":"FeatureCollection","features":[]}"""
        const val PLAN_BODY = "Seq Scan on items"
    }
}

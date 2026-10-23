// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.RecordedRequest
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `single()` asks for the rows the list read of the same query does, as one object: its answer is
 * cached as that list, one row long, under the list's key, through every guard a list response
 * passes, and the cache answers it, with the queued writes laid over it, when that list holds
 * exactly one row. No row, or several, answer nothing. supabase-kt 3.8's `maybeSingle()` asks the
 * same and is held to the same rule.
 */
class CachedSingleTest {

    private var online = true

    /** The next single-object read the server receives waits here, when set. */
    private var hold: Hold? = null

    /**
     * What the server answers a single-object read with instead of the row: body and type, or no
     * `Content-Type` at all for a blank type.
     */
    private var objectAnswer: Pair<String, String>? = null

    /** A read held at the server: [arrived] once it is there, answered once [release] is. */
    private class Hold {
        val arrived = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private val logs = RecordingLogSink()
    private val server = RecordingPostgrestServer(answer = { request -> respondTo(request) })
    private val queryCacheDao = FakeQueryCacheDao()
    private val client =
        newTestOfflineClient(
            supabase = server.supabaseClient(),
            queryCacheDao = queryCacheDao,
            engineLogger = logs.asEngineLogger(),
            coordinatorScope = idleCoroutineScope(),
        )
    private val items = client.from(TABLE)

    /** The single-object reads of list A's one row: `single()`, and on 3.8 `maybeSingle()`. */
    private val readsOfOneRow: List<Pair<String, SelectRequestBuilder.() -> Unit>> =
        listOf<Pair<String, SelectRequestBuilder.() -> Unit>>(
            "single" to
                {
                    filter { eq("list_id", "A") }
                    single()
                }
        ) + maybeSingleReadShapes(oneRow = ROW_1).map { (name, request, _) -> name to request }

    private val singleOfListA: SelectRequestBuilder.() -> Unit = {
        filter { eq("list_id", "A") }
        single()
    }

    /**
     * The server: every list read answered with the rows its filter matches; a single object with
     * the one row it matches, or PostgREST's `PGRST116` for no row or several.
     */
    private suspend fun MockRequestHandleScope.respondTo(
        request: RecordedRequest
    ): HttpResponseData {
        if (!online) connectivityFailure()
        if (request.method != "GET") return okJson("")
        val rows = rowsMatching(request.query)
        val accept = request.accept.orEmpty()
        if (!accept.startsWith(OBJECT)) return okJson(rows.joinToString(",", "[", "]"))
        hold?.let { held ->
            hold = null
            held.arrived.complete(Unit)
            held.release.await()
        }
        objectAnswer?.let { (body, type) ->
            val headers =
                if (type.isBlank()) headersOf() else headersOf(HttpHeaders.ContentType, type)
            return respond(body, HttpStatusCode.OK, headers)
        }
        val row =
            rows.singleOrNull()
                ?: return postgrestError(
                    HttpStatusCode.NotAcceptable,
                    code = "PGRST116",
                    message = "Cannot coerce the result to a single JSON object",
                )
        return respond(
            if ("nulls=stripped" in accept) ROW_1_STRIPPED else row,
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "$OBJECT; charset=utf-8"),
        )
    }

    /** List A holds row 1; list B rows 2 and 3; id 9 is no row; no filter is every row. */
    private fun rowsMatching(query: Map<String, String>): List<String> =
        when {
            query["id"] == "eq.1" -> listOf(ROW_1)
            query["id"] == "eq.9" -> emptyList()
            query["list_id"] == "eq.A" -> listOf(ROW_1)
            query["list_id"] == "eq.B" -> listOf(ROW_2, ROW_3)
            else -> listOf(ROW_1, ROW_2, ROW_3)
        }

    private fun cachedListOfA(): String? = queryCacheDao.getRow(TABLE, LIST_A_KEY)?.payload

    private fun assertSameRow(expected: String, actual: String, message: String? = null) =
        assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual), message)

    @Test
    fun an_online_single_read_is_cached_as_its_list_and_answers_offline() = runTest {
        readsOfOneRow.forEach { (name, request) ->
            // Given — nothing cached
            queryCacheDao.deleteAll()
            online = true

            // When — the read goes to the server
            val network = items.select(readStrategy = ReadStrategy.NetworkOnly, request = request)

            // Then — the server's object, and the list it is one row of, cached under the list's
            // key
            assertSameRow(ROW_1, network.data, name)
            assertFalse(network.isFromCache, name)
            assertEquals("[$ROW_1]", cachedListOfA(), name)

            // When — the same read offline, under every strategy that may use the cache
            online = false
            listOf(ReadStrategy.NetworkFirst(), ReadStrategy.CacheFirst(), ReadStrategy.CacheOnly())
                .forEach { strategy ->
                    val offline = items.select(readStrategy = strategy, request = request)

                    // Then — the row, as one object, from the cache
                    assertSameRow(ROW_1, offline.data, "$name via $strategy")
                    assertTrue(offline.isFromCache, "$name via $strategy")
                    assertFalse(offline.hasPendingMutations, "$name via $strategy")
                }
        }
    }

    @Test
    fun a_cache_first_single_read_the_cache_answers_sends_nothing() = runTest {
        // Given — the row's list cached
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        val sent = server.requests.size

        // When
        val read = items.select(readStrategy = ReadStrategy.CacheFirst(), request = singleOfListA)

        // Then
        assertSameRow(ROW_1, read.data)
        assertTrue(read.isFromCache)
        assertEquals(sent, server.requests.size)
    }

    @Test
    fun the_list_read_and_the_single_read_of_one_query_share_its_cache() = runTest {
        // Given — the list read of list A, cached
        items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq("list_id", "A") } }
        online = false

        // When — its single read, offline
        val single = items.select(readStrategy = ReadStrategy.CacheOnly(), request = singleOfListA)

        // Then — the list's one row
        assertSameRow(ROW_1, single.data)

        // Given — the other way round: the single read cached
        queryCacheDao.deleteAll()
        online = true
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        online = false

        // When — the list read, offline
        val list =
            items.select(readStrategy = ReadStrategy.CacheOnly()) {
                filter { eq("list_id", "A") }
            }

        // Then — the list, one row long
        assertEquals("[$ROW_1]", list.data)
    }

    @Test
    fun a_cached_list_of_no_row_or_several_answers_no_single_read() = runTest {
        listOf("list_id" to "B", "id" to "9").forEach { (column, value) ->
            // Given — the list cached, holding several rows, or none
            online = true
            items.select(readStrategy = ReadStrategy.NetworkOnly) { filter { eq(column, value) } }
            val cached = queryCacheDao.getRow(TABLE, "$TABLE?$column=eq.$value")?.payload
            val single: SelectRequestBuilder.() -> Unit = {
                filter { eq(column, value) }
                single()
            }

            // When — online, the server's answer: PostgREST's PGRST116
            val online406 = runCatching {
                items.select(readStrategy = ReadStrategy.NetworkOnly, request = single)
            }

            // Then — thrown, and the cached list untouched
            assertIs<PostgrestRestException>(online406.exceptionOrNull(), "$column=$value")
            assertEquals(cached, queryCacheDao.getRow(TABLE, "$TABLE?$column=eq.$value")?.payload)

            // When — offline
            online = false
            val networkFirst = runCatching {
                items.select(readStrategy = ReadStrategy.NetworkFirst(), request = single)
            }
            val cacheFirst = runCatching {
                items.select(readStrategy = ReadStrategy.CacheFirst(), request = single)
            }
            val cacheOnly = runCatching {
                items.select(readStrategy = ReadStrategy.CacheOnly(), request = single)
            }

            // Then — the network's failure, as before, or a cache miss: never a row
            listOf(networkFirst, cacheFirst).forEach { read ->
                assertTrue(
                    read.exceptionOrNull()?.isNetworkOrConnectivityFailure() == true,
                    "$column=$value gave $read",
                )
            }
            assertIs<CacheMissException>(cacheOnly.exceptionOrNull(), "$column=$value")
        }
    }

    @Test
    fun a_cache_only_single_read_with_nothing_cached_throws_and_sends_nothing() = runTest {
        // When
        val failure = runCatching {
            items.select(readStrategy = ReadStrategy.CacheOnly(), request = singleOfListA)
        }

        // Then
        assertIs<CacheMissException>(failure.exceptionOrNull())
        assertEquals(0, server.requests.size)
    }

    @Test
    fun a_queued_update_to_the_row_shows_in_the_offline_single_read() = runTest {
        // Given — the row cached, then an edit queued offline
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        online = false
        val outcome =
            items.update(Json.parseToJsonElement("""{"title":"edited"}""")) {
                filter { eq("id", 1) }
            }
        assertIs<MutationOutcome.Queued>(outcome)

        // When
        val read = items.select(readStrategy = ReadStrategy.NetworkFirst(), request = singleOfListA)

        // Then — the row with the edit laid over it
        assertEquals(
            "edited",
            Json.parseToJsonElement(read.data).jsonObject["title"]?.jsonPrimitive?.content,
        )
        assertTrue(read.isFromCache)
        assertTrue(read.hasPendingMutations)
    }

    @Test
    fun a_queued_delete_of_the_row_leaves_no_row_for_the_offline_single_read() = runTest {
        // Given — the row cached, then its delete queued offline
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        online = false
        assertIs<MutationOutcome.Queued>(items.delete { filter { eq("id", 1) } })

        // When
        val failure = runCatching {
            items.select(readStrategy = ReadStrategy.CacheOnly(), request = singleOfListA)
        }

        // Then — no row, as the server will say once the delete is sent
        assertIs<CacheMissException>(failure.exceptionOrNull())
    }

    @Test
    fun a_single_read_with_nulls_stripped_is_not_cached() = runTest {
        // Given
        val stripped: SelectRequestBuilder.() -> Unit = {
            filter { eq("list_id", "A") }
            single()
            stripNulls()
        }

        // When
        val network = items.select(readStrategy = ReadStrategy.NetworkOnly, request = stripped)
        val cacheOnly = runCatching {
            items.select(readStrategy = ReadStrategy.CacheOnly(), request = stripped)
        }

        // Then — the server's answer as it came, and nothing stored or served
        assertEquals(ROW_1_STRIPPED, network.data)
        assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE))
        val failure = assertIs<CacheMissException>(cacheOnly.exceptionOrNull())
        assertTrue(
            failure.message.orEmpty().contains("the cache holds lists of rows"),
            "${failure.message}",
        )
    }

    @Test
    fun a_single_read_answered_with_something_other_than_one_object_is_not_cached() = runTest {
        listOf(
                "<html>blocked</html>" to "text/html",
                """[$ROW_1]""" to "application/json",
                "" to "$OBJECT; charset=utf-8",
                """{"id":1""" to "application/json",
            )
            .forEach { answer ->
                // Given
                objectAnswer = answer

                // When
                val read =
                    items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)

                // Then — passed through as it came, and nothing stored
                assertEquals(answer.first, read.data)
                assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE), "$answer was stored")
            }
    }

    @Test
    fun a_reactive_single_read_caches_its_answer() = runTest {
        // When
        val emissions =
            items
                .selectAsFlow(
                    readStrategy = FlowReadStrategy.CacheFirstThenNetwork,
                    request = singleOfListA,
                )
                .toList()

        // Then — the server's object, once, and the list it is one row of, cached
        assertSameRow(ROW_1, emissions.single().result?.data.orEmpty())
        assertEquals("[$ROW_1]", cachedListOfA())

        // When / Then — a cache-only flow follows lists, and says how to read the row instead
        val failure = runCatching {
            items
                .selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly, request = singleOfListA)
                .toList()
        }
        val miss = assertIs<CacheMissException>(failure.exceptionOrNull())
        assertTrue(miss.message.orEmpty().contains("ReadStrategy.CacheOnly"), "${miss.message}")
    }

    @Test
    fun with_network_access_off_a_network_first_single_read_serves_the_cached_row() = runTest {
        // Given — the row cached, then network access turned off
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        client.disableNetwork()
        val sent = server.requests.size

        // When
        val read = items.select(readStrategy = ReadStrategy.NetworkFirst(), request = singleOfListA)

        // Then — from the cache, and nothing sent
        assertSameRow(ROW_1, read.data)
        assertTrue(read.isFromCache)
        assertEquals(sent, server.requests.size)
    }

    @Test
    fun a_single_read_answered_after_a_write_to_its_table_is_not_cached() = runTest {
        // Given — the whole table's list cached, and a single read held at the server
        items.select(readStrategy = ReadStrategy.NetworkOnly) {}
        val held = Hold()
        hold = held
        val read = async {
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        }
        held.arrived.await()

        // When — a write to the row is sent and patches the cache, then the read is answered
        items.update(Json.parseToJsonElement("""{"title":"renamed"}""")) {
            filter { eq("id", 1) }
        }
        held.release.complete(Unit)

        // Then — the server's answer as it came, but not cached: it predates the write
        assertSameRow(ROW_1, read.await().data)
        assertNull(cachedListOfA())
        assertTrue(
            logs.entriesAt(EngineLogLevel.INFO).any { entry ->
                "single object predates a local write" in entry.message
            },
            "${logs.entries}",
        )
    }

    @Test
    fun a_single_object_answered_with_no_media_type_is_cached() = runTest {
        // Given — a server that names no media type
        objectAnswer = ROW_1 to ""

        // When
        items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)

        // Then — taken as JSON, as a list read's answer is
        assertEquals("[$ROW_1]", cachedListOfA())
    }

    @Test
    fun a_single_read_answered_after_local_data_was_cleared_is_not_cached() = runTest {
        // Given — a single read held at the server
        val held = Hold()
        hold = held
        val read = async {
            items.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        }
        held.arrived.await()

        // When — local data is cleared, then the read is answered
        client.clearLocalData()
        held.release.complete(Unit)

        // Then — not cached: it may carry the cleared session's token
        read.await()
        assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE))
    }

    @Test
    fun a_single_read_answered_while_the_session_restores_is_not_cached() = runTest {
        // Given — a session that stays restoring, and a server that answers the row to anyone
        val (restoringServer, restoringItems) = restoringClient()

        // When — the read waits for the session, which does not settle, and is sent
        val read = async {
            restoringItems.select(readStrategy = ReadStrategy.NetworkOnly, request = singleOfListA)
        }
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — the anon key's answer, returned but not cached
        assertSameRow(ROW_1, read.await().data)
        assertEquals(1, restoringServer.requests.size)
        assertEquals(emptyList(), queryCacheDao.rowsOf(TABLE))
    }

    @Test
    fun a_network_first_single_read_whose_session_does_not_settle_serves_the_cached_row() =
        runTest {
            // Given — the row's list cached, and a session that stays restoring
            queryCacheDao.seedPayload(table = TABLE, cacheKey = LIST_A_KEY, payload = "[$ROW_1]")
            val (restoringServer, restoringItems) = restoringClient()

            // When — the cache does not answer before the network, and the session does not
            // settle within the wait
            val read = async {
                restoringItems.select(
                    readStrategy = ReadStrategy.NetworkFirst(),
                    request = singleOfListA,
                )
            }
            advanceTimeBy(5.seconds)
            runCurrent()

            // Then — the cached row, with a warning, and nothing sent on the anon key
            assertSameRow(ROW_1, read.await().data)
            assertTrue(read.await().isFromCache)
            assertEquals(0, restoringServer.requests.size)
            assertTrue(
                logs.entriesAt(EngineLogLevel.WARNING).any { entry ->
                    "serving the cache" in entry.message
                },
                "${logs.entries}",
            )
        }

    /**
     * A table handle of a client whose stored session restores for good, over a server that answers
     * a single-object read with the row whatever the token; everything runs on the test's
     * scheduler.
     */
    private fun TestScope.restoringClient():
        Pair<RecordingPostgrestServer, OfflinePostgrestQueryBuilder> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restoringServer =
            RecordingPostgrestServer(
                answer = {
                    respond(
                        ROW_1,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "$OBJECT; charset=utf-8"),
                    )
                },
                engineDispatcher = dispatcher,
            )
        val supabase =
            newTestSupabaseClient(engine = restoringServer.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Auth) {
                    sessionManager =
                        FakeHeldSessionManager(
                            UserSession(
                                accessToken = testJwt("u-1"),
                                refreshToken = "refresh-token",
                                expiresIn = 3600,
                                tokenType = "bearer",
                            )
                        )
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = true
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        val restoring =
            newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                engineLogger = logs.asEngineLogger(),
                coordinatorScope = backgroundScope,
            )
        // The plugin starts loading the stored session, the load the held store keeps waiting
        runCurrent()
        return restoringServer to restoring.from(TABLE)
    }

    private companion object {
        const val TABLE = "items"
        const val LIST_A_KEY = "$TABLE?list_id=eq.A"
        const val OBJECT = "application/vnd.pgrst.object+json"
        const val ROW_1 = """{"id":1,"list_id":"A","title":"one","note":null}"""
        const val ROW_1_STRIPPED = """{"id":1,"list_id":"A","title":"one"}"""
        const val ROW_2 = """{"id":2,"list_id":"B","title":"two","note":null}"""
        const val ROW_3 = """{"id":3,"list_id":"B","title":"three","note":null}"""
    }
}

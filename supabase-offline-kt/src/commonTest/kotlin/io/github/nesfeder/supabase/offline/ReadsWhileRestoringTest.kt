// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.query.Count
import io.github.jan.supabase.postgrest.query.request.SelectRequestBuilder
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Reads made while a stored session is still restoring, under the default trust rule: a read that
 * needs the network waits for the session and caches only the user's answer; a read the cache
 * answers sends nothing; a read of a shape the cache cannot hold waits too, and is sent once the
 * session has settled. Requests made meanwhile carry the anon key, which RLS answers with nothing.
 */
class ReadsWhileRestoringTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val logs = RecordingLogSink()

    /**
     * The read shapes the cache cannot hold that a screen reads with at cold start, each with the
     * user's answer: `single()` fails on the anon key (PostgREST's `PGRST116`), and a count is 0.
     */
    private val otherShapes: List<Triple<String, SelectRequestBuilder.() -> Unit, String>> =
        listOf(
            Triple(
                "single",
                {
                    filter { eq("id", 1) }
                    single()
                },
                ONE_ROW,
            ),
            Triple("count", { count(Count.EXACT) }, USER_ROWS),
            Triple(
                "head",
                {
                    head = true
                    count(Count.EXACT)
                },
                "",
            ),
        )

    /**
     * A table handle of a client whose stored session restores until [sessions] is released, with
     * the server it reads from; everything runs on the test's scheduler.
     */
    private data class Restoring(
        val server: RecordingPostgrestServer,
        val items: OfflinePostgrestQueryBuilder,
        val sessions: FakeHeldSessionManager,
    )

    private fun TestScope.restoringClient(storeHangs: Boolean = false): Restoring {
        val sessions =
            FakeHeldSessionManager(
                UserSession(
                    accessToken = testJwt("u-1"),
                    refreshToken = "refresh-token",
                    expiresIn = 3600,
                    tokenType = "bearer",
                ),
                holdsEveryLoad = storeHangs,
            )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    // The user's token sees the user's rows; the anon key sees none
                    val mine = request.authorization.orEmpty().contains(testJwt("u-1"))
                    when {
                        // A HEAD read: how many rows the token sees
                        request.method == "HEAD" ->
                            respond(
                                "",
                                HttpStatusCode.OK,
                                headersOf("Content-Range", if (mine) "*/1" else "*/0"),
                            )
                        // A single object: the user's row, or PostgREST's "no rows" on the anon key
                        request.accept.orEmpty().startsWith(OBJECT) ->
                            if (mine) okJson(ONE_ROW)
                            else
                                postgrestError(
                                    HttpStatusCode.NotAcceptable,
                                    code = "PGRST116",
                                    message = "no rows",
                                )
                        // A read with a count: the rows the token sees, and how many
                        request.prefer.orEmpty().contains("count=") ->
                            okJson(
                                if (mine) USER_ROWS else "[]",
                                mapOf("Content-Range" to if (mine) "0-0/1" else "*/0"),
                            )
                        else -> okJson(if (mine) USER_ROWS else "[]")
                    }
                },
                engineDispatcher = dispatcher,
            )
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Auth) {
                    sessionManager = sessions
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = true
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        val client =
            newTestOfflineClient(
                supabase = supabase,
                queryCacheDao = queryCacheDao,
                engineLogger = logs.asEngineLogger(),
                coordinatorScope = backgroundScope,
            )
        // The plugin starts loading the stored session, the load the held store keeps waiting
        runCurrent()
        return Restoring(server, client.from(TABLE), sessions)
    }

    private fun RecordingPostgrestServer.reads(): Int = requests.count { request ->
        request.method == "GET"
    }

    @Test
    fun a_read_that_needs_the_network_waits_for_the_session_and_caches_only_the_users_answer() =
        runTest {
            listOf(ReadStrategy.NetworkOnly, ReadStrategy.NetworkFirst(), ReadStrategy.CacheFirst())
                .forEach { strategy ->
                    // Given — nothing cached, and a session still restoring
                    queryCacheDao.deleteAll()
                    val (server, items, sessions) = restoringClient()

                    // When — the read starts before the session has loaded
                    val read = async { items.select(readStrategy = strategy) }
                    advanceTimeBy(1.seconds)
                    runCurrent()

                    // Then — it waits, and nothing the anon key returned was stored
                    assertTrue(
                        !read.isCompleted,
                        "$strategy returned while the session was restoring",
                    )
                    assertTrue(
                        queryCacheDao.rowsOf(TABLE).isEmpty(),
                        "$strategy stored the anon key's answer",
                    )
                    assertTrue(server.reads() >= 1, "$strategy sent nothing")

                    // When — the session settles as the user
                    sessions.release()
                    advanceTimeBy(5.seconds)
                    runCurrent()

                    // Then — the user's rows, cached
                    assertEquals(USER_ROWS, read.await().data, "$strategy")
                    assertEquals(
                        listOf(USER_ROWS),
                        queryCacheDao.rowsOf(TABLE).map { row -> row.payload },
                        "$strategy",
                    )
                }
        }

    @Test
    fun a_read_whose_session_never_settles_returns_the_last_answer_uncached() = runTest {
        // Given — nothing cached, and a session that stays restoring
        val (server, items, _) = restoringClient()

        // When
        val read = async { items.select(readStrategy = ReadStrategy.NetworkOnly) }
        advanceTimeBy(10.seconds)
        runCurrent()

        // Then — after the restore wait (300, 900 and 2,400 ms) it gives up with the anon key's
        // answer
        assertEquals("[]", read.await().data)
        assertTrue(server.reads() >= 1)
        assertTrue(queryCacheDao.rowsOf(TABLE).isEmpty(), "the anon key's answer was stored")
    }

    @Test
    fun a_read_the_cache_answers_sends_nothing_while_the_session_restores() = runTest {
        // Given — the list cached, and a session still restoring
        queryCacheDao.seedPayload(table = TABLE, cacheKey = TABLE, payload = USER_ROWS)
        val (server, items, _) = restoringClient()

        // When
        val cacheFirst = items.select(readStrategy = ReadStrategy.CacheFirst())
        val cacheOnly = items.select(readStrategy = ReadStrategy.CacheOnly())
        val reactive = items.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly).first()

        // Then
        assertEquals(USER_ROWS, cacheFirst.data)
        assertEquals(USER_ROWS, cacheOnly.data)
        assertEquals(USER_ROWS, reactive.result?.data)
        assertEquals(0, server.reads())
    }

    @Test
    fun a_read_waits_for_a_session_store_that_never_answers_only_so_long() = runTest {
        // Given — the list cached, and a session store whose every load hangs
        queryCacheDao.seedPayload(table = TABLE, cacheKey = TABLE, payload = USER_ROWS)
        val (server, items, sessions) = restoringClient(storeHangs = true)

        // When — a read the cache would answer, once it can tell whose the cache is
        val read = async { items.select(readStrategy = ReadStrategy.CacheFirst()) }
        advanceTimeBy(30.seconds)
        runCurrent()
        val completed = read.isCompleted
        sessions.release()

        // Then — it stopped waiting for the store, and asked the network instead
        assertTrue(completed, "the read is still waiting for the session store")
        assertTrue(server.reads() >= 1, "the read sent nothing")
        assertEquals("[]", read.await().data)
    }

    @Test
    fun a_network_first_reactive_read_waits_for_the_session_before_it_emits() = runTest {
        // Given — nothing cached, and a session still restoring
        val (_, items, sessions) = restoringClient()

        // When
        val first = async {
            items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).first()
        }
        advanceTimeBy(1.seconds)
        runCurrent()

        // Then — nothing emitted yet
        assertTrue(!first.isCompleted, "the read emitted while the session was restoring")

        // When — the session settles as the user
        sessions.release()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — the first emission is the user's rows
        assertEquals(USER_ROWS, first.await().result?.data)
        assertEquals(RefreshState.Idle, first.await().refresh)
    }

    @Test
    fun a_read_of_another_shape_waits_for_the_session_and_is_sent_once_as_the_user() = runTest {
        otherShapes.forEach { (name, request, usersAnswer) ->
            listOf(ReadStrategy.NetworkOnly, ReadStrategy.NetworkFirst(), ReadStrategy.CacheFirst())
                .forEach { strategy ->
                    // Given — a session still restoring
                    val (server, items, sessions) = restoringClient()

                    // When — the read starts before the session has loaded
                    val read = async { items.select(readStrategy = strategy, request = request) }
                    advanceTimeBy(1.seconds)
                    runCurrent()

                    // Then — it waits, and nothing was sent on the anon key
                    assertTrue(
                        !read.isCompleted,
                        "$name via $strategy returned while the session was restoring",
                    )
                    assertEquals(
                        0,
                        server.requests.size,
                        "$name via $strategy was sent while the session was restoring",
                    )

                    // When — the session settles as the user
                    sessions.release()
                    runCurrent()

                    // Then — at once, one request, with the user's token, and the user's answer,
                    // not stored
                    assertTrue(
                        read.isCompleted,
                        "$name via $strategy did not wake when the session settled",
                    )
                    assertEquals(usersAnswer, read.await().data, "$name via $strategy")
                    assertEquals(1, server.requests.size, "$name via $strategy")
                    assertTrue(
                        server.requests.single().authorization.orEmpty().contains(testJwt("u-1")),
                        "$name via $strategy was sent on the anon key",
                    )
                    assertTrue(
                        queryCacheDao.rowsOf(TABLE).isEmpty(),
                        "$name via $strategy was stored",
                    )
                }
        }
    }

    @Test
    fun a_read_of_another_shape_whose_session_never_settles_is_sent_once_after_the_wait() =
        runTest {
            // Given — a session that stays restoring
            val (server, items, _) = restoringClient()

            // When — a single() the anon key has no row for
            val read = async {
                runCatching {
                    items.select(readStrategy = ReadStrategy.NetworkOnly) {
                        filter { eq("id", 1) }
                        single()
                    }
                }
            }
            advanceTimeBy(3.seconds)
            runCurrent()

            // Then — still waiting (300, 900 and 2,400 ms), nothing sent
            assertTrue(!read.isCompleted, "the read gave up before the wait ran out")
            assertEquals(0, server.requests.size)

            // When — the wait runs out
            advanceTimeBy(1.seconds)
            runCurrent()

            // Then — sent once, on the anon key, with a warning, and PostgREST's "no rows" is what
            // the caller gets
            assertEquals(1, server.requests.size)
            val failure = assertIs<PostgrestRestException>(read.await().exceptionOrNull())
            assertEquals(HttpStatusCode.NotAcceptable.value, failure.statusCode)
            val warnings = logs.entriesAt(EngineLogLevel.WARNING).map { entry -> entry.message }
            assertTrue(
                warnings.any { message -> "sending the request as it is" in message },
                "got $warnings",
            )
        }

    @Test
    fun a_cache_only_read_of_another_shape_fails_at_once_while_the_session_restores() = runTest {
        // Given
        val (server, items, _) = restoringClient()

        // When / Then — refused before anything is sent, with no wait for the session
        assertFailsWith<CacheMissException> {
            items.select(readStrategy = ReadStrategy.CacheOnly()) {
                filter { eq("id", 1) }
                single()
            }
        }
        assertEquals(0L, currentTime)
        assertEquals(0, server.requests.size)
    }

    @Test
    fun a_reactive_read_of_another_shape_waits_for_the_session_before_it_emits() = runTest {
        // Given — a session still restoring
        val (server, items, sessions) = restoringClient()

        // When
        val first = async {
            items
                .selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache) {
                    filter { eq("id", 1) }
                    single()
                }
                .first()
        }
        advanceTimeBy(1.seconds)
        runCurrent()

        // Then — nothing emitted, nothing sent
        assertTrue(!first.isCompleted, "the read emitted while the session was restoring")
        assertEquals(0, server.requests.size)

        // When — the session settles as the user
        sessions.release()
        runCurrent()

        // Then — at once, the user's row, from one request with the user's token
        assertTrue(first.isCompleted, "the read did not wake when the session settled")
        assertEquals(ONE_ROW, first.await().result?.data)
        assertEquals(1, server.requests.size)
        assertTrue(server.requests.single().authorization.orEmpty().contains(testJwt("u-1")))
    }

    private companion object {
        const val TABLE = "todos"
        const val USER_ROWS = """[{"id":1,"title":"mine"}]"""
        const val ONE_ROW = """{"id":1,"title":"mine"}"""
        const val OBJECT = "application/vnd.pgrst.object+json"
    }
}

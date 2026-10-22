// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.TOKEN_ENDPOINT
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Reads made after a stored session's token refresh failed, under the default trust rule. Offline
 * for longer than the token lives, then back, supabase-kt tries the refresh, cannot reach the Auth
 * server, and tries again every ten seconds; meanwhile there is no session, so requests carry the
 * anon key, which row-level security answers with nothing. A read that needs the network waits for
 * the session as it does while one restores and caches only the user's answer; when the wait runs
 * out, a read with the query cached serves the cache rather than the anon key's answer.
 */
class ReadsDuringFailedRefreshTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val logs = RecordingLogSink()

    /**
     * A table handle of a client whose stored session has expired and could not be refreshed, with
     * the server it reads from and the client whose session it is.
     */
    private data class FailedRefresh(
        val server: RecordingPostgrestServer,
        val items: OfflinePostgrestQueryBuilder,
        val supabase: SupabaseClient,
    )

    /**
     * Runs [block] with a [FailedRefresh]; everything runs on the test's scheduler. The client is
     * closed once [block] returns, which stops the Auth plugin's retries of the refresh, ten
     * seconds apart, from running on.
     */
    private suspend fun TestScope.withFailedRefresh(
        block: suspend TestScope.(FailedRefresh) -> Unit
    ) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { request ->
                    val mine = request.authorization.orEmpty().contains(testJwt(USER))
                    when {
                        // The Auth server cannot be reached: the refresh fails, and is retried
                        request.table == TOKEN_ENDPOINT -> connectivityFailure()
                        // The user's token sees the user's rows; the anon key sees none
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
                    // The stored session has expired, so loading it refreshes it first
                    sessionManager = MemorySessionManager(sessionOf(expiresInSeconds = -60))
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = true
                    alwaysAutoRefresh = true
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
        try {
            // The plugin loads the stored session, tries to refresh it, and cannot
            runCurrent()
            assertIs<SessionStatus.RefreshFailure>(
                supabase.auth.sessionStatus.value,
                "the session's refresh did not fail",
            )
            block(FailedRefresh(server, client.from(TABLE), supabase))
        } finally {
            supabase.close()
        }
    }

    /** The refresh lands: the session is the user's again. */
    private suspend fun FailedRefresh.refreshLands() {
        supabase.auth.importSession(sessionOf(expiresInSeconds = 3600), autoRefresh = false)
    }

    private fun RecordingPostgrestServer.reads(): Int = requests.count { request ->
        request.method == "GET"
    }

    private fun warnings(): List<String> =
        logs.entriesAt(EngineLogLevel.WARNING).map { entry -> entry.message }

    @Test
    fun a_read_that_needs_the_network_waits_for_the_refresh_and_takes_the_users_answer() = runTest {
        listOf(ReadStrategy.NetworkOnly, ReadStrategy.NetworkFirst(), ReadStrategy.CacheFirst())
            .forEach { strategy ->
                // Given — nothing cached, and a session whose refresh failed
                queryCacheDao.deleteAll()
                withFailedRefresh { failed ->
                    val (server, items, _) = failed

                    // When — the read goes out on the anon key
                    val read = async { items.select(readStrategy = strategy) }
                    advanceTimeBy(1.seconds)
                    runCurrent()

                    // Then — it waits, and nothing the anon key returned was stored
                    assertTrue(
                        !read.isCompleted,
                        "$strategy returned while the refresh was failing",
                    )
                    assertTrue(
                        queryCacheDao.rowsOf(TABLE).isEmpty(),
                        "$strategy stored the anon key's answer",
                    )
                    assertTrue(server.reads() >= 1, "$strategy sent nothing")

                    // When — the refresh lands within the wait
                    failed.refreshLands()
                    runCurrent()

                    // Then — at once, the user's rows from a request with the user's token, cached
                    assertTrue(read.isCompleted, "$strategy did not wake when the session settled")
                    assertEquals(USER_ROWS, read.await().data, "$strategy")
                    assertFalse(read.await().isFromCache, "$strategy")
                    assertTrue(
                        server.requests.last().authorization.orEmpty().contains(testJwt(USER)),
                        "$strategy asked again on the anon key",
                    )
                    assertEquals(
                        listOf(USER_ROWS),
                        queryCacheDao.rowsOf(TABLE).map { row -> row.payload },
                        "$strategy",
                    )
                }
            }
    }

    @Test
    fun a_network_first_read_with_the_query_cached_serves_the_cache_when_the_refresh_does_not_land() =
        runTest {
            // Given — the user's list cached, and a session whose refresh failed
            queryCacheDao.seedPayload(table = TABLE, cacheKey = TABLE, payload = USER_ROWS)
            withFailedRefresh { (server, items, _) ->
                // When — the read goes out on the anon key, and the refresh does not land
                val read = async { items.select(readStrategy = ReadStrategy.NetworkFirst()) }
                advanceTimeBy(3.seconds)
                runCurrent()

                // Then — it is still waiting (300, 900 and 2,400 ms)
                assertTrue(!read.isCompleted, "the read returned before the wait ran out")
                assertTrue(server.reads() >= 1, "the read sent nothing")

                // When — the wait runs out
                advanceTimeBy(1.seconds)
                runCurrent()

                // Then — the cached rows, from the cache, not the anon key's empty answer
                assertEquals(USER_ROWS, read.await().data)
                assertTrue(read.await().isFromCache, "the cached rows were served as fresh")
                assertEquals(
                    listOf(USER_ROWS),
                    queryCacheDao.rowsOf(TABLE).map { row -> row.payload },
                    "the anon key's answer replaced the cache",
                )
                assertTrue(
                    warnings().any { message -> "serving the cache" in message },
                    "got ${warnings()}",
                )
            }
        }

    @Test
    fun a_cache_first_read_of_a_list_marked_stale_serves_it_when_the_refresh_does_not_land() =
        runTest {
            // Given — the user's list cached but marked stale, so the read fetches it again
            queryCacheDao.seedPayload(
                table = TABLE,
                cacheKey = TABLE,
                payload = USER_ROWS,
                updatedAt = -1L,
            )
            withFailedRefresh { (server, items, _) ->
                // When — the refresh does not land within the wait
                val read = async { items.select(readStrategy = ReadStrategy.CacheFirst()) }
                advanceTimeBy(5.seconds)
                runCurrent()

                // Then — the stale list, from the cache, not the anon key's empty answer
                assertEquals(USER_ROWS, read.await().data)
                assertTrue(read.await().isFromCache, "the cached rows were served as fresh")
                assertTrue(server.reads() >= 1, "the read sent nothing")
                assertEquals(
                    listOf(USER_ROWS),
                    queryCacheDao.rowsOf(TABLE).map { row -> row.payload },
                    "the anon key's answer replaced the cache",
                )
            }
        }

    @Test
    fun a_read_with_nothing_cached_returns_the_anon_keys_answer_uncached_when_the_refresh_does_not_land() =
        runTest {
            listOf(ReadStrategy.NetworkOnly, ReadStrategy.NetworkFirst()).forEach { strategy ->
                // Given — nothing cached, and a session whose refresh failed
                queryCacheDao.deleteAll()
                withFailedRefresh { (server, items, _) ->
                    // When — the refresh does not land within the wait
                    val read = async { items.select(readStrategy = strategy) }
                    advanceTimeBy(5.seconds)
                    runCurrent()

                    // Then — the anon key's answer, uncached and with a warning, as while a
                    // session that never settles restores
                    assertEquals("[]", read.await().data, "$strategy")
                    assertFalse(read.await().isFromCache, "$strategy")
                    assertTrue(server.reads() >= 1, "$strategy sent nothing")
                    assertTrue(
                        queryCacheDao.rowsOf(TABLE).isEmpty(),
                        "$strategy stored the anon key's answer",
                    )
                    assertTrue(
                        warnings().any { message -> "serving the response uncached" in message },
                        "$strategy: got ${warnings()}",
                    )
                }
            }
        }

    @Test
    fun a_read_the_cache_answers_sends_nothing_while_the_refresh_is_failing() = runTest {
        // Given — the user's list cached, and a session whose refresh failed
        queryCacheDao.seedPayload(table = TABLE, cacheKey = TABLE, payload = USER_ROWS)
        withFailedRefresh { (server, items, _) ->
            // When
            val cacheFirst = items.select(readStrategy = ReadStrategy.CacheFirst())
            val cacheOnly = items.select(readStrategy = ReadStrategy.CacheOnly())
            val reactive = items.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly).first()

            // Then — the stored user's cache, with no request on the anon key
            assertEquals(USER_ROWS, cacheFirst.data)
            assertEquals(USER_ROWS, cacheOnly.data)
            assertEquals(USER_ROWS, reactive.result?.data)
            assertEquals(0, server.reads())
        }
    }

    private companion object {
        const val TABLE = "todos"
        const val USER = "u-1"
        const val USER_ROWS = """[{"id":1,"title":"mine"}]"""

        /** The stored user's session, [expiresInSeconds] from now; negative for one expired. */
        fun sessionOf(expiresInSeconds: Long): UserSession =
            UserSession(
                accessToken = testJwt(USER),
                refreshToken = "refresh-token",
                expiresIn = expiresInSeconds,
                tokenType = "bearer",
            )
    }
}

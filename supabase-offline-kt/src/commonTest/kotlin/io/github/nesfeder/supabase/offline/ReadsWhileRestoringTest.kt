// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Reads made while a stored session is still restoring, under the default trust rule: a read that
 * needs the network waits for the session and caches only the user's answer; a read the cache
 * answers sends nothing. Requests made meanwhile carry the anon key, which RLS answers with
 * nothing.
 */
class ReadsWhileRestoringTest {

    private val objectDao = FakeObjectDao()

    /**
     * A table handle of a client whose stored session restores until [sessions] is released, with
     * the server it reads from; everything runs on the test's scheduler.
     */
    private data class Restoring(
        val server: RecordingPostgrestServer,
        val items: OfflinePostgrestQueryBuilder,
        val sessions: FakeHeldSessionManager,
    )

    private fun TestScope.restoringClient(): Restoring {
        val sessions =
            FakeHeldSessionManager(
                UserSession(
                    accessToken = testJwt("u-1"),
                    refreshToken = "refresh-token",
                    expiresIn = 3600,
                    tokenType = "bearer",
                )
            )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                // The user's token sees the user's rows; the anon key sees none
                answer = { request ->
                    okJson(
                        if (request.authorization.orEmpty().contains(testJwt("u-1"))) USER_ROWS
                        else "[]"
                    )
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
                store = newTestOfflineStore(objectDao = objectDao),
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
                    objectDao.deleteAll()
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
                        objectDao.rowsOf(TABLE).isEmpty(),
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
                        objectDao.rowsOf(TABLE).map { row -> row.payload },
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
        assertTrue(objectDao.rowsOf(TABLE).isEmpty(), "the anon key's answer was stored")
    }

    @Test
    fun a_read_the_cache_answers_sends_nothing_while_the_session_restores() = runTest {
        // Given — the list cached, and a session still restoring
        objectDao.seedPayload(table = TABLE, signature = TABLE, payload = USER_ROWS)
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

    private companion object {
        const val TABLE = "todos"
        const val USER_ROWS = """[{"id":1,"title":"mine"}]"""
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.postgrestError
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * supabase-kt 3.8's `maybeSingle()`, like the other read shapes of `ReadShapeTest`, goes to the
 * network once the session has settled, as `ReadsWhileRestoringTest` pins for `single()`; with no
 * row it then returns supabase-kt's empty result.
 */
class MaybeSingleWhileRestoringTest {

    private val queryCacheDao = FakeQueryCacheDao()

    @Test
    fun maybe_single_with_no_row_waits_for_the_session_then_returns_the_empty_result() = runTest {
        // Given — a session still restoring, and a server with no row for any single-object read:
        // PostgREST's `PGRST116`
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = {
                    postgrestError(
                        HttpStatusCode.NotAcceptable,
                        code = "PGRST116",
                        message = "no rows",
                    )
                },
                engineDispatcher = dispatcher,
            )
        val sessions =
            FakeHeldSessionManager(
                UserSession(
                    accessToken = testJwt("u-1"),
                    refreshToken = "r",
                    expiresIn = 3600,
                    tokenType = "bearer",
                )
            )
        val restoring =
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
        val reader =
            newTestOfflineClient(
                supabase = restoring,
                queryCacheDao = queryCacheDao,
                coordinatorScope = backgroundScope,
            )
        // The plugin starts loading the stored session, the load the held store keeps waiting
        runCurrent()

        // When — the read starts before the session has loaded
        val read = async {
            reader.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
                filter { eq("id", 404) }
                maybeSingle()
            }
        }
        advanceTimeBy(1.seconds)
        runCurrent()

        // Then — it waits, and nothing has been sent on the anon key
        assertTrue(!read.isCompleted, "the read returned while the session was restoring")
        assertEquals(0, server.requests.size)

        // When — the session settles as the user
        sessions.release()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — upstream's empty result, from one request carrying the user's token
        assertEquals("", read.await().data)
        assertEquals(1, server.requests.size)
        assertTrue(
            server.requests.single().authorization.orEmpty().contains(testJwt("u-1")),
            "the read was sent on the anon key",
        )
        if (queryCacheDao.rowsOf(TABLE).isNotEmpty()) fail("the empty result was stored")
    }

    private companion object {
        const val TABLE = "items"
    }
}

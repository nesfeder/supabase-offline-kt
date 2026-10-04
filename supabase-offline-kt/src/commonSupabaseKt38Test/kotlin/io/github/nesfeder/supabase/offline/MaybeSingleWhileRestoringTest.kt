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
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * supabase-kt 3.8's `maybeSingle()`, like the other read shapes of `ReadShapeTest`, goes to the
 * network; with no row it returns supabase-kt's empty result at once, even while the session
 * restores.
 */
class MaybeSingleWhileRestoringTest {

    /** A server with no row for any single-object read: PostgREST's `PGRST116`. */
    private val server =
        RecordingPostgrestServer(
            answer = {
                postgrestError(HttpStatusCode.NotAcceptable, code = "PGRST116", message = "no rows")
            }
        )
    private val queryCacheDao = FakeQueryCacheDao()

    @Test
    fun maybe_single_with_no_row_returns_the_empty_result_at_once_while_restoring() = runTest {
        // Given — a session still restoring, and no row for the filter
        val dispatcher = StandardTestDispatcher(testScheduler)
        val restoring =
            newTestSupabaseClient(engine = server.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Auth) {
                    sessionManager =
                        FakeHeldSessionManager(
                            UserSession(
                                accessToken = testJwt("u-1"),
                                refreshToken = "r",
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
        val reader =
            newTestOfflineClient(
                supabase = restoring,
                queryCacheDao = queryCacheDao,
                coordinatorScope = idleCoroutineScope(),
            )

        // When
        val read =
            reader.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly) {
                filter { eq("id", 404) }
                maybeSingle()
            }

        // Then — upstream's empty result, from one request, with no wait for the session
        assertEquals("", read.data)
        assertEquals(1, server.requests.size)
        assertEquals(0L, currentTime)
        if (queryCacheDao.rowsOf(TABLE).isNotEmpty()) fail("the empty result was stored")
    }

    private companion object {
        const val TABLE = "items"
    }
}

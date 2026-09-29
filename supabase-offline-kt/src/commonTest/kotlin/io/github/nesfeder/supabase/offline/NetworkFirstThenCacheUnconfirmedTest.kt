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
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * `FlowReadStrategy.NetworkFirstThenCache` that could not get the server's answer — the network was
 * unreachable, or the answer did not speak for the user — shows the cache and says it is not
 * confirmed (`Stalled`), and fetches again until it is; then it is idle.
 */
class NetworkFirstThenCacheUnconfirmedTest {

    private val objectDao =
        FakeObjectDao().apply { seedPayload(TABLE, TABLE, CACHED, updatedAt = 1) }

    /** Whether the server can be reached. */
    private var online = false

    private fun TestScope.newServer(): RecordingPostgrestServer =
        RecordingPostgrestServer(
            answer = { request ->
                if (!online) connectivityFailure()
                okJson(
                    if (request.authorization.orEmpty().contains(testJwt("u-1"))) FRESH else "[]"
                )
            },
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )

    private fun TestScope.items(server: RecordingPostgrestServer): OfflinePostgrestQueryBuilder =
        newTestOfflineClient(
                supabase =
                    newTestSupabaseClient(engine = server.engine) {
                        coroutineDispatcher = StandardTestDispatcher(testScheduler)
                        install(Postgrest)
                        accessToken = { testJwt("u-1") }
                    },
                store = newTestOfflineStore(objectDao = objectDao),
                coordinatorScope = backgroundScope,
            )
            .from(TABLE)

    @Test
    fun offline_it_shows_the_cache_as_not_confirmed_and_fetches_again_once_the_network_is_back() =
        runTest {
            // Given — the list cached, and no network
            val server = newServer()
            val items = items(server)
            val shown = mutableListOf<OfflineReadResult>()
            backgroundScope.launch {
                items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).collect {
                    read ->
                    shown += read
                }
            }
            runCurrent()

            // Then — the cache, not confirmed
            assertEquals(CACHED, shown.first().result?.data)
            assertTrue(shown.first().refresh is RefreshState.Stalled, "${shown.first().refresh}")

            // When — the network comes back
            online = true
            advanceTimeBy(2.minutes)
            runCurrent()

            // Then — fetched again, and confirmed
            assertEquals(FRESH, shown.last().result?.data)
            assertEquals(RefreshState.Idle, shown.last().refresh)
        }

    @Test
    fun an_answer_that_does_not_speak_for_the_user_leaves_the_cache_not_confirmed() = runTest {
        // Given — the list cached, and a stored session that never finishes restoring
        online = true
        val server = newServer()
        val items =
            newTestOfflineClient(
                    supabase =
                        newTestSupabaseClient(engine = server.engine) {
                            coroutineDispatcher = StandardTestDispatcher(testScheduler)
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
                        },
                    store = newTestOfflineStore(objectDao = objectDao),
                    coordinatorScope = backgroundScope,
                )
                .from(TABLE)
        runCurrent()
        val shown = mutableListOf<OfflineReadResult>()

        // When
        backgroundScope.launch {
            items.selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache).collect { read
                ->
                shown += read
            }
        }
        advanceTimeBy(10.seconds)
        runCurrent()

        // Then — the cache, not the anon key's answer, and not confirmed
        assertEquals(CACHED, shown.first().result?.data)
        assertTrue(shown.first().refresh is RefreshState.Stalled, "${shown.first().refresh}")
    }

    private companion object {
        const val TABLE = "items"
        const val CACHED = """[{"id":1,"title":"cached"}]"""
        const val FRESH = """[{"id":1,"title":"fresh"}]"""
    }
}

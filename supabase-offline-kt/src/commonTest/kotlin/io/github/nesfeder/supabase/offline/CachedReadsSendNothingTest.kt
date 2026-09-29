// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.SupabaseClientBuilder
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.logging.LogLevel
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeObjectDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingSupabaseLogs
import io.github.nesfeder.supabase.offline.testdoubles.idleCoroutineScope
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A read the cache answers sends nothing and asks nobody for a token: not the Auth plugin, whose
 * expired session would otherwise be refreshed inline and logged at ERROR, and not the app's own
 * token provider. Offline, or on a network that hangs, it answers at once.
 */
class CachedReadsSendNothingTest {

    private val objectDao = FakeObjectDao().apply { seedPayload(TABLE, TABLE, ROWS, updatedAt = 1) }
    private val logs = RecordingSupabaseLogs()

    /** The supabase-kt client [items] built last. */
    private lateinit var supabase: SupabaseClient

    private fun items(
        server: RecordingPostgrestServer,
        configure: SupabaseClientBuilder.() -> Unit,
    ): OfflinePostgrestQueryBuilder {
        supabase =
            newTestSupabaseClient(engine = server.engine) {
                defaultLogLevel = LogLevel.DEBUG
                defaultLoggingFactory = { logs }
                configure()
            }
        return newTestOfflineClient(
                supabase = supabase,
                store = newTestOfflineStore(objectDao = objectDao),
                coordinatorScope = idleCoroutineScope(),
            )
            .from(TABLE)
    }

    /**
     * A client signed in with a session that expired a minute ago, which supabase-kt would refresh
     * inline.
     */
    private suspend fun expiredSessionItems(
        server: RecordingPostgrestServer,
        configure: SupabaseClientBuilder.() -> Unit = {},
    ): OfflinePostgrestQueryBuilder {
        val items =
            items(server) {
                install(Postgrest)
                install(Auth) {
                    sessionManager = MemorySessionManager()
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = false
                    alwaysAutoRefresh = true
                    enableLifecycleCallbacks = false
                }
                configure()
            }
        supabase.auth.sessionStatus.first { status -> status !is SessionStatus.Initializing }
        supabase.signInForTest("u-1", expiresInSeconds = -60)
        return items
    }

    private suspend fun cachedReads(items: OfflinePostgrestQueryBuilder) {
        repeat(100) {
            assertEquals(ROWS, items.select(readStrategy = ReadStrategy.CacheFirst()).data)
        }
        repeat(100) {
            assertEquals(ROWS, items.select(readStrategy = ReadStrategy.CacheOnly()).data)
        }
        repeat(20) {
            assertEquals(
                ROWS,
                items.selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly).first().result?.data,
            )
        }
    }

    @Test
    fun with_an_expired_session_offline_a_cached_read_refreshes_nothing_and_logs_no_error() =
        runTest {
            // Given — a session that expired a minute ago, and no network
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val items = expiredSessionItems(server)

            // When
            cachedReads(items)

            // Then — no token refresh or anything else sent, and no ERROR from supabase-kt
            assertEquals(
                emptyList(),
                server.requests.map { request -> "${request.method} ${request.table}" },
            )
            assertEquals(emptyList(), logs.at(LogLevel.ERROR))
        }

    @Test
    fun on_a_network_that_hangs_a_cached_read_answers_at_once() = runTest {
        // Given — an expired session, and a network that never answers within supabase-kt's timeout
        val server = RecordingPostgrestServer(answer = { awaitCancellation() })
        val items = expiredSessionItems(server) { requestTimeout = 3.seconds }

        // When / Then — ten cached reads well inside one request timeout, on the real clock
        withContext(Dispatchers.Default) {
            withTimeout(2.seconds) {
                repeat(10) {
                    assertEquals(ROWS, items.select(readStrategy = ReadStrategy.CacheOnly()).data)
                }
            }
        }
    }

    @Test
    fun an_apps_token_provider_is_not_asked_for_a_cached_read() = runTest {
        // Given — no Auth plugin; the app supplies tokens itself
        val calls = AtomicInt(0)
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val items =
            items(server) {
                accessToken = {
                    calls.incrementAndFetch()
                    testJwt("third-party-user")
                }
                install(Postgrest)
            }

        // When
        cachedReads(items)

        // Then
        assertEquals(0, calls.load())
        assertEquals(emptyList(), server.requests.map { request -> request.table })
    }

    private companion object {
        const val TABLE = "items"
        const val ROWS = """[{"id":1,"title":"a"}]"""
    }
}

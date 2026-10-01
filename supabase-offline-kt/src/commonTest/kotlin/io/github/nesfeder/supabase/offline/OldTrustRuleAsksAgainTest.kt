// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.postgrest.ResponseTrustPolicy
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Under the authenticated-only trust rule, a read that got an answer the rule does not write — one
 * sent while the stored session restored, whatever its size, or one that came back after another
 * user signed in — asks again once the session has settled, and returns the settled user's rows.
 */
class OldTrustRuleAsksAgainTest {

    private val queryCacheDao = FakeQueryCacheDao()

    /** Held by the next read the server answers, until completed. */
    private var heldRead: CompletableDeferred<Unit>? = null

    /** The server: each user's token reads that user's rows, the anon key the public row only. */
    private fun TestScope.newServer(): RecordingPostgrestServer =
        RecordingPostgrestServer(
            answer = { request ->
                heldRead?.let { held ->
                    heldRead = null
                    held.await()
                }
                val token = request.authorization.orEmpty()
                okJson(
                    when {
                        token.contains(testJwt("A")) -> rowsOf("A")
                        token.contains(testJwt("B")) -> rowsOf("B")
                        else -> PUBLIC_ROWS
                    }
                )
            },
            engineDispatcher = StandardTestDispatcher(testScheduler),
        )

    private suspend fun TestScope.launchClient(
        server: RecordingPostgrestServer,
        sessions: SessionManager,
        restoresStoredSession: Boolean,
    ): OfflineClient {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val supabase =
            newTestSupabaseClient(engine = server.engine) {
                coroutineDispatcher = dispatcher
                install(Postgrest) { maxRetries = 0 }
                install(Auth) {
                    sessionManager = sessions
                    codeVerifierCache = MemoryCodeVerifierCache()
                    autoLoadFromStorage = restoresStoredSession
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                }
            }
        runCurrent()
        return newTestOfflineClient(
                supabase = supabase,
                store = newTestOfflineStore(queryCacheDao = queryCacheDao),
                coordinatorScope = backgroundScope,
                responseTrust = ResponseTrustPolicy.AUTHENTICATED_SESSION_ONLY,
            )
            .also { runCurrent() }
    }

    @Test
    fun a_one_shot_read_sent_while_restoring_asks_again_as_the_user() = runTest {
        // Given — A's stored session restoring
        val sessions = FakeHeldSessionManager(storedSessionOf("A"))
        val client = launchClient(newServer(), sessions, restoresStoredSession = true)

        // When — the read goes out on the anon key, then the session settles as A
        val read = async { client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly).data }
        advanceTimeBy(1.seconds)
        runCurrent()
        sessions.release()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — A's rows, not the public row the anon key saw
        assertEquals(rowsOf("A"), read.await())
    }

    @Test
    fun a_network_first_reactive_read_sent_while_restoring_starts_on_the_users_rows() = runTest {
        // Given — A's rows cached from before, and A's stored session restoring
        queryCacheDao.seedPayload(TABLE, TABLE, OLD_ROWS)
        val sessions = FakeHeldSessionManager(storedSessionOf("A"))
        val client = launchClient(newServer(), sessions, restoresStoredSession = true)

        // When
        val first = async {
            client
                .from(TABLE)
                .selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache)
                .first()
        }
        advanceTimeBy(1.seconds)
        runCurrent()
        sessions.release()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — A's rows as they are now
        assertEquals(rowsOf("A"), first.await().result?.data)
    }

    @Test
    fun a_one_shot_read_answered_after_another_user_signed_in_asks_again_as_that_user() = runTest {
        // Given — A signed in, and A's read held on the wire
        val sessions = MemorySessionManager()
        val client = launchClient(newServer(), sessions, restoresStoredSession = false)
        client.supabase.signInForTest("A")
        runCurrent()
        val held = CompletableDeferred<Unit>()
        heldRead = held
        val read = async {
            client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly).data
        }
        runCurrent()

        // When — B signs in, then A's answer arrives
        client.supabase.signInForTest("B")
        runCurrent()
        held.complete(Unit)
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — B's rows
        assertEquals(rowsOf("B"), read.await())
    }

    private companion object {
        const val TABLE = "notes"
        const val PUBLIC_ROWS = """[{"id":"public"}]"""
        const val OLD_ROWS = """[{"id":"public"},{"id":"a0"}]"""

        /** The public row, and the one row [user] can read. */
        fun rowsOf(user: String): String = """[{"id":"public"},{"id":"${user.lowercase()}1"}]"""

        fun storedSessionOf(user: String): UserSession =
            UserSession(
                accessToken = testJwt(user),
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
            )
    }
}

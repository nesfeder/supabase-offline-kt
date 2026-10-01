// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeHeldSessionManager
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.testJwt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * Writes made while a stored session is still restoring: a `QueueIfOffline` write is queued for the
 * stored user, online or not, and sent with that user's token once the session settles. An
 * `OnlineOnly` write is sent at once, as supabase-kt sends it.
 */
class WritesWhileRestoringTest {

    private var online = true
    private lateinit var server: RecordingPostgrestServer
    private val sessions =
        FakeHeldSessionManager(
            UserSession(
                accessToken = testJwt("u-1"),
                refreshToken = "refresh-token",
                expiresIn = 3600,
                tokenType = "bearer",
            )
        )

    private fun TestScope.restoringItems(): OfflinePostgrestQueryBuilder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        server =
            RecordingPostgrestServer(
                answer = {
                    if (!online) connectivityFailure()
                    okJson("")
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
        val client = newTestOfflineClient(supabase = supabase, coordinatorScope = backgroundScope)
        // The plugin starts loading the stored session, the load the held store keeps waiting
        runCurrent()
        return client.from(TABLE)
    }

    @Test
    fun an_online_write_is_queued_until_the_session_settles_then_sent_as_the_user() = runTest {
        // Given
        val items = restoringItems()

        // When — written while the session restores
        val outcome = items.insertJson(ROW)
        runCurrent()

        // Then — queued, and nothing sent on the anon key
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(0, server.writes())

        // When — the session settles as the user
        sessions.release()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — sent once, with the user's token
        val write = server.requests.single { request -> request.method == "POST" }
        assertEquals("Bearer ${testJwt("u-1")}", write.authorization)
    }

    @Test
    fun a_write_made_offline_while_restoring_is_queued() = runTest {
        // Given
        val items = restoringItems()
        online = false

        // When
        val outcome = items.insertJson(ROW)

        // Then
        assertIs<MutationOutcome.Queued>(outcome)
    }

    @Test
    fun an_online_only_write_is_sent_at_once_with_the_token_sent_meanwhile() = runTest {
        // Given
        val items = restoringItems()

        // When
        val outcome = items.insertJson(ROW, mutationStrategy = MutationStrategy.OnlineOnly)

        // Then — sent now, as supabase-kt sends any request while the session loads: on the anon
        // key
        assertEquals(true, outcome is MutationOutcome.Applied)
        assertEquals(
            "Bearer $TEST_SUPABASE_KEY",
            server.requests.single { request -> request.method == "POST" }.authorization,
        )
    }

    private fun RecordingPostgrestServer.writes(): Int = requests.count { request ->
        request.method != "GET"
    }

    private companion object {
        const val TABLE = "items"
        val ROW = Json.parseToJsonElement("""[{"id":1,"title":"a"}]""") as JsonArray
    }
}

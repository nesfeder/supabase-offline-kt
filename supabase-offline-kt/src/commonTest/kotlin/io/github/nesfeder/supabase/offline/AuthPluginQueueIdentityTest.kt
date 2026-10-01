// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueueReplayer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/**
 * The per-identity queue with supabase-kt's real Auth plugin installed. Writes made while signed
 * out replay while signed out (on the anon key); a signed-in user's writes queue and replay while
 * they are signed in; neither blocks the other.
 */
class AuthPluginQueueIdentityTest {

    private val server = RecordingPostgrestServer(answer = { connectivityFailure() })
    private val supabase: SupabaseClient =
        newTestSupabaseClient(engine = server.engine) {
            install(Postgrest) { maxRetries = 0 }
            install(Auth) {
                sessionManager = MemorySessionManager()
                codeVerifierCache = MemoryCodeVerifierCache()
                autoLoadFromStorage = false
                alwaysAutoRefresh = false
                enableLifecycleCallbacks = false
            }
        }
    private val store = newTestOfflineStore()
    private val builder =
        OfflinePostgrestQueryBuilderImpl(delegate = supabase.postgrest.from(TABLE), store = store)
    private val syncer = MutationQueueReplayer(store = store, supabase = supabase)

    private fun sentBodies(): List<String> =
        server.requests.filter { it.method == "POST" }.map { it.body }

    @Test
    fun signed_out_writes_replay_signed_out_and_a_signed_in_users_writes_replay_signed_in() =
        runTest {
            // Given — Auth installed, nobody signed in, and an offline write
            supabase.auth.sessionStatus.first { status -> status !is SessionStatus.Initializing }
            val anonymousOutcome = builder.insertJson(rows("""[{"id":"n0","title":"anonymous"}]"""))
            val attemptsWhileOffline = sentBodies().size

            // When — a pass while still signed out
            server.answer = { okJson("[]") }
            val signedOut = syncer.sync()

            // Then — the anonymous write went out
            assertEquals(MutationOutcome.Queued, anonymousOutcome)
            assertEquals(1, signedOut.syncedCount)
            assertEquals(
                listOf("""[{"id":"n0","title":"anonymous"}]"""),
                sentBodies().drop(attemptsWhileOffline),
            )

            // When — user A signs in and writes offline, then a pass runs signed in
            supabase.auth.importSession(
                UserSession(
                    accessToken = jwt("user-a"),
                    refreshToken = "r",
                    expiresIn = 3600,
                    tokenType = "bearer",
                ),
                autoRefresh = false,
            )
            server.answer = { connectivityFailure() }
            val userAOutcome = builder.insertJson(rows("""[{"id":"n1","title":"A's note"}]"""))
            server.answer = { okJson("[]") }
            val signedIn = syncer.sync()

            // Then — A's write queued and went out under A
            assertEquals(MutationOutcome.Queued, userAOutcome)
            assertEquals(1, signedIn.syncedCount)
            assertEquals(0, store.allPendingMutations().size)
        }

    private fun jwt(subject: String): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload =
            encoder.encode(
                """{"sub":"$subject","role":"authenticated","exp":9999999999}""".encodeToByteArray()
            )
        return "$header.$payload.signature"
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private companion object {
        const val TABLE = "notes"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Offline for longer than a session token lives, supabase-kt refreshes the token before building a
 * request, cannot, and throws before anything is sent. A write that may queue still queues, as the
 * signed-in user's: telling a dry run needs only the request's shape, not the session's token. A
 * dry run in the same state is still refused.
 */
class ExpiredSessionWriteTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestOfflineStore(mutationDao = mutationDao)

    @Test
    fun a_write_with_an_expired_session_offline_is_queued_as_its_users() = runTest {
        // Given — user A's session expired, and no network to refresh it
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val inserted = builder.insertJson(rows("""[{"id":"k1","title":"Buy milk"}]"""))
        val updated = builder.updateJson(patch()) { filter { eq("id", "k1") } }

        // Then — both queued, as A's, and no request went out with a token it could not refresh
        assertEquals(MutationOutcome.Queued, inserted)
        assertEquals(MutationOutcome.Queued, updated)
        assertEquals(listOf("user:user-a", "user:user-a"), mutationDao.inserted.map { it.owner })
        assertTrue(server.requests.none { it.table == TABLE }, "${server.requests}")
    }

    @Test
    fun a_dry_run_with_an_expired_session_offline_is_still_refused() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            OfflinePostgrestQueryBuilderImpl(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val outcome = runCatching {
            builder.updateJson(patch()) {
                filter { eq("id", "k1") }
                dryRun()
            }
        }

        // Then — the caller gets the failure, and nothing is queued
        assertTrue(outcome.isFailure)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    private fun rows(json: String): JsonArray = Json.parseToJsonElement(json) as JsonArray

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"new"}""")

    private companion object {
        const val TABLE = "todos"
    }
}

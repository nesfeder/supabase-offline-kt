// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Offline for longer than a session token lives, a write that may queue still queues
 * (`ExpiredSessionWriteTest`), but a supabase-kt 3.8 `dryRun()` in the same state is still refused.
 */
class ExpiredSessionDryRunTest {

    private val mutationDao = FakeMutationDao()
    private val store = newTestStore(mutationDao = mutationDao)

    @Test
    fun a_dry_run_with_an_expired_session_offline_is_still_refused() = runTest {
        // Given
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            newTestOfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
            )

        // When
        val outcome = runCatching {
            builder.update(patch()) {
                filter { eq("id", "k1") }
                dryRun()
            }
        }

        // Then — the caller gets the failure, and nothing is queued
        assertTrue(outcome.isFailure)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"new"}""")

    private companion object {
        const val TABLE = "todos"
    }
}

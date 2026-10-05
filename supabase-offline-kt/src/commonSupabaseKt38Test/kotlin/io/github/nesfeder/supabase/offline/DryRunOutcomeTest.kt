// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.assertNotQueueable
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * What happens to a write built with supabase-kt 3.8's `dryRun()`, once the engine has told it is
 * one. On the online-success path the server's own `Preference-Applied` header decides whether the
 * write was rolled back: PostgREST's default `db-tx-end` ignores `tx=rollback` and commits, and
 * then the write must be overlaid like any other. Offline, a dry run is never persisted.
 */
class DryRunOutcomeTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store = newTestStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    @Test
    fun a_dry_run_the_server_did_not_honor_is_overlaid_like_any_committed_write() = runTest {
        // Given — a server on PostgREST's default db-tx-end: tx=rollback is ignored and the write
        // commits
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ORIGINAL)
        val builder =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When
        builder.update(patch(), MutationStrategy.QueueIfOffline) {
            filter { eq("id", 1) }
            dryRun()
        }

        // Then — the cache shows the committed write
        assertEquals(
            """[{"id":1,"title":"new"}]""",
            queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload,
        )
    }

    @Test
    fun a_dry_run_the_server_rolled_back_leaves_the_cache_untouched() = runTest {
        // Given — a server that allows the override and says so
        val server =
            RecordingPostgrestServer(
                answer = { okJson("[]", mapOf(PREFERENCE_APPLIED to "tx=rollback")) }
            )
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ORIGINAL)
        val builder =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When
        builder.update(patch(), MutationStrategy.QueueIfOffline) {
            filter { eq("id", 1) }
            dryRun()
        }

        // Then
        assertEquals(ORIGINAL, queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload)
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun an_offline_dry_run_is_never_persisted() = runTest {
        // Given — offline
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val builder =
            newTestOfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When / Then
        assertNotQueueable<HttpRequestException> {
            builder.update(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
                dryRun()
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"new"}""")

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?id=eq.1"
        const val ORIGINAL = """[{"id":1,"title":"committed"}]"""
        const val PREFERENCE_APPLIED = "Preference-Applied"
    }
}

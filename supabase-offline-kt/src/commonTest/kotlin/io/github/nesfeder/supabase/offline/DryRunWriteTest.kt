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
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * A write built with `dryRun()` asks PostgREST to roll the transaction back (`Prefer:
 * tx=rollback`). The engine never queues one — the queue does not carry `Prefer`, so a queued dry
 * run would replay as a real write — and does not overlay one the server says it rolled back
 * (`Preference-Applied: tx=rollback`). Here the server honours the preference, as a PostgREST that
 * allows the transaction override does.
 */
class DryRunWriteTest {

    private val server =
        RecordingPostgrestServer(
            answer = { okJson("[]", mapOf(PREFERENCE_APPLIED to "tx=rollback")) }
        )
    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)
    private val builder =
        OfflinePostgrestQueryBuilderImpl(
            delegate = server.supabaseClient().postgrest.from(TABLE),
            store = store,
        )

    @Test
    fun an_online_dry_run_update_leaves_the_cache_untouched() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ORIGINAL)

        // When
        val outcome =
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
                dryRun()
            }

        // Then — the server saw a dry run, rolled it back, and the cache still shows the committed
        // row
        assertIs<MutationOutcome.Applied>(outcome)
        assertTrue(server.requests.single().prefer.orEmpty().contains("tx=rollback"))
        assertEquals(ORIGINAL, queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload)
    }

    @Test
    fun an_online_dry_run_insert_is_not_materialized_into_the_cache() = runTest {
        // Given
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ORIGINAL)

        // When
        builder.insertJson(
            Json.parseToJsonElement("""[{"id":2,"title":"never committed"}]""") as JsonArray
        ) {
            dryRun()
        }

        // Then
        assertEquals(ORIGINAL, queryCacheDao.getRow(TABLE, CACHE_KEY)?.payload)
    }

    @Test
    fun an_offline_dry_run_is_refused_and_never_queued() = runTest {
        // Given
        server.answer = { connectivityFailure() }

        // When / Then — the connectivity failure surfaces, as it would under OnlineOnly
        assertFailsWith<HttpRequestException> {
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
                dryRun()
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_dry_run_on_a_table_with_pending_writes_goes_online_instead_of_joining_the_queue() =
        runTest {
            // Given — an earlier write to the table is still queued
            server.answer = { connectivityFailure() }
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) { filter { eq("id", 2) } }
            server.answer = { okJson("[]", mapOf(PREFERENCE_APPLIED to "tx=rollback")) }

            // When
            val outcome =
                builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                    filter { eq("id", 1) }
                    dryRun()
                }

            // Then — sent as the dry run it is; nothing joined the queue
            assertIs<MutationOutcome.Applied>(outcome)
            assertTrue(server.requests.last().prefer.orEmpty().contains("tx=rollback"))
            assertEquals(1, mutationDao.inserted.size)
        }

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"never committed"}""")

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?id=eq.1"
        const val ORIGINAL = """[{"id":1,"title":"committed"}]"""
        const val PREFERENCE_APPLIED = "Preference-Applied"
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.nesfeder.supabase.offline.internal.postgrest.OfflineTable
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeRemoteTable
import io.github.nesfeder.supabase.offline.testdoubles.RecordingOfflineLogger
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import io.ktor.client.network.sockets.ConnectTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A dry run must never reach the queue, so the engine classifies a write before it persists it.
 * When it cannot tell whether a write is a dry run, it does not persist it at all: it sends it, and
 * never queues it — and says so, once, at ERROR. On the online-success path the server's own
 * `Preference-Applied` header decides whether the write was rolled back: PostgREST's default
 * `db-tx-end` ignores `tx=rollback` and commits, and then the write must be overlaid like any
 * other.
 */
class DryRunClassificationTest {

    private val queryCacheDao = FakeQueryCacheDao()
    private val mutationDao = FakeMutationDao()
    private val store =
        newTestOfflineStore(queryCacheDao = queryCacheDao, mutationDao = mutationDao)

    @Test
    fun a_write_whose_dry_run_status_cannot_be_told_is_never_queued_and_the_engine_says_so_once() =
        runTest {
            // Given — a handle that cannot classify writes, offline
            val logger = RecordingOfflineLogger()
            val client = newTestSupabaseClient()
            val remoteTable =
                FakeRemoteTable(
                    table = TABLE,
                    postgrest = client.postgrest,
                    onUpdate = { throw ConnectTimeoutException("connect timed out", null) },
                    dryRunVerdict = null,
                )
            val builder =
                OfflineTable(
                    delegate = remoteTable,
                    store = store,
                    engineLogger = logger.asEngineLogger(),
                )

            // When — two such writes
            repeat(2) {
                assertFailsWith<ConnectTimeoutException> {
                    builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                        filter { eq("id", 1) }
                    }
                }
            }

            // Then — nothing was queued, and one ERROR says why
            assertTrue(mutationDao.inserted.isEmpty())
            assertEquals(
                1,
                logger.entriesAt(OfflineLogLevel.ERROR).count { it.message.contains("dry run") },
            )
        }

    @Test
    fun a_dry_run_the_server_did_not_honour_is_overlaid_like_any_committed_write() = runTest {
        // Given — a server on PostgREST's default db-tx-end: tx=rollback is ignored and the write
        // commits
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        queryCacheDao.seedPayload(TABLE, CACHE_KEY, ORIGINAL)
        val builder =
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When
        builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
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
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When
        builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
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
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
            )

        // When / Then
        assertFailsWith<HttpRequestException> {
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
                dryRun()
            }
        }
        assertTrue(mutationDao.inserted.isEmpty())
    }

    @Test
    fun a_write_whose_session_token_cannot_be_refreshed_is_classified_and_queued() = runTest {
        // Given — an expired session, and no network to refresh it: supabase-kt fails the write
        // while building its request, but the check reads it on a handle that needs no token
        val logger = RecordingOfflineLogger()
        val server = RecordingPostgrestServer(answer = { connectivityFailure() })
        val supabase = newTestSupabaseClientWithAuth(server.engine, alwaysAutoRefresh = true)
        supabase.signInForTest("user-a", expiresInSeconds = -60)
        val builder =
            OfflineTable(
                delegate = supabase.postgrest.from(TABLE),
                store = store,
                engineLogger = logger.asEngineLogger(),
            )

        // When
        val outcome =
            builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                filter { eq("id", 1) }
            }

        // Then — queued, without a false "cannot tell" ERROR
        assertIs<MutationOutcome.Queued>(outcome)
        assertEquals(
            0,
            logger.entriesAt(OfflineLogLevel.ERROR).count { it.message.contains("dry run") },
        )
    }

    @Test
    fun a_write_whose_request_cannot_be_built_fails_to_its_caller_and_is_never_queued() = runTest {
        // Given — a request lambda that throws from its second run, the dry-run check's; the first
        // built the request the queue would capture
        val logger = RecordingOfflineLogger()
        val server = RecordingPostgrestServer(answer = { okJson("[]") })
        val builder =
            OfflineTable(
                delegate = server.supabaseClient().postgrest.from(TABLE),
                store = store,
                engineLogger = logger.asEngineLogger(),
            )
        var runs = 0

        // When
        val failure =
            assertFailsWith<IllegalStateException> {
                builder.updateJson(patch(), MutationStrategy.QueueIfOffline) {
                    runs++
                    check(runs < 2) { "cannot build" }
                    filter { eq("id", 1) }
                }
            }

        // Then — the caller gets the write's own failure; nothing queued, sent or reported
        assertEquals("cannot build", failure.message)
        assertEquals(0, mutationDao.insertCalls)
        assertTrue(server.requests.isEmpty(), "${server.requests}")
        assertEquals(
            0,
            logger.entriesAt(OfflineLogLevel.ERROR).count { it.message.contains("dry run") },
        )
    }

    private fun patch(): JsonElement = Json.parseToJsonElement("""{"title":"new"}""")

    private companion object {
        const val TABLE = "todos"
        const val CACHE_KEY = "todos?id=eq.1"
        const val ORIGINAL = """[{"id":1,"title":"committed"}]"""
        const val PREFERENCE_APPLIED = "Preference-Applied"
    }
}

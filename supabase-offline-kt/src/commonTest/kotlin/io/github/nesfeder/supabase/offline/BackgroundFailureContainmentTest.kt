// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeQueryCacheDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.RecordingUncaughtExceptions
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A failure in work the client does by itself — a refresh, a replay pass, the identity watcher, the
 * connectivity monitor — is logged through the log sink and stays inside the client: nothing
 * reaches the scope the client was made in, even when that is the app's own scope, and the client
 * goes on serving calls. Each test injects one failure and waits, on real threads, for its log
 * line.
 */
class BackgroundFailureContainmentTest {

    private val server = RecordingPostgrestServer()
    private val logs = RecordingLogSink()

    /** Records what would crash the app: anything that reaches the app's scope uncaught. */
    private val uncaught = RecordingUncaughtExceptions()

    /** The app's own scope, made the client's parent, as the old testing factory made it. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + uncaught)

    @AfterTest
    fun cancelTheAppScope() {
        appScope.cancel()
    }

    @Test
    fun a_refresh_whose_cache_write_fails_is_logged_and_the_read_recovers() = runTest {
        // Given — the first cache write of a reactive read's refresh fails
        val cacheDao = FakeQueryCacheDao().apply { failNextUpsertOf { true } }
        server.answer = { okJson(ROWS) }
        val client = clientOver(queryCacheDao = cacheDao)

        // When — the read refreshes, fails, and tries again on its schedule
        val rows =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(WAIT) {
                    client
                        .from(TABLE)
                        .selectAsFlow(readStrategy = FlowReadStrategy.CacheFirstThenNetwork)
                        .first { snapshot -> snapshot.result?.data == ROWS }
                }
            }

        // Then — the rows arrived after all, the failure was logged, nothing escaped
        assertNotNull(rows, "the read never recovered from the failed refresh")
        assertLogged("IllegalStateException")
        assertEquals(emptyList(), uncaught.recorded())
        client.close()
    }

    @Test
    fun a_replay_pass_that_fails_is_logged_and_the_queue_still_sends() = runTest {
        // Given — one write queued offline, and recording the next send will fail
        val mutationDao = FakeMutationDao()
        val client = clientOver(mutationDao = mutationDao)
        server.answer = { connectivityFailure() }
        client.from(TABLE).insert(rowsToWrite(id = 1, title = "first"))
        server.answer = { okJson("[]") }
        mutationDao.failNextMarkSendUnconfirmed = true

        // When — a write made online queues behind it and asks the library for a pass, which fails
        val second = client.from(TABLE).insert(rowsToWrite(id = 2, title = "second"))
        awaitLogged("IllegalStateException")
        val report = client.syncPendingMutations()

        // Then — the app's own sync sends both, and nothing escaped
        assertIs<MutationOutcome.Queued>(second)
        assertEquals(2, report.syncedCount)
        assertEquals(emptyList(), uncaught.recorded())
        client.close()
    }

    @Test
    fun an_identity_watcher_that_fails_is_logged_and_the_client_keeps_serving() = runTest {
        // Given — the watcher's first read of the queue will fail
        val mutationDao = FakeMutationDao().apply { failNextHasPendingOwnedBy = true }
        server.answer = { okJson(ROWS) }
        val supabase = newTestSupabaseClientWithAuth(server.engine)

        // When — the client starts watching the session, and the read fails
        val client =
            newTestOfflineClient(
                supabase = supabase,
                mutationDao = mutationDao,
                engineLogger = logs.asEngineLogger(),
                coordinatorScope = appScope,
            )
        awaitLogged("IllegalStateException")

        // Then — reads and writes still work, and nothing escaped
        assertEquals(ROWS, client.from(TABLE).select().data)
        assertIs<MutationOutcome.Applied>(
            client.from(TABLE).insert(rowsToWrite(id = 3, title = "after"))
        )
        assertEquals(emptyList(), uncaught.recorded())
        client.close()
    }

    @Test
    fun a_connectivity_monitor_that_fails_is_logged_and_the_client_keeps_serving() = runTest {
        // Given — a monitor that reports offline, then breaks
        server.answer = { okJson(ROWS) }
        val brokenMonitor = flow {
            emit(false)
            error("Scripted failure of the connectivity monitor")
        }

        // When
        val client =
            newTestOfflineClient(
                supabase = newTestSupabaseClient(server.engine),
                engineLogger = logs.asEngineLogger(),
                coordinatorScope = appScope,
                networkAvailability = brokenMonitor,
            )
        awaitLogged("IllegalStateException")

        // Then — a request is still tried (the monitor is a hint), and nothing escaped
        assertEquals(ROWS, client.from(TABLE).select().data)
        assertEquals(emptyList(), uncaught.recorded())
        client.close()
    }

    /** A client over the given tables whose work runs in the app's scope and logs to [logs]. */
    private fun clientOver(
        queryCacheDao: QueryCacheDao = FakeQueryCacheDao(),
        mutationDao: MutationDao = FakeMutationDao(),
    ): OfflineClient =
        newTestOfflineClient(
            supabase = newTestSupabaseClient(server.engine),
            queryCacheDao = queryCacheDao,
            mutationDao = mutationDao,
            engineLogger = logs.asEngineLogger(),
            coordinatorScope = appScope,
        )

    /** Waits, on a real clock, until a WARNING or ERROR naming [failureType] is logged. */
    private suspend fun awaitLogged(failureType: String) {
        val logged =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(WAIT) {
                    while (!isLogged(failureType)) delay(POLL)
                    true
                }
            }
        assertTrue(logged == true, "nothing logged $failureType: ${logs.entries}")
    }

    private fun assertLogged(failureType: String) {
        assertTrue(isLogged(failureType), "nothing logged $failureType: ${logs.entries}")
    }

    private fun isLogged(failureType: String): Boolean =
        logs.entries.any { entry ->
            entry.level >= EngineLogLevel.WARNING && failureType in entry.message
        }

    /** One row with its key, so its replay records the send (only a keyed insert's does). */
    private fun rowsToWrite(id: Int, title: String): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("id", id)
                    put("title", title)
                }
            )
        )

    private companion object {
        const val TABLE = "todos"
        const val ROWS = """[{"id":1,"title":"Buy milk"}]"""
        val WAIT = 10.seconds
        val POLL = 10.milliseconds
    }
}

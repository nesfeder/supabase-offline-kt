// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.SupabaseClient
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.testdoubles.FakeMutationDao
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.enqueueInsert
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.newTestStore
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClientWithAuth
import io.github.nesfeder.supabase.offline.testdoubles.signInForTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * When the library sends queued writes by itself, and what `autoSync = false` stops: the passes it
 * starts when the session settles and when the network comes back, never those that send a write
 * queued behind an earlier one. With write-ahead, an online write made while an earlier one of its
 * user is queued returns `Queued`, and only those passes send it.
 */
class AutoSyncTest {

    /** A client whose work and whose server's answers all run on this test's scheduler. */
    private class Rig(
        val server: RecordingPostgrestServer,
        val supabase: SupabaseClient,
        val client: OfflineClient,
        val scope: CoroutineScope,
    ) {
        /** The writes the server received, the attempts that failed offline included. */
        val writes: List<String>
            get() = server.requests.filter { it.method == "POST" }.map { it.body }
    }

    private suspend fun TestScope.rig(
        autoSync: Boolean,
        signal: MutableStateFlow<Boolean>? = null,
        withAuth: Boolean = false,
        mutationDao: MutationDao = FakeMutationDao(),
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server =
            RecordingPostgrestServer(
                answer = { connectivityFailure() },
                engineDispatcher = dispatcher,
            )
        val supabase =
            if (withAuth) newTestSupabaseClientWithAuth(engine = server.engine)
            else server.supabaseClient()
        val client =
            newTestOfflineClient(
                supabase = supabase,
                mutationDao = mutationDao,
                coordinatorScope = scope,
                config = Offline.Config().apply { this.autoSync = autoSync },
                networkAvailability = signal,
            )
        return Rig(server, supabase, client, scope)
    }

    /** Runs everything due, then lets every backoff the passes may wait out go by. */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(2.minutes)
        runCurrent()
    }

    @Test
    fun with_auto_sync_off_an_online_write_behind_a_queued_one_is_sent_without_the_app_asking() =
        runTest {
            // Given — the library may not sync by itself, and a write was queued offline. The
            // client runs on real threads, as an app's does
            val server = RecordingPostgrestServer(answer = { connectivityFailure() })
            val client =
                newTestOfflineClient(
                    supabase = server.supabaseClient(),
                    config = Offline.Config().apply { autoSync = false },
                )
            client.from(TABLE).insert(row(1))

            // When — the network is back and the app writes again; it never calls sync
            server.answer = { okJson("[]") }
            val second = client.from(TABLE).insert(row(2))
            val sent =
                withContext(Dispatchers.Default) {
                    withTimeout(10.seconds) {
                        while (client.pendingMutationCount() != 0) delay(5)
                        server.requests.filter { it.method == "POST" }.map { it.body }.takeLast(2)
                    }
                }

            // Then — the online write waited behind the queued one, and both were sent, in order
            assertIs<MutationOutcome.Queued>(second)
            assertEquals(listOf(row(1).toString(), row(2).toString()), sent)
            client.close()
        }

    @Test
    fun the_network_coming_back_sends_queued_writes_by_itself() = runTest {
        // Given — the device is offline and a write is queued
        val signal = MutableStateFlow(false)
        val rig = rig(autoSync = true, signal = signal)
        rig.client.from(TABLE).insert(row(1))
        runCurrent()

        // When — the network comes back
        rig.server.requests.clear()
        rig.server.answer = { okJson("[]") }
        signal.value = true
        settle()

        // Then
        assertEquals(listOf(row(1).toString()), rig.writes)
        assertEquals(0, rig.client.pendingMutationCount())
        rig.scope.cancel()
    }

    @Test
    fun with_auto_sync_off_the_network_coming_back_sends_nothing() = runTest {
        // Given
        val signal = MutableStateFlow(false)
        val rig = rig(autoSync = false, signal = signal)
        rig.client.from(TABLE).insert(row(1))
        runCurrent()

        // When
        rig.server.requests.clear()
        rig.server.answer = { okJson("[]") }
        signal.value = true
        settle()

        // Then — the write waits for the app
        assertEquals(emptyList(), rig.writes)
        assertEquals(1, rig.client.pendingMutationCount())
        rig.scope.cancel()
    }

    @Test
    fun turning_network_access_back_on_sends_queued_writes_by_itself() = runTest {
        // Given — network access is off and a write is queued; nothing was sent
        val rig = rig(autoSync = true)
        rig.server.answer = { okJson("[]") }
        rig.client.disableNetwork()
        rig.client.from(TABLE).insert(row(1))
        settle()
        val sentWhileOff = rig.writes

        // When
        rig.client.enableNetwork()
        settle()

        // Then
        assertEquals(emptyList(), sentWhileOff)
        assertEquals(listOf(row(1).toString()), rig.writes)
        rig.scope.cancel()
    }

    @Test
    fun with_auto_sync_off_turning_network_access_back_on_sends_nothing() = runTest {
        // Given
        val rig = rig(autoSync = false)
        rig.server.answer = { okJson("[]") }
        rig.client.disableNetwork()
        rig.client.from(TABLE).insert(row(1))

        // When
        rig.client.enableNetwork()
        settle()

        // Then
        assertEquals(emptyList(), rig.writes)
        assertEquals(1, rig.client.pendingMutationCount())
        rig.scope.cancel()
    }

    @Test
    fun auto_sync_is_read_once_when_the_client_opens() = runTest {
        // Given — a client opened with autoSync on, whose configuration the app then changes, and
        // a write queued offline
        val signal = MutableStateFlow(false)
        val rig = rig(autoSync = true, signal = signal)
        rig.client.config.autoSync = false
        rig.client.from(TABLE).insert(row(1))
        runCurrent()

        // When — the network comes back
        rig.server.requests.clear()
        rig.server.answer = { okJson("[]") }
        signal.value = true
        settle()

        // Then — the client kept the value it was opened with
        assertEquals(listOf(row(1).toString()), rig.writes)
        assertEquals(0, rig.client.pendingMutationCount())
        rig.scope.cancel()
    }

    @Test
    fun the_session_settling_with_writes_queued_sends_them_unless_auto_sync_is_off() = runTest {
        for (autoSync in listOf(true, false)) {
            // Given — user A signed in, and A's write queued offline
            val rig = rig(autoSync = autoSync, withAuth = true)
            rig.supabase.signInForTest(USER_A)
            settle()
            rig.client.from(TABLE).insert(row(1))
            settle()

            // When — the network is back and A's session settles anew, as a refresh leaves it
            rig.server.requests.clear()
            rig.server.answer = { okJson("[]") }
            rig.supabase.signInForTest(USER_A)
            settle()

            // Then — sent by itself, or left for the app
            val expected = if (autoSync) listOf(row(1).toString()) else emptyList()
            assertEquals(expected, rig.writes, "autoSync = $autoSync")
            assertEquals(if (autoSync) 0 else 1, rig.client.pendingMutationCount())
            rig.scope.cancel()
        }
    }

    @Test
    fun a_reconnection_sends_at_once_however_long_a_wait_failed_passes_built() = runTest {
        // Given — the monitor says online but the server cannot be reached (a captive portal): a
        // write is queued, then each write queued behind it asks for a pass that fails, until the
        // wait before the next pass has grown to its cap
        val signal = MutableStateFlow(true)
        val rig = rig(autoSync = true, signal = signal)
        rig.client.from(TABLE).insert(row(1))
        for (id in 2..9) {
            rig.client.from(TABLE).insert(row(id))
            runCurrent()
            advanceTimeBy(65.seconds)
        }
        rig.client.from(TABLE).insert(row(10))
        runCurrent()

        // When — the device drops off the network and comes back, and the server answers again
        signal.value = false
        runCurrent()
        rig.server.answer = { okJson("[]") }
        signal.value = true
        runCurrent()

        // Then — the queue was sent at once, not after the minute the failed passes asked for
        assertEquals(0, rig.client.pendingMutationCount())
        rig.scope.cancel()
    }

    @Test
    fun no_pass_runs_by_itself_while_the_monitor_says_offline() = runTest {
        // Given — the device is offline and a write is queued: its own attempt is the one request
        val signal = MutableStateFlow(false)
        val rig = rig(autoSync = true, signal = signal)
        rig.client.from(TABLE).insert(row(1))
        runCurrent()

        // When — writes queued behind it ask for passes while the monitor still says offline
        rig.client.from(TABLE).insert(row(2))
        rig.client.from(TABLE).insert(row(3))
        settle()

        // Then — nothing more was tried until the network comes back
        assertEquals(listOf(row(1).toString()), rig.writes)
        rig.scope.cancel()
    }

    @Test
    fun a_client_without_auth_sends_what_an_earlier_run_left_queued_when_it_opens() = runTest {
        for (autoSync in listOf(true, false)) {
            // Given — a write an earlier run left queued
            val mutationDao = FakeMutationDao()
            newTestStore(mutationDao = mutationDao).mutationQueue.enqueueInsert(TABLE, row(1))

            // When — a client without Auth opens with the server reachable, and nothing else asks
            val rig = rig(autoSync = autoSync, mutationDao = mutationDao)
            rig.server.answer = { okJson("[]") }
            settle()

            // Then — sent by itself, or left for the app
            val expected = if (autoSync) listOf(row(1).toString()) else emptyList()
            assertEquals(expected, rig.writes, "autoSync = $autoSync")
            rig.scope.cancel()
        }
    }

    private fun row(id: Int): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("title", "note $id")
            }
        )
    }

    private companion object {
        const val TABLE = "notes"
        const val USER_A = "user-a"
    }
}

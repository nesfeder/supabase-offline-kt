// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `disableNetwork()` turns the library's network access off, as an airplane-mode switch does: reads
 * are answered from the cache as offline, writes that may queue are queued, nothing is sent, and
 * the status says `DISABLED`, not `OFFLINE`. Requests that must reach the server throw
 * `NetworkDisabledException`. The switch is not kept: a new client starts with access on.
 */
class NetworkSwitchTest {

    private val server = RecordingPostgrestServer(answer = { okJson(ROWS) })

    @Test
    fun the_status_says_disabled_as_soon_as_the_switch_returns_and_not_after_it_is_back_on() =
        runTest {
            // Given
            val client = newTestOfflineClient(supabase = server.supabaseClient())
            val before = client.syncStatus.value.network

            // When / Then
            client.disableNetwork()
            assertEquals(NetworkStatus.DISABLED, client.syncStatus.value.network)
            client.enableNetwork()
            assertEquals(before, client.syncStatus.value.network)
            client.close()
        }

    @Test
    fun while_disabled_reads_come_from_the_cache_writes_queue_and_nothing_is_sent() = runTest {
        // Given — a list read and cached while online
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        val requestsBefore = server.requests.size

        // When
        client.disableNetwork()
        val read = client.from(TABLE).select()
        val write = client.from(TABLE).insert(row(2))
        val report = client.syncPendingMutations()

        // Then
        assertEquals(ROWS, read.data)
        assertTrue(read.isFromCache)
        assertIs<MutationOutcome.Queued>(write)
        assertEquals(0 to 1, report.syncedCount to report.remainingCount)
        assertEquals(requestsBefore, server.requests.size, "${server.requests}")
        client.close()
    }

    @Test
    fun while_disabled_calls_that_must_reach_the_server_throw_and_send_nothing() = runTest {
        // Given
        val client = newTestOfflineClient(supabase = server.supabaseClient())
        client.disableNetwork()

        // When
        val networkOnly =
            assertFailsWith<NetworkDisabledException> {
                client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
            }
        val uncached = assertFailsWith<NetworkDisabledException> { client.from(TABLE).select() }
        assertFailsWith<NetworkDisabledException> {
            client.from(TABLE).insert(row(1), mutationStrategy = MutationStrategy.OnlineOnly)
        }
        assertFailsWith<NetworkDisabledException> { client.rpc("tick") }

        // Then — the library's own failure, seen as being offline, and nothing was sent
        assertTrue(networkOnly.isNetworkOrConnectivityFailure())
        assertTrue(uncached.isNetworkOrConnectivityFailure())
        assertEquals(emptyList(), server.requests)
        client.close()
    }

    @Test
    fun while_disabled_a_reactive_reads_refresh_is_paused_and_runs_once_access_is_back() = runTest {
        // Given — the client's work on this test's scheduler, access off
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val onScheduler =
            RecordingPostgrestServer(answer = { okJson(ROWS) }, engineDispatcher = dispatcher)
        val client =
            newTestOfflineClient(
                supabase = onScheduler.supabaseClient(),
                coordinatorScope = scope,
            )
        client.disableNetwork()
        val shown = MutableStateFlow<QuerySnapshot?>(null)
        scope.launch { client.from(TABLE).selectAsFlow().collect { shown.value = it } }

        // When — a long while passes, then access is back
        runCurrent()
        advanceTimeBy(5.minutes)
        runCurrent()
        val whileDisabled = shown.value?.refresh
        val sentWhileDisabled = onScheduler.requests.size
        client.enableNetwork()
        runCurrent()

        // Then
        assertEquals(RefreshState.Paused, whileDisabled)
        assertEquals(0, sentWhileDisabled)
        assertEquals(RefreshState.Idle, shown.value?.refresh)
        assertEquals(ROWS, shown.value?.result?.data)
        scope.cancel()
    }

    @Test
    fun a_new_client_starts_with_network_access_on() = runTest {
        // Given — one client turned access off and closed
        val first = newTestOfflineClient(supabase = server.supabaseClient())
        first.disableNetwork()
        first.close()

        // When
        val next = newTestOfflineClient(supabase = server.supabaseClient())
        next.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // Then
        assertTrue(next.syncStatus.value.network != NetworkStatus.DISABLED)
        assertEquals(1, server.requests.size)
        next.close()
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
        const val ROWS = """[{"id":1,"title":"note 1"}]"""
    }
}

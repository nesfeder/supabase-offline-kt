// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline

import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Count
import io.github.nesfeder.supabase.offline.internal.OfflineClient
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.connectivityFailure
import io.github.nesfeder.supabase.offline.testdoubles.RecordingPostgrestServer.Companion.okJson
import io.github.nesfeder.supabase.offline.testdoubles.newTestOfflineClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What a read says about its rows, true of each emission: whether they are the device's copy or an
 * answer the read had from the server, when they were fetched (never when a local change was
 * applied to them), whether a queued write changed one, and whether a refresh that cannot reach the
 * network is waiting for it (`Paused`) or failing (`Stalled`). One-shot reads say the same through
 * extensions on supabase-kt's result.
 */
class QuerySnapshotTest {

    /** The store's clock, in epoch milliseconds: what each cached payload is stamped with. */
    private var clock = 1_000L

    /** A client on this test's scheduler, its server answering through [answer]. */
    private class Rig(
        val server: RecordingPostgrestServer,
        val client: OfflineClient,
        val scope: CoroutineScope,
    )

    private fun TestScope.rig(
        signal: MutableStateFlow<Boolean>? = null,
        answer: suspend (RecordingPostgrestServer.RecordedRequest) -> String = { ROWS },
    ): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val server =
            RecordingPostgrestServer(
                answer = { request -> okJson(answer(request)) },
                engineDispatcher = dispatcher,
            )
        val client =
            newTestOfflineClient(
                supabase = server.supabaseClient(),
                nowMillis = { clock },
                coordinatorScope = scope,
                networkAvailability = signal,
            )
        return Rig(server, client, scope)
    }

    /** Collects [flow] in [scope], keeping every emission. */
    private fun <T> collected(scope: CoroutineScope, flow: Flow<T>): List<T> {
        val values = mutableListOf<T>()
        scope.launch { flow.collect { value -> values += value } }
        return values
    }

    @Test
    fun an_emission_is_from_the_cache_until_the_reads_refresh_fetches_it() = runTest {
        // Given — the list cached at 1,000; its refresh, at 2,000, is held at the server
        val held = CompletableDeferred<Unit>()
        var holding = false
        val rig =
            rig(
                answer = {
                    if (holding) held.await()
                    ROWS
                }
            )
        rig.client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        clock = 2_000L
        holding = true

        // When
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        val beforeRefresh = shown.last()
        held.complete(Unit)
        runCurrent()

        // Then — the same rows, from the cache and fetched at 1,000, then the refresh's, at 2,000
        assertTrue(beforeRefresh.isFromCache)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), beforeRefresh.fetchedAt)
        assertEquals(RefreshState.Refreshing, beforeRefresh.refresh)
        val afterRefresh = shown.last()
        assertFalse(afterRefresh.isFromCache)
        assertEquals(Instant.fromEpochMilliseconds(2_000L), afterRefresh.fetchedAt)
        assertEquals(RefreshState.Idle, afterRefresh.refresh)
        rig.scope.cancel()
    }

    @Test
    fun a_local_change_applied_to_the_rows_keeps_the_time_they_were_fetched() = runTest {
        // Given — a read whose refresh fetched the list at 1,000
        val rig = rig()
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()

        // When — later, the server reports a change to a row, applied to the cached list
        clock = 5_000L
        rig.client.applyServerRecord(
            TABLE,
            buildJsonObject {
                put("id", 1)
                put("list", "a")
                put("title", "renamed")
            },
        )
        runCurrent()

        // Then — the rows changed; when they were fetched did not
        val patched = shown.last()
        assertTrue("renamed" in patched.result?.data.orEmpty(), "${patched.result?.data}")
        assertEquals(Instant.fromEpochMilliseconds(1_000L), patched.fetchedAt)
        assertFalse(patched.isFromCache)
        rig.scope.cancel()
    }

    @Test
    fun a_queued_write_marks_the_snapshot_pending_only_when_it_changes_a_row_shown() = runTest {
        // Given — a live read of list a, then the network goes down
        var online = true
        val rig =
            rig(
                answer = {
                    if (!online) connectivityFailure()
                    ROWS
                }
            )
        val shown =
            collected(
                rig.scope,
                rig.client.from(TABLE).selectAsFlow { filter { eq("list", "a") } },
            )
        runCurrent()
        online = false

        // When — a write to list b, which the read does not show, then one to list a
        rig.client.from(TABLE).insert(row(id = 2, list = "b"))
        runCurrent()
        val afterOtherList = shown.last()
        rig.client.from(TABLE).insert(row(id = 3, list = "a"))
        runCurrent()
        val afterThisList = shown.last()

        // Then
        assertFalse(afterOtherList.hasPendingMutations)
        assertTrue(afterThisList.hasPendingMutations)
        rig.scope.cancel()
    }

    @Test
    fun a_cache_only_read_is_always_from_the_cache() = runTest {
        // Given — a list cached at 1,000
        val rig = rig()
        rig.client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)

        // When
        val shown =
            collected(
                rig.scope,
                rig.client.from(TABLE).selectAsFlow(readStrategy = FlowReadStrategy.CacheOnly),
            )
        runCurrent()

        // Then
        assertTrue(shown.last().isFromCache)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), shown.last().fetchedAt)
        rig.scope.cancel()
    }

    @Test
    fun a_refresh_that_cannot_reach_a_network_known_to_be_down_is_paused_not_stalled() = runTest {
        // Given — the monitor says the device is offline, and it is
        val signal = MutableStateFlow(false)
        var online = false
        val rig =
            rig(
                signal = signal,
                answer = {
                    if (!online) connectivityFailure()
                    ROWS
                },
            )

        // When — the read keeps failing for minutes, then the network comes back
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        advanceTimeBy(5.minutes)
        runCurrent()
        val whileOffline = shown.last().refresh
        online = true
        signal.value = true
        runCurrent()

        // Then — waiting, not failing; and refreshed at once when the network returned
        assertEquals(RefreshState.Paused, whileOffline)
        assertEquals(RefreshState.Idle, shown.last().refresh)
        assertEquals(ROWS, shown.last().result?.data)
        rig.scope.cancel()
    }

    @Test
    fun a_refresh_that_keeps_failing_while_the_network_is_believed_up_stalls() = runTest {
        // Given — the monitor says online, but no request gets through
        val rig = rig(signal = MutableStateFlow(true), answer = { connectivityFailure() })

        // When
        val shown = collected(rig.scope, rig.client.from(TABLE).selectAsFlow())
        runCurrent()
        advanceTimeBy(1.minutes)
        runCurrent()

        // Then
        assertIs<RefreshState.Stalled>(shown.last().refresh)
        rig.scope.cancel()
    }

    @Test
    fun a_network_first_read_that_falls_back_to_the_cache_while_offline_is_paused() = runTest {
        // Given — a cached list, and a monitor that says the device is offline
        val signal = MutableStateFlow(true)
        var online = true
        val rig =
            rig(
                signal = signal,
                answer = {
                    if (!online) connectivityFailure()
                    ROWS
                },
            )
        rig.client.from(TABLE).select(readStrategy = ReadStrategy.NetworkOnly)
        online = false
        signal.value = false
        runCurrent()

        // When
        val shown =
            collected(
                rig.scope,
                rig.client
                    .from(TABLE)
                    .selectAsFlow(readStrategy = FlowReadStrategy.NetworkFirstThenCache),
            )
        runCurrent()

        // Then — the cached rows, from the cache, waiting for the network
        assertEquals(ROWS, shown.last().result?.data)
        assertTrue(shown.last().isFromCache)
        assertEquals(RefreshState.Paused, shown.last().refresh)
        rig.scope.cancel()
    }

    @Test
    fun a_one_shot_read_says_where_its_rows_came_from() = runTest {
        // Given — a list fetched at 1,000
        var online = true
        val rig =
            rig(
                answer = {
                    if (!online) connectivityFailure()
                    ROWS
                }
            )
        val fetched = rig.client.from(TABLE).select()

        // When — offline, the same read falls back to the cache; then a queued write changes it
        online = false
        clock = 3_000L
        val fallback = rig.client.from(TABLE).select()
        rig.client.from(TABLE).insert(row(id = 2, list = "a"))
        val withQueuedWrite = rig.client.from(TABLE).select()
        val upstream = rig.server.supabaseClient().postgrest.from(TABLE)

        // Then
        assertFalse(fetched.isFromCache)
        assertTrue(fetched.fetchedAt != null)
        assertFalse(fetched.hasPendingMutations)
        assertTrue(fallback.isFromCache)
        assertEquals(Instant.fromEpochMilliseconds(1_000L), fallback.fetchedAt)
        assertFalse(fallback.hasPendingMutations)
        assertTrue(withQueuedWrite.hasPendingMutations)
        online = true
        val fromSupabase = upstream.select()
        assertFalse(fromSupabase.isFromCache)
        assertNull(fromSupabase.fetchedAt)
        rig.scope.cancel()
    }

    @Test
    fun a_read_the_cache_never_holds_keeps_the_servers_own_headers() = runTest {
        // Given — a server that counts
        val dispatcher = StandardTestDispatcher(testScheduler)
        val server =
            RecordingPostgrestServer(
                answer = { okJson(ROWS, extraHeaders = mapOf("Content-Range" to "0-0/42")) },
                engineDispatcher = dispatcher,
            )
        val client = newTestOfflineClient(supabase = server.supabaseClient())

        // When
        val counted = client.from(TABLE).select { count(Count.EXACT) }

        // Then
        assertEquals(42L, counted.countOrNull())
        assertFalse(counted.isFromCache)
        client.close()
    }

    private fun row(id: Int, list: String): JsonArray = buildJsonArray {
        add(
            buildJsonObject {
                put("id", id)
                put("list", list)
                put("title", "note $id")
            }
        )
    }

    private companion object {
        const val TABLE = "notes"
        const val ROWS = """[{"id":1,"list":"a","title":"note 1"}]"""
    }
}

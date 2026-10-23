// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(OfflineInternal::class, ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.ReadStrategy
import io.github.nesfeder.supabase.offline.RealtimeFilter
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeEvent
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import io.github.nesfeder.supabase.offline.offline
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_KEY
import io.github.nesfeder.supabase.offline.testdoubles.TEST_SUPABASE_URL
import io.github.nesfeder.supabase.offline.testdoubles.openInMemory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The gated hook behind the testing artifact's `FakeRealtime`: each channel the client opens
 * reaches the test with the tables the configuration named, what the test reports on it is applied,
 * and a channel the test fails or ends stalls the client, which joins again. A channel built with a
 * capacity drops what it cannot hold, as supabase-kt 3.6.0's does, and counts it.
 */
class ScriptedRealtimeTest {

    private val channels = Channel<ScriptedRealtimeChannel>(Channel.UNLIMITED)

    @Test
    fun each_channel_reaches_the_test_with_the_named_tables_and_delivers_what_it_reports() =
        runTest(timeout = 2.minutes) {
            // Given — two tables named, followed through scripted channels, one row cached
            val filter = RealtimeFilter("status", FilterOperator.EQ, "open")
            val supabase =
                createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                    httpEngine = MockEngine { respondOk("""[{"id":"t1","title":"Milk"}]""") }
                    install(Postgrest)
                    install(Offline) {
                        openInMemory()
                        followServerChangesThrough { channel -> channels.trySend(channel) }
                        realtime {
                            table("tasks")
                            table("audit", schema = "app", filter = filter)
                        }
                    }
                }
            val offline = supabase.offline
            offline.from("tasks").select()
            val first = channels.receiveWithin()

            // Then — the channel names the tables as the configuration did, and is joining
            assertEquals(
                listOf(RealtimeTable("tasks", null, null), RealtimeTable("audit", "app", filter)),
                first.tables,
            )
            assertEquals("public", first.defaultSchema)

            // When — joined, then a row saved and the cached one deleted
            first.join()
            offline.awaitStatus { status -> status == RealtimeStatus.Subscribed }
            first.saved(
                "tasks",
                null,
                buildJsonObject {
                    put("id", "t2")
                    put("title", "Tea")
                },
            )
            first.deleted("tasks", null, buildJsonObject { put("id", "t1") })

            // Then — the cached list carries both changes
            offline.awaitCached("tasks") { data -> "Tea" in data && "Milk" !in data }

            // When — the switch leaves the channel
            val left = CompletableDeferred<Unit>()
            first.onLeft { left.complete(Unit) }
            offline.disableRealtime()

            // Then — the channel hears it
            withContext(Dispatchers.Default) { withTimeout(10.seconds) { left.await() } }

            // When — switched on, the server refuses the next channel, and closes the one after
            offline.enableRealtime()
            val second = channels.receiveWithin()
            second.fail(IllegalStateException("not in the publication"))
            val stalled = offline.awaitStatus { status -> status is RealtimeStatus.Stalled }
            val third = channels.receiveWithin()
            third.join()
            offline.awaitStatus { status -> status == RealtimeStatus.Subscribed }
            third.end()
            offline.awaitStatus { status -> status is RealtimeStatus.Stalled }

            // Then — each stall says why, and the client joins again after each
            val cause = assertIs<RealtimeStatus.Stalled>(stalled).cause
            assertTrue("not in the publication" in cause.message.orEmpty(), "$cause")
            channels.receiveWithin()
            supabase.close()
            assertEquals(RealtimeStatus.Off, offline.realtimeStatus.value)
        }

    @Test
    fun a_channel_with_a_capacity_drops_what_it_cannot_hold_and_counts_it() =
        runTest(timeout = 2.minutes) {
            // Given — a channel that holds two events beyond the one its waiting collector takes
            // at once, and that collector not running again until told
            var opened: ScriptedRealtimeChannel? = null
            val feed = scriptedServerChangeFeed(capacity = 2) { channel -> opened = channel }
            val events = mutableListOf<String>()
            val collecting = backgroundScope.launch {
                feed.follow(listOf(WatchedTable("tasks")), "public").collect { event ->
                    events += (event as? ServerChangeEvent.Saved)?.record?.get("id").toString()
                }
            }
            runCurrent()
            val channel = assertNotNull(opened)

            // When — four rows are reported before the collector runs
            for (id in listOf("t1", "t2", "t3", "t4")) {
                channel.saved("tasks", null, buildJsonObject { put("id", id) })
            }

            // Then — the fourth was dropped, and counted; the first three are delivered
            assertEquals(1, channel.droppedCount)
            runCurrent()
            assertEquals(listOf("\"t1\"", "\"t2\"", "\"t3\""), events)
            collecting.cancel()
        }

    @Test
    fun the_join_hook_runs_once_the_client_has_handled_each_join() =
        runTest(timeout = 2.minutes) {
            // Given — a client following a table through scripted channels, each channel hooked
            val supabase =
                createSupabaseClient(TEST_SUPABASE_URL, TEST_SUPABASE_KEY) {
                    httpEngine = MockEngine { respondOk("[]") }
                    install(Postgrest)
                    install(Offline) {
                        openInMemory()
                        followServerChangesThrough { channel -> channels.trySend(channel) }
                        realtime { table("tasks") }
                    }
                }
            val offline = supabase.offline
            val statusesAtJoin = Channel<RealtimeStatus>(Channel.UNLIMITED)
            val first = channels.receiveWithin()
            first.onJoinHandled { statusesAtJoin.trySend(offline.realtimeStatus.value) }

            // When — the channel joins, ends, and the next one joins
            first.join()
            val firstJoin = statusesAtJoin.receiveWithin()
            first.end()
            val second = channels.receiveWithin()
            second.onJoinHandled { statusesAtJoin.trySend(offline.realtimeStatus.value) }
            second.join()

            // Then — each hook ran after the client reported the channel subscribed, once
            assertEquals(RealtimeStatus.Subscribed, firstJoin)
            assertEquals(RealtimeStatus.Subscribed, statusesAtJoin.receiveWithin())
            assertTrue(statusesAtJoin.tryReceive().isFailure)
            supabase.close()
        }

    @Test
    fun a_stalled_status_is_built_for_a_test_double() {
        // Given / When / Then
        val cause = IllegalStateException("refused")
        assertSame(cause, ValueConstructors.stalledRealtime(cause).cause)
    }

    private suspend fun <T> Channel<T>.receiveWithin(): T =
        withContext(Dispatchers.Default) { withTimeout(10.seconds) { receive() } }

    private suspend fun Offline.awaitStatus(matches: (RealtimeStatus) -> Boolean): RealtimeStatus =
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) { realtimeStatus.first(matches) }
        }

    /** Waits until the cached rows of [table] satisfy [matches], reading the cache alone. */
    private suspend fun Offline.awaitCached(table: String, matches: (String) -> Boolean) {
        withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                while (!matches(from(table).select(readStrategy = ReadStrategy.CacheOnly()).data)) {
                    delay(20)
                }
            }
        }
    }
}

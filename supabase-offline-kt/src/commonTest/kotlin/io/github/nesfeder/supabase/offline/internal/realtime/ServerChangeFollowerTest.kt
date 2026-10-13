// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The follower joins while the subscription is on, a user is signed in and the network is not known
 * down, leaves otherwise, reads the watched tables again on each join, applies what arrives in
 * order, and joins again after a channel that ended, waiting longer each time until an edge or a
 * follow that lasted.
 */
class ServerChangeFollowerTest {

    private val feed = FakeServerChangeFeed()
    private val sessionReady = MutableSharedFlow<Boolean>(replay = 1)
    private val network = MutableStateFlow(NetworkStatus.UNKNOWN)
    private val wakeUps = MutableSharedFlow<Unit>()
    private val applied = mutableListOf<ServerChangeEvent>()
    private val staled = mutableListOf<Pair<String, String>>()
    private val logs = RecordingLogSink()

    /** The follower on this test's scheduler; [scope] is canceled by the caller at the end. */
    private fun TestScope.newFollower(
        scope: CoroutineScope,
        tables: List<WatchedTable> = listOf(WatchedTable(TABLE)),
        markStale: suspend (String, String) -> Unit = { table, schema ->
            staled += table to schema
        },
        apply: suspend (ServerChangeEvent) -> Unit = { event -> applied += event },
    ): ServerChangeFollower =
        ServerChangeFollower(
            scope = scope,
            tables = tables,
            feed = feed,
            defaultSchema = { SCHEMA },
            sessionReady = sessionReady,
            networkStatuses = network,
            wakeUps = wakeUps,
            apply = apply,
            markStale = markStale,
            engineLogger = logs.asEngineLogger(),
            initialBackoff = 1.seconds,
            maxBackoff = 4.seconds,
            steadyFollow = 1.minutes,
            timeSource = testScheduler.timeSource,
        )

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun a_failure_to_mark_stale_is_logged_by_type_and_the_follow_goes_on() = runTest {
        // Given — marking stale fails
        val scope = newScope()
        val follower =
            newFollower(scope, markStale = { _, _ -> throw IllegalStateException("db locked") })
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // When — joined, then a change
        val first = feed.follows.value.single()
        first.join()
        first.saved(TABLE, buildJsonObject { put("id", "t1") })
        runCurrent()

        // Then — subscribed and applying, the failure logged by its type and not its text
        assertEquals(ServerChangeFollowState.Subscribed, follower.state.value)
        assertTrue(applied.any { event -> event is ServerChangeEvent.Saved }, "$applied")
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("IllegalStateException" in warning, warning)
        assertTrue("locked" !in warning, warning)
        scope.cancel()
    }

    @Test
    fun follows_while_on_signed_in_and_the_network_is_not_known_down() = runTest {
        // Given
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // Then — joined at once, and the join reads the table again
        assertEquals(ServerChangeFollowState.Subscribing, follower.state.value)
        val first = feed.follows.value.single()
        first.join()
        runCurrent()
        assertEquals(ServerChangeFollowState.Subscribed, follower.state.value)
        assertEquals(listOf(TABLE to SCHEMA), staled)

        // When / Then — the network known down pauses it; back, it joins anew
        network.value = NetworkStatus.OFFLINE
        runCurrent()
        assertEquals(ServerChangeFollowState.Paused, follower.state.value)
        assertTrue(first.isLeft)
        network.value = NetworkStatus.ONLINE
        runCurrent()
        assertEquals(2, feed.follows.value.size)
        assertEquals(ServerChangeFollowState.Subscribing, follower.state.value)

        // When / Then — no user pauses it; a user joins anew
        sessionReady.emit(false)
        runCurrent()
        assertEquals(ServerChangeFollowState.Paused, follower.state.value)
        assertTrue(feed.follows.value[1].isLeft)
        sessionReady.emit(true)
        runCurrent()
        assertEquals(3, feed.follows.value.size)

        // When / Then — the switch off leaves; on joins anew
        follower.setEnabled(false)
        runCurrent()
        assertEquals(ServerChangeFollowState.Off, follower.state.value)
        assertTrue(feed.follows.value[2].isLeft)
        follower.setEnabled(true)
        runCurrent()
        assertEquals(4, feed.follows.value.size)

        // When / Then — network access switched off pauses it too
        network.value = NetworkStatus.DISABLED
        runCurrent()
        assertEquals(ServerChangeFollowState.Paused, follower.state.value)
        assertTrue(feed.follows.value[3].isLeft)
        scope.cancel()
    }

    @Test
    fun a_change_that_leaves_the_answer_alone_never_restarts_the_channel() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()
        assertEquals(1, feed.follows.value.size)

        // When — the network goes from unknown to online, and the session settles anew
        network.value = NetworkStatus.ONLINE
        sessionReady.emit(true)
        runCurrent()

        // Then — the same follow
        assertEquals(1, feed.follows.value.size)
        assertTrue(!feed.follows.value.single().isLeft)
        scope.cancel()
    }

    @Test
    fun changes_are_applied_in_order_and_a_failing_apply_marks_the_table_stale() = runTest {
        // Given — joined, with an apply that refuses one record
        val scope = newScope()
        val follower =
            newFollower(scope) { event ->
                if (
                    event is ServerChangeEvent.Saved &&
                        event.record["id"]?.jsonPrimitive?.content == "t2"
                )
                    error("refused")
                applied += event
            }
        sessionReady.emit(true)
        follower.start()
        runCurrent()
        val follow = feed.follows.value.single()
        follow.join()
        runCurrent()
        staled.clear()

        // When
        follow.saved(TABLE, row("t1"))
        follow.saved(TABLE, row("t2"))
        follow.deleted(TABLE, row("t1"))
        runCurrent()

        // Then — the others applied in order, the refused one's table marked stale, still joined
        assertEquals(
            listOf(
                ServerChangeEvent.Saved(TABLE, SCHEMA, row("t1")),
                ServerChangeEvent.Deleted(TABLE, SCHEMA, row("t1")),
            ),
            applied,
        )
        assertEquals(listOf(TABLE to SCHEMA), staled)
        assertEquals(ServerChangeFollowState.Subscribed, follower.state.value)
        assertEquals(1, logs.entriesAt(EngineLogLevel.WARNING).size)
        scope.cancel()
    }

    @Test
    fun a_channel_that_ends_is_joined_again_after_a_wait_that_doubles_up_to_the_cap() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // When — the channel ends
        val cause = IllegalStateException("refused")
        feed.follows.value[0].fail(cause)
        runCurrent()

        // Then — stalled with the cause, joined again after 1 s, then 2 s, then 4 s, then 4 s
        val stalled = assertIs<ServerChangeFollowState.Stalled>(follower.state.value)
        assertIs<IllegalStateException>(stalled.cause)
        assertEquals(cause.message, stalled.cause.message)
        assertEquals(1, feed.follows.value.size)
        for ((attempt, wait) in listOf(1.seconds, 2.seconds, 4.seconds, 4.seconds).withIndex()) {
            advanceTimeBy(wait - 1.milliseconds)
            runCurrent()
            assertEquals(attempt + 1, feed.follows.value.size, "before the wait of $wait")
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(attempt + 2, feed.follows.value.size, "after the wait of $wait")
            feed.follows.value[attempt + 1].fail(cause)
            runCurrent()
        }
        scope.cancel()
    }

    @Test
    fun an_edge_cuts_the_wait_short() = runTest {
        // Given — stalled, with a 2-second wait ahead
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()
        feed.follows.value[0].fail(IllegalStateException("refused"))
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        feed.follows.value[1].fail(IllegalStateException("refused again"))
        runCurrent()
        assertEquals(2, feed.follows.value.size)

        // When — the network comes back
        wakeUps.emit(Unit)
        runCurrent()

        // Then — joined at once
        assertEquals(3, feed.follows.value.size)
        assertEquals(ServerChangeFollowState.Subscribing, follower.state.value)
        scope.cancel()
    }

    @Test
    fun a_follow_that_lasted_starts_the_wait_over() = runTest {
        // Given — two quick failures, so the next wait would be 4 s
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()
        feed.follows.value[0].fail(IllegalStateException("refused"))
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        feed.follows.value[1].fail(IllegalStateException("refused"))
        runCurrent()
        advanceTimeBy(2.seconds)
        runCurrent()
        val lasting = feed.follows.value[2]
        lasting.join()
        runCurrent()

        // When — that follow lasts a minute, then ends
        advanceTimeBy(1.minutes)
        runCurrent()
        lasting.fail(IllegalStateException("closed"))
        runCurrent()

        // Then — the wait is the first one again
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(4, feed.follows.value.size)
        scope.cancel()
    }

    @Test
    fun a_feed_that_completes_counts_as_a_channel_that_ended() = runTest {
        // Given
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // When
        feed.follows.value[0].end()
        runCurrent()

        // Then
        val stalled = assertIs<ServerChangeFollowState.Stalled>(follower.state.value)
        assertIs<ServerChangeFeedEndedException>(stalled.cause)
        scope.cancel()
    }

    @Test
    fun without_tables_nothing_is_followed_and_the_state_is_off() = runTest {
        // Given / When
        val scope = newScope()
        val follower = newFollower(scope, tables = emptyList())
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // Then
        assertEquals(ServerChangeFollowState.Off, follower.state.value)
        assertTrue(feed.follows.value.isEmpty())
        scope.cancel()
    }

    @Test
    fun a_join_reads_every_watched_table_again_in_its_own_schema() = runTest {
        // Given — a table of another schema and one of the default schema
        val scope = newScope()
        val follower =
            newFollower(
                scope,
                tables = listOf(WatchedTable("audit", schema = "app"), WatchedTable(TABLE)),
            )
        sessionReady.emit(true)
        follower.start()
        runCurrent()

        // When
        val follow = feed.follows.value.single()
        follow.join()
        runCurrent()

        // Then
        assertEquals(SCHEMA, follow.defaultSchema)
        assertEquals(listOf("audit" to "app", TABLE to SCHEMA), staled)
        scope.cancel()
    }

    @Test
    fun stop_makes_the_state_off_for_good() = runTest {
        // Given — joined
        val scope = newScope()
        val follower = newFollower(scope)
        sessionReady.emit(true)
        follower.start()
        runCurrent()
        feed.follows.value[0].join()
        runCurrent()

        // When — the client closes: its scope ends, then the follower is told
        scope.cancel()
        runCurrent()
        follower.stop()

        // Then
        assertEquals(ServerChangeFollowState.Off, follower.state.value)
        assertTrue(feed.follows.value[0].isLeft)
    }

    private fun row(id: String) = buildJsonObject { put("id", id) }

    private companion object {
        const val TABLE = "tasks"
        const val SCHEMA = "public"
    }
}

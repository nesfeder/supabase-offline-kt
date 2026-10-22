// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class, OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.testing.ScriptedRealtimeChannel
import io.github.nesfeder.supabase.offline.internal.testing.scriptedServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The follower joins while the subscription is on, there is an identity to subscribe for and the
 * network is not known down, leaves otherwise, joins again for another identity, reads the watched
 * tables again on each join, applies what arrives in order, each table's waiting changes as one
 * batch for the identity joined for, discards a backlog past the cap and reads the tables again
 * instead, and joins again after a channel that ended, waiting longer each time until an edge or a
 * follow that lasted; a channel that ended is logged at DEBUG, and the warning comes with the
 * rejoin.
 */
class ServerChangeFollowerTest {

    private val feed = FakeServerChangeFeed()
    private val sessionIdentity = MutableSharedFlow<String?>(replay = 1)
    private val network = MutableStateFlow(NetworkStatus.UNKNOWN)
    private val wakeUps = MutableSharedFlow<Unit>()
    private val applied = mutableListOf<List<ServerChangeEvent.Change>>()
    private val staled = mutableListOf<Pair<String, String>>()
    private val logs = RecordingLogSink()

    /** The follower on this test's scheduler; [scope] is canceled by the caller at the end. */
    private fun TestScope.newFollower(
        scope: CoroutineScope,
        tables: List<WatchedTable> = listOf(WatchedTable(TABLE)),
        feed: ServerChangeFeed = this@ServerChangeFollowerTest.feed,
        backlogCapacity: Int = ServerChangeFollower.BACKLOG_CAPACITY,
        markStale: suspend (String, String) -> Unit = { table, schema ->
            staled += table to schema
        },
        apply: suspend (List<ServerChangeEvent.Change>) -> Unit = { changes ->
            applied += changes
        },
    ): ServerChangeFollower =
        ServerChangeFollower(
            scope = scope,
            tables = tables,
            feed = feed,
            defaultSchema = { SCHEMA },
            sessionIdentity = sessionIdentity,
            networkStatuses = network,
            wakeUps = wakeUps,
            apply = apply,
            markStale = markStale,
            engineLogger = logs.asEngineLogger(),
            initialBackoff = 1.seconds,
            maxBackoff = 4.seconds,
            steadyFollow = 1.minutes,
            timeSource = testScheduler.timeSource,
            backlogCapacity = backlogCapacity,
        )

    private fun TestScope.newScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test
    fun a_failure_to_mark_stale_is_logged_by_type_and_the_follow_goes_on() = runTest {
        // Given — marking stale fails
        val scope = newScope()
        val follower =
            newFollower(scope, markStale = { _, _ -> throw IllegalStateException("db locked") })
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // When — joined, then a change
        val first = feed.follows.value.single()
        first.join()
        first.saved(TABLE, buildJsonObject { put("id", "t1") })
        runCurrent()

        // Then — subscribed and applying, the failure logged by its type and not its text
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        assertTrue(applied.flatten().any { event -> event is ServerChangeEvent.Saved }, "$applied")
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
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // Then — joined at once, and the join reads the table again
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)
        val first = feed.follows.value.single()
        first.join()
        runCurrent()
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        assertEquals(listOf(TABLE to SCHEMA), staled)

        // When / Then — the network known down pauses it; back, it joins anew
        network.value = NetworkStatus.OFFLINE
        runCurrent()
        assertEquals(RealtimeStatus.Paused, follower.state.value)
        assertTrue(first.isLeft)
        network.value = NetworkStatus.ONLINE
        runCurrent()
        assertEquals(2, feed.follows.value.size)
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)

        // When / Then — no user pauses it; a user joins anew
        sessionIdentity.emit(null)
        runCurrent()
        assertEquals(RealtimeStatus.Paused, follower.state.value)
        assertTrue(feed.follows.value[1].isLeft)
        sessionIdentity.emit(USER)
        runCurrent()
        assertEquals(3, feed.follows.value.size)

        // When / Then — the switch off leaves; on joins anew
        follower.setEnabled(false)
        runCurrent()
        assertEquals(RealtimeStatus.Off, follower.state.value)
        assertTrue(feed.follows.value[2].isLeft)
        follower.setEnabled(true)
        runCurrent()
        assertEquals(4, feed.follows.value.size)

        // When / Then — network access switched off pauses it too
        network.value = NetworkStatus.DISABLED
        runCurrent()
        assertEquals(RealtimeStatus.Paused, follower.state.value)
        assertTrue(feed.follows.value[3].isLeft)
        scope.cancel()
    }

    @Test
    fun a_change_that_leaves_the_answer_alone_never_restarts_the_channel() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        assertEquals(1, feed.follows.value.size)

        // When — the network goes from unknown to online, and the session settles anew
        network.value = NetworkStatus.ONLINE
        sessionIdentity.emit(USER)
        runCurrent()

        // Then — the same follow
        assertEquals(1, feed.follows.value.size)
        assertTrue(!feed.follows.value.single().isLeft)
        scope.cancel()
    }

    @Test
    fun changes_are_applied_in_order_and_a_failing_apply_marks_the_table_stale() = runTest {
        // Given — joined, with an apply that refuses a batch holding one record
        val scope = newScope()
        val follower =
            newFollower(scope) { changes ->
                if (
                    changes.any { change -> change is ServerChangeEvent.Saved && change.id == "t2" }
                )
                    error("refused")
                applied += changes
            }
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val follow = feed.follows.value.single()
        follow.join()
        runCurrent()
        staled.clear()

        // When — three changes wait together, then two more arrive
        follow.saved(TABLE, row("t1"))
        follow.saved(TABLE, row("t2"))
        follow.deleted(TABLE, row("t1"))
        runCurrent()
        follow.saved(TABLE, row("t3"))
        follow.saved(TABLE, row("t4"))
        runCurrent()

        // Then — the refused batch's table marked stale, once; the next batch applied in order;
        // still joined
        assertEquals<List<List<ServerChangeEvent.Change>>>(
            listOf(
                listOf(
                    ServerChangeEvent.Saved(TABLE, SCHEMA, row("t3")),
                    ServerChangeEvent.Saved(TABLE, SCHEMA, row("t4")),
                )
            ),
            applied,
        )
        assertEquals(listOf(TABLE to SCHEMA), staled)
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("3 change(s) to $TABLE failed" in warning, warning)
        assertTrue("refused" !in warning, warning)
        scope.cancel()
    }

    @Test
    fun interleaved_changes_of_two_tables_keep_each_tables_order_in_the_batches_handed_on() =
        runTest {
            // Given — following two tables, joined
            val scope = newScope()
            val follower =
                newFollower(
                    scope,
                    tables = listOf(WatchedTable(TABLE), WatchedTable("audit", schema = "app")),
                )
            sessionIdentity.emit(USER)
            follower.start()
            runCurrent()
            val follow = feed.follows.value.single()
            follow.join()
            runCurrent()

            // When — the tables' changes arrive interleaved, and wait together
            follow.saved(TABLE, row("t1"))
            follow.saved("audit", row("a1"), schema = "app")
            follow.saved(TABLE, row("t2"))
            follow.deleted("audit", row("a1"), schema = "app")
            follow.deleted(TABLE, row("t1"))
            runCurrent()

            // Then — one batch per table, each holding its table's changes in the order sent
            assertEquals(2, applied.size, "$applied")
            assertTrue(
                applied.all { batch -> batch.map { it.table to it.schema }.toSet().size == 1 },
                "$applied",
            )
            assertEquals(
                listOf(
                    ServerChangeEvent.Saved(TABLE, SCHEMA, row("t1")),
                    ServerChangeEvent.Saved(TABLE, SCHEMA, row("t2")),
                    ServerChangeEvent.Deleted(TABLE, SCHEMA, row("t1")),
                ),
                applied.flatten().filter { change -> change.table == TABLE },
            )
            assertEquals(
                listOf(
                    ServerChangeEvent.Saved("audit", "app", row("a1")),
                    ServerChangeEvent.Deleted("audit", "app", row("a1")),
                ),
                applied.flatten().filter { change -> change.table == "audit" },
            )
            scope.cancel()
        }

    @Test
    fun a_backlog_past_the_cap_is_discarded_with_what_was_taken_and_every_table_read_again_once() =
        runTest {
            // Given — following two tables with a cap of ten, joined, and an apply that holds
            // audit's batch
            val release = CompletableDeferred<Unit>()
            val scope = newScope()
            val follower =
                newFollower(
                    scope,
                    tables = listOf(WatchedTable(TABLE), WatchedTable("audit", schema = "app")),
                    backlogCapacity = 10,
                ) { changes ->
                    if (changes.first().table == "audit") release.await()
                    applied += changes
                }
            sessionIdentity.emit(USER)
            follower.start()
            runCurrent()
            val follow = feed.follows.value.single()
            follow.join()
            runCurrent()
            staled.clear()

            // When — an audit change and a tasks change are taken together, so the applier sits
            // in audit's batch with the tasks change in hand, and more changes than the cap arrive
            follow.saved("audit", row("a1"), schema = "app")
            follow.saved(TABLE, row("taken"))
            runCurrent()
            repeat(11) { index ->
                follow.saved(
                    TABLE,
                    buildJsonObject {
                        put("id", "t$index")
                        put("title", PRIVATE)
                    },
                )
            }
            runCurrent()

            // Then — nothing is marked or logged while the applier is held: the re-read must
            // follow what it applies, not race it
            assertEquals(emptyList(), staled)
            assertEquals(emptyList(), logs.entriesAt(EngineLogLevel.WARNING))

            // When — audit's batch goes through
            release.complete(Unit)
            runCurrent()

            // Then — the tasks change taken before the discard is never applied, each table is
            // marked stale once, and one warning names the count, the tables with their schema,
            // and nothing of a record
            assertEquals<List<List<ServerChangeEvent.Change>>>(
                listOf(listOf(ServerChangeEvent.Saved("audit", "app", row("a1")))),
                applied,
            )
            assertEquals(listOf(TABLE to SCHEMA, "audit" to "app"), staled)
            val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
            assertTrue("12 change(s)" in warning, warning)
            assertTrue("$TABLE, app.audit" in warning, warning)
            assertTrue(PRIVATE !in warning, warning)

            // When — a change arrives after the discard
            follow.saved(TABLE, row("after"))
            runCurrent()

            // Then — it is applied, and nothing more is marked or logged
            assertEquals(
                listOf(ServerChangeEvent.Saved(TABLE, SCHEMA, row("after"))),
                applied.last(),
            )
            assertEquals(2, staled.size)
            assertEquals(1, logs.entriesAt(EngineLogLevel.WARNING).size)
            assertEquals(RealtimeStatus.Subscribed, follower.state.value)
            scope.cancel()
        }

    @Test
    fun discards_during_the_re_read_are_folded_into_one_warning_and_marked_again() = runTest {
        // Given — a cap of two, and a mark that, once armed, reports a further overflow while it
        // runs
        val scope = newScope()
        lateinit var follow: FakeServerChangeFeed.Follow
        var armed = false
        val follower =
            newFollower(
                scope,
                backlogCapacity = 2,
                markStale = { table, schema ->
                    staled += table to schema
                    if (armed) {
                        armed = false
                        repeat(3) { index -> follow.saved(TABLE, row("more$index")) }
                        // The collector takes them while the mark is under way
                        yield()
                    }
                },
            )
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        follow = feed.follows.value.single()
        follow.join()
        runCurrent()
        staled.clear()
        armed = true

        // When — one overflow, whose re-read meets another
        repeat(3) { index -> follow.saved(TABLE, row("t$index")) }
        runCurrent()

        // Then — marked twice, one warning with both counts, nothing applied from either burst
        assertEquals(listOf(TABLE to SCHEMA, TABLE to SCHEMA), staled)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("6 change(s)" in warning, warning)
        assertEquals(emptyList(), applied)
        scope.cancel()
    }

    @Test
    fun a_channel_that_holds_few_changes_loses_none_to_a_slow_apply() = runTest {
        // Given — a channel that holds 64 changes, as supabase-kt 3.6.0's does, followed with an
        // apply held on its first batch
        var opened: ScriptedRealtimeChannel? = null
        val bounded = scriptedServerChangeFeed(capacity = 64) { channel -> opened = channel }
        val release = CompletableDeferred<Unit>()
        val scope = newScope()
        val follower =
            newFollower(scope, feed = bounded) { changes ->
                release.await()
                applied += changes
            }
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val channel = assertNotNull(opened)
        channel.join()
        runCurrent()
        channel.saved(TABLE, null, row("held"))
        runCurrent()

        // When — a hundred changes arrive one by one while that batch is applied
        repeat(100) { index ->
            channel.saved(TABLE, null, row("t$index"))
            runCurrent()
        }

        // Then — each was taken off the channel as it came, so none was dropped
        assertEquals(0, channel.droppedCount)

        // When — the apply goes through
        release.complete(Unit)
        runCurrent()

        // Then — every change was applied, in order
        assertEquals(
            List(100) { index -> ServerChangeEvent.Saved(TABLE, SCHEMA, row("t$index")) },
            applied.flatten().drop(1),
        )
        scope.cancel()
    }

    @Test
    fun a_channel_that_ends_is_joined_again_after_a_wait_that_doubles_up_to_the_cap() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // When — the channel ends
        val cause = IllegalStateException("refused")
        feed.follows.value[0].fail(cause)
        runCurrent()

        // Then — stalled with the cause, joined again after 1 s, then 2 s, then 4 s, then 4 s
        val stalled = assertIs<RealtimeStatus.Stalled>(follower.state.value)
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
        sessionIdentity.emit(USER)
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
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)
        scope.cancel()
    }

    @Test
    fun a_follow_that_lasted_starts_the_wait_over() = runTest {
        // Given — two quick failures, so the next wait would be 4 s
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
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
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // When
        feed.follows.value[0].end()
        runCurrent()

        // Then
        val stalled = assertIs<RealtimeStatus.Stalled>(follower.state.value)
        assertIs<ServerChangeFeedEndedException>(stalled.cause)
        scope.cancel()
    }

    @Test
    fun without_tables_nothing_is_followed_and_the_state_is_off() = runTest {
        // Given / When
        val scope = newScope()
        val follower = newFollower(scope, tables = emptyList())
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // Then
        assertEquals(RealtimeStatus.Off, follower.state.value)
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
        sessionIdentity.emit(USER)
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
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        feed.follows.value[0].join()
        runCurrent()

        // When — the client closes: its scope ends, then the follower is told
        scope.cancel()
        runCurrent()
        follower.stop()

        // Then
        assertEquals(RealtimeStatus.Off, follower.state.value)
        assertTrue(feed.follows.value[0].isLeft)
    }

    @Test
    fun another_identity_leaves_and_joins_again_and_a_new_session_of_the_same_one_does_not() =
        runTest {
            // Given — joined for one user, with a change applied
            val scope = newScope()
            val follower = newFollower(scope)
            sessionIdentity.emit(USER)
            follower.start()
            runCurrent()
            val first = feed.follows.value.single()
            first.join()
            first.saved(TABLE, row("t1"))
            runCurrent()
            assertEquals(1, applied.size)

            // When — the same user's session settles anew
            sessionIdentity.emit(USER)
            runCurrent()

            // Then — the same channel
            assertEquals(1, feed.follows.value.size)
            assertTrue(!first.isLeft)
            assertEquals(RealtimeStatus.Subscribed, follower.state.value)

            // When — another user signs in, with no sign-out first
            sessionIdentity.emit(OTHER_USER)
            runCurrent()

            // Then — left, joined again, the table read again on the join, and the new channel's
            // changes applied
            assertTrue(first.isLeft)
            val second = feed.follows.value[1]
            assertEquals(RealtimeStatus.Subscribing, follower.state.value)
            staled.clear()
            second.join()
            second.saved(TABLE, row("t2"))
            runCurrent()
            assertEquals(listOf(TABLE to SCHEMA), staled)
            assertEquals(listOf(ServerChangeEvent.Saved(TABLE, SCHEMA, row("t2"))), applied.last())
            assertEquals(RealtimeStatus.Subscribed, follower.state.value)
            scope.cancel()
        }

    @Test
    fun a_batch_taken_for_a_follow_another_user_has_replaced_is_dropped() = runTest {
        // Given — joined for one user, with an apply held on its first batch past any cancellation,
        // as a patch inside the cache's locks is
        val release = CompletableDeferred<Unit>()
        var held = false
        val scope = newScope()
        val follower =
            newFollower(scope) { changes ->
                if (!held) {
                    held = true
                    withContext(NonCancellable) { release.await() }
                }
                applied += changes
            }
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val first = feed.follows.value.single()
        first.join()
        first.saved(TABLE, row("held"))
        runCurrent()
        first.saved(TABLE, row("behind"))
        runCurrent()

        // When — another user signs in while the batch is held, and the hold is released
        sessionIdentity.emit(OTHER_USER)
        runCurrent()
        release.complete(Unit)
        runCurrent()

        // Then — the held batch finished, the one behind it was dropped, said at DEBUG, and the
        // new user's channel was joined only then
        assertEquals<List<List<ServerChangeEvent.Change>>>(
            listOf(listOf(ServerChangeEvent.Saved(TABLE, SCHEMA, row("held")))),
            applied,
        )
        val debug = logs.entriesAt(EngineLogLevel.DEBUG).single { "being left" in it.message }
        assertTrue("1 change(s) to $TABLE" in debug.message, debug.message)
        assertTrue(first.isLeft)
        assertEquals(2, feed.follows.value.size)
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)
        scope.cancel()
    }

    @Test
    fun a_channel_that_ended_is_logged_at_debug_and_the_warning_comes_with_the_rejoin() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // When — the channel ends
        feed.follows.value[0].fail(IllegalStateException("closed"))
        runCurrent()

        // Then — stalled, said at DEBUG by the failure's type, and no warning yet
        assertIs<RealtimeStatus.Stalled>(follower.state.value)
        val debug = logs.entriesAt(EngineLogLevel.DEBUG).single { "channel ended" in it.message }
        assertTrue("IllegalStateException" in debug.message, debug.message)
        assertTrue("closed" !in debug.message, debug.message)
        assertEquals(emptyList(), logs.entriesAt(EngineLogLevel.WARNING))

        // When — the wait is over
        advanceTimeBy(1.seconds)
        runCurrent()

        // Then — joined again, with the warning
        assertEquals(2, feed.follows.value.size)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("channel ended" in warning && "joining again" in warning, warning)
        assertTrue("closed" !in warning, warning)
        scope.cancel()
    }

    @Test
    fun a_channel_that_ended_as_the_follow_paused_leaves_no_warning() = runTest {
        // Given — following
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()

        // When — the channel ends, and the user signs out before the wait is over
        feed.follows.value[0].fail(IllegalStateException("disconnected"))
        runCurrent()
        sessionIdentity.emit(null)
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()

        // Then — paused, nothing joined again, and no warning
        assertEquals(RealtimeStatus.Paused, follower.state.value)
        assertEquals(1, feed.follows.value.size)
        assertEquals(emptyList(), logs.entriesAt(EngineLogLevel.WARNING))
        scope.cancel()
    }

    @Test
    fun what_a_channel_reports_after_stop_leaves_the_state_off() = runTest {
        // Given — joined, and the client closed before its scope ended
        val scope = newScope()
        val follower = newFollower(scope)
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        feed.follows.value[0].join()
        runCurrent()
        follower.stop()
        assertEquals(RealtimeStatus.Off, follower.state.value)

        // When — the channel ends after that, and is joined again
        feed.follows.value[0].fail(IllegalStateException("disconnected"))
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()
        feed.follows.value[1].join()
        runCurrent()

        // Then — off throughout
        assertEquals(RealtimeStatus.Off, follower.state.value)
        scope.cancel()
    }

    private fun row(id: String) = buildJsonObject { put("id", id) }

    private val ServerChangeEvent.Saved.id: String?
        get() = record["id"]?.jsonPrimitive?.content

    private companion object {
        const val TABLE = "tasks"
        const val SCHEMA = "public"
        const val USER = "user:a"
        const val OTHER_USER = "user:b"

        /** A value no log may carry. */
        const val PRIVATE = "someone@example.com"
    }
}

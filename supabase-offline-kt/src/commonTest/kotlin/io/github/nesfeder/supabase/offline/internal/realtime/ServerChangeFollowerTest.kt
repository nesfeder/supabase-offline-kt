// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalCoroutinesApi::class, OfflineInternal::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.NetworkStatus
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogLevel
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFollower.Companion.aggregateOf
import io.github.nesfeder.supabase.offline.internal.testing.ScriptedRealtimeChannel
import io.github.nesfeder.supabase.offline.internal.testing.scriptedServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.FakeServerChangeFeed
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
 * network is not known down, leaves otherwise, joins again for another identity, follows each table
 * on a channel of its own, reads a table again on each of its joins, applies what arrives in order,
 * each table's waiting changes as one batch for the identity joined for, discards a backlog past
 * the cap and reads the table again instead, and joins a table again after its channel ended,
 * waiting longer each time until an edge or a follow that lasted, while the other tables stay
 * joined; a channel that ended is logged at DEBUG, and the warning comes with the rejoin. The
 * tables' statuses sum up to one, a stall first.
 */
class ServerChangeFollowerTest {

    private val feed = FakeServerChangeFeed()
    private val sessionIdentity = MutableSharedFlow<String?>(replay = 1)
    private val network = MutableStateFlow(NetworkStatus.UNKNOWN)
    private val wakeUps = MutableSharedFlow<Unit>()
    private val applied = mutableListOf<List<ServerChangeEvent.Change>>()
    private val appliedFor = mutableListOf<String>()
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
        currentIdentity: () -> String? = { sessionIdentity.replayCache.firstOrNull() },
        apply: suspend (String, List<ServerChangeEvent.Change>) -> Unit = { _, changes ->
            applied += changes
        },
    ): ServerChangeFollower =
        ServerChangeFollower(
            scope = scope,
            tables = tables,
            feed = feed,
            defaultSchema = { SCHEMA },
            sessionIdentity = sessionIdentity,
            currentIdentity = currentIdentity,
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
            newFollower(scope) { _, changes ->
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
    fun each_table_is_followed_on_a_channel_of_its_own_and_keeps_its_order() = runTest {
        // Given — following two tables, each of its channel joined
        val scope = newScope()
        val follower = newFollower(scope, tables = listOf(WatchedTable(TABLE), AUDIT))
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val (tasks, audit) = feed.follows.value
        assertEquals(listOf(WatchedTable(TABLE)), tasks.tables)
        assertEquals(listOf(AUDIT), audit.tables)
        tasks.join()
        audit.join()
        runCurrent()

        // When — the tables' changes arrive interleaved, each on its own channel
        tasks.saved(TABLE, row("t1"))
        audit.saved("audit", row("a1"), schema = "app")
        tasks.saved(TABLE, row("t2"))
        audit.deleted("audit", row("a1"), schema = "app")
        tasks.deleted(TABLE, row("t1"))
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
    fun a_refused_table_stalls_alone_and_the_others_keep_receiving() = runTest {
        // Given — following two tables, both joined
        val scope = newScope()
        val follower = newFollower(scope, tables = listOf(WatchedTable(TABLE), AUDIT))
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val (tasks, audit) = feed.follows.value
        tasks.join()
        audit.join()
        runCurrent()
        assertEquals(
            mapOf(
                TASKS_NAMED to RealtimeStatus.Subscribed,
                AUDIT_NAMED to RealtimeStatus.Subscribed,
            ),
            follower.tableState.value,
        )
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        staled.clear()

        // When — the server refuses the tasks binding
        val refusal = IllegalStateException("column renamed")
        tasks.fail(refusal)
        runCurrent()

        // Then — tasks alone stalls; audit's channel stays joined and its changes still apply
        val tasksStatus = assertIs<RealtimeStatus.Stalled>(follower.tableState.value[TASKS_NAMED])
        assertEquals(refusal.message, tasksStatus.cause.message)
        assertEquals(RealtimeStatus.Subscribed, follower.tableState.value[AUDIT_NAMED])
        assertEquals(tasksStatus, follower.state.value)
        assertFalse(audit.isLeft)
        audit.saved("audit", row("a1"), schema = "app")
        runCurrent()
        assertEquals(listOf(ServerChangeEvent.Saved("audit", "app", row("a1"))), applied.last())

        // When — the wait is over, and the binding is accepted again
        advanceTimeBy(1.seconds)
        runCurrent()
        val rejoined = feed.follows.value.last()
        rejoined.join()
        runCurrent()

        // Then — tasks alone was joined again and read again; audit's channel was never left
        assertEquals(3, feed.follows.value.size)
        assertEquals(listOf(WatchedTable(TABLE)), rejoined.tables)
        assertFalse(audit.isLeft)
        assertEquals(listOf(TABLE to SCHEMA), staled)
        assertEquals(RealtimeStatus.Subscribed, follower.tableState.value[TASKS_NAMED])
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        scope.cancel()
    }

    @Test
    fun a_table_refused_again_and_again_waits_longer_each_time_on_its_own() = runTest {
        // Given — following two tables, both joined
        val scope = newScope()
        val follower = newFollower(scope, tables = listOf(WatchedTable(TABLE), AUDIT))
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val (tasks, audit) = feed.follows.value
        tasks.join()
        audit.join()
        runCurrent()

        // When / Then — each refusal of tasks doubles its wait, 1 s, then 2 s, then 4 s
        tasks.fail(IllegalStateException("refused"))
        runCurrent()
        for ((attempt, wait) in listOf(1.seconds, 2.seconds, 4.seconds).withIndex()) {
            advanceTimeBy(wait - 1.milliseconds)
            runCurrent()
            assertEquals(attempt + 2, feed.follows.value.size, "before the wait of $wait")
            advanceTimeBy(1.milliseconds)
            runCurrent()
            assertEquals(attempt + 3, feed.follows.value.size, "after the wait of $wait")
            val next = feed.follows.value.last()
            assertEquals(listOf(WatchedTable(TABLE)), next.tables)
            next.fail(IllegalStateException("refused"))
            runCurrent()
        }

        // Then — audit was joined once, and is still
        assertEquals(1, feed.follows.value.count { follow -> follow.tables == listOf(AUDIT) })
        assertFalse(audit.isLeft)
        assertEquals(RealtimeStatus.Subscribed, follower.tableState.value[AUDIT_NAMED])
        scope.cancel()
    }

    @Test
    fun every_table_reads_off_paused_or_joining_at_once_in_the_order_named() = runTest {
        // Given — two tables, the first with a schema and a filter
        val filtered =
            WatchedTable(
                "audit",
                schema = "app",
                filter = WatchedTableFilter("status", FilterOperator.EQ, "open"),
            )
        val scope = newScope()
        val follower = newFollower(scope, tables = listOf(filtered, WatchedTable(TABLE)))

        // Then — paused before the start, keyed as the configuration names each table, in order
        assertEquals(
            listOf(filtered.toRealtimeTable(), TASKS_NAMED),
            follower.tableState.value.keys.toList(),
        )
        assertTrue(follower.tableState.value.values.all { it == RealtimeStatus.Paused })
        assertEquals(RealtimeStatus.Paused, follower.state.value)

        // When / Then — following: both joining, then both subscribed
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        assertTrue(follower.tableState.value.values.all { it == RealtimeStatus.Subscribing })
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)
        feed.follows.value[0].join()
        runCurrent()
        assertEquals(RealtimeStatus.Subscribing, follower.state.value)
        feed.follows.value[1].join()
        runCurrent()
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)

        // When / Then — the network known down pauses both; the switch off makes both off
        network.value = NetworkStatus.OFFLINE
        runCurrent()
        assertTrue(follower.tableState.value.values.all { it == RealtimeStatus.Paused })
        assertEquals(RealtimeStatus.Paused, follower.state.value)
        follower.setEnabled(false)
        assertTrue(follower.tableState.value.values.all { it == RealtimeStatus.Off })
        assertEquals(RealtimeStatus.Off, follower.state.value)

        // When / Then — stopped, both stay off
        scope.cancel()
        follower.stop()
        assertTrue(follower.tableState.value.values.all { it == RealtimeStatus.Off })
    }

    @Test
    fun the_tables_statuses_sum_up_to_a_stall_first_then_a_join_in_flight() {
        // Given
        val first = RealtimeStatus.Stalled(IllegalStateException("first"))
        val second = RealtimeStatus.Stalled(IllegalStateException("second"))
        fun of(tasks: RealtimeStatus, audit: RealtimeStatus) =
            aggregateOf(mapOf(TASKS_NAMED to tasks, AUDIT_NAMED to audit))

        // Then
        assertEquals(RealtimeStatus.Off, aggregateOf(emptyMap()))
        assertEquals(RealtimeStatus.Off, of(RealtimeStatus.Off, RealtimeStatus.Off))
        assertEquals(RealtimeStatus.Paused, of(RealtimeStatus.Paused, RealtimeStatus.Paused))
        assertEquals(
            RealtimeStatus.Subscribing,
            of(RealtimeStatus.Subscribed, RealtimeStatus.Subscribing),
        )
        assertEquals(
            RealtimeStatus.Subscribed,
            of(RealtimeStatus.Subscribed, RealtimeStatus.Subscribed),
        )
        assertEquals(first, of(RealtimeStatus.Subscribing, first))
        assertEquals(first, of(RealtimeStatus.Subscribed, first))
        assertEquals(second, of(second, first))
    }

    @Test
    fun a_backlog_past_the_cap_is_discarded_and_its_table_alone_is_read_again_once() = runTest {
        // Given — following two tables with a cap of ten, both joined, and an apply that holds
        // tasks' first batch
        val release = CompletableDeferred<Unit>()
        val scope = newScope()
        val follower =
            newFollower(scope, tables = listOf(WatchedTable(TABLE), AUDIT), backlogCapacity = 10) {
                _,
                changes ->
                if (changes.first().table == TABLE && applied.none { it.first().table == TABLE })
                    release.await()
                applied += changes
            }
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val (tasks, audit) = feed.follows.value
        tasks.join()
        audit.join()
        runCurrent()
        staled.clear()
        tasks.saved(TABLE, row("held"))
        runCurrent()

        // When — more tasks changes than the cap arrive while that batch is applied
        repeat(11) { index ->
            tasks.saved(
                TABLE,
                buildJsonObject {
                    put("id", "t$index")
                    put("title", PRIVATE)
                },
            )
        }
        runCurrent()

        // Then — nothing is marked or logged while the applier is held: the re-read must follow
        // what it applies, not race it; audit's changes still apply meanwhile
        assertEquals(emptyList(), staled)
        assertEquals(emptyList(), logs.entriesAt(EngineLogLevel.WARNING))
        audit.saved("audit", row("a1"), schema = "app")
        runCurrent()
        assertEquals(listOf(ServerChangeEvent.Saved("audit", "app", row("a1"))), applied.last())

        // When — the held batch goes through
        release.complete(Unit)
        runCurrent()

        // Then — tasks alone is marked stale, once, and one warning names the count and the
        // table, and nothing of a record
        assertEquals(listOf(TABLE to SCHEMA), staled)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("11 change(s) to $TABLE" in warning, warning)
        assertTrue(PRIVATE !in warning, warning)

        // When — a change arrives after the discard
        tasks.saved(TABLE, row("after"))
        runCurrent()

        // Then — it is applied, and nothing in between was
        assertEquals(
            listOf(
                ServerChangeEvent.Saved(TABLE, SCHEMA, row("held")),
                ServerChangeEvent.Saved(TABLE, SCHEMA, row("after")),
            ),
            applied.flatten().filter { change -> change.table == TABLE },
        )
        assertEquals(1, logs.entriesAt(EngineLogLevel.WARNING).size)
        assertEquals(RealtimeStatus.Subscribed, follower.state.value)
        scope.cancel()
    }

    @Test
    fun a_change_taken_before_a_discard_is_never_applied() = runTest {
        // Given — a cap of ten, a feed that delivers two tables' changes on one channel, and an
        // apply that holds audit's batch
        val release = CompletableDeferred<Unit>()
        val scope = newScope()
        val follower =
            newFollower(scope, backlogCapacity = 10) { _, changes ->
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

        // When — an audit change and a tasks change are taken together, so the applier sits in
        // audit's batch with the tasks change in hand, and more changes than the cap arrive
        follow.saved("audit", row("a1"), schema = "app")
        follow.saved(TABLE, row("taken"))
        runCurrent()
        repeat(11) { index -> follow.saved(TABLE, row("t$index")) }
        runCurrent()
        release.complete(Unit)
        runCurrent()

        // Then — the tasks change taken before the discard is never applied, and the count
        // holds it
        assertEquals<List<List<ServerChangeEvent.Change>>>(
            listOf(listOf(ServerChangeEvent.Saved("audit", "app", row("a1")))),
            applied,
        )
        assertEquals(listOf(TABLE to SCHEMA), staled)
        val warning = logs.entriesAt(EngineLogLevel.WARNING).single().message
        assertTrue("12 change(s)" in warning, warning)
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
            newFollower(scope, feed = bounded) { _, changes ->
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
    fun a_join_reads_its_table_again_in_its_own_schema() = runTest {
        // Given — a table of another schema and one of the default schema
        val scope = newScope()
        val follower = newFollower(scope, tables = listOf(AUDIT, WatchedTable(TABLE)))
        sessionIdentity.emit(USER)
        follower.start()
        runCurrent()
        val (audit, tasks) = feed.follows.value

        // When / Then — each join reads its own table again, in its own schema
        audit.join()
        runCurrent()
        assertEquals(listOf("audit" to "app"), staled)
        tasks.join()
        runCurrent()
        assertEquals(listOf("audit" to "app", TABLE to SCHEMA), staled)
        assertEquals(SCHEMA, audit.defaultSchema)
        assertEquals(SCHEMA, tasks.defaultSchema)
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
            newFollower(scope) { identity, changes ->
                if (!held) {
                    held = true
                    withContext(NonCancellable) { release.await() }
                }
                appliedFor += identity
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

        // Then — the held batch finished, for the user it was joined for, the one behind it was
        // dropped, said at DEBUG, and the new user's channel was joined only then
        assertEquals<List<List<ServerChangeEvent.Change>>>(
            listOf(listOf(ServerChangeEvent.Saved(TABLE, SCHEMA, row("held")))),
            applied,
        )
        assertEquals(listOf(USER), appliedFor)
        val debug =
            logs.entriesAt(EngineLogLevel.DEBUG).single { "no longer the session's" in it.message }
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

    @Test
    fun a_batch_taken_once_the_session_is_another_users_is_dropped_before_the_inputs_say_so() =
        runTest {
            // Given — joined for one user, with the session's identity read fresh, and an apply
            // held on its first batch
            var sessionNow: String? = USER
            val release = CompletableDeferred<Unit>()
            var held = false
            val scope = newScope()
            val follower =
                newFollower(scope, currentIdentity = { sessionNow }) { _, changes ->
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

            // When — the session is another user's before the follower's inputs say so, and the
            // hold is released
            sessionNow = OTHER_USER
            release.complete(Unit)
            runCurrent()

            // Then — the batch behind the held one was dropped all the same, and the follow is
            // still the first user's until the inputs change
            assertEquals<List<List<ServerChangeEvent.Change>>>(
                listOf(listOf(ServerChangeEvent.Saved(TABLE, SCHEMA, row("held")))),
                applied,
            )
            assertTrue(
                logs.entriesAt(EngineLogLevel.DEBUG).any { "no longer the session's" in it.message }
            )
            assertTrue(!first.isLeft)
            assertEquals(1, feed.follows.value.size)
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

        /** A table of another schema, beside [TABLE]. */
        val AUDIT = WatchedTable("audit", schema = "app")

        /** [TABLE] and [AUDIT] as the configuration names them. */
        val TASKS_NAMED: RealtimeTable = WatchedTable(TABLE).toRealtimeTable()
        val AUDIT_NAMED: RealtimeTable = AUDIT.toRealtimeTable()

        /** A value no log may carry. */
        const val PRIVATE = "someone@example.com"
    }
}

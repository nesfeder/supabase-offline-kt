// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.ScriptedRealtimeWebsocket
import io.github.nesfeder.supabase.offline.testdoubles.ScriptedRealtimeWebsocketFactory
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The feed over supabase-kt's real Realtime plugin, driven through a scripted websocket: the join
 * names each table with its schema and filter, a subscribed channel reports joined and then each
 * change as sent, a burst of changes reaches a slow collector whole and in order, a refused join
 * ends the feed once supabase-kt gives up rejoining it, a close from the server ends it too, and a
 * canceled collector leaves the channel. A channel error while joined is joined again by
 * supabase-kt, and reported joined once more. A binding the server refuses ends the feed, though
 * supabase-kt keeps the channel joined; an error from another extension does not. With a channel
 * per table on one socket, a refused channel is left on the server while another table's keeps
 * receiving, and a channel left while joining is left on the server. Releasing the network closes a
 * socket that holds only this library's channels, and never one that holds an app's. A token
 * provider that throws keeps the channel joining, and the feed waits, until it stops throwing.
 */
class SupabaseRealtimeFeedTest {

    private val sockets = ScriptedRealtimeWebsocketFactory()
    private val logs = RecordingLogSink()
    private val supabase = newTestSupabaseClient {
        install(Postgrest)
        install(Realtime) {
            websocketFactory = sockets
            heartbeatInterval = 1.hours
            reconnectDelay = 1.hours
            rejoinDelay = 10.milliseconds
            maxAttempts = 2
            // supabase-kt 3.9 disconnects a socket with no channels after
            // disconnectOnEmptyChannelsAfter (by default twice the heartbeat interval), and these
            // tests want the close at once
            disconnectOnEmptyChannelsAfter = Duration.ZERO
        }
    }
    private val feed = SupabaseRealtimeFeed(supabase, logs.asEngineLogger())
    private val tables =
        listOf(
            WatchedTable("tasks"),
            WatchedTable(
                "audit",
                schema = "app",
                filter = WatchedTableFilter("status", FilterOperator.EQ, "open"),
            ),
        )

    @Test
    fun the_join_names_each_table_and_a_subscribed_channel_reports_joined_then_each_change() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — following two tables
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val collecting = launch {
                    feed.follow(tables, "public").collect { event -> events.send(event) }
                }
                val socket = sockets.awaitSocket(0)

                // When — supabase-kt joins
                val join = socket.awaitSent("phx_join")

                // Then — the join names each table, its schema and its filter, on a topic of
                // the feed's own
                assertTrue(join.topic.startsWith("realtime:supabase-offline-"), join.topic)
                val bindings =
                    assertNotNull(
                        join.payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray
                    )
                assertEquals(
                    setOf("public.tasks:null", "app.audit:status=eq.open"),
                    bindings.map { binding -> binding.described() }.toSet(),
                )

                // When — the server takes the join
                socket.answer(joinTaken(join, bindings))

                // Then
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — an insert of tasks, then a delete of audit
                socket.answer(
                    change(
                        join.topic,
                        ids = listOf(1),
                        data =
                            insert(
                                buildJsonObject {
                                    put("id", "t1")
                                    put("title", "Milk")
                                }
                            ),
                    )
                )
                socket.answer(
                    change(
                        join.topic,
                        ids = listOf(2),
                        data =
                            delete(
                                buildJsonObject {
                                    put("id", "a1")
                                }
                            ),
                    )
                )

                // Then — each as sent, with its table and schema. Each table's changes are
                // collected on their own, so two tables' changes may arrive in either order
                assertEquals(
                    setOf(
                        ServerChangeEvent.Saved(
                            "tasks",
                            "public",
                            buildJsonObject {
                                put("id", "t1")
                                put("title", "Milk")
                            },
                        ),
                        ServerChangeEvent.Deleted(
                            "audit",
                            "app",
                            buildJsonObject { put("id", "a1") },
                        ),
                    ),
                    setOf(events.receiveWithin(), events.receiveWithin()),
                )

                // When — the collector is canceled
                collecting.cancelAndJoin()

                // Then — the channel is left, and the socket closed once nothing is subscribed
                val leave = socket.awaitSent("phx_leave")
                socket.answer(
                    RealtimeMessage(
                        leave.topic,
                        "phx_reply",
                        buildJsonObject { put("status", "ok") },
                        leave.ref,
                    )
                )
                socket.awaitDisconnected()
            }
            supabase.close()
        }

    @Test
    fun a_change_sent_as_soon_as_the_join_is_taken_reaches_its_table() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — following a table from a collector whose dispatcher starts the feed and
                // then runs nothing until released, as a busy one may: nothing the feed hands it
                // runs before the server has answered
                val dispatcher = HoldAfterFirstDispatcher()
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val collecting =
                    launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                        feed.follow(listOf(WatchedTable("tasks")), "public").collect { event ->
                            events.send(event)
                        }
                    }
                val row = buildJsonObject {
                    put("id", "t1")
                    put("title", "Milk")
                }

                // When — the server takes the join and sends a change at once, and supabase-kt has
                // handled both before the collector's dispatcher runs anything more
                val socket =
                    try {
                        val socket = sockets.awaitSocket(0)
                        val join = socket.awaitSent("phx_join")
                        socket.answer(joinTaken(join, join.bindings()))
                        socket.answer(change(join.topic, ids = listOf(1), data = insert(row)))
                        socket.awaitHandled()
                        socket
                    } finally {
                        // Also when the join never came, so the held coroutines can end
                        dispatcher.release()
                    }

                // Then — the change reached the table: its callback was in place before the join
                assertEquals(
                    setOf(
                        ServerChangeEvent.Joined,
                        ServerChangeEvent.Saved("tasks", "public", row),
                    ),
                    setOf(events.receiveWithin(), events.receiveWithin()),
                )
                collecting.cancelAndJoin()
                val leave = socket.awaitSent("phx_leave")
                socket.answer(
                    RealtimeMessage(
                        leave.topic,
                        "phx_reply",
                        buildJsonObject { put("status", "ok") },
                        leave.ref,
                    )
                )
                socket.awaitDisconnected()
            }
            supabase.close()
        }

    @Test
    fun a_burst_of_changes_reaches_a_slow_collector_whole_and_in_order() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — joined, with a collector that takes 5 ms over each change, as one that
                // patches the cache does
                val joined = Channel<Unit>(Channel.CONFLATED)
                val arrived = MutableStateFlow<List<String>>(emptyList())
                val collecting = launch {
                    feed.follow(tables, "public").collect { event ->
                        when (event) {
                            ServerChangeEvent.Joined -> joined.send(Unit)
                            is ServerChangeEvent.Saved -> {
                                delay(5.milliseconds)
                                val id = event.record["id"]?.jsonPrimitive?.content ?: "?"
                                arrived.update { ids -> ids + id }
                            }
                            is ServerChangeEvent.Deleted -> Unit
                        }
                    }
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                val bindings =
                    assertNotNull(
                        join.payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray
                    )
                socket.answer(joinTaken(join, bindings))
                withTimeout(10.seconds) { joined.receive() }

                // When — one statement's worth of changes, sent within milliseconds
                val ids = List(BURST) { index -> "t$index" }
                for (id in ids) {
                    socket.answer(
                        change(
                            join.topic,
                            ids = listOf(1),
                            data = insert(buildJsonObject { put("id", id) }),
                        )
                    )
                }

                // Then — every change arrives, in the order sent
                val complete =
                    withTimeoutOrNull(30.seconds) { arrived.first { got -> got.size == BURST } }
                val got = complete ?: arrived.value
                assertEquals(BURST, got.size, "${got.size} of $BURST changes arrived")
                assertEquals(ids, got)
                collecting.cancelAndJoin()
                val leave = socket.awaitSent("phx_leave")
                socket.answer(
                    RealtimeMessage(
                        leave.topic,
                        "phx_reply",
                        buildJsonObject { put("status", "ok") },
                        leave.ref,
                    )
                )
            }
            supabase.close()
        }

    @Test
    fun a_refused_channel_is_left_and_another_tables_channel_keeps_receiving() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — two tables followed, each on a channel of its own, both joined
                val two = followTwoTables(feed, sockets)

                // When — the server refuses the tasks binding, which supabase-kt only logs,
                // keeping the channel joined
                two.socket.answer(bindingRefused(two.tasksJoin.topic))

                // Then — the tasks feed ends, naming the refused binding
                val ended = withTimeout(10.seconds) { two.tasksOutcome.receive() }
                val failure = assertIs<ServerChangeFeedEndedException>(ended.exceptionOrNull())
                assertTrue("refused" in failure.message.orEmpty(), "${failure.message}")

                // And — the channel, still joined, is left on the server with a plain leave of
                // that join, which the server would otherwise keep, trying its binding
                val leave = two.socket.awaitSent("phx_leave")
                assertEquals(two.tasksJoin.topic, leave.topic)
                assertNotNull(leave.joinRef)
                assertEquals(two.tasksJoin.joinRef, leave.joinRef)
                assertFalse(supabase.realtime.subscriptions.containsKey(two.tasksJoin.topic))

                // And — the audit channel keeps receiving on the socket, which stays open
                two.socket.answer(
                    change(
                        two.auditJoin.topic,
                        ids = listOf(1),
                        data = insert(buildJsonObject { put("id", "a1") }),
                    )
                )
                assertEquals(
                    ServerChangeEvent.Saved("audit", "public", buildJsonObject { put("id", "a1") }),
                    two.auditEvents.receiveWithin(),
                )
                assertFalse(two.socket.disconnected.isCompleted)
                two.auditFollow.cancelAndJoin()
            }
            supabase.close()
        }

    @Test
    fun an_error_from_another_extension_does_not_end_the_feed() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a table followed and joined
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val collecting = launch {
                    feed.follow(listOf(WatchedTable("tasks")), "public").collect {
                        events.send(it)
                    }
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                socket.answer(joinTaken(join, join.bindings()))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — the server reports an error from an extension other than the bindings'
                socket.answer(systemError(join.topic, extension = "system"))
                socket.answer(
                    change(
                        join.topic,
                        ids = listOf(1),
                        data = insert(buildJsonObject { put("id", "t1") }),
                    )
                )

                // Then — the feed goes on delivering, and the error is logged by its extension
                assertEquals(
                    ServerChangeEvent.Saved("tasks", "public", buildJsonObject { put("id", "t1") }),
                    events.receiveWithin(),
                )
                // The error reaches the feed's own collector, which may log it after the change
                withTimeout(10.seconds) {
                    while (logs.entries.none { entry -> "system extension" in entry.message }) {
                        delay(20.milliseconds)
                    }
                }
                assertTrue(collecting.isActive)
                collecting.cancelAndJoin()
                socket.answerLeaveIfSent()
            }
            supabase.close()
        }

    @Test
    fun a_leave_never_closes_a_socket_that_holds_an_apps_own_channel() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — an app's own channel on the socket beside the library's, joined
                val appChannel = supabase.channel("app-presence")
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val outcome = Channel<Result<Unit>>(Channel.UNLIMITED)
                launch {
                    outcome.send(
                        runCatching {
                            feed.follow(listOf(WatchedTable("tasks")), "public").collect {
                                events.send(it)
                            }
                        }
                    )
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                socket.answer(joinTaken(join, join.bindings()))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — the server refuses the binding, the feed ends and leaves, and the network
                // is released, as the follower does when the network goes away
                socket.answer(bindingRefused(join.topic))
                val ended = withTimeout(10.seconds) { outcome.receive() }
                assertTrue(ended.exceptionOrNull() is ServerChangeFeedEndedException, "$ended")
                assertEquals(join.topic, socket.awaitSent("phx_leave").topic)
                feed.releaseNetwork()

                // Then — the socket stays open: closing it would end the app's channel
                assertFalse(socket.disconnected.isCompleted)
                assertEquals(Realtime.Status.CONNECTED, supabase.realtime.status.value)
                assertTrue(supabase.realtime.subscriptions.containsKey(appChannel.topic))
            }
            supabase.close()
        }

    @Test
    fun releasing_the_network_closes_a_socket_left_with_only_the_librarys_channels_gone() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a Realtime plugin that keeps a socket with no channels open for the
                // default two heartbeats, and a table followed, joined and then left
                val ownSockets = ScriptedRealtimeWebsocketFactory()
                val own = newTestSupabaseClient {
                    install(Postgrest)
                    install(Realtime) {
                        websocketFactory = ownSockets
                        heartbeatInterval = 1.hours
                        reconnectDelay = 1.hours
                    }
                }
                val ownFeed = SupabaseRealtimeFeed(own, logs.asEngineLogger())
                val socket = followJoinedAndLeft(ownFeed, ownSockets)
                assertFalse(socket.disconnected.isCompleted)

                // When — the network is released
                ownFeed.releaseNetwork()

                // Then — the socket closes at once, rather than after two heartbeats
                socket.awaitDisconnected()
                assertEquals(Realtime.Status.DISCONNECTED, own.realtime.status.value)
                own.close()
            }
            supabase.close()
        }

    @Test
    fun releasing_the_network_leaves_open_a_socket_the_app_keeps_without_channels() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a Realtime plugin the app told to keep a socket with no channels open,
                // and a table followed, joined and then left
                val ownSockets = ScriptedRealtimeWebsocketFactory()
                val own = newTestSupabaseClient {
                    install(Postgrest)
                    install(Realtime) {
                        websocketFactory = ownSockets
                        heartbeatInterval = 1.hours
                        reconnectDelay = 1.hours
                        disconnectOnNoSubscriptions = false
                    }
                }
                val ownFeed = SupabaseRealtimeFeed(own, logs.asEngineLogger())
                val socket = followJoinedAndLeft(ownFeed, ownSockets)

                // When — the network is released
                ownFeed.releaseNetwork()

                // Then — the socket stays open, as the app configured it
                assertFalse(socket.disconnected.isCompleted)
                assertEquals(Realtime.Status.CONNECTED, own.realtime.status.value)
                own.close()
            }
            supabase.close()
        }

    @Test
    fun a_channel_left_while_joining_is_left_on_the_server() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — the audit channel joined, which keeps the socket open, and the tasks
                // channel's join not answered yet
                val auditEvents = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val auditFollow = launch {
                    feed.follow(listOf(WatchedTable("audit")), "public").collect {
                        auditEvents.send(it)
                    }
                }
                val socket = sockets.awaitSocket(0)
                val auditJoin = socket.awaitSent("phx_join")
                socket.answer(joinTaken(auditJoin, auditJoin.bindings()))
                assertEquals(ServerChangeEvent.Joined, auditEvents.receiveWithin())
                val tasksFollow = launch {
                    feed.follow(listOf(WatchedTable("tasks")), "public").collect {}
                }
                val tasksJoin = socket.awaitSent("phx_join")

                // When — the tasks follow is canceled before the server answers
                tasksFollow.cancelAndJoin()

                // Then — a leave goes out for it, so the server does not keep a channel nobody
                // reads
                assertEquals(tasksJoin.topic, socket.awaitSent("phx_leave").topic)
                assertFalse(socket.disconnected.isCompleted)
                auditFollow.cancelAndJoin()
            }
            supabase.close()
        }

    @Test
    fun a_refused_join_ends_the_feed() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a server that refuses every join and takes every leave; supabase-kt joins
                // again after a refusal, the channel still joining, until maxAttempts (two here)
                val joins = AtomicInt(0)
                val refusing = launch {
                    val socket = sockets.awaitSocket(0)
                    for (message in socket.sent) {
                        if (message.event == "phx_join") joins.incrementAndFetch()
                        val reply =
                            when (message.event) {
                                "phx_join" ->
                                    buildJsonObject {
                                        put("status", "error")
                                        putJsonObject("response") {
                                            put("reason", "no publication")
                                        }
                                    }
                                "phx_leave" -> buildJsonObject { put("status", "ok") }
                                else -> continue
                            }
                        socket.answer(
                            RealtimeMessage(message.topic, "phx_reply", reply, message.ref)
                        )
                    }
                }

                // When / Then — the feed ends once supabase-kt gives up, after its own rejoin,
                // leaving the next join to the follower
                assertFailsWith<ServerChangeFeedEndedException> {
                    withTimeout(30.seconds) { feed.follow(tables, "public").collect {} }
                }
                assertEquals(2, joins.load())
                refusing.cancelAndJoin()
            }
            supabase.close()
        }

    @Test
    fun a_channel_error_while_joined_is_joined_again_and_reported_joined_once_more() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — joined
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val outcome = Channel<Result<Unit>>(Channel.UNLIMITED)
                val following = launch {
                    outcome.send(
                        runCatching {
                            feed.follow(listOf(WatchedTable("tasks")), "public").collect {
                                events.send(it)
                            }
                        }
                    )
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                socket.answer(joinTaken(join, join.bindings()))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — the server reports an error on the channel
                socket.answer(RealtimeMessage(join.topic, "phx_error", buildJsonObject {}, null))

                // Then — supabase-kt joins the same channel again, without a leave, and the feed
                // reports the join once more rather than ending
                val rejoin = socket.awaitSent("phx_join")
                assertEquals(join.topic, rejoin.topic)
                socket.answer(joinTaken(rejoin, rejoin.bindings()))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())
                assertTrue(outcome.tryReceive().isFailure)
                following.cancelAndJoin()
                socket.answerLeaveIfSent()
            }
            supabase.close()
        }

    @Test
    fun a_close_from_the_server_ends_the_feed() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — joined
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val outcome = Channel<Result<Unit>>(Channel.UNLIMITED)
                launch {
                    outcome.send(
                        runCatching { feed.follow(tables, "public").collect { events.send(it) } }
                    )
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                val bindings =
                    assertNotNull(
                        join.payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray
                    )
                socket.answer(joinTaken(join, bindings))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — the server closes the channel
                socket.answer(RealtimeMessage(join.topic, "phx_close", buildJsonObject {}, null))

                // Then — the feed ends; supabase-kt leaves and disconnects at once on a close, and
                // the leave may or may not reach the socket before the disconnect closes it
                socket.answerLeaveIfSent()
                val ended = withTimeout(10.seconds) { outcome.receive() }
                assertTrue(ended.exceptionOrNull() is ServerChangeFeedEndedException, "$ended")
            }
            supabase.close()
        }

    @Test
    fun an_update_is_reported_as_saved_and_a_select_is_dropped() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — joined
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val collecting = launch {
                    feed.follow(tables, "public").collect { event -> events.send(event) }
                }
                val socket = sockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")
                val bindings =
                    assertNotNull(
                        join.payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray
                    )
                socket.answer(joinTaken(join, bindings))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())

                // When — a select (never asked for), an update, then an insert
                val row = buildJsonObject {
                    put("id", "t1")
                    put("title", "Oat milk")
                }
                socket.answer(change(join.topic, ids = listOf(1), data = select(row)))
                socket.answer(change(join.topic, ids = listOf(1), data = update(row)))
                socket.answer(change(join.topic, ids = listOf(1), data = insert(row)))

                // Then — the update and the insert, both as saved; nothing for the select
                assertEquals(
                    ServerChangeEvent.Saved("tasks", "public", row),
                    events.receiveWithin(),
                )
                assertEquals(
                    ServerChangeEvent.Saved("tasks", "public", row),
                    events.receiveWithin(),
                )
                collecting.cancelAndJoin()
                val leave = socket.awaitSent("phx_leave")
                socket.answer(
                    RealtimeMessage(
                        leave.topic,
                        "phx_reply",
                        buildJsonObject { put("status", "ok") },
                        leave.ref,
                    )
                )
            }
            supabase.close()
        }

    @Test
    fun without_auth_the_feed_from_the_installed_plugin_refreshes_the_providers_token() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a client with its own token provider and no Auth plugin
                val ownSockets = ScriptedRealtimeWebsocketFactory()
                val own = newTestSupabaseClient {
                    accessToken = { jwt(expiresAtSeconds = Clock.System.now().epochSeconds + 3600) }
                    install(Postgrest)
                    install(Realtime) {
                        websocketFactory = ownSockets
                        heartbeatInterval = 1.hours
                        reconnectDelay = 1.hours
                        // supabase-kt 3.9 disconnects a socket with no channels after
                        // disconnectOnEmptyChannelsAfter (by default twice the heartbeat
                        // interval), and this test wants the close at once
                        disconnectOnEmptyChannelsAfter = Duration.ZERO
                    }
                }
                val ownFeed =
                    SupabaseRealtimeFeed.forInstalledPlugin(
                        own,
                        logs.asEngineLogger(),
                        Clock.System,
                    )
                val collecting = launch { ownFeed.follow(tables, "public").collect {} }
                val socket = ownSockets.awaitSocket(0)
                val join = socket.awaitSent("phx_join")

                // Then — the provider's token is handed to the channel
                withTimeout(10.seconds) {
                    while (logs.entries.none { entry -> "Realtime:" in entry.message }) delay(20)
                }
                val said = logs.entries.map { entry -> entry.message }
                assertTrue(
                    said.any { message -> "sent the provider's token" in message },
                    "$said",
                )
                // When — the collector is canceled before the join was ever answered
                collecting.cancelAndJoin()

                // Then — nothing is subscribed, so supabase-kt disconnects
                assertTrue(join.topic.startsWith("realtime:supabase-offline-"), join.topic)
                socket.awaitDisconnected()
                own.close()
            }
            supabase.close()
        }

    @Test
    fun a_token_provider_that_throws_keeps_the_channel_joining_until_it_stops_throwing() =
        runTest(timeout = 2.minutes) {
            withContext(Dispatchers.Default) {
                // Given — a Realtime token provider that throws, under supabase-kt's default
                // requireValidSession, with one refusal allowed
                val throwing = AtomicBoolean(true)
                val asked = AtomicInt(0)
                val ownSockets = ScriptedRealtimeWebsocketFactory()
                val own = newTestSupabaseClient {
                    install(Postgrest)
                    install(Realtime) {
                        websocketFactory = ownSockets
                        heartbeatInterval = 1.hours
                        reconnectDelay = 1.hours
                        rejoinDelay = 10.milliseconds
                        maxAttempts = 1
                        accessToken = {
                            asked.incrementAndFetch()
                            check(!throwing.load()) { "no token" }
                            null
                        }
                    }
                }
                val ownFeed = SupabaseRealtimeFeed(own, logs.asEngineLogger())
                val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                val outcome = Channel<Result<Unit>>(Channel.UNLIMITED)
                launch {
                    outcome.send(
                        runCatching {
                            ownFeed.follow(listOf(WatchedTable("tasks")), "public").collect {
                                events.send(it)
                            }
                        }
                    )
                }
                val socket = ownSockets.awaitSocket(0)

                // When — the provider has thrown for longer than maxAttempts rejoins would take
                withTimeout(10.seconds) { while (asked.load() < 10) delay(10.milliseconds) }

                // Then — supabase-kt asked again every rejoinDelay without giving up, sent no join,
                // and the feed neither joined nor ended
                val sentSoFar = buildList {
                    while (true) add(socket.sent.tryReceive().getOrNull()?.event ?: break)
                }
                assertTrue("phx_join" !in sentSoFar, "$sentSoFar")
                assertTrue(events.tryReceive().isFailure)
                assertTrue(outcome.tryReceive().isFailure)
                assertEquals(
                    RealtimeChannel.Status.SUBSCRIBING,
                    own.realtime.subscriptions.values.single().status.value,
                )

                // When — the provider stops throwing
                throwing.store(false)

                // Then — the next rejoin sends the join, and the feed joins
                val join = socket.awaitSent("phx_join")
                socket.answer(joinTaken(join, join.bindings()))
                assertEquals(ServerChangeEvent.Joined, events.receiveWithin())
                own.close()
            }
            supabase.close()
        }

    /**
     * Follows `tasks` through [feed] on the socket [factory] opens, joins it, cancels the follow
     * and answers its leave: the library's one channel is gone, and the socket is returned.
     */
    private suspend fun CoroutineScope.followJoinedAndLeft(
        feed: SupabaseRealtimeFeed,
        factory: ScriptedRealtimeWebsocketFactory,
    ): ScriptedRealtimeWebsocket {
        val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
        val collecting = launch {
            feed.follow(listOf(WatchedTable("tasks")), "public").collect { events.send(it) }
        }
        val socket = factory.awaitSocket(0)
        val join = socket.awaitSent("phx_join")
        socket.answer(joinTaken(join, join.bindings()))
        assertEquals(ServerChangeEvent.Joined, events.receiveWithin())
        collecting.cancelAndJoin()
        val leave = socket.awaitSent("phx_leave")
        socket.answer(
            RealtimeMessage(
                leave.topic,
                "phx_reply",
                buildJsonObject { put("status", "ok") },
                leave.ref,
            )
        )
        socket.awaitHandled()
        return socket
    }

    /** Two tables followed through [feed], each on a channel of its own, both joined. */
    private class TwoTables(
        val socket: ScriptedRealtimeWebsocket,
        val tasksJoin: RealtimeMessage,
        val auditJoin: RealtimeMessage,
        val tasksOutcome: Channel<Result<Unit>>,
        val auditOutcome: Channel<Result<Unit>>,
        val auditEvents: Channel<ServerChangeEvent>,
        val auditFollow: Job,
    )

    /**
     * Follows `tasks` and `audit` through [feed], one follow each, as the follower does, and joins
     * both on the socket [factory] opens.
     */
    private suspend fun CoroutineScope.followTwoTables(
        feed: SupabaseRealtimeFeed,
        factory: ScriptedRealtimeWebsocketFactory,
    ): TwoTables {
        val tasksEvents = Channel<ServerChangeEvent>(Channel.UNLIMITED)
        val auditEvents = Channel<ServerChangeEvent>(Channel.UNLIMITED)
        val tasksOutcome = Channel<Result<Unit>>(Channel.UNLIMITED)
        val auditOutcome = Channel<Result<Unit>>(Channel.UNLIMITED)
        launch {
            tasksOutcome.send(
                runCatching {
                    feed.follow(listOf(WatchedTable("tasks")), "public").collect {
                        tasksEvents.send(it)
                    }
                }
            )
        }
        val auditFollow = launch {
            auditOutcome.send(
                runCatching {
                    feed.follow(listOf(WatchedTable("audit")), "public").collect {
                        auditEvents.send(it)
                    }
                }
            )
        }
        val socket = factory.awaitSocket(0)
        val joins = listOf(socket.awaitSent("phx_join"), socket.awaitSent("phx_join"))
        for (join in joins) socket.answer(joinTaken(join, join.bindings()))
        assertEquals(ServerChangeEvent.Joined, tasksEvents.receiveWithin())
        assertEquals(ServerChangeEvent.Joined, auditEvents.receiveWithin())
        return TwoTables(
            socket = socket,
            tasksJoin = joins.single { join -> join.boundTable() == "tasks" },
            auditJoin = joins.single { join -> join.boundTable() == "audit" },
            tasksOutcome = tasksOutcome,
            auditOutcome = auditOutcome,
            auditEvents = auditEvents,
            auditFollow = auditFollow,
        )
    }

    /** The `postgres_changes` bindings a join carries. */
    private fun RealtimeMessage.bindings(): List<JsonElement> =
        assertNotNull(payload["config"]?.jsonObject?.get("postgres_changes")?.jsonArray)

    /** The table of a join's one binding. */
    private fun RealtimeMessage.boundTable(): String? =
        bindings().single().jsonObject["table"]?.jsonPrimitive?.contentOrNull

    /**
     * The server's word that a channel's binding is refused, as it sends one after the join reply:
     * a system message from the `postgres_changes` extension with an error status, which names no
     * binding supabase-kt reads.
     */
    private fun bindingRefused(topic: String): RealtimeMessage =
        systemError(
            topic,
            extension = "postgres_changes",
            message = "Unable to subscribe to changes with given parameters",
        )

    /**
     * A system message from [extension] with an error status. It names the channel by its
     * sub-topic, a field supabase-kt 3.9 requires: without it, the message fails to decode in
     * supabase-kt's read loop, which then reconnects.
     */
    private fun systemError(
        topic: String,
        extension: String,
        message: String = "Something went wrong",
    ): RealtimeMessage =
        RealtimeMessage(
            topic,
            "system",
            buildJsonObject {
                put("status", "error")
                put("extension", extension)
                put("message", message)
                put("channel", topic.removePrefix("realtime:"))
            },
            null,
        )

    /** A JWT whose payload carries [expiresAtSeconds] as `exp`. */
    private fun jwt(expiresAtSeconds: Long): String {
        val encoder = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = encoder.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload =
            encoder.encode("""{"sub":"user","exp":$expiresAtSeconds}""".encodeToByteArray())
        // supabase-kt decodes the signature too, so it must be base64 as well
        return "$header.$payload.${encoder.encode("signature".encodeToByteArray())}"
    }

    /** The server's answer to [join]: every binding taken, numbered from one. */
    private fun joinTaken(join: RealtimeMessage, bindings: List<JsonElement>): RealtimeMessage =
        RealtimeMessage(
            join.topic,
            "phx_reply",
            buildJsonObject {
                put("status", "ok")
                putJsonObject("response") {
                    putJsonArray("postgres_changes") {
                        bindings.forEachIndexed { index, binding ->
                            add(
                                buildJsonObject {
                                    binding.jsonObject.forEach { (key, value) -> put(key, value) }
                                    put("id", index + 1)
                                }
                            )
                        }
                    }
                }
            },
            join.ref,
        )

    /** A `postgres_changes` message for the bindings [ids], carrying [data]. */
    private fun change(topic: String, ids: List<Int>, data: JsonObject): RealtimeMessage =
        RealtimeMessage(
            topic,
            "postgres_changes",
            buildJsonObject {
                putJsonArray("ids") { ids.forEach { id -> add(id) } }
                put("data", data)
            },
            null,
        )

    private fun insert(record: JsonObject): JsonObject = buildJsonObject {
        put("type", "INSERT")
        put("record", record)
        putJsonArray("columns") {}
        put("commit_timestamp", "2026-10-13T12:00:00Z")
    }

    private fun update(record: JsonObject): JsonObject = buildJsonObject {
        put("type", "UPDATE")
        put("record", record)
        put("old_record", buildJsonObject { put("id", "t1") })
        putJsonArray("columns") {}
        put("commit_timestamp", "2026-10-13T12:00:00Z")
    }

    private fun select(record: JsonObject): JsonObject = buildJsonObject {
        put("type", "SELECT")
        put("record", record)
        putJsonArray("columns") {}
        put("commit_timestamp", "2026-10-13T12:00:00Z")
    }

    private fun delete(oldRecord: JsonObject): JsonObject = buildJsonObject {
        put("type", "DELETE")
        put("old_record", oldRecord)
        putJsonArray("columns") {}
        put("commit_timestamp", "2026-10-13T12:00:00Z")
    }

    /** `schema.table:filter` of a binding supabase-kt sent. */
    private fun JsonElement.described(): String {
        val binding = jsonObject
        val schema = binding["schema"]?.jsonPrimitive?.contentOrNull
        val table = binding["table"]?.jsonPrimitive?.contentOrNull
        val filter = binding["filter"]?.jsonPrimitive?.contentOrNull
        return "$schema.$table:$filter"
    }

    private suspend fun Channel<ServerChangeEvent>.receiveWithin(): ServerChangeEvent =
        withTimeout(10.seconds) { receive() }

    /**
     * Answers the leave supabase-kt sends, when it reaches this socket within two seconds. After a
     * close from the server, supabase-kt removes the channel, which sends its leave and, with no
     * channel left and `disconnectOnEmptyChannelsAfter` at zero, disconnects right after; the
     * socket may close before a leave is read.
     */
    private suspend fun ScriptedRealtimeWebsocket.answerLeaveIfSent() {
        val leave =
            try {
                withTimeoutOrNull(2.seconds) { awaitSent("phx_leave") }
            } catch (closed: ClosedReceiveChannelException) {
                null
            } ?: return
        answer(
            RealtimeMessage(
                leave.topic,
                "phx_reply",
                buildJsonObject { put("status", "ok") },
                leave.ref,
            )
        )
    }

    private companion object {
        /** How many changes one statement over a few hundred visible rows sends. */
        const val BURST = 500
    }
}

/**
 * Hands its first dispatch to [Dispatchers.Default] and holds every later one until [release], then
 * hands every dispatch on: a collector's dispatcher that is busy from the moment the feed starts.
 */
private class HoldAfterFirstDispatcher : CoroutineDispatcher() {
    private val dispatches = AtomicInt(0)
    private val released = AtomicBoolean(false)
    private val held = Channel<Pair<CoroutineContext, Runnable>>(Channel.UNLIMITED)

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val holds = dispatches.incrementAndFetch() > 1 && !released.load()
        // A dispatch that races the release finds the queue closed and goes on at once
        if (!holds || !held.trySend(context to block).isSuccess) {
            Dispatchers.Default.dispatch(context, block)
        }
    }

    /** Hands on every dispatch held so far, in order, and every later one at once. */
    fun release() {
        released.store(true)
        held.close()
        while (true) {
            val (context, block) = held.tryReceive().getOrNull() ?: break
            Dispatchers.Default.dispatch(context, block)
        }
    }
}

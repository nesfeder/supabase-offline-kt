// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.internal.realtime

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import io.github.nesfeder.supabase.offline.testdoubles.RecordingLogSink
import io.github.nesfeder.supabase.offline.testdoubles.ScriptedRealtimeWebsocketFactory
import io.github.nesfeder.supabase.offline.testdoubles.newTestSupabaseClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The feed collected on a dispatcher that runs its work in place, as `Dispatchers.Main.immediate`
 * does on the main thread: a table's producer then waits in that thread's queue behind the feed's
 * own coroutine, and still registers its callback before the join goes out. It blocks a thread, so
 * it runs on the JVM only.
 */
class SupabaseRealtimeFeedInPlaceTest {

    private val sockets = ScriptedRealtimeWebsocketFactory()
    private val gated = JoinBlockingSocketFactory(sockets)
    private val supabase = newTestSupabaseClient {
        install(Postgrest)
        install(Realtime) {
            websocketFactory = gated
            heartbeatInterval = 1.hours
            reconnectDelay = 1.hours
            rejoinDelay = 10.milliseconds
            maxAttempts = 2
        }
    }
    private val feed = SupabaseRealtimeFeed(supabase, RecordingLogSink().asEngineLogger())

    @Test
    fun a_feed_on_a_dispatcher_that_runs_in_place_registers_its_table_before_the_join() =
        runTest(timeout = 2.minutes) {
            val inPlace = InPlaceOnItsOwnThread()
            try {
                withContext(Dispatchers.Default) {
                    // Given — following a table on a dispatcher that runs in place on its own
                    // thread, whose join's send blocks that thread until released
                    val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)
                    val collecting =
                        launch(inPlace) {
                            feed.follow(listOf(WatchedTable("tasks")), "public").collect { event ->
                                events.send(event)
                            }
                        }
                    val socket = sockets.awaitSocket(0)
                    val join = socket.awaitSent("phx_join")

                    // When — the server takes the join and sends a change, and supabase-kt has
                    // handled both while the feed's thread still sends the join
                    val row = buildJsonObject { put("id", "t1") }
                    socket.answer(joinTaken(join))
                    socket.answer(change(join.topic, row))
                    socket.awaitHandled()
                    gated.joinRelease.countDown()

                    // Then — the change reached the table: its callback was in place before the
                    // join
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
                }
            } finally {
                gated.joinRelease.countDown()
                inPlace.executor.shutdown()
            }
            supabase.close()
        }

    private suspend fun Channel<ServerChangeEvent>.receiveWithin(): ServerChangeEvent =
        withTimeout(10.seconds) { receive() }

    /** The server's answer to [join]: every binding taken, numbered from one. */
    private fun joinTaken(join: RealtimeMessage): RealtimeMessage {
        val bindings = join.payload["config"]!!.jsonObject["postgres_changes"]!!.jsonArray
        return RealtimeMessage(
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
    }

    /** An insert of [record] for the join's first binding. */
    private fun change(topic: String, record: JsonObject): RealtimeMessage =
        RealtimeMessage(
            topic,
            "postgres_changes",
            buildJsonObject {
                putJsonArray("ids") { add(1) }
                put(
                    "data",
                    buildJsonObject {
                        put("type", "INSERT")
                        put("record", record)
                        putJsonArray("columns") {}
                        put("commit_timestamp", "2026-10-13T12:00:00Z")
                    },
                )
            },
            null,
        )
}

/**
 * Runs its work in place when already on its own thread and dispatches to that thread from any
 * other, as `Dispatchers.Main.immediate` does with the main thread.
 */
private class InPlaceOnItsOwnThread : CoroutineDispatcher() {
    @Volatile private var thread: Thread? = null
    val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "in-place").also { thread = it }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        Thread.currentThread() !== thread

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute(block)
    }
}

/**
 * Scripted sockets whose send of a join blocks the sending thread until [joinRelease] counts down.
 */
private class JoinBlockingSocketFactory(private val sockets: ScriptedRealtimeWebsocketFactory) :
    RealtimeWebsocketFactory {
    val joinRelease = CountDownLatch(1)

    override suspend fun create(url: String): RealtimeWebsocket {
        val socket = sockets.create(url)
        return object : RealtimeWebsocket by socket {
            override suspend fun send(message: RealtimeMessage) {
                socket.send(message)
                if (message.event == "phx_join") joinRelease.await(30, TimeUnit.SECONDS)
            }
        }
    }
}

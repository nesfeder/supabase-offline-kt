// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.RealtimeProtocolVersion
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import io.ktor.websocket.Frame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * A Realtime websocket a test scripts: what supabase-kt sends arrives on [sent], and the test
 * answers through [answer], as the Realtime server would. Disconnecting ends both.
 *
 * It speaks Realtime protocol 2.0.0, supabase-kt's default: each answer reaches supabase-kt as the
 * text frame the server sends, the JSON array `[join_ref, ref, topic, event, payload]`. The socket
 * builds that array itself because supabase-kt's own encoder is internal. Messages supabase-kt
 * sends arrive already decoded, whatever protocol version it encodes them for.
 */
internal class ScriptedRealtimeWebsocket : RealtimeWebsocket {

    /** Every message supabase-kt sent, in order; closed once disconnected. */
    val sent: Channel<RealtimeMessage> = Channel(Channel.UNLIMITED)

    private val incoming = Channel<RealtimeMessage>(Channel.UNLIMITED)

    /** How many messages [answer] has delivered. */
    private val answered = MutableStateFlow(0)

    /** How many times supabase-kt has asked for the next message. */
    private val receives = MutableStateFlow(0)

    /** Completed once supabase-kt disconnected this socket. */
    val disconnected: CompletableDeferred<Unit> = CompletableDeferred()

    /** The next ref [makeRef] hands out. */
    private val nextRef = MutableStateFlow(0)

    override val hasIncomingMessages: Boolean
        get() = !incoming.isClosedForReceive

    /**
     * A ref per message, counting from `"0"`, as the real socket does. It must be numeric:
     * supabase-kt reads a heartbeat's ref back as an integer.
     */
    override fun makeRef(): String = nextRef.getAndUpdate { ref -> ref + 1 }.toString()

    override suspend fun receive(): Frame {
        receives.update { count -> count + 1 }
        val message = incoming.receive()
        val serverFrame = buildJsonArray {
            add(message.joinRef)
            add(message.ref)
            add(message.topic)
            add(message.event)
            add(message.payload)
        }
        return Frame.Text(serverFrame.toString())
    }

    override suspend fun send(message: RealtimeMessage, vsn: RealtimeProtocolVersion) {
        sent.send(message)
    }

    /** Fails: supabase-kt sends binary frames only for broadcasts, and the library sends none. */
    override suspend fun send(data: ByteArray) {
        error("The library never sends a binary broadcast, but ${data.size} bytes were sent")
    }

    override suspend fun blockUntilDisconnect() {
        disconnected.await()
    }

    override fun disconnect() {
        incoming.close()
        sent.close()
        disconnected.complete(Unit)
    }

    /** Delivers [message] to supabase-kt, as the server would. */
    fun answer(message: RealtimeMessage) {
        answered.update { count -> count + 1 }
        incoming.trySend(message)
    }

    /**
     * Waits up to [timeout] for supabase-kt to have handled every message answered so far. Its read
     * loop handles one message at a time and asks for the next only once done, so it has handled
     * them all once it asks for the one after them.
     */
    suspend fun awaitHandled(timeout: Duration = 10.seconds) {
        val count = answered.value
        withTimeout(timeout) { receives.first { asked -> asked > count } }
    }

    /** The next message supabase-kt sends with [event], skipping others, within [timeout]. */
    suspend fun awaitSent(event: String, timeout: Duration = 10.seconds): RealtimeMessage =
        withTimeout(timeout) {
            var message = sent.receive()
            while (message.event != event) message = sent.receive()
            message
        }

    /** Waits up to [timeout] for supabase-kt to disconnect this socket. */
    suspend fun awaitDisconnected(timeout: Duration = 10.seconds) {
        withTimeout(timeout) { disconnected.await() }
    }
}

/** Makes a [ScriptedRealtimeWebsocket] for each connection supabase-kt opens, and keeps them. */
internal class ScriptedRealtimeWebsocketFactory : RealtimeWebsocketFactory {

    private val opened = MutableStateFlow<List<ScriptedRealtimeWebsocket>>(emptyList())

    /** Every socket opened so far, in order. */
    val sockets: StateFlow<List<ScriptedRealtimeWebsocket>> = opened.asStateFlow()

    override suspend fun create(url: String): RealtimeWebsocket =
        ScriptedRealtimeWebsocket().also { socket -> opened.update { current -> current + socket } }

    /** The socket at [index], waiting up to [timeout] for supabase-kt to open it. */
    suspend fun awaitSocket(index: Int, timeout: Duration = 10.seconds): ScriptedRealtimeWebsocket =
        withTimeout(timeout) { sockets.first { current -> current.size > index }[index] }
}

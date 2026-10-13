// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(SupabaseInternal::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.realtime.RealtimeMessage
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocket
import io.github.jan.supabase.realtime.websocket.RealtimeWebsocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout

/**
 * A Realtime websocket a test scripts: what supabase-kt sends arrives on [sent], and the test
 * answers through [answer], as the Realtime server would. Disconnecting ends both.
 */
internal class ScriptedRealtimeWebsocket : RealtimeWebsocket {

    /** Every message supabase-kt sent, in order; closed once disconnected. */
    val sent: Channel<RealtimeMessage> = Channel(Channel.UNLIMITED)

    private val incoming = Channel<RealtimeMessage>(Channel.UNLIMITED)

    /** Completed once supabase-kt disconnected this socket. */
    val disconnected: CompletableDeferred<Unit> = CompletableDeferred()

    override val hasIncomingMessages: Boolean
        get() = !incoming.isClosedForReceive

    override suspend fun receive(): RealtimeMessage = incoming.receive()

    override suspend fun send(message: RealtimeMessage) {
        sent.send(message)
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
        incoming.trySend(message)
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

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeEvent
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.WatchedTable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * A server-change feed a test scripts: each follow the client starts is recorded with what it asked
 * for, and the test joins it, reports changes on it, ends it, or fails it. Nothing is reported
 * until the test says so, so a follow is not joined by itself.
 */
internal class FakeServerChangeFeed : ServerChangeFeed {

    /** One follow the client started: what it asked for, and the script that answers it. */
    class Follow internal constructor(val tables: List<WatchedTable>, val defaultSchema: String) {

        private val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)

        /** Completed once the client has left this follow, however the follow ended. */
        val left: CompletableDeferred<Unit> = CompletableDeferred()

        /** Whether the client has left this follow. */
        val isLeft: Boolean
            get() = left.isCompleted

        /** Waits, on real threads, up to [timeout] for the client to leave this follow. */
        suspend fun awaitLeft(timeout: Duration = 10.seconds) {
            withContext(Dispatchers.Default) { withTimeout(timeout) { left.await() } }
        }

        /** Reports the channel joined. */
        fun join() {
            events.trySend(ServerChangeEvent.Joined)
        }

        /** Reports a row of [table] inserted or updated. */
        fun saved(table: String, record: JsonObject, schema: String = defaultSchema) {
            events.trySend(ServerChangeEvent.Saved(table, schema, record))
        }

        /** Reports a row of [table] deleted. */
        fun deleted(table: String, oldRecord: JsonObject, schema: String = defaultSchema) {
            events.trySend(ServerChangeEvent.Deleted(table, schema, oldRecord))
        }

        /** Ends the follow with [cause], as a channel the server refused or closed does. */
        fun fail(cause: Throwable) {
            events.close(cause)
        }

        /** Ends the follow quietly, as a feed that completes does. */
        fun end() {
            events.close()
        }

        /** Hands every scripted event to [collector] until the follow ends. */
        internal suspend fun emitInto(collector: FlowCollector<ServerChangeEvent>) {
            for (event in events) collector.emit(event)
        }
    }

    private val started = MutableStateFlow<List<Follow>>(emptyList())

    /** Every follow the client started, in order. */
    val follows: StateFlow<List<Follow>> = started.asStateFlow()

    override fun follow(
        tables: List<WatchedTable>,
        defaultSchema: String,
    ): Flow<ServerChangeEvent> = flow {
        val follow = Follow(tables, defaultSchema)
        started.update { current -> current + follow }
        try {
            follow.emitInto(this)
        } finally {
            follow.left.complete(Unit)
        }
    }

    /**
     * The follow at [index], waiting on real threads up to [timeout] for the client to start it:
     * the client's work runs on real dispatchers, which a test scheduler's clock never reaches.
     */
    suspend fun awaitFollow(index: Int, timeout: Duration = 10.seconds): Follow =
        withContext(Dispatchers.Default) {
            withTimeout(timeout) { follows.first { current -> current.size > index }[index] }
        }
}

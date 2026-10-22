// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class, ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeEvent
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.toRealtimeTable
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.experimental.ExperimentalObjCRefinement
import kotlin.native.HiddenFromObjC
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/**
 * A channel a test scripts in place of Realtime's: what the client asked to follow, and the changes
 * the test reports on it until it ends. One is made for each follow the client starts; the
 * `supabase-offline-kt-testing` artifact's `FakeRealtime` drives it.
 *
 * The channel holds as many reported events for the client as its capacity, unlimited for the
 * testing artifact: one reported while that many wait is dropped, as the Realtime plugin of
 * supabase-kt 3.6.0 drops a change once 64 wait, and counted in `droppedCount`.
 *
 * @property tables the tables the client follows through this channel.
 * @property defaultSchema the schema of a table that names none.
 */
@OfflineInternal
@HiddenFromObjC
public class ScriptedRealtimeChannel
internal constructor(
    public val tables: List<RealtimeTable>,
    public val defaultSchema: String,
    capacity: Int,
) {

    private val events = Channel<ServerChangeEvent>(capacity)

    private val dropped = AtomicInt(0)

    private val left = CompletableDeferred<Unit>()

    /** How many reported events were dropped because the channel's capacity was full. */
    internal val droppedCount: Int
        get() = dropped.load()

    /** Reports the channel joined: every change reported from now on is delivered. */
    public fun join() {
        report(ServerChangeEvent.Joined)
    }

    /**
     * Reports a row of [table] in [schema], the default schema when `null`, inserted or updated.
     */
    public fun saved(table: String, schema: String?, record: JsonObject) {
        report(ServerChangeEvent.Saved(table, schema ?: defaultSchema, record))
    }

    /** Reports a row of [table] in [schema], the default schema when `null`, deleted. */
    public fun deleted(table: String, schema: String?, oldRecord: JsonObject) {
        report(ServerChangeEvent.Deleted(table, schema ?: defaultSchema, oldRecord))
    }

    /** Hands [event] to the client, or drops it when the capacity is full. */
    private fun report(event: ServerChangeEvent) {
        val sent = events.trySend(event)
        if (sent.isFailure && !sent.isClosed) dropped.incrementAndFetch()
    }

    /** Ends the channel with [cause], as a server that refused the join or closed it does. */
    public fun fail(cause: Throwable) {
        events.close(cause)
    }

    /** Ends the channel quietly, as a lost socket does. */
    public fun end() {
        events.close()
    }

    /** Runs [action] once the client has left the channel; at once when it already has. */
    public fun onLeft(action: () -> Unit) {
        left.invokeOnCompletion { action() }
    }

    /** Hands every reported event to [collector] until the channel ends or the client leaves. */
    internal suspend fun emitInto(collector: FlowCollector<ServerChangeEvent>) {
        try {
            for (event in events) collector.emit(event)
        } finally {
            left.complete(Unit)
        }
    }
}

/**
 * Makes the plugin follow the tables `realtime { }` names through channels a test scripts, in place
 * of the Realtime plugin, which then need not be installed: [onChannel] receives each channel the
 * client opens, before anything is delivered on it.
 *
 * This is the hook behind `inMemory(clock, realtime)` in the `supabase-offline-kt-testing`
 * artifact, which is the supported entry point. The opt-in gates Kotlin callers; [HiddenFromObjC]
 * keeps it out of the Objective-C export, where an opt-in cannot be enforced.
 */
@OfflineInternal
@HiddenFromObjC
public fun Offline.Config.followServerChangesThrough(onChannel: (ScriptedRealtimeChannel) -> Unit) {
    serverChangeFeed = { scriptedServerChangeFeed(onChannel = onChannel) }
}

/**
 * A feed over channels a test scripts, one for each follow the client starts, each holding at most
 * [capacity] reported events for the client: [onChannel] receives each channel before anything is
 * delivered on it.
 */
@OptIn(OfflineInternal::class)
internal fun scriptedServerChangeFeed(
    capacity: Int = Channel.UNLIMITED,
    onChannel: (ScriptedRealtimeChannel) -> Unit,
): ServerChangeFeed = ServerChangeFeed { tables, defaultSchema ->
    flow {
        val channel =
            ScriptedRealtimeChannel(
                tables = tables.map { watched -> watched.toRealtimeTable() },
                defaultSchema = defaultSchema,
                capacity = capacity,
            )
        onChannel(channel)
        channel.emitInto(this)
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalObjCRefinement::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeTable
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeEvent
import io.github.nesfeder.supabase.offline.internal.realtime.ServerChangeFeed
import io.github.nesfeder.supabase.offline.internal.realtime.toRealtimeTable
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
 * @property tables the tables the client follows through this channel.
 * @property defaultSchema the schema of a table that names none.
 */
@OfflineInternal
@HiddenFromObjC
public class ScriptedRealtimeChannel
internal constructor(public val tables: List<RealtimeTable>, public val defaultSchema: String) {

    private val events = Channel<ServerChangeEvent>(Channel.UNLIMITED)

    private val left = CompletableDeferred<Unit>()

    /** Reports the channel joined: every change reported from now on is delivered. */
    public fun join() {
        events.trySend(ServerChangeEvent.Joined)
    }

    /**
     * Reports a row of [table] in [schema], the default schema when `null`, inserted or updated.
     */
    public fun saved(table: String, schema: String?, record: JsonObject) {
        events.trySend(ServerChangeEvent.Saved(table, schema ?: defaultSchema, record))
    }

    /** Reports a row of [table] in [schema], the default schema when `null`, deleted. */
    public fun deleted(table: String, schema: String?, oldRecord: JsonObject) {
        events.trySend(ServerChangeEvent.Deleted(table, schema ?: defaultSchema, oldRecord))
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
    serverChangeFeed = {
        ServerChangeFeed { tables, defaultSchema ->
            flow {
                val channel =
                    ScriptedRealtimeChannel(
                        tables = tables.map { watched -> watched.toRealtimeTable() },
                        defaultSchema = defaultSchema,
                    )
                onChannel(channel)
                channel.emitInto(this)
            }
        }
    }
}

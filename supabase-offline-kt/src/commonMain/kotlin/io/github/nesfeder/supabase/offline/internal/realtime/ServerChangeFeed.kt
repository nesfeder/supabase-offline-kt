// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.realtime

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** What a [ServerChangeFeed] reports while a channel is followed. */
internal sealed interface ServerChangeEvent {

    /**
     * The channel is subscribed: every change from now on arrives. What happened before is not
     * replayed, so the watched tables are read again on each one.
     */
    data object Joined : ServerChangeEvent

    /**
     * A row of [table] in [schema] was inserted or updated; [record] is the whole row as the server
     * sent it.
     */
    data class Saved(val table: String, val schema: String, val record: JsonObject) :
        ServerChangeEvent

    /** A row of [table] in [schema] was deleted; [oldRecord] carries at least its key. */
    data class Deleted(val table: String, val schema: String, val oldRecord: JsonObject) :
        ServerChangeEvent
}

/**
 * The failure of a channel that ended on its own: the server refused the join or closed the
 * channel, or supabase-kt gave up rejoining it.
 */
internal class ServerChangeFeedEndedException(message: String) : IllegalStateException(message)

/** Where the server's row changes come from: Realtime, or a test's script. */
internal fun interface ServerChangeFeed {

    /**
     * Follows [tables]' changes on the server: emits [ServerChangeEvent.Joined] once the channel is
     * subscribed, then each change in the order the server sent them; fails when the channel ends
     * on its own; leaves the channel when its collector is canceled.
     *
     * @param tables the tables to follow; at least one.
     * @param defaultSchema the schema of a table that names none.
     */
    fun follow(tables: List<WatchedTable>, defaultSchema: String): Flow<ServerChangeEvent>
}

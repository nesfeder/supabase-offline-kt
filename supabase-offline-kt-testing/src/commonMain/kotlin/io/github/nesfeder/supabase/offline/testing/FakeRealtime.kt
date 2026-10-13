// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class, OfflineInternal::class)

package io.github.nesfeder.supabase.offline.testing

import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.internal.testing.ScriptedRealtimeChannel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The server's row changes a test reports to an in-memory client, in place of a Realtime channel:
 * ```kotlin
 * val fakeRealtime = FakeRealtime()
 * val supabase = createSupabaseClient(url, key) {
 *     httpEngine = MockEngine { request -> … }
 *     install(Postgrest)
 *     install(Offline) {
 *         inMemory(realtime = fakeRealtime) // no install(Realtime) needed
 *         realtime { table("tasks") }
 *     }
 * }
 * fakeRealtime.subscribedTables.first { "tasks" in it }
 * fakeRealtime.reportInsert("tasks", buildJsonObject { put("id", "t1"); put("title", "Milk") })
 * ```
 *
 * The client joins a channel as it would with Realtime: while a user is signed in (or Auth is not
 * installed), the network is not known down and [Offline.disableRealtime] has not switched it off.
 * The fake answers each join at once, and [subscribedTables] says which tables the client follows
 * now, so a test waits for a table there before reporting a change to it. A report to a table no
 * joined channel follows is dropped, as a server drops a change for a channel nobody joined, and
 * counted in [droppedReportCount]. Nothing is replayed: a change reported while the client was not
 * joined never reaches it, as with Realtime, and each join has the client read the named tables
 * again.
 */
public class FakeRealtime {

    private val current = MutableStateFlow<ScriptedRealtimeChannel?>(null)

    private val followed = MutableStateFlow<Set<String>>(emptySet())

    private val dropped = AtomicInt(0)

    /**
     * The tables the client follows through the joined channel now, each as `realtime { }` keys it:
     * its name alone in the client's default schema, `schema.table` in another. Empty while no
     * channel is joined: before the client joins, while it is paused or switched off, and between a
     * channel that ended and the next join.
     */
    public val subscribedTables: StateFlow<Set<String>> = followed.asStateFlow()

    /** How many reports found no joined channel following their table and were dropped. */
    public val droppedReportCount: Int
        get() = dropped.load()

    /**
     * Reports a row of [table] inserted on the server; the client applies [record] to every cached
     * query of the table, as it applies a Realtime record.
     *
     * @param table the table's name.
     * @param record the whole row, as the server would send it.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportInsert(table: String, record: JsonObject, schema: String? = null) {
        report(table, schema) { channel -> channel.saved(table, schema, record) }
    }

    /**
     * Reports a row of [table] updated on the server; the client applies [record] as it applies a
     * Realtime record.
     *
     * @param table the table's name.
     * @param record the whole row after the update, as the server would send it.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportUpdate(table: String, record: JsonObject, schema: String? = null) {
        report(table, schema) { channel -> channel.saved(table, schema, record) }
    }

    /**
     * Reports a row of [table] deleted on the server; the client removes the row [oldRecord] keys
     * from every cached query of the table.
     *
     * @param table the table's name.
     * @param oldRecord the deleted row's values, carrying at least its key.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportDelete(table: String, oldRecord: JsonObject, schema: String? = null) {
        report(table, schema) { channel -> channel.deleted(table, schema, oldRecord) }
    }

    /**
     * Ends the joined channel with [cause], as a server that refuses the join or closes the
     * channel: the client reports [RealtimeStatus.Stalled] with [cause], joins again after a wait
     * of at most a minute (a second the first time), and reads the named tables again. Nothing
     * happens while no channel is joined.
     */
    public fun failChannel(cause: Throwable) {
        current.value?.fail(cause)
    }

    /**
     * Ends the joined channel quietly, as a lost socket does: the client reports
     * [RealtimeStatus.Stalled], joins again after a short wait, and reads the named tables again.
     * Nothing happens while no channel is joined.
     */
    public fun dropChannel() {
        current.value?.end()
    }

    /** What `inMemory(clock, realtime)` installs: takes each channel the client opens. */
    internal val channelHook: (ScriptedRealtimeChannel) -> Unit = { channel -> open(channel) }

    private fun open(channel: ScriptedRealtimeChannel) {
        current.value = channel
        followed.value =
            channel.tables
                .map { table -> keyOf(table.table, table.schema, channel.defaultSchema) }
                .toSet()
        channel.onLeft { if (current.compareAndSet(channel, null)) followed.value = emptySet() }
        channel.join()
    }

    private fun report(table: String, schema: String?, deliver: (ScriptedRealtimeChannel) -> Unit) {
        val channel = current.value
        if (channel == null || keyOf(table, schema, channel.defaultSchema) !in followed.value) {
            dropped.incrementAndFetch()
            return
        }
        deliver(channel)
    }

    /** [table] as `realtime { }` keys it: the name alone in the default schema. */
    private fun keyOf(table: String, schema: String?, defaultSchema: String): String =
        if (schema == null || schema == defaultSchema) table else "$schema.$table"
}

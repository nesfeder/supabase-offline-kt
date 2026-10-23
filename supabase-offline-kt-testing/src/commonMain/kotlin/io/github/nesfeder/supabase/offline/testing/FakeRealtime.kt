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
import kotlinx.coroutines.flow.update
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
 * The client joins a channel for each named table as it would with Realtime: while a user is signed
 * in (or Auth is not installed), the network is not known down and [Offline.disableRealtime] has
 * not switched it off. The fake answers each join at once, and [subscribedTables] says which tables
 * the client follows now, so a test waits for a table there before reporting a change to it. A
 * report to a table no joined channel follows is dropped, as a server drops a change for a channel
 * nobody joined, and counted in [droppedReportCount]. Nothing is replayed: a change reported while
 * the client was not joined never reaches it, as with Realtime, and each join has the client read
 * the table again. [failTable] refuses one table's channel, as a server refuses a binding, and
 * leaves the others joined.
 */
public class FakeRealtime {

    /** The channel open for each followed table, keyed as [subscribedTables] keys it. */
    private val channels = MutableStateFlow<Map<String, ScriptedRealtimeChannel>>(emptyMap())

    private val followed = MutableStateFlow<Set<String>>(emptySet())

    private val dropped = AtomicInt(0)

    /**
     * The tables the client follows through a joined channel now, each as `realtime { }` keys it:
     * its name alone in the client's default schema, `schema.table` in another. A table is missing
     * while its channel is not joined: before the client joins, while it is paused or switched off,
     * and between a channel that ended and the next join of that table.
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
     * Ends every joined channel with [cause], as a server that refuses the joins or closes the
     * channels: the client reports [RealtimeStatus.Stalled] with [cause], joins each table again
     * after a wait of at most a minute (a second the first time), and reads it again. Nothing
     * happens while no channel is joined.
     */
    public fun failChannel(cause: Throwable) {
        channels.value.values.distinct().forEach { channel -> channel.fail(cause) }
    }

    /**
     * Ends every joined channel quietly, as a lost socket does: the client reports
     * [RealtimeStatus.Stalled], joins each table again after a short wait, and reads it again.
     * Nothing happens while no channel is joined.
     */
    public fun dropChannel() {
        channels.value.values.distinct().forEach { channel -> channel.end() }
    }

    /**
     * Ends the channel of [table] alone with [cause], as a server that refuses that table's
     * binding: [Offline.realtimeTableStatus] reads [RealtimeStatus.Stalled] with [cause] for it,
     * the other tables stay joined and keep receiving reports, and the client joins [table] again
     * after a wait of at most a minute (a second the first time), and reads it again. Nothing
     * happens while [table] is not joined.
     *
     * @param table the table's name.
     * @param cause why the server refused it.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun failTable(table: String, cause: Throwable, schema: String? = null) {
        channelOf(table, schema)?.fail(cause)
    }

    /** What `inMemory(clock, realtime)` installs: takes each channel the client opens. */
    internal val channelHook: (ScriptedRealtimeChannel) -> Unit = { channel -> open(channel) }

    private fun open(channel: ScriptedRealtimeChannel) {
        val keys =
            channel.tables.map { table -> keyOf(table.table, table.schema, channel.defaultSchema) }
        channels.update { current -> current + keys.associateWith { channel } }
        followed.update { current -> current + keys }
        // A table's next channel opens only once the client has left this one, so a key removed
        // here is never the next channel's
        channel.onLeft {
            channels.update { current ->
                current.filterNot { (key, open) -> key in keys && open === channel }
            }
            followed.update { current -> current - keys.toSet() }
        }
        channel.join()
    }

    private fun report(table: String, schema: String?, deliver: (ScriptedRealtimeChannel) -> Unit) {
        val channel = channelOf(table, schema)
        if (channel == null) {
            dropped.incrementAndFetch()
            return
        }
        deliver(channel)
    }

    /** The joined channel that follows [table] in [schema], or `null`. */
    private fun channelOf(table: String, schema: String?): ScriptedRealtimeChannel? {
        val open = channels.value
        val defaultSchema = open.values.firstOrNull()?.defaultSchema ?: return null
        return open[keyOf(table, schema, defaultSchema)]
    }

    /** [table] as `realtime { }` keys it: the name alone in the default schema. */
    private fun keyOf(table: String, schema: String?, defaultSchema: String): String =
        if (schema == null || schema == defaultSchema) table else "$schema.$table"
}

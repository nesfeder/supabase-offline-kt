// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class, OfflineInternal::class)

package io.github.nesfeder.supabase.offline.testing

import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.nesfeder.supabase.offline.Offline
import io.github.nesfeder.supabase.offline.OfflineInternal
import io.github.nesfeder.supabase.offline.RealtimeFilter
import io.github.nesfeder.supabase.offline.RealtimeStatus
import io.github.nesfeder.supabase.offline.internal.testing.ScriptedRealtimeChannel
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The server's row changes a test reports to an in-memory client, in place of a Realtime channel:
 * ```kotlin
 * var tasks = JsonArray(emptyList()) // the server's rows: the engine answers every read with them
 * val fakeRealtime = FakeRealtime()
 * val supabase = createSupabaseClient(url, key) {
 *     httpEngine = MockEngine { respond(tasks.toString(), headers = jsonHeaders) }
 *     install(Postgrest)
 *     install(Offline) {
 *         inMemory(realtime = fakeRealtime) // no install(Realtime) needed
 *         realtime { table("tasks") }
 *     }
 * }
 * fakeRealtime.joinCount.first { it > 0 }
 * val milk = buildJsonObject { put("id", "t1"); put("title", "Milk") }
 * tasks = JsonArray(tasks + milk) // the row is on the server, then its change is sent
 * fakeRealtime.reportInsert("tasks", milk)
 * ```
 *
 * The client joins a channel as it would with Realtime: while a user is signed in (or Auth is not
 * installed), the network is not known down and [Offline.disableRealtime] has not switched it off.
 * The fake answers each join at once. [subscribedTables] says which tables the client follows as
 * soon as it opens a channel, and [joinCount] moves once the client has handled the join: it has
 * reported [RealtimeStatus.Subscribed] and asked for the named tables' cached queries to be read
 * again, since nothing is replayed. Wait for [joinCount] before reporting a change, or the join may
 * be handled after it.
 *
 * The test's `MockEngine` plays the server, and the fake only the channel: the client reads the
 * named tables from the engine on every join, and reads a query again after a change that met a
 * read in flight or that it cannot apply to the query exactly. So the engine must answer with the
 * rows as they are after each change the test reports, as a server would; one that still answers
 * with the rows from before has those reads take the change back out of the cache.
 *
 * A report reaches the client as Realtime would send it. A report to a table no joined channel
 * follows is dropped, as a server drops a change for a channel nobody joined, and so is an insert
 * or an update whose record does not meet the table's [RealtimeFilter], which the server evaluates
 * before it sends; both are counted in [droppedReportCount]. A delete reaches the client whatever
 * the filter, as Realtime sends a delete to every subscriber of the table. Nothing is replayed: a
 * change reported while the client was not joined never reaches it.
 */
public class FakeRealtime {

    private val current = MutableStateFlow<ScriptedRealtimeChannel?>(null)

    private val followed = MutableStateFlow<Set<String>>(emptySet())

    private val dropped = AtomicInt(0)

    private val joins = MutableStateFlow(0)

    /**
     * The tables the client follows through the joined channel now, each as `realtime { }` keys it:
     * its name alone in the client's default schema, `schema.table` in another. Empty while no
     * channel is joined: before the client joins, while it is paused or switched off, and between a
     * channel that ended and the next join.
     */
    public val subscribedTables: StateFlow<Set<String>> = followed.asStateFlow()

    /**
     * How many joins the client has handled, every channel counted: it has reported the channel
     * [RealtimeStatus.Subscribed] and asked for the named tables' cached queries to be read again,
     * though those reads may still be in flight. A test waits for it to move, after the client
     * opens and after each [failChannel], [dropChannel] or switch back on, before it reports a
     * change.
     */
    public val joinCount: StateFlow<Int> = joins.asStateFlow()

    /**
     * How many reports were dropped: those that found no joined channel following their table, and
     * the inserts and updates whose record does not meet the table's [RealtimeFilter].
     */
    public val droppedReportCount: Int
        get() = dropped.load()

    /**
     * Reports a row of [table] inserted on the server; the client applies [record] to every cached
     * query of the table, as it applies a Realtime record. Dropped when [record] does not meet the
     * table's [RealtimeFilter].
     *
     * @param table the table's name.
     * @param record the whole row, as the server would send it.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportInsert(table: String, record: JsonObject, schema: String? = null) {
        report(table, schema, filtered = record) { channel -> channel.saved(table, schema, record) }
    }

    /**
     * Reports a row of [table] updated on the server; the client applies [record] as it applies a
     * Realtime record. Dropped when [record], the row after the update, does not meet the table's
     * [RealtimeFilter], as the server sends no change of a row the update moved out of it.
     *
     * @param table the table's name.
     * @param record the whole row after the update, as the server would send it.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportUpdate(table: String, record: JsonObject, schema: String? = null) {
        report(table, schema, filtered = record) { channel -> channel.saved(table, schema, record) }
    }

    /**
     * Reports a row of [table] deleted on the server; the client removes the row [oldRecord] keys
     * from every cached query of the table. The table's [RealtimeFilter] does not apply: Realtime
     * sends a delete to every subscriber of the table.
     *
     * @param table the table's name.
     * @param oldRecord the deleted row's values, carrying at least its key.
     * @param schema the table's schema, or `null` for the client's default schema.
     */
    public fun reportDelete(table: String, oldRecord: JsonObject, schema: String? = null) {
        report(table, schema, filtered = null) { channel ->
            channel.deleted(table, schema, oldRecord)
        }
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
        channel.onJoinHandled { joins.update { count -> count + 1 } }
        channel.join()
    }

    /**
     * Hands a report to the joined channel through [deliver], or drops and counts it: when no
     * joined channel follows [table], or when [filtered], the record of an insert or an update,
     * does not meet the table's filter.
     */
    private fun report(
        table: String,
        schema: String?,
        filtered: JsonObject?,
        deliver: (ScriptedRealtimeChannel) -> Unit,
    ) {
        val channel = current.value
        val key = channel?.let { keyOf(table, schema, it.defaultSchema) }
        if (channel == null || key !in followed.value) {
            dropped.incrementAndFetch()
            return
        }
        val filter =
            channel.tables
                .firstOrNull { followed ->
                    keyOf(followed.table, followed.schema, channel.defaultSchema) == key
                }
                ?.filter
        if (filter != null && filtered != null && !filter.admits(filtered)) {
            dropped.incrementAndFetch()
            return
        }
        deliver(channel)
    }

    /** [table] as `realtime { }` keys it: the name alone in the default schema. */
    private fun keyOf(table: String, schema: String?, defaultSchema: String): String =
        if (schema == null || schema == defaultSchema) table else "$schema.$table"
}

/**
 * Whether [record] meets this filter, as the server evaluates it: numbers are compared as numbers
 * and any other value as its text, as supabase-kt sends the filter's value. A record whose column
 * is missing, `null` or not a single value meets no filter, as SQL's `NULL` meets no comparison.
 */
private fun RealtimeFilter.admits(record: JsonObject): Boolean {
    val cell = record[column] as? JsonPrimitive
    if (cell == null || cell is JsonNull) return false
    val actual = cell.content
    return when (operator) {
        FilterOperator.EQ -> compareFilterValues(actual, value) == 0
        FilterOperator.NEQ -> compareFilterValues(actual, value) != 0
        FilterOperator.GT -> compareFilterValues(actual, value) > 0
        FilterOperator.GTE -> compareFilterValues(actual, value) >= 0
        FilterOperator.LT -> compareFilterValues(actual, value) < 0
        FilterOperator.LTE -> compareFilterValues(actual, value) <= 0
        FilterOperator.IN ->
            (value as List<*>).any { listed -> compareFilterValues(actual, listed) == 0 }
        // RealtimeFilter refuses every other operator
        else -> true
    }
}

/**
 * [actual], a record's value as text, against [expected], a filter's value: as numbers when both
 * read as numbers, as text otherwise.
 */
private fun compareFilterValues(actual: String, expected: Any?): Int {
    val expectedText = if (expected is JsonPrimitive) expected.content else expected.toString()
    val actualNumber = actual.toDoubleOrNull()
    val expectedNumber = expectedText.toDoubleOrNull()
    return if (actualNumber != null && expectedNumber != null)
        actualNumber.compareTo(expectedNumber)
    else actual.compareTo(expectedText)
}

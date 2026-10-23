// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

import io.github.nesfeder.supabase.offline.bridge.internal.InvalidDescriptionException
import io.github.nesfeder.supabase.offline.bridge.internal.bridgeErrorOf
import io.github.nesfeder.supabase.offline.bridge.internal.parseJsonText
import io.github.nesfeder.supabase.offline.testing.FakeRealtime
import kotlinx.serialization.json.JsonObject

/**
 * The server's row changes an app's test reports to an in-memory client, in place of a Realtime
 * channel; hand one to [BridgeTesting.openInMemory]. The client joins a channel here as it would
 * Realtime's, and the fake answers each join at once. A report reaches the client as Realtime would
 * send it: an insert or an update whose record does not meet the table's filter is dropped, and a
 * delete is not. The test's fake server plays the server, and must answer with the rows as they are
 * after each change reported. Records cross as JSON text.
 */
public class BridgeFakeRealtime {

    /** The library's fake, which the in-memory client follows. */
    internal val fake: FakeRealtime = FakeRealtime()

    /**
     * The tables the client follows now, keyed as the library keys them: the name alone in the
     * default schema, `schema.table` in another; empty while no channel is joined.
     */
    public val subscribedTables: Set<String>
        get() = fake.subscribedTables.value

    /**
     * How many joins the client has handled: it has reported the channel subscribed and asked for
     * the named tables to be read again. A test waits for it to move before reporting a change.
     */
    public val joinCount: Int
        get() = fake.joinCount.value

    /**
     * How many reports were dropped: those that found no joined channel following their table, and
     * the inserts and updates whose record does not meet the table's filter.
     */
    public val droppedReportCount: Int
        get() = fake.droppedReportCount

    /**
     * Reports a row of [table] inserted on the server.
     *
     * @param record the whole row, a JSON object as text.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @return `null`, or the failure when [record] is not a JSON object.
     */
    public fun reportInsert(table: String, record: String, schema: String?): BridgeError? =
        reporting(record) { row -> fake.reportInsert(table, row, schema) }

    /**
     * Reports a row of [table] updated on the server.
     *
     * @param record the whole row after the update, a JSON object as text.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @return `null`, or the failure when [record] is not a JSON object.
     */
    public fun reportUpdate(table: String, record: String, schema: String?): BridgeError? =
        reporting(record) { row -> fake.reportUpdate(table, row, schema) }

    /**
     * Reports a row of [table] deleted on the server.
     *
     * @param oldRecord the deleted row's values, a JSON object carrying at least its key.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @return `null`, or the failure when [oldRecord] is not a JSON object.
     */
    public fun reportDelete(table: String, oldRecord: String, schema: String?): BridgeError? =
        reporting(oldRecord) { row -> fake.reportDelete(table, row, schema) }

    /**
     * Ends the joined channel as a server that refuses the join or closes the channel: the client
     * reports the channel stalled, with [message], and joins again after a short wait.
     */
    public fun failChannel(message: String) {
        fake.failChannel(IllegalStateException(message))
    }

    /** Ends the joined channel as a lost socket: the client joins again after a short wait. */
    public fun dropChannel() {
        fake.dropChannel()
    }

    private inline fun reporting(text: String, report: (JsonObject) -> Unit): BridgeError? =
        try {
            val row =
                parseJsonText(text, "record") as? JsonObject
                    ?: throw InvalidDescriptionException("A reported record must be a JSON object")
            report(row)
            null
        } catch (failure: Throwable) {
            bridgeErrorOf(failure, clientClosing = false)
        }
}

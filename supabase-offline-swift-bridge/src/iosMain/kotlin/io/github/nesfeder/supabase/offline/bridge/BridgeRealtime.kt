// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.bridge

/**
 * A table the client keeps current through Realtime, as the app names it in its configuration.
 *
 * @property table the table's name.
 * @property schema its schema, or `null` for the client's default schema.
 * @property filter one condition the server evaluates before sending a change, or `null` for every
 *   row the user may read.
 */
public class BridgeRealtimeTable(
    public val table: String,
    public val schema: String?,
    public val filter: BridgeRealtimeFilter?,
)

/**
 * One condition Realtime evaluates on the server: `eq`, `neq`, `gt`, `gte`, `lt`, `lte` or `in`,
 * with one value, or a list of values for `in`. Any other operator refuses the open.
 *
 * @property column the column compared.
 * @property operatorName the operator's name, as a request writes it.
 * @property value the value compared with, as text; `null` when [values] is given.
 * @property values the values of `in`; `null` when [value] is given.
 */
public class BridgeRealtimeFilter(
    public val column: String,
    public val operatorName: String,
    public val value: String?,
    public val values: List<String>?,
)

/**
 * Where the client stands with the channel that keeps its Realtime tables current.
 *
 * @property kind the state.
 * @property cause what ended the channel, for [BridgeRealtimeStatusKind.STALLED]; `null` otherwise.
 */
public class BridgeRealtimeStatus
internal constructor(public val kind: BridgeRealtimeStatusKind, public val cause: BridgeError?)

/**
 * Where one table the client keeps current through Realtime stands, on its own channel.
 *
 * @property table the table's name, as [BridgeClientOptions.realtimeTables] names it.
 * @property schema its schema as named there, or `null` for the client's default schema.
 * @property status where the table's channel stands.
 */
public class BridgeRealtimeTableStatus
internal constructor(
    public val table: String,
    public val schema: String?,
    public val status: BridgeRealtimeStatus,
)

/** The states of the channel that keeps the client's Realtime tables current. */
public enum class BridgeRealtimeStatusKind {
    /** No table is named, the app switched the subscription off, or the client is closed. */
    OFF,
    /** Tables are named and the subscription is on, but the network is known down or off. */
    PAUSED,
    /** A join is in flight. */
    SUBSCRIBING,
    /** Changes arrive and are applied; entered again after every rejoin. */
    SUBSCRIBED,
    /** The join was refused or the channel ended; it is tried again after a bounded wait. */
    STALLED,
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * A table named in `realtime { }`: the plugin follows its changes on the server through Realtime
 * and applies them to the cache as they happen ([Offline.Config.realtimeTables]).
 *
 * @property table the table's name.
 * @property schema its schema, or `null` for the client's default schema.
 * @property filter the condition the server evaluates before sending a change, or `null` for every
 *   row the user may read.
 */
public class RealtimeTable
internal constructor(
    public val table: String,
    public val schema: String?,
    public val filter: RealtimeFilter?,
) {

    override fun equals(other: Any?): Boolean =
        other is RealtimeTable &&
            other.table == table &&
            other.schema == schema &&
            other.filter == filter

    override fun hashCode(): Int =
        (table.hashCode() * 31 + schema.hashCode()) * 31 + filter.hashCode()

    override fun toString(): String = "RealtimeTable(table=$table, schema=$schema, filter=$filter)"
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline

/**
 * The `realtime { }` block of [Offline.Config]: names the tables whose changes on the server the
 * plugin applies to the cache as they happen.
 */
public class RealtimeTables internal constructor(private val named: MutableList<RealtimeTable>) {

    /**
     * Follows [table] of [schema], the client's default schema when `null`, narrowed on the server
     * to [filter]. A change of the table is applied as [Offline.applyServerRecord] or
     * [Offline.applyServerDelete] applies one, so its rows are matched by the key
     * [Offline.Config.primaryKeys] gives: `schema.table` for a table of another schema.
     *
     * @param table the table's name.
     * @param schema the table's schema, or `null` for the client's default schema.
     * @param filter the condition the server evaluates, or `null` for every row the user may read.
     * @throws IllegalArgumentException when [table] is blank, or already named for [schema].
     */
    public fun table(table: String, schema: String? = null, filter: RealtimeFilter? = null) {
        require(table.isNotBlank()) { "A Realtime table needs a name" }
        require(named.none { current -> current.table == table && current.schema == schema }) {
            "The table $table is named twice in realtime { }"
        }
        named += RealtimeTable(table, schema, filter)
    }
}

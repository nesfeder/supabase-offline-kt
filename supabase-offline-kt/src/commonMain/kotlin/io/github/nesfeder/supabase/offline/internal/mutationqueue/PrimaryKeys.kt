package io.github.nesfeder.supabase.offline.internal.mutationqueue

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The primary-key column(s) of each table, as far as the engine knows them.
 *
 * This is the one place the engine asks. Today every table's key is [DEFAULT_PRIMARY_KEY]; a
 * per-table configuration can replace [DefaultPrimaryKeys] without touching any caller.
 */
internal fun interface PrimaryKeys {

    /**
     * The primary-key columns of [table].
     *
     * @param table A table name.
     * @return Its key columns, never empty.
     */
    fun of(table: String): List<String>
}

/** The key the engine assumes when nothing says otherwise: a single `id` column. */
internal val DEFAULT_PRIMARY_KEY: List<String> = listOf("id")

/** [PrimaryKeys] that answers [DEFAULT_PRIMARY_KEY] for every table. */
internal val DefaultPrimaryKeys: PrimaryKeys = PrimaryKeys { DEFAULT_PRIMARY_KEY }

/**
 * The key columns of [row], as an object of just those columns — or `null` when [row] is not an
 * object or lacks one of them (absent, JSON `null`, an array or an object).
 *
 * @param row A body row.
 * @param keyColumns The table's primary-key columns.
 * @return The row's key, or `null` when it does not carry all of it.
 */
internal fun primaryKeyOf(row: JsonElement, keyColumns: List<String>): JsonObject? {
    val rowObject = row as? JsonObject ?: return null
    if (keyColumns.isEmpty()) return null
    val key = keyColumns.associateWith { column ->
        val value = rowObject[column]
        if (value !is JsonPrimitive || value is JsonNull) return null
        value
    }
    return JsonObject(key)
}

/**
 * Whether every row of [body] carries the whole primary key — the condition under which a queued
 * INSERT can replay as an upsert that ignores duplicates.
 *
 * @param body An INSERT body.
 * @param keyColumns The table's primary-key columns.
 * @return `true` when [body] has rows and each carries every key column.
 */
internal fun everyRowCarriesPrimaryKey(body: JsonArray, keyColumns: List<String>): Boolean =
    body.isNotEmpty() && body.all { row -> primaryKeyOf(row, keyColumns) != null }

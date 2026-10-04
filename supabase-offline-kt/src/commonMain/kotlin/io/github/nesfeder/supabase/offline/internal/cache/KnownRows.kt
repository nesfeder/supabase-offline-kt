// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.mutationqueue.PrimaryKeys
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import io.github.nesfeder.supabase.offline.internal.postgrest.RowFilter
import io.github.nesfeder.supabase.offline.internal.postgrest.RowMatch
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.isPlainColumn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A table's cached rows, one per key from the most recently fetched payload holding it, with the
 * [Projection] each was read with. A query is given only copies [mayMatter] keeps, and only keys
 * some copy of which may matter are indexed, so [targetFor] costs a query's rows and those.
 *
 * @param keyColumns the table's key columns; rows without them are left out.
 * @param mayMatter whether a row, with its key, may matter to the writes at hand; see
 *   [rowsThatMayMatter]. It is asked of the copy a query would be given, never of another.
 */
internal class KnownRows(
    parsed: List<Pair<CachedQueryRow, ParsedPayload>>,
    shapes: Map<String, QueryShape?>,
    private val keyColumns: List<String>,
    mayMatter: (JsonObject, List<String>) -> Boolean,
) {
    /**
     * A row, the query that holds it, its projection (`null` when that is not known), and whether
     * it [matters] to the writes at hand.
     */
    private class Held(
        val cacheKey: String,
        val projection: Projection?,
        val row: JsonObject,
        val matters: Boolean,
    )

    /**
     * Each indexed key's holders, most recently fetched first, keys in the order they first appear.
     */
    private val byKey = LinkedHashMap<List<String>, MutableList<Held>>()

    /** The indexed keys held under each projection, in the order they first appear. */
    private val keysByProjection = HashMap<Projection, LinkedHashSet<List<String>>>()

    init {
        // Which copy a query is given depends on the query, so a key is indexed, with every
        // copy, when any may matter; targetFor keeps a query's copy only if that one matters
        val newestFirst = parsed.sortedByDescending { (row, _) -> fetchedAtOf(row.updatedAt) }
        val indexed = HashSet<List<String>>()
        newestFirst.forEach { (_, payload) ->
            forEachKeyedRow(payload) { key, row -> if (mayMatter(row, key)) indexed += key }
        }
        if (indexed.isNotEmpty()) {
            newestFirst.forEach { (row, payload) ->
                val projection = shapes[row.cacheKey]?.let(::projectionOf)
                forEachKeyedRow(payload) { key, candidate ->
                    if (key !in indexed) return@forEachKeyedRow
                    byKey.getOrPut(key) { mutableListOf() } +=
                        Held(row.cacheKey, projection, candidate, mayMatter(candidate, key))
                    if (projection != null)
                        keysByProjection.getOrPut(projection) { LinkedHashSet() } += key
                }
            }
        }
    }

    /** Each row of [payload] that carries the key, with it. */
    private inline fun forEachKeyedRow(
        payload: ParsedPayload,
        action: (List<String>, JsonObject) -> Unit,
    ) {
        val keys = payload.keysFor(keyColumns)
        payload.rows?.forEachIndexed { index, element ->
            val key = keys[index] ?: return@forEachIndexed
            val row = element as? JsonObject ?: return@forEachIndexed
            action(key, row)
        }
    }

    /**
     * What the patch of the query cached under [cacheKey], holding [rows], may draw on.
     *
     * @param rowKeys [rows]' keys when already read; see [ParsedPayload.keysFor].
     */
    fun targetFor(
        cacheKey: String,
        shape: QueryShape?,
        rows: List<JsonElement>,
        rowKeys: List<List<String>?>? = null,
    ): PatchTarget {
        val projection = shape?.let(::projectionOf)
        val own = HashSet<List<String>>()
        (rowKeys
                ?: rows.map { element ->
                    (element as? JsonObject)?.let { row -> keyOf(row, keyColumns) }
                })
            .forEach { key -> if (key != null) own += key }
        val same = mutableListOf<JsonObject>()
        // Keys another query holds with this projection: given from there, or not at all
        val sameKeys = HashSet<List<String>>()
        if (projection != null) {
            keysByProjection[projection]?.forEach { key ->
                if (key in own) return@forEach
                val holder =
                    byKey.getValue(key).firstOrNull { held ->
                        held.cacheKey != cacheKey && held.projection == projection
                    } ?: return@forEach
                sameKeys += key
                if (holder.matters) same += holder.row
            }
        }
        val other = mutableListOf<JsonObject>()
        byKey.forEach { (key, holders) ->
            if (key in own || key in sameKeys) return@forEach
            val holder =
                holders.firstOrNull { held ->
                    held.cacheKey != cacheKey &&
                        (projection == null || held.projection != projection)
                } ?: return@forEach
            if (holder.matters) other += holder.row
        }
        return PatchTarget(
            rows,
            shape,
            sameSelectRows = same,
            otherSelectRows = other,
            keyColumns = keyColumns.toSet(),
        )
    }
}

/**
 * Whether a row other queries of [table] hold may matter to [mutations]: an UPSERT's row shares its
 * conflict key, or an UPDATE's filter matches it. No mutation here reads or changes any other row
 * held aside, so leaving those out changes no overlay and no stale mark. `null` when no row can
 * matter.
 *
 * @param unknownMatters whether a row an UPDATE's filter cannot judge matters too: to a patch,
 *   which marks stale what it cannot judge, it does; to the read overlay, which moves only rows a
 *   filter matches, it does not.
 */
internal fun rowsThatMayMatter(
    table: String,
    mutations: List<MutationRow>,
    primaryKeys: PrimaryKeys,
    json: Json,
    unknownMatters: Boolean,
): ((JsonObject, List<String>) -> Boolean)? {
    val keyColumns = primaryKeys.of(table)
    val uuidColumns = keyColumns.toSet()
    val filters =
        mutations
            .filter { mutation -> mutation.operation == MutationOperation.UPDATE }
            .mapNotNull { mutation -> decodeFilterOrNull(mutation.filterJson, json) }
            .map { filter -> RowFilter(filter, uuidColumns) }
            .filter { filter -> unknownMatters || filter.canMatch }
    val reached =
        if (unknownMatters) setOf(RowMatch.MATCHES, RowMatch.UNKNOWN) else setOf(RowMatch.MATCHES)
    // The keys each set of conflict columns upserts
    val upserted = HashMap<List<String>, HashSet<List<String>>>()
    mutations
        .filter { mutation -> mutation.operation == MutationOperation.UPSERT }
        .forEach { mutation ->
            val columns = conflictColumnsOf(mutation, primaryKeys)
            val keys = upserted.getOrPut(columns) { HashSet() }
            upsertRowsOf(mutation, json).forEach { row -> keyOf(row, columns)?.let(keys::add) }
        }
    // Upserts on the key look a row up by the key it already has; the rest read the row
    val byKey = upserted.remove(keyColumns)?.takeIf { keys -> keys.isNotEmpty() }
    val byOtherColumns = upserted.entries.filter { (_, keys) -> keys.isNotEmpty() }
    if (byKey == null && byOtherColumns.isEmpty() && filters.isEmpty()) return null
    return { row, key ->
        (byKey != null && key in byKey) ||
            byOtherColumns.any { (columns, keys) -> keyOf(row, columns) in keys } ||
            filters.any { filter -> filter.match(row) in reached }
    }
}

/**
 * [bringing], and each other UPDATE or UPSERT of [mutations] that sets a column one of those reads
 * (an UPSERT's conflict columns, an UPDATE's filter columns), until no more join: only these can
 * change a row held aside in a way the overlay then reads.
 */
internal fun mutationsThatReach(
    bringing: List<MutationRow>,
    mutations: List<MutationRow>,
    primaryKeys: PrimaryKeys,
    json: Json,
): List<MutationRow> {
    val reaching = bringing.toMutableList()
    val read = HashSet<String>()
    var readsEveryColumn = false
    fun noteWhatItReads(mutation: MutationRow) {
        when (mutation.operation) {
            MutationOperation.UPSERT ->
                conflictColumnsOf(mutation, primaryKeys).forEach { column ->
                    read += jsonNameOf(column)
                }
            MutationOperation.UPDATE ->
                decodeFilterOrNull(mutation.filterJson, json)?.conditions?.forEach { condition ->
                    if (isPlainColumn(condition.column)) read += jsonNameOf(condition.column)
                    else readsEveryColumn = true
                }
            MutationOperation.INSERT,
            MutationOperation.DELETE -> Unit
        }
    }
    reaching.forEach(::noteWhatItReads)
    // Both change rows held aside: an UPSERT merges into the one with its key
    var rest = mutations.filter { mutation ->
        (mutation.operation == MutationOperation.UPDATE ||
            mutation.operation == MutationOperation.UPSERT) && mutation !in reaching
    }
    while (rest.isNotEmpty()) {
        // Read without parsing: a column the body sets appears as its quoted name
        val joining = rest.filter { mutation ->
            readsEveryColumn || read.any { name -> name in mutation.bodyJson }
        }
        if (joining.isEmpty()) break
        reaching += joining
        joining.forEach(::noteWhatItReads)
        rest = rest - joining.toSet()
    }
    return reaching
}

/** An UPSERT's body rows; none when the body is not JSON. */
internal fun upsertRowsOf(mutation: MutationRow, json: Json): List<JsonObject> =
    try {
        when (val body = json.parseToJsonElement(mutation.bodyJson)) {
            is JsonArray -> body.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(body)
            else -> emptyList()
        }
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

/** [column] quoted and escaped as a JSON body writes it as a key. */
internal fun jsonNameOf(column: String): String = JsonPrimitive(column).toString()

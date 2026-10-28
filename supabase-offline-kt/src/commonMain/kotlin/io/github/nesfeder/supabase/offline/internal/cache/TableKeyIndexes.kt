// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.EngineLimits
import io.github.nesfeder.supabase.offline.internal.db.PayloadWriteCountingDao
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.serialization.json.JsonObject

/**
 * Each table's cached rows by key: the index through which [QueryOverlay] finds the rows other
 * queries hold, read again once a payload of the table was written since. Takes no lock: an index
 * is one atomic value, kept only when no payload write landed while it was read. A table holding
 * more rows than [EngineLimits.MAX_KEY_INDEXED_ROWS] keeps none.
 *
 * @param queryCacheDao the object cache, whose write counts say when an index is stale.
 */
internal class TableKeyIndexes(
    private val queryCacheDao: PayloadWriteCountingDao,
    private val queryCache: QueryCache,
) {

    /** Each table's [TableKeyIndex], as last read. */
    private val indexes = AtomicReference<Map<String, TableKeyIndex>>(emptyMap())

    /**
     * For each of [keys], the row with it from the most recently fetched other query of [table]
     * read with [projection], as [KnownRows] gives it; `null` when [indexOf] cannot say. Reads only
     * the payloads that hold one of [keys].
     *
     * @param cacheKey the reading query's cache key; its own payload is never drawn on.
     * @param shapes the table's query shapes, by cache key.
     */
    suspend fun borrowedByKey(
        table: String,
        cacheKey: String,
        projection: Projection,
        shapes: Map<String, QueryShape?>,
        keys: Set<List<String>>,
        keyColumns: List<String>,
    ): List<JsonObject>? {
        val index = indexOf(table, keyColumns) ?: return null
        val borrowed = mutableListOf<JsonObject>()
        for (key in keys) {
            val holder =
                index.holders[key]?.firstOrNull { held ->
                    held.cacheKey != cacheKey &&
                        shapes[held.cacheKey]?.let(::projectionOf) == projection
                } ?: continue
            // The index is read with the payload writes it counted; this checks the row is there
            val row = queryCacheDao.get(table, holder.cacheKey) ?: return null
            val payload = queryCache.parsedPayloadOf(row)
            if (payload.keysFor(keyColumns).getOrNull(holder.position) != key) return null
            borrowed += payload.rows?.getOrNull(holder.position) as? JsonObject ?: return null
        }
        return borrowed
    }

    /**
     * The newest cached row of [table] with [key] (as [keyOf] gives it) that holds [column], from
     * the payloads newest fetch first. `null` when none does, or the table is too large for its key
     * index. May rebuild the key index: no lock may be held.
     */
    suspend fun newestRowHolding(
        table: String,
        key: List<String>,
        column: String,
        keyColumns: List<String>,
    ): JsonObject? {
        val index = indexOf(table, keyColumns) ?: return null
        for (holder in index.holders[key].orEmpty()) {
            val row = queryCacheDao.get(table, holder.cacheKey) ?: continue
            val payload = queryCache.parsedPayloadOf(row)
            // The index may predate a write to this payload: the row must still be the key's
            if (payload.keysFor(keyColumns).getOrNull(holder.position) != key) continue
            val candidate = payload.rows?.getOrNull(holder.position) as? JsonObject ?: continue
            if (column in candidate) return candidate
        }
        return null
    }

    /** Forgets every table's key index, after the cache was emptied. */
    fun forget() {
        indexes.store(emptyMap())
    }

    /**
     * [table]'s cached rows by key, as last read, or read now when a payload of the table was
     * written since; `null` when the table holds more rows than
     * [EngineLimits.MAX_KEY_INDEXED_ROWS]. Kept only when no payload write landed while it was
     * read.
     */
    private suspend fun indexOf(table: String, keyColumns: List<String>): TableKeyIndex? {
        val writes = queryCacheDao.writesTo(table)
        indexes.load()[table]?.let { index ->
            if (index.writes == writes && index.keyColumns == keyColumns) return index
        }
        // Most recently fetched first, as KnownRows orders the copies of one row
        val newestFirst =
            queryCacheDao
                .allForTable(table)
                .map { row -> row to queryCache.parsedPayloadOf(row) }
                .sortedByDescending { (row, _) -> fetchedAtOf(row.updatedAt) }
        val holders = HashMap<List<String>, MutableList<KeyHolder>>()
        var indexed = 0
        for ((row, payload) in newestFirst) {
            payload.keysFor(keyColumns).forEachIndexed { position, key ->
                if (key == null) return@forEachIndexed
                if (++indexed > EngineLimits.MAX_KEY_INDEXED_ROWS) return null
                holders.getOrPut(key) { mutableListOf() } += KeyHolder(row.cacheKey, position)
            }
        }
        val index = TableKeyIndex(writes, keyColumns, holders)
        if (queryCacheDao.writesTo(table) == writes) {
            indexes.update { indexes -> indexes + (table to index) }
        }
        return index
    }

    /**
     * A table's cached rows by key: for each key, the payloads holding a row with it, most recently
     * fetched first.
     *
     * @property writes the table's count of payload writes when it was read.
     * @property keyColumns the key columns it is read under.
     */
    private class TableKeyIndex(
        val writes: Pair<Long, Long>,
        val keyColumns: List<String>,
        val holders: Map<List<String>, List<KeyHolder>>,
    )

    /**
     * A payload holding a row with some key.
     *
     * @property cacheKey the payload's cache key.
     * @property position the row's position in the payload.
     */
    private class KeyHolder(val cacheKey: String, val position: Int)
}

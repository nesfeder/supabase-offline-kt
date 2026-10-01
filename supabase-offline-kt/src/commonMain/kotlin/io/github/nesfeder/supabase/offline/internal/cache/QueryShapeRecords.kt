// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_SIGNATURE_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.postgrest.QueryShape
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One cached query's record: its [QueryShape], or `null` when two different queries have shared its
 * cache key, so neither shape can be trusted for it.
 *
 * @property version the record's format, [QUERY_SHAPE_RECORD_VERSION].
 * @property shape the query's shape.
 */
@Serializable internal data class QueryShapeRecord(val version: Int, val shape: QueryShape? = null)

/** The format of the query-shape records this build writes. */
internal const val QUERY_SHAPE_RECORD_VERSION: Int = 1

/**
 * The [QueryShape] of each cached query, kept in a row of [RESERVED_TABLE_NAME] beside its payload
 * so it outlives the process, and read back when a write is patched into the table's cached
 * queries. A query cached by a build that kept no record has no shape until it is read: its
 * conditions are unknown, and unknown admits, so a patch meanwhile places any of the table's rows
 * in it. Records are loaded once per table per process and kept in memory. Its lock is always taken
 * last and held over no other.
 */
internal class QueryShapeRecords(private val objectDao: ObjectDao, private val json: Json) {

    private val mutex = Mutex()

    /** Table → cache key → shape; a `null` shape is one two queries have shared. */
    private val shapes = mutableMapOf<String, MutableMap<String, QueryShape?>>()

    /** Tables whose records have been read from the database this process. */
    private val loadedTables = mutableSetOf<String>()

    /** `(table, cache key)` whose record in the database holds what [shapes] holds. */
    private val written = mutableSetOf<Pair<String, String>>()

    /**
     * Records that the query cached under [cacheKey] has [shape], and writes the record when
     * [persist] — when a payload for it is, or is about to be, in the cache. A different shape for
     * the same key makes the key's shape unknown.
     */
    suspend fun remember(table: String, cacheKey: String, shape: QueryShape, persist: Boolean) {
        mutex.withLock {
            val perTable = loadedTable(table)
            val recorded = perTable.containsKey(cacheKey)
            val existing = perTable[cacheKey]
            val next = if (!recorded || existing == shape) shape else null
            perTable[cacheKey] = next
            if (persist && (next != existing || !recorded || (table to cacheKey) !in written)) {
                objectDao.upsert(recordRow(table, cacheKey, next))
                written += table to cacheKey
            }
        }
    }

    /** The shape of the query cached under [cacheKey], or `null` when it is not known. */
    suspend fun shapeOf(table: String, cacheKey: String): QueryShape? = mutex.withLock {
        loadedTable(table)[cacheKey]
    }

    /** The known shape of each of [table]'s cached queries, by cache key. */
    suspend fun shapesOf(table: String): Map<String, QueryShape?> = mutex.withLock {
        loadedTable(table).toMap()
    }

    /** Drops the record of the query cached under [cacheKey], with its payload. */
    suspend fun forget(table: String, cacheKey: String) {
        mutex.withLock {
            loadedTable(table).remove(cacheKey)
            written -= table to cacheKey
            objectDao.delete(RESERVED_TABLE_NAME, reservedKeyOf(table, cacheKey))
        }
    }

    /** Drops every record of [table], with its payloads. */
    suspend fun forgetTable(table: String) {
        mutex.withLock {
            shapes[table]?.clear()
            written.removeAll { (recordedTable, _) -> recordedTable == table }
            objectDao.deleteWithSignaturePrefix(RESERVED_TABLE_NAME, tablePrefixOf(table))
        }
    }

    /** Forgets everything in memory, after a clear deleted every record with the cache. */
    suspend fun forgetAll() {
        mutex.withLock {
            shapes.clear()
            loadedTables.clear()
            written.clear()
        }
    }

    /**
     * Deletes the records whose payload eviction took, and forgets which records are written, so
     * the next payload written for such a query writes its record again.
     */
    suspend fun dropOrphans() {
        mutex.withLock {
            if (objectDao.deleteOrphanedQueryShapes() > 0) written.clear()
        }
    }

    /** [table]'s shapes, read from the database the first time. Caller holds [mutex]. */
    private suspend fun loadedTable(table: String): MutableMap<String, QueryShape?> {
        val perTable = shapes.getOrPut(table) { mutableMapOf() }
        if (loadedTables.add(table)) {
            val prefix = tablePrefixOf(table)
            objectDao.rowsWithSignaturePrefix(RESERVED_TABLE_NAME, prefix).forEach { row ->
                val cacheKey = row.signature.removePrefix(prefix)
                // A record this process wrote since is newer than the one read back
                if (perTable.containsKey(cacheKey)) return@forEach
                perTable[cacheKey] = decodedOrNull(row.payload)
                written += table to cacheKey
            }
        }
        return perTable
    }

    /**
     * A record's shape, or `null` for one that does not decode, which is then as good as unknown.
     */
    private fun decodedOrNull(payload: String): QueryShape? =
        try {
            json
                .decodeFromString(QueryShapeRecord.serializer(), payload)
                .takeIf { record -> record.version == QUERY_SHAPE_RECORD_VERSION }
                ?.shape
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun recordRow(table: String, cacheKey: String, shape: QueryShape?): ObjectRow =
        ObjectRow(
            table = RESERVED_TABLE_NAME,
            signature = reservedKeyOf(table, cacheKey),
            payload =
                json.encodeToString(
                    QueryShapeRecord.serializer(),
                    QueryShapeRecord(QUERY_SHAPE_RECORD_VERSION, shape),
                ),
            updatedAt = 0L,
        )

    private fun tablePrefixOf(table: String): String =
        "$QUERY_SHAPE_SIGNATURE_PREFIX$table$SEPARATOR"

    private fun reservedKeyOf(table: String, cacheKey: String): String =
        tablePrefixOf(table) + cacheKey

    private companion object {
        /** Between the table and the cache key in a record's signature: no table name holds it. */
        const val SEPARATOR = '\u001F'
    }
}

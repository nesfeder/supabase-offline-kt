// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
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
 * in it. A record of another format (another build's, or one that does not decode) is deleted when
 * its table's records are first read, and the query is treated the same way, so the next read of it
 * records the shape this build knows; left in place it would pass for the record of a shared key,
 * which no read replaces. Records are loaded once per table per process and kept in memory. Its
 * lock is always taken last and held over no other.
 */
internal class QueryShapeRecords(private val queryCacheDao: QueryCacheDao, private val json: Json) {

    private val mutex = Mutex()

    /** Table → cache key → shape; a `null` shape is one two queries have shared. */
    private val shapes = mutableMapOf<String, MutableMap<String, QueryShape?>>()

    /** Tables whose records have been read from the database this process. */
    private val loadedTables = mutableSetOf<String>()

    /** `(table, cache key)` whose record in the database holds what [shapes] holds. */
    private val written = mutableSetOf<Pair<String, String>>()

    /**
     * Records that the query cached under [cacheKey] has [shape], and writes the record when
     * [persist] and a payload for the query is in the cache: a record never outlives its payload,
     * which eviction, holding no lock, may have taken since the caller saw it. The caller writes
     * under the clear lock ([CacheFreshness.writeUnlessCleared]), so no clear parts the two. A
     * different shape for the same key makes the key's shape unknown.
     */
    suspend fun remember(table: String, cacheKey: String, shape: QueryShape, persist: Boolean) {
        mutex.withLock {
            val perTable = loadedTable(table)
            val recorded = perTable.containsKey(cacheKey)
            val existing = perTable[cacheKey]
            val next = if (!recorded || existing == shape) shape else null
            perTable[cacheKey] = next
            val record = table to cacheKey
            if (!persist || (recorded && next == existing && record in written)) return
            if (!queryCacheDao.hasRow(table, cacheKey)) {
                // The payload is gone: whatever record is on disk goes with it, and the next
                // payload written for the query writes its record again
                written -= record
                return
            }
            queryCacheDao.upsert(recordRow(table, cacheKey, next))
            written += record
        }
    }

    /**
     * Whether the record on disk of the query cached under [cacheKey] already holds [shape], or the
     * marker of a shared key, so that [remember] with `persist` would write nothing. A reader asks
     * this before taking the clear lock for the write, which most reads then skip; [remember]
     * checks again under the lock, so a record written in between costs nothing but the lock.
     */
    suspend fun isRecorded(table: String, cacheKey: String, shape: QueryShape): Boolean =
        mutex.withLock {
            val perTable = loadedTable(table)
            perTable.containsKey(cacheKey) &&
                (table to cacheKey) in written &&
                perTable[cacheKey].let { existing -> existing == null || existing == shape }
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
            queryCacheDao.delete(RESERVED_TABLE_NAME, reservedKeyOf(table, cacheKey))
        }
    }

    /** Drops every record of [table], with its payloads. */
    suspend fun forgetTable(table: String) {
        mutex.withLock {
            shapes[table]?.clear()
            written.removeAll { (recordedTable, _) -> recordedTable == table }
            queryCacheDao.deleteWithCacheKeyPrefix(RESERVED_TABLE_NAME, tablePrefixOf(table))
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
            if (queryCacheDao.deleteOrphanedQueryShapes() > 0) written.clear()
        }
    }

    /**
     * [table]'s shapes, read from the database the first time. A record this build cannot read is
     * deleted and left out, so its query has no shape until it is read. Caller holds [mutex].
     */
    private suspend fun loadedTable(table: String): MutableMap<String, QueryShape?> {
        val perTable = shapes.getOrPut(table) { mutableMapOf() }
        if (loadedTables.add(table)) {
            val prefix = tablePrefixOf(table)
            queryCacheDao.rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, prefix).forEach { row ->
                val cacheKey = row.cacheKey.removePrefix(prefix)
                // A record this process wrote since is newer than the one read back
                if (perTable.containsKey(cacheKey)) return@forEach
                val record = decodedOrNull(row.payload)
                if (record == null) {
                    // Not a record of this build's format: kept, it would read as a shared key's,
                    // which admits every row and which no read ever writes over
                    queryCacheDao.delete(RESERVED_TABLE_NAME, row.cacheKey)
                    return@forEach
                }
                perTable[cacheKey] = record.shape
                written += table to cacheKey
            }
        }
        return perTable
    }

    /**
     * [payload] as a record of this build's format, or `null` when it does not decode or carries
     * another [QueryShapeRecord.version].
     */
    private fun decodedOrNull(payload: String): QueryShapeRecord? =
        try {
            json.decodeFromString(QueryShapeRecord.serializer(), payload).takeIf { record ->
                record.version == QUERY_SHAPE_RECORD_VERSION
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun recordRow(table: String, cacheKey: String, shape: QueryShape?): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = reservedKeyOf(table, cacheKey),
            payload =
                json.encodeToString(
                    QueryShapeRecord.serializer(),
                    QueryShapeRecord(QUERY_SHAPE_RECORD_VERSION, shape),
                ),
            updatedAt = 0L,
        )

    private fun tablePrefixOf(table: String): String =
        "$QUERY_SHAPE_CACHE_KEY_PREFIX$table$SEPARATOR"

    private fun reservedKeyOf(table: String, cacheKey: String): String =
        tablePrefixOf(table) + cacheKey

    private companion object {
        /** Between the table and the cache key in a record's signature: no table name holds it. */
        const val SEPARATOR = '\u001F'
    }
}

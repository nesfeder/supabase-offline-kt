// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.nesfeder.supabase.offline.internal.cache.OfflineStore
import kotlinx.coroutines.flow.Flow

/**
 * Cached query result per (table, signature). We store the raw JSON payload (as returned by
 * PostgREST) and a timestamp.
 */
@Entity(
    tableName = "object_cache",
    primaryKeys = ["table_name", "signature"],
)
internal data class CachedQueryRow(
    @ColumnInfo(name = "table_name") val table: String,
    @ColumnInfo(name = "signature") val cacheKey: String,
    /** Full JSON payload from PostgREST (usually a JSON array string). */
    @ColumnInfo(name = "payload") val payload: String,
    /**
     * When the payload was fetched, in epoch milliseconds; negated when it is stale: served, but
     * fetched again when the network allows. See [isStaleStamp] and [fetchedAtOf].
     */
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /**
     * The [MutationRow.syncSequence] through which the payload already holds every synced mutation
     * of its table: a network response written after they replayed. `0` holds none for certain.
     */
    @ColumnInfo(name = "synced_through", defaultValue = "0") val syncedThrough: Long = 0,
)

/** Whether a payload stamped [updatedAt] is stale: served, but fetched again when it can be. */
internal fun isStaleStamp(updatedAt: Long): Boolean = updatedAt < 0

/** When a payload stamped [updatedAt] was fetched, or made, stale or not. */
internal fun fetchedAtOf(updatedAt: Long): Long = if (updatedAt < 0) -updatedAt else updatedAt

/** [updatedAt] marked stale, keeping when the payload was fetched; one stamped 0 reads as 1. */
internal fun staleStampOf(updatedAt: Long): Long =
    if (updatedAt < 0) updatedAt else -maxOf(updatedAt, 1L)

@Dao
internal interface QueryCacheDao {

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :cacheKey LIMIT 1")
    suspend fun get(table: String, cacheKey: String): CachedQueryRow?

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :cacheKey LIMIT 1")
    fun observe(table: String, cacheKey: String): Flow<CachedQueryRow?>

    /**
     * All cached SELECT payloads for [table], across every signature currently observed.
     *
     * Used by [OfflineStore.patchCachedQueries] so a successful online mutation can patch every
     * live `selectAsFlow` subscription's underlying cache without requiring a round-trip SELECT.
     */
    @Query("SELECT * FROM object_cache WHERE table_name = :table")
    suspend fun allForTable(table: String): List<CachedQueryRow>

    /** The highest [CachedQueryRow.syncedThrough] of any row; `0` when there is none. */
    @Query("SELECT COALESCE(MAX(synced_through), 0) FROM object_cache")
    suspend fun maxSyncedThrough(): Long

    /**
     * The lowest [CachedQueryRow.syncedThrough] of [table]'s cached payloads: the synced mutations
     * at or below it are held by every one. `null` when the table has no cached payload.
     */
    @Query("SELECT MIN(synced_through) FROM object_cache WHERE table_name = :table")
    suspend fun minSyncedThroughForTable(table: String): Long?

    /**
     * The signature of every cached SELECT payload for [table], without loading the payloads.
     *
     * Used by [OfflineStore.retireCoveredSyncedMutations] to decide which cached queries a synced
     * mutation's overlay still stands in for.
     *
     * @param table Table whose cached signatures are listed.
     * @return Every cached signature of [table], in no particular order.
     */
    @Query("SELECT signature FROM object_cache WHERE table_name = :table")
    suspend fun cacheKeysForTable(table: String): List<String>

    /**
     * The rows of [table] whose signature starts with [prefix]; for the engine's per-query records
     * under [RESERVED_TABLE_NAME].
     */
    @Query(
        "SELECT * FROM object_cache WHERE table_name = :table AND substr(signature, 1, length(:prefix)) = :prefix"
    )
    suspend fun rowsWithCacheKeyPrefix(table: String, prefix: String): List<CachedQueryRow>

    /** Deletes the rows of [table] whose signature starts with [prefix]. */
    @Query(
        "DELETE FROM object_cache WHERE table_name = :table AND substr(signature, 1, length(:prefix)) = :prefix"
    )
    suspend fun deleteWithCacheKeyPrefix(table: String, prefix: String)

    /**
     * Deletes each query-shape record (see [QUERY_SHAPE_CACHE_KEY_PREFIX]) whose cached payload is
     * gone, as eviction leaves them.
     *
     * @return how many records were deleted.
     */
    @Query(
        "DELETE FROM object_cache WHERE table_name = '$RESERVED_TABLE_NAME' " +
            "AND substr(signature, 1, $QUERY_SHAPE_CACHE_KEY_PREFIX_LENGTH) = '$QUERY_SHAPE_CACHE_KEY_PREFIX_NAME' || char(31) " +
            "AND substr(signature, ${QUERY_SHAPE_CACHE_KEY_PREFIX_LENGTH + 1}) NOT IN (" +
            "SELECT table_name || char(31) || signature FROM object_cache WHERE table_name != '$RESERVED_TABLE_NAME'" +
            ")"
    )
    suspend fun deleteOrphanedQueryShapes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: CachedQueryRow)

    @Query("DELETE FROM object_cache WHERE table_name = :table AND signature = :cacheKey")
    suspend fun delete(table: String, cacheKey: String)

    @Query("DELETE FROM object_cache WHERE table_name = :table")
    suspend fun deleteTable(table: String)

    /** Deletes every cached SELECT payload across all tables and signatures. */
    @Query("DELETE FROM object_cache") suspend fun deleteAll()

    /**
     * Deletes every row of every table but [table]: the cache's payloads, keeping the engine's
     * rows.
     */
    @Query("DELETE FROM object_cache WHERE table_name != :table")
    suspend fun deleteAllExceptTable(table: String)

    /**
     * Summed character length of every cached SELECT payload across all tables and signatures,
     * leaving out the engine's reserved rows (see [RESERVED_TABLE_NAME]): they are engine state,
     * not cache, and must never push a real payload out.
     *
     * This is the **logical** size of the object cache, used by [OfflineStore.evictOverBudget] as
     * the eviction signal. The on-disk database file size can't be used: SQLite returns pages freed
     * by `DELETE` to its internal freelist rather than to the operating system, so the file never
     * shrinks after eviction and would make the eviction loop spin forever. Summed payload length,
     * by contrast, decreases as rows are deleted. For the ASCII JSON payloads PostgREST returns,
     * character length is a close proxy for byte size.
     *
     * @return total payload length in characters, or `0` when the cache is empty.
     */
    @Query(
        "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM object_cache " +
            "WHERE table_name != '$RESERVED_TABLE_NAME'"
    )
    suspend fun totalPayloadBytes(): Long

    /**
     * Delete up to [limit] oldest payload rows across all tables, ordered by when each was fetched
     * ([fetchedAtOf]), stale or not. Used by [OfflineStore.evictOverBudget] to evict old cache
     * entries when the summed payload size grows beyond the configured limit. The engine's reserved
     * rows (see [RESERVED_TABLE_NAME]) are never candidates: they hold the rejected-mutation list,
     * the cache's owner and the query shapes, which no refetch restores.
     *
     * @param limit maximum number of rows to delete in this batch.
     * @param keptCacheKeys signatures that are never candidates: queries a live reader shows. A
     *   signature begins with its table, so it names one row.
     * @return the number of rows actually deleted; `0` once nothing is left to evict, which the
     *   eviction loop relies on as its progress guard to guarantee termination.
     */
    @Query(
        "DELETE FROM object_cache " +
            "WHERE rowid IN (" +
            "SELECT rowid FROM object_cache WHERE table_name != '$RESERVED_TABLE_NAME' " +
            "AND signature NOT IN (:keptCacheKeys) " +
            "ORDER BY abs(updated_at) ASC LIMIT :limit" +
            ")"
    )
    suspend fun deleteOldestPayloadRows(limit: Int, keptCacheKeys: List<String>): Int
}

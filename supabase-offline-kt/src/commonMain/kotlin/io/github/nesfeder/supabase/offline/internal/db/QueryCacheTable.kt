// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.github.nesfeder.supabase.offline.internal.cache.CachePatcher
import io.github.nesfeder.supabase.offline.internal.cache.QueryCache
import io.github.nesfeder.supabase.offline.internal.mutationqueue.MutationQueue
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

/**
 * One cached payload's place in eviction order and its size, without the payload or its key: what
 * an eviction pass reads for every row; see [QueryCacheDao.payloadLengthsOldestFirst].
 *
 * @property rowId the row's SQLite `rowid`, which names it to [QueryCacheDao.deletePayloads].
 * @property fetchedAt when the payload was fetched ([fetchedAtOf]), stale or not.
 * @property length the payload's length in characters.
 * @property kept whether a live reader shows the payload, so a pass leaves it.
 */
internal data class PayloadLength(
    @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Long,
    @ColumnInfo(name = "payload_length") val length: Long,
    @ColumnInfo(name = "kept") val kept: Boolean,
)

@Dao
internal interface QueryCacheDao {

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :cacheKey LIMIT 1")
    suspend fun get(table: String, cacheKey: String): CachedQueryRow?

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :cacheKey LIMIT 1")
    fun observe(table: String, cacheKey: String): Flow<CachedQueryRow?>

    /** Whether a row is cached for [table]/[cacheKey], without reading its payload. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM object_cache WHERE table_name = :table AND signature = :cacheKey)"
    )
    suspend fun hasRow(table: String, cacheKey: String): Boolean

    /**
     * All cached SELECT payloads for [table], across every signature currently observed.
     *
     * Used by [CachePatcher.patchCachedQueries] so a successful online mutation can patch every
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
     * Used by [MutationQueue.retireCoveredSyncedMutations] to decide which cached queries a synced
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

    /**
     * Sets the payload and fetch stamp of the row cached for [table]/[cacheKey], when there is one,
     * keeping its [CachedQueryRow.syncedThrough]. A patch of a cached query writes through this,
     * never [upsert]: eviction holds no table lock, and an upsert would bring back a list it
     * deleted between the patch's read and its write.
     *
     * @return `1` when the row changed, `0` when no payload is cached under the key.
     */
    @Query(
        "UPDATE object_cache SET payload = :payload, updated_at = :updatedAt " +
            "WHERE table_name = :table AND signature = :cacheKey"
    )
    suspend fun updatePayload(
        table: String,
        cacheKey: String,
        payload: String,
        updatedAt: Long,
    ): Int

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
     * This is the **logical** size of the object cache, the size its bound is on. The on-disk
     * database file size can't be used: SQLite returns pages freed by `DELETE` to its internal
     * freelist rather than to the operating system, so the file never shrinks after eviction.
     * Summed payload length, by contrast, decreases as rows are deleted. For the ASCII JSON
     * payloads PostgREST returns, character length is a close proxy for byte size.
     *
     * `LENGTH()` of a `TEXT` column reads the whole value, so this reads every payload in full;
     * [QueryCache] keeps a running count instead and reads [payloadLengthsOldestFirst] once per
     * eviction pass.
     *
     * @return total payload length in characters, or `0` when the cache is empty.
     */
    @Query(
        "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM object_cache " +
            "WHERE table_name != '$RESERVED_TABLE_NAME'"
    )
    suspend fun totalPayloadBytes(): Long

    /**
     * The length in characters of the payload cached for [table]/[cacheKey], or `null` for none.
     */
    @Query(
        "SELECT LENGTH(payload) FROM object_cache WHERE table_name = :table AND signature = :cacheKey"
    )
    suspend fun payloadLengthOf(table: String, cacheKey: String): Long?

    /**
     * Every cached SELECT payload's `rowid`, fetch time and character length, in eviction order:
     * the one fetched longest ago first ([fetchedAtOf]), stale or not, each marked when its
     * signature is one of [keptCacheKeys]. One read of every payload, from which
     * [QueryCache.evictOverBudget] takes the cache's size and the rows to delete. The engine's
     * reserved rows (see [RESERVED_TABLE_NAME]) are left out: they hold the rejected list, the
     * cache's owner and the query shapes, which no refetch restores.
     *
     * @param keptCacheKeys signatures a live reader shows, at most
     *   [QueryCache.MAX_KEPT_LIVE_CACHE_KEYS] within SQLite's bound on a statement's variables. A
     *   signature begins with its table, so it names one row.
     */
    @Query(
        "SELECT rowid AS row_id, abs(updated_at) AS fetched_at, LENGTH(payload) AS payload_length, " +
            "signature IN (:keptCacheKeys) AS kept FROM object_cache " +
            "WHERE table_name != '$RESERVED_TABLE_NAME' ORDER BY abs(updated_at) ASC"
    )
    suspend fun payloadLengthsOldestFirst(keptCacheKeys: List<String>): List<PayloadLength>

    /**
     * Deletes the cached payloads among [rowIds] fetched at [fetchedAtOrBefore] or earlier, as an
     * eviction pass deletes the rows it chose from [payloadLengthsOldestFirst]. The fetch time
     * guards the window between the read and the delete: a list fetched again since has a later
     * stamp and stays, now the newest, and so does a row written under a `rowid` SQLite gave out
     * again after the table was emptied; a patched or stale-marked list keeps its stamp and goes.
     * The engine's reserved rows (see [RESERVED_TABLE_NAME]) are never deleted, whatever `rowid`
     * they were given.
     *
     * @param rowIds at most [QueryCache.MAX_DELETE_KEYS] of them, within SQLite's bound on a
     *   statement's variables.
     * @return how many rows were deleted; fewer than named when some were gone or fetched again.
     */
    @Query(
        "DELETE FROM object_cache WHERE rowid IN (:rowIds) AND abs(updated_at) <= :fetchedAtOrBefore " +
            "AND table_name != '$RESERVED_TABLE_NAME'"
    )
    suspend fun deletePayloads(rowIds: List<Long>, fetchedAtOrBefore: Long): Int

    /**
     * Runs [block] in one write transaction: every DAO call it makes, on any of the database's
     * DAOs, commits together or not at all. Keep [block] to DAO calls — never wait on a coroutine
     * lock inside it, or a writer holding that lock could wait on this transaction's connection.
     *
     * @param block The writes to make atomically.
     */
    @Transaction
    suspend fun runInTransaction(block: suspend () -> Unit) {
        block()
    }
}

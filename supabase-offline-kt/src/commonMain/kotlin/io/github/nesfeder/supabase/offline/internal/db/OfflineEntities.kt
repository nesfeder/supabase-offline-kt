// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/* ───────────────────────── Object cache ───────────────────────── */

/**
 * Cached query result per (table, signature). We store the raw JSON payload (as returned by
 * PostgREST) and a timestamp.
 */
@Entity(
    tableName = "object_cache",
    primaryKeys = ["table_name", "signature"],
)
internal data class ObjectRow(
    @ColumnInfo(name = "table_name") val table: String,
    @ColumnInfo(name = "signature") val signature: String,
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
internal interface ObjectDao {

    @Query(
        "SELECT * FROM object_cache WHERE table_name = :table AND signature = :signature LIMIT 1"
    )
    suspend fun get(table: String, signature: String): ObjectRow?

    @Query(
        "SELECT * FROM object_cache WHERE table_name = :table AND signature = :signature LIMIT 1"
    )
    fun observe(table: String, signature: String): Flow<ObjectRow?>

    /**
     * All cached SELECT payloads for [table], across every signature currently observed.
     *
     * Used by [OfflineStore.patchCachedQueries] so a successful online mutation can patch every
     * live `selectAsFlow` subscription's underlying cache without requiring a round-trip SELECT.
     */
    @Query("SELECT * FROM object_cache WHERE table_name = :table")
    suspend fun allForTable(table: String): List<ObjectRow>

    /** The highest [ObjectRow.syncedThrough] of any row; `0` when there is none. */
    @Query("SELECT COALESCE(MAX(synced_through), 0) FROM object_cache")
    suspend fun maxSyncedThrough(): Long

    /**
     * The lowest [ObjectRow.syncedThrough] of [table]'s cached payloads: the synced mutations at or
     * below it are held by every one. `null` when the table has no cached payload.
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
    suspend fun signaturesForTable(table: String): List<String>

    /**
     * The rows of [table] whose signature starts with [prefix]; for the engine's per-query records
     * under [RESERVED_TABLE_NAME].
     */
    @Query(
        "SELECT * FROM object_cache WHERE table_name = :table AND substr(signature, 1, length(:prefix)) = :prefix"
    )
    suspend fun rowsWithSignaturePrefix(table: String, prefix: String): List<ObjectRow>

    /** Deletes the rows of [table] whose signature starts with [prefix]. */
    @Query(
        "DELETE FROM object_cache WHERE table_name = :table AND substr(signature, 1, length(:prefix)) = :prefix"
    )
    suspend fun deleteWithSignaturePrefix(table: String, prefix: String)

    /**
     * Deletes each query-shape record (see [QUERY_SHAPE_SIGNATURE_PREFIX]) whose cached payload is
     * gone, as eviction leaves them.
     *
     * @return how many records were deleted.
     */
    @Query(
        "DELETE FROM object_cache WHERE table_name = '$RESERVED_TABLE_NAME' " +
            "AND substr(signature, 1, $QUERY_SHAPE_SIGNATURE_PREFIX_LENGTH) = '$QUERY_SHAPE_SIGNATURE_PREFIX_NAME' || char(31) " +
            "AND substr(signature, ${QUERY_SHAPE_SIGNATURE_PREFIX_LENGTH + 1}) NOT IN (" +
            "SELECT table_name || char(31) || signature FROM object_cache WHERE table_name != '$RESERVED_TABLE_NAME'" +
            ")"
    )
    suspend fun deleteOrphanedQueryShapes(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: ObjectRow)

    @Query("DELETE FROM object_cache WHERE table_name = :table AND signature = :signature")
    suspend fun delete(table: String, signature: String)

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
     * This is the **logical** size of the object cache, used by
     * [OfflineStore.enforceDbSizeLimitIfNeeded] as the eviction signal. The on-disk database file
     * size can't be used: SQLite returns pages freed by `DELETE` to its internal freelist rather
     * than to the operating system, so the file never shrinks after eviction and would make the
     * eviction loop spin forever. Summed payload length, by contrast, decreases as rows are
     * deleted. For the ASCII JSON payloads PostgREST returns, character length is a close proxy for
     * byte size.
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
     * ([fetchedAtOf]), stale or not. Used by [OfflineStore.enforceDbSizeLimitIfNeeded] to evict old
     * cache entries when the summed payload size grows beyond the configured limit. The engine's
     * reserved rows (see [RESERVED_TABLE_NAME]) are never candidates: they hold the
     * rejected-mutation list, the cache's owner and the query shapes, which no refetch restores.
     *
     * @param limit maximum number of rows to delete in this batch.
     * @param keptSignatures signatures that are never candidates: queries a live reader shows. A
     *   signature begins with its table, so it names one row.
     * @return the number of rows actually deleted; `0` once nothing is left to evict, which the
     *   eviction loop relies on as its progress guard to guarantee termination.
     */
    @Query(
        "DELETE FROM object_cache " +
            "WHERE rowid IN (" +
            "SELECT rowid FROM object_cache WHERE table_name != '$RESERVED_TABLE_NAME' " +
            "AND signature NOT IN (:keptSignatures) " +
            "ORDER BY abs(updated_at) ASC LIMIT :limit" +
            ")"
    )
    suspend fun deleteOldestPayloadRows(limit: Int, keptSignatures: List<String>): Int
}

/* ───────────────────────── Storage metadata ───────────────────────── */

/** Tracks a downloaded/cached storage object. Primary key is the tuple (bucketId + "::" + path). */
@Entity(
    tableName = "storage_objects",
    indices = [Index(value = ["bucketId", "path"], unique = true)],
)
internal data class StorageRow(
    @PrimaryKey val key: String, // "$bucketId::$path"
    val bucketId: String,
    val path: String,
    val localPath: String, // absolute file path
    val sizeBytes: Long?,
    val eTag: String?, // optional (if you add revalidation later)
    val lastFetchedEpochMs: Long,
)

@Dao
internal interface StorageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(row: StorageRow)

    @Query("SELECT * FROM storage_objects WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): StorageRow?

    @Query("DELETE FROM storage_objects WHERE `key` = :key") suspend fun delete(key: String)

    @Query("DELETE FROM storage_objects WHERE bucketId = :bucketId AND path = :path")
    suspend fun delete(bucketId: String, path: String)

    /** Deletes every cached storage-object metadata row across all buckets. */
    @Query("DELETE FROM storage_objects") suspend fun deleteAll()

    /**
     * The rows of one object in [bucketId], variants included. Its own row's `path` is [path]; its
     * image-transform variants' `path` starts with [variantPrefix].
     *
     * @param bucketId the bucket.
     * @param path the object path.
     * @param variantPrefix [path] followed by the variant separator.
     * @return the matching rows.
     */
    @Query(
        "SELECT * FROM storage_objects WHERE bucketId = :bucketId AND " +
            "(path = :path OR substr(path, 1, length(:variantPrefix)) = :variantPrefix)"
    )
    suspend fun forObject(bucketId: String, path: String, variantPrefix: String): List<StorageRow>

    /** Every cached storage-object row, for the size bound's least-recently-used pass. */
    @Query("SELECT * FROM storage_objects") suspend fun all(): List<StorageRow>

    /** The summed size of every cached file whose size is recorded, in bytes; `0` when none is. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM storage_objects")
    suspend fun totalSizeBytes(): Long
}

/* ───────────────────────── Mutation queue ───────────────────────── */

/**
 * A queued mutation (INSERT / UPSERT / UPDATE / DELETE) for a given PostgREST table, with the
 * options of supabase-kt's request builders it replays with, the identity that made it, and where
 * its send and its replay stand. The overlay shows it on cached reads until it is retired.
 */
@Entity(
    tableName = "mutation_cache",
    indices = [Index(value = ["owner", "synced_at_ms"]), Index(value = ["table_name"])],
)
internal data class MutationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "table_name") val table: String,

    /** "INSERT", "UPSERT", "UPDATE", or "DELETE". */
    val operation: String,

    /**
     * Raw JSON body string. For INSERT/UPSERT usually a JSON array; for UPDATE a JSON object; for
     * DELETE usually "{}".
     */
    @ColumnInfo(name = "body_json") val bodyJson: String,

    /** The JSON encoding of the `FilterDescriptor` the write targets rows by; `null` for none. */
    @ColumnInfo(name = "filter_json") val filterJson: String?,

    /**
     * Client-side timestamp (epoch millis) when the mutation was enqueued. Informational only:
     * replay and overlay order by [id], because a wall clock can step backwards.
     */
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,

    /**
     * When the mutation replayed, by the device clock; `null` while it is pending. Informational:
     * which cached payloads already hold it is decided by [syncSequence].
     */
    @ColumnInfo(name = "synced_at_ms") val syncedAtMs: Long? = null,

    /** Mirrors the INSERT and UPSERT builders' `defaultToNull`; ignored for UPDATE and DELETE. */
    @ColumnInfo(name = "default_to_null") val defaultToNull: Boolean? = null,

    /** Mirrors the UPSERT builder's `onConflict`: its comma-separated conflict columns. */
    @ColumnInfo(name = "on_conflict") val onConflict: String? = null,

    /** Mirrors the UPSERT builder's `ignoreDuplicates`; ignored for other operations. */
    @ColumnInfo(name = "ignore_duplicates") val ignoreDuplicates: Boolean? = null,

    /**
     * The stored value of the identity that made the write; `null` when none was recorded, for a
     * write the engine queued with no session model.
     */
    @ColumnInfo(name = "owner") val owner: String? = null,

    /**
     * The order in which the mutation replayed among all synced mutations, from a sequence that
     * never goes back; `null` while it is pending.
     */
    @ColumnInfo(name = "sync_sequence") val syncSequence: Long? = null,

    /**
     * How many replays PostgREST refused because its schema cache did not know the table or a
     * column yet; the write is rejected once they reach the bound.
     */
    @ColumnInfo(name = "schema_cache_misses", defaultValue = "0") val schemaCacheMisses: Int = 0,

    /**
     * Whether a send of this INSERT started and its outcome is unknown. The server may then hold
     * its rows, so it replays as an upsert that takes them as its own. Set only for an INSERT whose
     * rows all carry the table's key, before its request goes out; cleared on proof that the write
     * did not run, and when it replays.
     */
    @ColumnInfo(name = "send_unconfirmed", defaultValue = "0") val sendUnconfirmed: Boolean = false,
)

@Dao
internal interface MutationDao {

    /**
     * Stores [row]. A row whose [MutationRow.id] is `0` receives the next autoincrement id.
     *
     * @param row The mutation to store.
     * @return The row's id: the one it was given, or the one SQLite assigned.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(row: MutationRow): Long

    /**
     * Pending mutations for a specific table, in insertion order.
     *
     * Only rows with [MutationRow.syncedAtMs] == null are considered pending. The order is the
     * autoincrement [MutationRow.id], never the wall clock: `created_at_ms` can step backwards (a
     * clock change, an NTP correction) and ties within a millisecond, and replay order has to be
     * the order the writes were made.
     */
    @Query(
        "SELECT * FROM mutation_cache " +
            "WHERE table_name = :table AND synced_at_ms IS NULL " +
            "ORDER BY id ASC"
    )
    suspend fun pendingForTable(table: String): List<MutationRow>

    /** All pending mutations across all tables, in insertion order (by [MutationRow.id]). */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    suspend fun pendingAll(): List<MutationRow>

    /**
     * Every pending mutation across all tables, in insertion order, re-emitted on every queue
     * change.
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    fun pendingAllFlow(): Flow<List<MutationRow>>

    /**
     * The pending mutations of [owner] — and with [includeOwnerless] those with no recorded owner —
     * in insertion order.
     */
    @Query(
        "SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL)) ORDER BY id ASC"
    )
    suspend fun pendingOwnedBy(owner: String, includeOwnerless: Boolean): List<MutationRow>

    /** [pendingOwnedBy], of [table] only. */
    @Query(
        "SELECT * FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NULL " +
            "AND (owner = :owner OR (:includeOwnerless AND owner IS NULL)) ORDER BY id ASC"
    )
    suspend fun pendingOwnedByInTable(
        owner: String,
        includeOwnerless: Boolean,
        table: String,
    ): List<MutationRow>

    /**
     * Whether any mutation of [owner], or with [includeOwnerless] any with no recorded owner, is
     * pending with an id below [beforeId]. Reads the owner index only.
     */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE owner = :owner " +
            "AND synced_at_ms IS NULL AND id < :beforeId) OR (:includeOwnerless AND EXISTS(" +
            "SELECT 1 FROM mutation_cache WHERE owner IS NULL AND synced_at_ms IS NULL " +
            "AND id < :beforeId))"
    )
    suspend fun hasPendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
        beforeId: Long,
    ): Boolean

    /** Whether any mutation is pending with an id below [beforeId], whoever owns it. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE synced_at_ms IS NULL AND id < :beforeId)"
    )
    suspend fun hasPendingBefore(beforeId: Long): Boolean

    /** Records [owner] as the owner of mutation [id]. */
    @Query("UPDATE mutation_cache SET owner = :owner WHERE id = :id")
    suspend fun setOwner(id: Long, owner: String)

    /**
     * Records [owner] for every pending mutation that has none.
     *
     * @return How many it adopted.
     */
    @Query("UPDATE mutation_cache SET owner = :owner WHERE owner IS NULL AND synced_at_ms IS NULL")
    suspend fun adoptOwnerless(owner: String): Int

    /** Whether the mutation [id] is queued and not yet synced. */
    @Query("SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE id = :id AND synced_at_ms IS NULL)")
    suspend fun isPending(id: Long): Boolean

    /** The mutation [id], pending or synced, or `null` when it is not queued. */
    @Query("SELECT * FROM mutation_cache WHERE id = :id") suspend fun byId(id: Long): MutationRow?

    /** [table]'s synced mutations still retained for the overlay, oldest replay first. */
    @Query(
        "SELECT * FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NOT NULL " +
            "ORDER BY sync_sequence ASC, id ASC"
    )
    suspend fun syncedForTable(table: String): List<MutationRow>

    /** Every table with a synced mutation retained for the overlay. */
    @Query("SELECT DISTINCT table_name FROM mutation_cache WHERE synced_at_ms IS NOT NULL")
    suspend fun tablesWithSyncedMutations(): List<String>

    /** The highest [MutationRow.syncSequence] of any row; `0` when there is none. */
    @Query("SELECT COALESCE(MAX(sync_sequence), 0) FROM mutation_cache")
    suspend fun maxSyncSequence(): Long

    /** Counts one more schema-cache miss for mutation [id]. */
    @Query("UPDATE mutation_cache SET schema_cache_misses = schema_cache_misses + 1 WHERE id = :id")
    suspend fun recordSchemaCacheMiss(id: Long)

    @Query("SELECT COUNT(*) FROM mutation_cache") suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM mutation_cache WHERE synced_at_ms IS NULL")
    suspend fun countPending(): Int

    @Query("SELECT COUNT(*) FROM mutation_cache WHERE synced_at_ms IS NULL")
    fun countPendingFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM mutation_cache WHERE table_name = :table")
    suspend fun countForTable(table: String): Int

    @Query("SELECT COUNT(*) FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NULL")
    suspend fun countPendingForTable(table: String): Int

    @Query("SELECT COUNT(*) FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NULL")
    fun countPendingForTableFlow(table: String): Flow<Int>

    @Query("DELETE FROM mutation_cache WHERE id = :id") suspend fun deleteById(id: Long)

    /**
     * All mutations for a table (pending + already-synced), in insertion order (by
     * [MutationRow.id]). Used when applying overlays on top of cached SELECT data while offline.
     */
    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY id ASC")
    suspend fun allForTable(table: String): List<MutationRow>

    /**
     * Marks mutation [id] synced: when, by the device clock, and its [MutationRow.syncSequence].
     */
    @Query(
        "UPDATE mutation_cache SET synced_at_ms = :syncedAtMs, sync_sequence = :syncSequence, " +
            "send_unconfirmed = 0 WHERE id = :id"
    )
    suspend fun markSynced(id: Long, syncedAtMs: Long, syncSequence: Long)

    /**
     * Records that a send of mutation [id] started ([MutationRow.sendUnconfirmed]).
     *
     * @return `1` when this call set it, `0` when it was set already or there is no such row.
     */
    @Query("UPDATE mutation_cache SET send_unconfirmed = 1 WHERE id = :id AND send_unconfirmed = 0")
    suspend fun markSendUnconfirmed(id: Long): Int

    /** Clears [MutationRow.sendUnconfirmed] of mutation [id]: its send never reached the server. */
    @Query("UPDATE mutation_cache SET send_unconfirmed = 0 WHERE id = :id")
    suspend fun clearSendUnconfirmed(id: Long)

    /** The ids of every mutation whose send started and whose outcome is unknown. */
    @Query("SELECT id FROM mutation_cache WHERE send_unconfirmed = 1")
    suspend fun unconfirmedSendIds(): List<Long>

    /** Deletes every queued mutation (pending + already-synced) across all tables. */
    @Query("DELETE FROM mutation_cache") suspend fun deleteAll()

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

/* ───────────────────────── Mutation operations ───────────────────────── */

/** Simple operation "enum" so we don't rely on Room @TypeConverter right away. */
internal object MutationOperation {
    const val INSERT = "INSERT"
    const val UPSERT = "UPSERT"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
}

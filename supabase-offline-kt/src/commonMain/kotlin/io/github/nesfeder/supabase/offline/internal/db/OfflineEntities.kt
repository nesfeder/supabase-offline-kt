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
 * Cached query result per (table, signature).
 * We store the raw JSON payload (as returned by PostgREST) and a timestamp.
 */
@Entity(
    tableName = "object_cache",
    primaryKeys = ["table_name", "signature"]
)
internal data class ObjectRow(
    @ColumnInfo(name = "table_name") val table: String,
    @ColumnInfo(name = "signature") val signature: String,
    /** Full JSON payload from PostgREST (usually a JSON array string). */
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)

@Dao
internal interface ObjectDao {

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :signature LIMIT 1")
    suspend fun get(table: String, signature: String): ObjectRow?

    @Query("SELECT * FROM object_cache WHERE table_name = :table AND signature = :signature LIMIT 1")
    fun observe(table: String, signature: String): Flow<ObjectRow?>

    /**
     * All cached SELECT payloads for [table], across every signature currently observed.
     *
     * Used by [OfflineStore.applyLocalMutationToCache] so a successful online mutation
     * can patch every live `selectAsFlow` subscription's underlying cache without requiring
     * a round-trip SELECT.
     */
    @Query("SELECT * FROM object_cache WHERE table_name = :table")
    suspend fun allForTable(table: String): List<ObjectRow>

    /**
     * The signature of every cached SELECT payload for [table], without loading the payloads.
     *
     * Used by [OfflineStore.clearSyncedMutationsForTable] to decide which cached queries a synced
     * mutation's overlay still stands in for.
     *
     * @param table Table whose cached signatures are listed.
     * @return Every cached signature of [table], in no particular order.
     */
    @Query("SELECT signature FROM object_cache WHERE table_name = :table")
    suspend fun signaturesForTable(table: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ObjectRow)

    @Query("DELETE FROM object_cache WHERE table_name = :table AND signature = :signature")
    suspend fun delete(table: String, signature: String)

    @Query("DELETE FROM object_cache WHERE table_name = :table")
    suspend fun deleteTable(table: String)

    /** Deletes every cached SELECT payload across all tables and signatures. */
    @Query("DELETE FROM object_cache")
    suspend fun deleteAll()

    /**
     * Summed character length of every cached SELECT payload across all tables and signatures,
     * leaving out the engine's reserved rows (see [RESERVED_TABLE_NAME]): they are engine state,
     * not cache, and must never push a real payload out.
     *
     * This is the **logical** size of the object cache, used by
     * [OfflineStore.enforceDbSizeLimitIfNeeded] as the eviction signal. The on-disk database
     * file size can't be used: SQLite returns pages freed by `DELETE` to its internal freelist
     * rather than to the operating system, so the file never shrinks after eviction and would
     * make the eviction loop spin forever. Summed payload length, by contrast, decreases as
     * rows are deleted. For the ASCII JSON payloads PostgREST returns, character length is a
     * close proxy for byte size.
     *
     * @return total payload length in characters, or `0` when the cache is empty.
     */
    @Query(
        "SELECT COALESCE(SUM(LENGTH(payload)), 0) FROM object_cache " +
            "WHERE table_name != '$RESERVED_TABLE_NAME'"
    )
    suspend fun totalPayloadBytes(): Long

    /**
     * Delete up to [limit] oldest payload rows across all tables, ordered by [ObjectRow.updatedAt].
     * Used by [OfflineStore.enforceDbSizeLimitIfNeeded] to evict old cache entries when the
     * summed payload size grows beyond the configured limit. The engine's reserved rows (see
     * [RESERVED_TABLE_NAME]) are never candidates: they hold the rejected-mutation list and the
     * owner of each queued mutation, which no refetch can restore.
     *
     * @param limit maximum number of rows to delete in this batch.
     * @return the number of rows actually deleted; `0` once the table is empty, which the
     *   eviction loop relies on as its progress guard to guarantee termination.
     */
    @Query(
        "DELETE FROM object_cache " +
                "WHERE rowid IN (" +
                "SELECT rowid FROM object_cache WHERE table_name != '$RESERVED_TABLE_NAME' " +
                "ORDER BY updated_at ASC LIMIT :limit" +
                ")"
    )
    suspend fun deleteOldestPayloadRows(limit: Int): Int
}

/* ───────────────────────── Storage metadata ───────────────────────── */

/**
 * Tracks a downloaded/cached storage object.
 * Primary key is the tuple (bucketId + "::" + path).
 */
@Entity(
    tableName = "storage_objects",
    indices = [Index(value = ["bucketId", "path"], unique = true)]
)
internal data class StorageRow(
    @PrimaryKey val key: String,      // "$bucketId::$path"
    val bucketId: String,
    val path: String,
    val localPath: String,            // absolute file path
    val sizeBytes: Long?,
    val eTag: String?,                // optional (if you add revalidation later)
    val lastFetchedEpochMs: Long
)

@Dao
internal interface StorageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: StorageRow)

    @Query("SELECT * FROM storage_objects WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): StorageRow?

    @Query("DELETE FROM storage_objects WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM storage_objects WHERE bucketId = :bucketId AND path = :path")
    suspend fun delete(bucketId: String, path: String)

    /** Deletes every cached storage-object metadata row across all buckets. */
    @Query("DELETE FROM storage_objects")
    suspend fun deleteAll()

    /**
     * The rows of one object in [bucketId]: its own row, whose `path` is [path], and the rows of its
     * image-transform variants, whose `path` starts with [variantPrefix].
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
    @Query("SELECT * FROM storage_objects")
    suspend fun all(): List<StorageRow>

    /** The summed size of every cached file whose size is recorded, in bytes; `0` when none is. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM storage_objects")
    suspend fun totalSizeBytes(): Long
}

/* ───────────────────────── Mutation queue ───────────────────────── */

/**
 * A queued mutation (INSERT / UPSERT / UPDATE / DELETE) for a given PostgREST table.
 *
 * - [bodyJson] is the raw JSON payload string (array or object) we will send to PostgREST.
 * - [filterJson] is an optional serialized filter descriptor that describes which rows
 *   are targeted by the mutation (e.g. "WHERE id = '123'").
 * - [defaultToNull], [onConflict], and [ignoreDuplicates] mirror the options from
 *   supabase-kt's [InsertRequestBuilder] / [UpsertRequestBuilder] so that when we
 *   replay the mutation online we preserve the original semantics.
 *
 * We *overlay* these on top of cached SELECT payloads when offline using [FilterEngine].
 */
@Entity(tableName = "mutation_cache")
internal data class MutationRow(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "table_name")
    val table: String,

    /** "INSERT", "UPSERT", "UPDATE", or "DELETE". */
    val operation: String,

    /**
     * Raw JSON body string. For INSERT/UPSERT usually a JSON array; for UPDATE a JSON object;
     * for DELETE usually "{}".
     */
    @ColumnInfo(name = "body_json")
    val bodyJson: String,

    /**
     * Optional JSON-encoded filter descriptor:
     *
     * {
     *   "mode": "AND" | "OR",
     *   "conditions": [
     *     { "column": "id", "operatorId": "eq", "value": "123" },
     *     ...
     *   ]
     * }
     *
     * Here "operatorId" is the FilterOperator.identifier (e.g. "eq", "lt", "ilike", ...).
     *
     * Only used for UPDATE / DELETE. INSERT / UPSERT typically don't carry filters.
     */
    @ColumnInfo(name = "filter_json")
    val filterJson: String?,

    /**
     * Client-side timestamp (epoch millis) when the mutation was enqueued. Informational only:
     * replay and overlay order by [id], because a wall clock can step backwards.
     */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long,

    /**
     * Timestamp (epoch millis) when this mutation was successfully synced to the
     * server. A `null` value means the mutation is still *pending*.
     *
     * We keep synced mutations around so they can still participate in local
     * overlays when we have to fall back to cached SELECT data while offline.
     */
    @ColumnInfo(name = "synced_at_ms")
    val syncedAtMs: Long? = null,

    /**
     * Mirrors InsertRequestBuilder.defaultToNull / UpsertRequestBuilder.defaultToNull.
     *
     * - If true, missing fields default to `null`.
     * - If false, missing fields use the column's default value in Postgres.
     *
     * Only meaningful for INSERT / UPSERT; ignored for UPDATE / DELETE.
     * Nullable for backward compatibility and for non-INSERT/UPSERT rows.
     */
    @ColumnInfo(name = "default_to_null")
    val defaultToNull: Boolean? = null,

    /**
     * Mirrors UpsertRequestBuilder.onConflict.
     *
     * Comma-separated UNIQUE column(s) that define how duplicate rows are detected
     * during UPSERT.
     *
     * Only meaningful for UPSERT; ignored for other operations.
     */
    @ColumnInfo(name = "on_conflict")
    val onConflict: String? = null,

    /**
     * Mirrors UpsertRequestBuilder.ignoreDuplicates.
     *
     * - If true, duplicate rows are ignored on UPSERT.
     * - If false, duplicate rows are merged with existing rows.
     *
     * Only meaningful for UPSERT; ignored for other operations.
     */
    @ColumnInfo(name = "ignore_duplicates")
    val ignoreDuplicates: Boolean? = null,

    /**
     * Optional JSON-encoded list of [io.github.nesfeder.supabase.offline.NumericDeltaColumn] declarations: the
     * columns of [bodyJson] whose written value is a *relative* change measured against the
     * snapshot recorded here, rather than an absolute the replay should restate verbatim.
     *
     * Only meaningful for UPDATE. When present, the syncer re-reads the targeted row before
     * replaying and rewrites each declared column to `serverCurrent + (desired - snapshot)`, so a
     * change that landed server-side while this mutation waited in the queue is carried rather
     * than clobbered. `null` (the default, and every row written before delta encoding shipped)
     * replays the body verbatim.
     */
    @ColumnInfo(name = "delta_json")
    val deltaJson: String? = null
)

@Dao
internal interface MutationDao {

    /**
     * Stores [row]. A row whose [MutationRow.id] is `0` receives the next autoincrement id.
     *
     * @param row The mutation to store.
     * @return The row's id: the one it was given, or the one SQLite assigned.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MutationRow): Long

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

    /**
     * All pending mutations across all tables, in insertion order (by [MutationRow.id]).
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    suspend fun pendingAll(): List<MutationRow>

    /**
     * Every pending mutation across all tables, in insertion order, re-emitted on every queue
     * change.
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY id ASC")
    fun pendingAllFlow(): Flow<List<MutationRow>>

    /** The ids of the mutations queued before [id], in any table, that are still pending, in order. */
    @Query("SELECT id FROM mutation_cache WHERE synced_at_ms IS NULL AND id < :id ORDER BY id ASC")
    suspend fun pendingIdsBefore(id: Long): List<Long>

    /** Whether the mutation [id] is queued and not yet synced. */
    @Query("SELECT EXISTS(SELECT 1 FROM mutation_cache WHERE id = :id AND synced_at_ms IS NULL)")
    suspend fun isPending(id: Long): Boolean

    /** The mutation [id], pending or synced, or `null` when it is not queued. */
    @Query("SELECT * FROM mutation_cache WHERE id = :id")
    suspend fun byId(id: Long): MutationRow?

    /** The ids of [table]'s synced mutations still retained for the overlay. */
    @Query("SELECT id FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NOT NULL")
    suspend fun syncedIdsForTable(table: String): List<Long>

    @Query("SELECT COUNT(*) FROM mutation_cache")
    suspend fun countAll(): Int

    /** The id of every queued mutation, pending or synced. */
    @Query("SELECT id FROM mutation_cache")
    suspend fun allIds(): List<Long>

    /**
     * Synced mutations marked synced before [syncedBeforeMs], by the device clock.
     *
     * @param syncedBeforeMs The cut-off, in epoch milliseconds.
     * @return The rows, in insertion order.
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NOT NULL AND synced_at_ms < :syncedBeforeMs ORDER BY id ASC")
    suspend fun syncedBefore(syncedBeforeMs: Long): List<MutationRow>

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

    @Query("DELETE FROM mutation_cache WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * All mutations for a table (pending + already-synced), in insertion order (by
     * [MutationRow.id]). Used when applying overlays on top of cached SELECT data while offline.
     */
    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY id ASC")
    suspend fun allForTable(table: String): List<MutationRow>

    /** [allForTable], re-emitted on every queue change. */
    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY id ASC")
    fun allForTableFlow(table: String): Flow<List<MutationRow>>

    /**
     * Mark a single mutation as synced by setting [MutationRow.syncedAtMs].
     */
    @Query("UPDATE mutation_cache SET synced_at_ms = :syncedAtMs WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAtMs: Long)

    /** Deletes every queued mutation (pending + already-synced) across all tables. */
    @Query("DELETE FROM mutation_cache")
    suspend fun deleteAll()

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

/**
 * Simple operation "enum" so we don't rely on Room @TypeConverter right away.
 */
internal object MutationOperation {
    const val INSERT = "INSERT"
    const val UPSERT = "UPSERT"
    const val UPDATE = "UPDATE"
    const val DELETE = "DELETE"
}
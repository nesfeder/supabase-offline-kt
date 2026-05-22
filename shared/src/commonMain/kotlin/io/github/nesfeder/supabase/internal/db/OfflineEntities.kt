package io.github.nesfeder.supabase.internal.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ObjectRow)

    @Query("DELETE FROM object_cache WHERE table_name = :table AND signature = :signature")
    suspend fun delete(table: String, signature: String)

    @Query("SELECT * FROM object_cache WHERE table_name = :table ORDER BY updated_at DESC")
    suspend fun getAllForTable(table: String): List<ObjectRow>

    @Query("SELECT * FROM object_cache WHERE table_name = :table ORDER BY updated_at DESC")
    fun observeAllForTable(table: String): Flow<List<ObjectRow>>

    @Query("DELETE FROM object_cache WHERE table_name = :table")
    suspend fun deleteTable(table: String)

    /**
     * Delete up to [limit] oldest payload rows across all tables, ordered by [ObjectRow.updatedAt].
     * Used by [OfflineStore.enforceDbSizeLimitIfNeeded] to evict old cache entries when
     * the underlying database file grows beyond the configured size limit.
     */
    @Query(
        "DELETE FROM object_cache " +
                "WHERE rowid IN (" +
                "SELECT rowid FROM object_cache ORDER BY updated_at ASC LIMIT :limit" +
                ")"
    )
    suspend fun deleteOldestPayloadRows(limit: Int)
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
     * Client-side timestamp (epoch millis) when the mutation was enqueued.
     * Used to enforce FIFO ordering when syncing.
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
    val ignoreDuplicates: Boolean? = null
)

@Dao
internal interface MutationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: MutationRow)

    /**
     * Pending mutations for a specific table, ordered by enqueue time.
     *
     * Only rows with [MutationRow.syncedAtMs] == null are considered pending.
     */
    @Query(
        "SELECT * FROM mutation_cache " +
                "WHERE table_name = :table AND synced_at_ms IS NULL " +
                "ORDER BY created_at_ms ASC"
    )
    suspend fun pendingForTable(table: String): List<MutationRow>

    /**
     * All pending mutations across all tables, ordered by enqueue time.
     */
    @Query("SELECT * FROM mutation_cache WHERE synced_at_ms IS NULL ORDER BY created_at_ms ASC")
    suspend fun pendingAll(): List<MutationRow>

    @Query("SELECT COUNT(*) FROM mutation_cache")
    suspend fun countAll(): Int

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
     * All mutations for a table (pending + already-synced), ordered by enqueue time.
     * Used when applying overlays on top of cached SELECT data while offline.
     */
    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY created_at_ms ASC")
    suspend fun allForTable(table: String): List<MutationRow>

    @Query("SELECT * FROM mutation_cache WHERE table_name = :table ORDER BY created_at_ms ASC")
    fun allForTableFlow(table: String): Flow<List<MutationRow>>

    /**
     * Mark a single mutation as synced by setting [MutationRow.syncedAtMs].
     */
    @Query("UPDATE mutation_cache SET synced_at_ms = :syncedAtMs WHERE id = :id")
    suspend fun markSynced(id: Long, syncedAtMs: Long)

    /**
     * Delete all mutations for a table that have already been synced.
     *
     * Called after a successful fresh SELECT for that table so the cache
     * doesn't grow without bounds.
     */
    @Query("DELETE FROM mutation_cache WHERE table_name = :table AND synced_at_ms IS NOT NULL")
    suspend fun deleteSyncedForTable(table: String)
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
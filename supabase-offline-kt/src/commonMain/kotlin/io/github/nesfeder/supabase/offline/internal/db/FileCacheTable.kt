// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

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

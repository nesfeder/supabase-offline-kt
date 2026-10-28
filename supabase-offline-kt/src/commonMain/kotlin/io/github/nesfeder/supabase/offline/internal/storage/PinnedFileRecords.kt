// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.storage

import io.github.nesfeder.supabase.offline.internal.EngineJson
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.PINNED_FILE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import kotlinx.serialization.Serializable

/**
 * One pinned Storage object's record, as persisted.
 *
 * @property version the record's format, [PINNED_FILE_RECORD_VERSION].
 * @property bucketId the object's bucket.
 * @property path the object's path in the bucket.
 */
@Serializable
internal data class PinnedFileRecord(val version: Int, val bucketId: String, val path: String)

/** The format of the pinned-file records this build writes. */
internal const val PINNED_FILE_RECORD_VERSION: Int = 1

/**
 * The Storage objects pinned on the device, one row of [RESERVED_TABLE_NAME] each: its signature is
 * [PINNED_FILE_CACHE_KEY_PREFIX] and the object's cached-file key, its payload a
 * [PinnedFileRecord]. The row is the pin, whatever its payload holds, so a record another build
 * wrote keeps its object all the same. It keeps nothing in memory and has no lock: the file cache
 * writes the records under its own lock, and its eviction pass reads them under it.
 *
 * @param queryCacheDao the table the records are rows of.
 */
internal class PinnedFileRecords(private val queryCacheDao: QueryCacheDao) {

    /** The cached-file key of every pinned object, `<bucket>::<path>`. */
    suspend fun pinnedObjectKeys(): Set<String> =
        queryCacheDao
            .rowsWithCacheKeyPrefix(RESERVED_TABLE_NAME, PINNED_FILE_CACHE_KEY_PREFIX)
            .mapTo(mutableSetOf()) { row ->
                row.cacheKey.removePrefix(PINNED_FILE_CACHE_KEY_PREFIX)
            }

    /** Whether the object at [objectPath] in [bucketId] is pinned. */
    suspend fun isPinned(bucketId: String, objectPath: String): Boolean =
        queryCacheDao.hasRow(RESERVED_TABLE_NAME, signatureOf(bucketId, objectPath))

    /** Records the object at [objectPath] in [bucketId] as pinned; pinned already, it stays so. */
    suspend fun pin(bucketId: String, objectPath: String) {
        queryCacheDao.upsert(
            CachedQueryRow(
                table = RESERVED_TABLE_NAME,
                cacheKey = signatureOf(bucketId, objectPath),
                payload =
                    EngineJson.encodeToString(
                        PinnedFileRecord.serializer(),
                        PinnedFileRecord(PINNED_FILE_RECORD_VERSION, bucketId, objectPath),
                    ),
                updatedAt = 0L,
            )
        )
    }

    /** Deletes the pin of the object at [objectPath] in [bucketId], if it has one. */
    suspend fun unpin(bucketId: String, objectPath: String) {
        queryCacheDao.delete(RESERVED_TABLE_NAME, signatureOf(bucketId, objectPath))
    }

    /** Deletes every pin. */
    suspend fun unpinAll() {
        queryCacheDao.deleteWithCacheKeyPrefix(RESERVED_TABLE_NAME, PINNED_FILE_CACHE_KEY_PREFIX)
    }

    /**
     * Runs [block] in one write transaction of the database the records are in, which the file
     * cache's rows share: see [QueryCacheDao.runInTransaction].
     */
    suspend fun runInTransaction(block: suspend () -> Unit) {
        queryCacheDao.runInTransaction(block)
    }

    private fun signatureOf(bucketId: String, objectPath: String): String =
        PINNED_FILE_CACHE_KEY_PREFIX + cachedFileKeyOf(bucketId, objectPath)
}

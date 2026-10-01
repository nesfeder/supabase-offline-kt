// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

/**
 * [delegate], telling [onWrite] of each write once it has returned or failed: the table it changed,
 * or `null` for one that may change any table. The store reads through it, so every write it makes
 * to the object cache is counted.
 */
internal class PayloadWriteCountingDao(
    private val delegate: QueryCacheDao,
    private val onWrite: (table: String?) -> Unit,
) : QueryCacheDao by delegate {

    override suspend fun upsert(row: CachedQueryRow) = counted(row.table) { delegate.upsert(row) }

    override suspend fun delete(table: String, cacheKey: String) =
        counted(table) { delegate.delete(table, cacheKey) }

    override suspend fun deleteTable(table: String) = counted(table) { delegate.deleteTable(table) }

    override suspend fun deleteAll() = counted(null) { delegate.deleteAll() }

    override suspend fun deleteAllExceptTable(table: String) =
        counted(null) { delegate.deleteAllExceptTable(table) }

    override suspend fun deleteWithCacheKeyPrefix(table: String, prefix: String) =
        counted(table) { delegate.deleteWithCacheKeyPrefix(table, prefix) }

    override suspend fun deleteOrphanedQueryShapes(): Int =
        counted(null) { delegate.deleteOrphanedQueryShapes() }

    override suspend fun deleteOldestPayloadRows(limit: Int, keptCacheKeys: List<String>): Int =
        counted(null) { delegate.deleteOldestPayloadRows(limit, keptCacheKeys) }

    /** Runs [write], then counts it against [table] whether it returned or failed. */
    private inline fun <T> counted(table: String?, write: () -> T): T =
        try {
            write()
        } finally {
            onWrite(table)
        }
}

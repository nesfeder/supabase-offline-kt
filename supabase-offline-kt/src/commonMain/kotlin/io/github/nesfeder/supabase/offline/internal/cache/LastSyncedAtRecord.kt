// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.cache

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.LAST_SYNCED_AT_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * When a user's queue was last emptied by sending, as persisted.
 *
 * @property version the record's format, [LAST_SYNCED_AT_RECORD_VERSION].
 * @property owner the identity whose queue it was, as the mutation queue stores one: `user:<id>` or
 *   `anonymous`.
 * @property syncedAtMs when, in milliseconds since 1970, by the device's clock.
 */
@Serializable
internal data class LastSyncedAtRow(val version: Int, val owner: String, val syncedAtMs: Long)

/** The format of the last-synced record this build writes. */
internal const val LAST_SYNCED_AT_RECORD_VERSION: Int = 1

/**
 * When the current user's queue was last emptied by sending, [LastSyncedAt], and whose queue it
 * was.
 *
 * @property owner the identity, as the mutation queue stores one.
 * @property at when, to the millisecond.
 */
internal data class LastSyncedAt(val owner: String, val at: Instant)

/**
 * When the current user's queue was last emptied by sending, kept in one row of
 * [RESERVED_TABLE_NAME] beside the cache owner's, so a new client starts with it. Written each time
 * sending empties the queue, read once at open, and gone with `clearLocalData` and with a drop of
 * the cache for another identity ([LocalData]), whose transactions remove the row. It keeps nothing
 * in memory and takes no lock: the status it feeds holds the current value.
 */
internal class LastSyncedAtRecord(
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
) {

    /**
     * The record as the device holds it, or `null` for none; a record this build cannot read is
     * removed, as no identity's, and read as none.
     */
    suspend fun load(): LastSyncedAt? {
        val row = queryCacheDao.get(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY) ?: return null
        val record = recordOf(row.payload)
        if (record == null) queryCacheDao.delete(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)
        return record
    }

    /** Records [lastSyncedAt], in place of any record before it. */
    suspend fun save(lastSyncedAt: LastSyncedAt) {
        queryCacheDao.upsert(rowOf(lastSyncedAt))
    }

    /** Removes the record, whosever it is. */
    suspend fun clear() {
        queryCacheDao.delete(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)
    }

    /**
     * Removes the record unless it is [owner]'s, for the transaction that makes the cache
     * [owner]'s: the identity before it never shows its time to the next, while a time the new
     * identity recorded just before the drop stays. The caller holds the transaction.
     */
    suspend fun clearUnlessOwnedBy(owner: String) {
        val row = queryCacheDao.get(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY) ?: return
        if (recordOf(row.payload)?.owner != owner) {
            queryCacheDao.delete(RESERVED_TABLE_NAME, LAST_SYNCED_AT_CACHE_KEY)
        }
    }

    /** The row recording [lastSyncedAt]. */
    fun rowOf(lastSyncedAt: LastSyncedAt): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = LAST_SYNCED_AT_CACHE_KEY,
            payload =
                json.encodeToString(
                    LastSyncedAtRow.serializer(),
                    LastSyncedAtRow(
                        LAST_SYNCED_AT_RECORD_VERSION,
                        lastSyncedAt.owner,
                        lastSyncedAt.at.toEpochMilliseconds(),
                    ),
                ),
            updatedAt = 0L,
        )

    /** The record a payload holds, or `null` for one this build cannot read. */
    private fun recordOf(payload: String): LastSyncedAt? =
        try {
            json
                .decodeFromString(LastSyncedAtRow.serializer(), payload)
                .takeIf { record -> record.version == LAST_SYNCED_AT_RECORD_VERSION }
                ?.let { record ->
                    LastSyncedAt(record.owner, Instant.fromEpochMilliseconds(record.syncedAtMs))
                }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}

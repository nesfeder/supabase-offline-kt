// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.db

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Whose the cache is, as persisted.
 *
 * @property version the record's format, [CACHE_OWNER_RECORD_VERSION].
 * @property owner the identity, as the mutation queue stores one: `user:<id>` or `anonymous`.
 */
@Serializable internal data class CacheOwnerRow(val version: Int, val owner: String)

/** The format of the cache-owner record this build writes. */
internal const val CACHE_OWNER_RECORD_VERSION: Int = 1

/**
 * The identity the query cache and the Storage file cache belong to, kept in one row of
 * [RESERVED_TABLE_NAME] and read once per process. An install whose earlier build recorded none has
 * no owner yet. Its lock is always taken last and held over no other.
 */
internal class CacheOwnerRecord(private val objectDao: ObjectDao, private val json: Json) {

    /** The owner as last read or written; `null` until it has been read. */
    private val state = MutableStateFlow<Owner?>(null)

    /** How many times the cache was emptied, by a clear or a drop, in this process. */
    private val emptied = MutableStateFlow(0L)

    private val mutex = Mutex()

    /**
     * How many times the cache was emptied in this process. A payload a reader received under an
     * earlier count may be gone from the database; see [OfflineStore.overlaidPayloadFlow].
     */
    fun emptiedCount(): Long = emptied.value

    /**
     * The recorded owner, or `null` when none is recorded. When none is and [claimant] is given,
     * [claimant] is recorded first and returned: the first identity to use an unowned cache owns
     * it.
     */
    suspend fun owner(claimant: String? = null): String? {
        state.value?.let { known ->
            if (known.value != null || claimant == null) return known.value
        }
        return mutex.withLock {
            val recorded = loaded().value
            if (recorded != null || claimant == null) return@withLock recorded
            objectDao.upsert(rowOf(claimant))
            state.value = Owner(claimant)
            claimant
        }
    }

    /** The row recording [owner], for a caller that writes it in its own transaction. */
    fun rowOf(owner: String): ObjectRow =
        ObjectRow(
            table = RESERVED_TABLE_NAME,
            signature = CACHE_OWNER_SIGNATURE,
            payload =
                json.encodeToString(
                    CacheOwnerRow.serializer(),
                    CacheOwnerRow(CACHE_OWNER_RECORD_VERSION, owner),
                ),
            updatedAt = 0L,
        )

    /**
     * Records that a caller's committed transaction emptied the cache and left [owner] as its
     * owner, `null` for none. The count moves before the owner, so whoever sees the new owner sees
     * it too.
     */
    suspend fun recorded(owner: String?) {
        mutex.withLock {
            emptied.value += 1
            state.value = Owner(owner)
        }
    }

    /** The owner, read from the database the first time. Caller holds [mutex]. */
    private suspend fun loaded(): Owner =
        state.value
            ?: Owner(
                    objectDao.get(RESERVED_TABLE_NAME, CACHE_OWNER_SIGNATURE)?.let { row ->
                        ownerOf(row.payload)
                    }
                )
                .also { read -> state.value = read }

    /**
     * The owner a record names; [UNREADABLE_OWNER] for one this build cannot read, which then
     * belongs to no identity, so the next settled one drops the cache and records itself.
     */
    private fun ownerOf(payload: String): String =
        try {
            json
                .decodeFromString(CacheOwnerRow.serializer(), payload)
                .takeIf { record -> record.version == CACHE_OWNER_RECORD_VERSION }
                ?.owner ?: UNREADABLE_OWNER
        } catch (_: SerializationException) {
            UNREADABLE_OWNER
        } catch (_: IllegalArgumentException) {
            UNREADABLE_OWNER
        }

    /**
     * The owner as read or written.
     *
     * @property value the owner, or `null` for none recorded.
     */
    private data class Owner(val value: String?)

    private companion object {
        /** Stands for a record this build cannot read; no identity's stored value is empty. */
        const val UNREADABLE_OWNER = ""
    }
}

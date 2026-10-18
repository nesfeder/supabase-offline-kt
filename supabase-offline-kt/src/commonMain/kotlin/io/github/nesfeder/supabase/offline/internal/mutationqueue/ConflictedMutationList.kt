// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalSerializationApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.db.CONFLICTED_MUTATIONS_CACHE_KEY
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Version written into the conflicted list's reserved row, so a later format can tell this one. */
private const val CONFLICTED_MUTATION_LIST_FORMAT_VERSION: Int = 1

/**
 * One conflicted mutation as persisted: what its mutation row held, its bases included, so it can
 * be re-queued exactly, and what the server's row was when the replay found it moved.
 *
 * @property id The id it had in the queue.
 * @property operation [MutationRow.operation], in its stored form.
 * @property bodyJson [MutationRow.bodyJson], or empty when [bodyOmittedChars] is set.
 * @property owner The identity that made the write, or `null` when none was recorded.
 * @property baseVersion [MutationRow.baseVersion].
 * @property baseJson [MutationRow.baseJson].
 * @property serverRowJson The server's row as the replay read it, as JSON; `null` when it was gone
 *   or too long to keep.
 * @property serverVersion The server row's version; `null` when the row was gone.
 * @property changedOnServer The write's columns whose server value differs from the base.
 * @property conflictingColumns Of those, the ones the write also sets to another value.
 * @property message Why it was parked.
 * @property conflictedAtMs When it was parked, by the device clock.
 * @property bodyOmittedChars The body's length when it was too long to keep; such an entry cannot
 *   be re-queued.
 */
@Serializable
internal data class StoredConflict(
    val id: Long,
    val table: String,
    val operation: String,
    val bodyJson: String,
    val filterJson: String? = null,
    val createdAtMs: Long,
    val defaultToNull: Boolean? = null,
    val onConflict: String? = null,
    val ignoreDuplicates: Boolean? = null,
    val owner: String? = null,
    val baseVersion: String? = null,
    val baseJson: String? = null,
    val serverRowJson: String? = null,
    val serverVersion: String? = null,
    val changedOnServer: List<String> = emptyList(),
    val conflictingColumns: List<String> = emptyList(),
    val message: String,
    val conflictedAtMs: Long,
    val bodyOmittedChars: Int? = null,
)

/**
 * The persisted conflicted-mutation list, oldest first.
 *
 * @property droppedCount How many entries left the list to stay within its bounds.
 */
@Serializable
internal data class StoredConflictList(
    @EncodeDefault val version: Int = CONFLICTED_MUTATION_LIST_FORMAT_VERSION,
    val entries: List<StoredConflict> = emptyList(),
    val droppedCount: Long = 0,
)

/**
 * The mutation queue's conflicted list, kept in a reserved `object_cache` row within the rejected
 * list's bounds. Callers hold the queue lock and write the row this builds in their own transaction.
 */
internal class ConflictedMutationList(
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val logger: EngineLogger,
) {

    private val conflictedCache = MutableStateFlow<StoredConflictList?>(null)

    /** Every conflicted mutation, oldest first; an unreadable row is kept aside and reads empty. */
    suspend fun conflicts(): List<StoredConflict> = conflictedList().entries

    /** [conflicts], re-emitted whenever the list changes. */
    fun conflictsFlow(): Flow<List<StoredConflict>> = flow {
        conflictedList()
        emitAll(conflictedCache.filterNotNull().map { list -> list.entries })
    }

    /** Whether the list holds an entry with [id]; answered from memory once loaded. */
    suspend fun hasConflict(id: Long): Boolean =
        conflictedList().entries.any { entry -> entry.id == id }

    /**
     * Removes the entry with [id], if present, and writes the list. Caller holds the queue lock.
     *
     * @return The removed entry, or `null` when there is none.
     */
    suspend fun removeConflict(id: Long): StoredConflict? {
        val removed = conflictedList().entries.firstOrNull { entry -> entry.id == id } ?: return null
        val (row, list) = conflictedRowWith(removeId = id)
        queryCacheDao.upsert(row)
        conflictWritten(list)
        return removed
    }

    /**
     * The list's row with [add] added as the newest entry and the entry with [removeId] removed,
     * bounded as the rejected list is. Write it in the caller's transaction, then [conflictWritten].
     *
     * @return The row and the new list.
     */
    suspend fun conflictedRowWith(
        add: StoredConflict? = null,
        removeId: Long? = null,
    ): Pair<CachedQueryRow, StoredConflictList> {
        val current = conflictedList()
        var entries =
            current.entries.filterNot { entry -> entry.id == removeId || entry.id == add?.id }
        if (add != null) entries = entries + bounded(add)
        val sizes = entries.map(::charactersOf)
        var total = sizes.sum()
        var first = 0
        var droppedCount = current.droppedCount
        while (entries.size - first > MAX_REJECTED_MUTATIONS || total > MAX_REJECTED_LIST_CHARS) {
            val dropped = entries[first]
            logger.warning {
                "Conflicted list full: dropped conflicted ${dropped.operation} ${dropped.id} on ${dropped.table}"
            }
            total -= sizes[first]
            first++
            droppedCount++
        }
        val list = StoredConflictList(entries = entries.drop(first), droppedCount = droppedCount)
        return rowOf(list) to list
    }

    /** Records that the row holding [list] was committed. */
    fun conflictWritten(list: StoredConflictList) {
        conflictedCache.value = list
    }

    /** Records that a clear deleted the row, so the list reads empty without writing one back. */
    fun resetCaches() {
        conflictedCache.value = StoredConflictList()
    }

    private suspend fun conflictedList(): StoredConflictList {
        conflictedCache.value?.let {
            return it
        }
        val payload =
            queryCacheDao.get(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY)?.payload
        val loaded =
            if (payload == null) StoredConflictList()
            else decodeOrNull(StoredConflictList.serializer(), payload) ?: moveAsideUnreadable(payload)
        conflictedCache.compareAndSet(null, loaded)
        return conflictedCache.value ?: loaded
    }

    /** Keeps an unreadable payload under `<key>.unreadable.<ms>`, logs it, and starts afresh. */
    private suspend fun moveAsideUnreadable(payload: String): StoredConflictList {
        val fresh = StoredConflictList()
        val asideCacheKey = "$CONFLICTED_MUTATIONS_CACHE_KEY.unreadable.${nowMillis()}"
        queryCacheDao.upsert(
            CachedQueryRow(
                table = RESERVED_TABLE_NAME,
                cacheKey = asideCacheKey,
                payload = payload,
                updatedAt = nowMillis(),
            )
        )
        logger.error {
            "The mutation queue's $CONFLICTED_MUTATIONS_CACHE_KEY record could not be read; kept it aside as $asideCacheKey and started a new one"
        }
        queryCacheDao.upsert(rowOf(fresh))
        return fresh
    }

    private fun rowOf(list: StoredConflictList): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = CONFLICTED_MUTATIONS_CACHE_KEY,
            payload = json.encodeToString(StoredConflictList.serializer(), list),
            updatedAt = nowMillis(),
        )

    /** [entry] within the per-entry bounds: texts trimmed, an oversized body or row dropped. */
    private fun bounded(entry: StoredConflict): StoredConflict {
        var bounded = entry.copy(message = entry.message.take(MAX_REJECTED_TEXT_CHARS))
        if ((entry.serverRowJson?.length ?: 0) > MAX_REJECTED_BODY_CHARS)
            bounded = bounded.copy(serverRowJson = null)
        if ((entry.baseJson?.length ?: 0) > MAX_REJECTED_BODY_CHARS)
            bounded = bounded.copy(baseJson = null)
        if (entry.bodyJson.length > MAX_REJECTED_BODY_CHARS) {
            logger.warning {
                "Conflicted ${entry.operation} ${entry.id} on ${entry.table}: its body of " +
                    "${entry.bodyJson.length} characters is too long to keep, so it cannot be re-queued"
            }
            bounded = bounded.copy(bodyJson = "", bodyOmittedChars = entry.bodyJson.length)
        }
        return bounded
    }

    /** How many characters [entry] takes in the persisted list. */
    private fun charactersOf(entry: StoredConflict): Int =
        json.encodeToString(StoredConflict.serializer(), entry).length

    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. The payload is never logged.
            null
        }
}

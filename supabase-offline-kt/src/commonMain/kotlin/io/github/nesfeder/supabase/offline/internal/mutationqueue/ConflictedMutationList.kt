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
 * @property table [MutationRow.table].
 * @property operation [MutationRow.operation], in its stored form.
 * @property bodyJson [MutationRow.bodyJson], or empty when [bodyOmittedChars] is set.
 * @property filterJson [MutationRow.filterJson].
 * @property createdAtMs [MutationRow.createdAtMs].
 * @property defaultToNull [MutationRow.defaultToNull].
 * @property onConflict [MutationRow.onConflict].
 * @property ignoreDuplicates [MutationRow.ignoreDuplicates].
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
 * The conflicted list as held in memory: the persisted [list] and, beside each entry, how many
 * characters it takes in the row, measured once when it was added or loaded, as
 * [MeasuredRejectionList] keeps the rejected list's.
 *
 * @property list The list as persisted.
 * @property entrySizes Each entry's encoded length, one per entry of [list], in its order.
 */
internal data class MeasuredConflictList(val list: StoredConflictList, val entrySizes: List<Int>) {

    init {
        require(entrySizes.size == list.entries.size) { "One size per conflicted entry" }
    }

    /** [StoredConflictList.entries]. */
    val entries: List<StoredConflict>
        get() = list.entries

    /** [StoredConflictList.droppedCount]. */
    val droppedCount: Long
        get() = list.droppedCount
}

/**
 * The mutation queue's conflicted list, kept in a reserved `object_cache` row within the rejected
 * list's bounds. Callers hold the queue lock and write the row this builds in their own
 * transaction.
 *
 * @param entrySerializer How an entry is encoded to measure it; a test counts the encodings.
 */
internal class ConflictedMutationList(
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val logger: EngineLogger,
    private val entrySerializer: KSerializer<StoredConflict> = StoredConflict.serializer(),
) {

    /** The list as last read or written, measured; `null` until it is first read. */
    private val conflictedCache = MutableStateFlow<MeasuredConflictList?>(null)

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
        val removed =
            conflictedList().entries.firstOrNull { entry -> entry.id == id } ?: return null
        val (row, list) = conflictedRowWith(removeId = id)
        queryCacheDao.upsert(row)
        conflictWritten(list)
        return removed
    }

    /**
     * The list's row with [add] added as the newest entry and the entry with [removeId] removed,
     * bounded as the rejected list is. Only [add] is encoded to measure it; every other entry's
     * size is kept beside it. Write it in the caller's transaction, then [conflictWritten].
     *
     * @return The row and the new list, measured.
     */
    suspend fun conflictedRowWith(
        add: StoredConflict? = null,
        removeId: Long? = null,
    ): Pair<CachedQueryRow, MeasuredConflictList> {
        val current = conflictedList()
        val entries = ArrayList<StoredConflict>(current.entries.size + 1)
        val sizes = ArrayList<Int>(current.entries.size + 1)
        current.entries.forEachIndexed { index, entry ->
            if (entry.id == removeId || entry.id == add?.id) return@forEachIndexed
            entries += entry
            sizes += current.entrySizes[index]
        }
        if (add != null) {
            val entry = bounded(add)
            entries += entry
            sizes += charactersOf(entry)
        }
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
        val list =
            MeasuredConflictList(
                StoredConflictList(entries = entries.drop(first), droppedCount = droppedCount),
                sizes.drop(first),
            )
        return rowOf(list.list) to list
    }

    /** Records that the row holding [list] was committed. */
    fun conflictWritten(list: MeasuredConflictList) {
        conflictedCache.value = list
    }

    /** Records that a clear deleted the row, so the list reads empty without writing one back. */
    fun resetCaches() {
        conflictedCache.value = MeasuredConflictList(StoredConflictList(), emptyList())
    }

    /**
     * The list, read from its row the first time and measured then, entry by entry; an unreadable
     * row is kept aside.
     */
    private suspend fun conflictedList(): MeasuredConflictList {
        conflictedCache.value?.let {
            return it
        }
        val payload =
            queryCacheDao.get(RESERVED_TABLE_NAME, CONFLICTED_MUTATIONS_CACHE_KEY)?.payload
        val loaded =
            if (payload == null) StoredConflictList()
            else
                decodeOrNull(StoredConflictList.serializer(), payload)
                    ?: moveAsideUnreadable(payload)
        val measured = MeasuredConflictList(loaded, loaded.entries.map(::charactersOf))
        conflictedCache.compareAndSet(null, measured)
        return conflictedCache.value ?: measured
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

    /** The reserved row holding [list]. */
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
        json.encodeToString(entrySerializer, entry).length

    /** [payload] decoded with [serializer], or `null` when it does not decode. */
    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. The payload is never logged.
            null
        }
}

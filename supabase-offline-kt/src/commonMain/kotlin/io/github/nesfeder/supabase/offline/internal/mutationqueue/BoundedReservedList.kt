// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.cache.LocalData
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** What a bounded reserved list reads of each entry: its id, and what a log line names it by. */
internal interface ReservedListEntry {

    /** The id the write had in the queue. */
    val id: Long

    /** The write's table. */
    val table: String

    /** The write's operation, in its stored form. */
    val operation: String
}

/**
 * A bounded reserved list as persisted: its entries, oldest first, and how many entries left it to
 * stay within its bounds.
 */
internal interface StoredReservedList<E> {

    /** The entries, oldest first. */
    val entries: List<E>

    /** How many entries left the list to stay within its bounds. */
    val droppedCount: Long
}

/**
 * A bounded reserved list as held in memory: the persisted [list] and, beside each entry, how many
 * characters it takes in the row, measured once when it was added or loaded. The list's bounds are
 * checked from these sizes, so a change encodes its own entry once and the row once, never every
 * entry again.
 *
 * @property list The list as persisted.
 * @property entrySizes Each entry's encoded length, one per entry of [list], in its order.
 */
internal data class MeasuredList<E, L : StoredReservedList<E>>(
    val list: L,
    val entrySizes: List<Int>,
) {

    init {
        require(entrySizes.size == list.entries.size) { "One size per entry" }
    }

    /** [StoredReservedList.entries]. */
    val entries: List<E>
        get() = list.entries

    /** [StoredReservedList.droppedCount]. */
    val droppedCount: Long
        get() = list.droppedCount
}

/**
 * One of the mutation queue's lists kept in a reserved `object_cache` row (see
 * [RESERVED_TABLE_NAME]): one value read and written whole, rarely, bounded to
 * [MAX_REJECTED_MUTATIONS] entries and [MAX_REJECTED_LIST_CHARS] characters, the oldest going
 * first. It is read once, then held in memory with each entry's size beside it ([MeasuredList]): a
 * mirror of the row that every writer keeps in step, as [MutationQueue] describes.
 *
 * Callers hold the queue lock ([MutationQueue.holdingQueueLock]), and build the row here before
 * opening a transaction, so no transaction waits on a coroutine lock. An unreadable row is kept
 * aside, logged, and started afresh.
 *
 * Each list keeps its own stored format: its serializers encode its row and measure its entries,
 * and it says how an entry is trimmed to the per-entry bounds.
 */
internal abstract class BoundedReservedList<E : ReservedListEntry, L : StoredReservedList<E>>(
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    /** Where a full list, an entry trimmed and an unreadable row are reported. */
    protected val logger: EngineLogger,
    /** The cache key of the list's reserved row. */
    private val cacheKey: String,
    /** How the row's list is encoded and decoded. */
    private val listSerializer: KSerializer<L>,
    /** How an entry is encoded to measure it; a test counts the encodings. */
    private val entrySerializer: KSerializer<E>,
    /** What a log line calls an entry of the list, such as `rejected`. */
    private val kind: String,
) {

    /** The list as last read or written, measured; `null` until it is first read. */
    private val cache = MutableStateFlow<MeasuredList<E, L>?>(null)

    /** A new list of [entries] with [droppedCount], in the list's format version. */
    protected abstract fun listOf(entries: List<E>, droppedCount: Long): L

    /** [list] with [droppedCount] as its count of dropped entries, and nothing else changed. */
    protected abstract fun withDroppedCount(list: L, droppedCount: Long): L

    /** [entry] within the per-entry bounds: its long texts trimmed, an oversized value dropped. */
    protected abstract fun bounded(entry: E): E

    /** Every entry, oldest first; an unreadable row is kept aside and reads empty. */
    protected suspend fun entries(): List<E> = measuredList().entries

    /** [entries], re-emitted whenever the list changes. */
    protected fun entriesFlow(): Flow<List<E>> = flow {
        measuredList()
        emitAll(cache.filterNotNull().map { list -> list.entries })
    }

    /**
     * How many entries left the list to stay within its bounds since [resetDroppedCount].
     *
     * @return The count.
     */
    suspend fun droppedCount(): Long = measuredList().droppedCount

    /** Resets [droppedCount] to zero. Caller holds the queue lock. */
    suspend fun resetDroppedCount() {
        val current = measuredList()
        write(MeasuredList(withDroppedCount(current.list, 0), current.entrySizes))
    }

    /** Whether the list holds an entry with [id]; answered from memory once it is loaded. */
    protected suspend fun hasEntry(id: Long): Boolean =
        measuredList().entries.any { entry -> entry.id == id }

    /**
     * Removes the entry with [id], if present, and writes the list. Caller holds the queue lock.
     *
     * @return The removed entry, or `null` when the list has no entry with [id].
     */
    protected suspend fun removeEntry(id: Long): E? {
        val removed = measuredList().entries.firstOrNull { entry -> entry.id == id } ?: return null
        val (row, list) = rowWith(removeId = id)
        queryCacheDao.upsert(row)
        written(list)
        return removed
    }

    /**
     * The list's row with [add] added as the newest entry (replacing an earlier entry with the same
     * id) and the entries with [removeId] and [removeIds] removed, bounded to
     * [MAX_REJECTED_MUTATIONS] entries and [MAX_REJECTED_LIST_CHARS] characters. [add] is trimmed
     * to the per-entry bounds first; each entry that has to go is logged and counted. Only [add] is
     * encoded to measure it: every other entry's size is kept beside it. Write the row in the
     * caller's transaction, then commit it with [written]. Caller holds the queue lock.
     *
     * @return The row and the new list, measured.
     */
    protected suspend fun rowWith(
        add: E? = null,
        removeId: Long? = null,
        removeIds: Set<Long> = emptySet(),
    ): Pair<CachedQueryRow, MeasuredList<E, L>> {
        val current = measuredList()
        val entries = ArrayList<E>(current.entries.size + 1)
        val sizes = ArrayList<Int>(current.entries.size + 1)
        current.entries.forEachIndexed { index, entry ->
            if (entry.id == removeId || entry.id in removeIds || entry.id == add?.id)
                return@forEachIndexed
            entries += entry
            sizes += current.entrySizes[index]
        }
        if (add != null) {
            val entry = bounded(add)
            entries += entry
            sizes += charactersOf(entry)
        }
        // The oldest leave while the list is over either bound
        var total = sizes.sum()
        var first = 0
        var droppedCount = current.droppedCount
        while (entries.size - first > MAX_REJECTED_MUTATIONS || total > MAX_REJECTED_LIST_CHARS) {
            val dropped = entries[first]
            logger.warning {
                "${kind.replaceFirstChar { it.uppercaseChar() }} list full: dropped $kind " +
                    "${dropped.operation} ${dropped.id} on ${dropped.table}"
            }
            total -= sizes[first]
            first++
            droppedCount++
        }
        val list = MeasuredList(listOf(entries.drop(first), droppedCount), sizes.drop(first))
        return rowOf(list.list) to list
    }

    /**
     * Records that the row holding [list] was committed.
     *
     * @param list The list the committed row holds, as [rowWith] measured it.
     */
    protected fun written(list: MeasuredList<E, L>) {
        cache.value = list
    }

    /**
     * Records that every row is gone and the queue empty; called once [LocalData.clearLocalData]
     * has deleted them. The list then reads empty without a row, so no reader writes one back.
     */
    fun resetCaches() {
        cache.value = MeasuredList(listOf(emptyList(), 0), emptyList())
    }

    /** The list, read from its row the first time and measured then, entry by entry. */
    private suspend fun measuredList(): MeasuredList<E, L> {
        cache.value?.let {
            return it
        }
        val payload = queryCacheDao.get(RESERVED_TABLE_NAME, cacheKey)?.payload
        val loaded =
            if (payload == null) {
                listOf(emptyList(), 0)
            } else {
                decodeOrNull(listSerializer, payload) ?: moveAsideUnreadable(payload)
            }
        // Each entry is measured once, here or when it is added
        val measured = MeasuredList(loaded, loaded.entries.map(::charactersOf))
        cache.compareAndSet(null, measured)
        return cache.value ?: measured
    }

    /**
     * Keeps an unreadable payload under `<cacheKey>.unreadable.<ms>`, logs it at ERROR, and starts
     * a new list.
     */
    private suspend fun moveAsideUnreadable(payload: String): L {
        val fresh = listOf(emptyList(), 0)
        val asideCacheKey = "$cacheKey.unreadable.${nowMillis()}"
        queryCacheDao.upsert(reservedRow(asideCacheKey, payload))
        logger.error {
            "The mutation queue's $cacheKey record could not be read; kept it aside as $asideCacheKey and started a new one"
        }
        queryCacheDao.upsert(rowOf(fresh))
        return fresh
    }

    private suspend fun write(list: MeasuredList<E, L>) {
        queryCacheDao.upsert(rowOf(list.list))
        written(list)
    }

    /** The reserved row holding [list]. */
    private fun rowOf(list: L): CachedQueryRow =
        reservedRow(cacheKey, json.encodeToString(listSerializer, list))

    private fun reservedRow(cacheKey: String, payload: String): CachedQueryRow =
        CachedQueryRow(
            table = RESERVED_TABLE_NAME,
            cacheKey = cacheKey,
            payload = payload,
            updatedAt = nowMillis(),
        )

    /** How many characters [entry] takes in the persisted list. */
    private fun charactersOf(entry: E): Int = json.encodeToString(entrySerializer, entry).length

    /** [payload] decoded with [serializer], or `null` when it does not decode. */
    private fun <T> decodeOrNull(serializer: KSerializer<T>, payload: String): T? =
        try {
            json.decodeFromString(serializer, payload)
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException. The payload is never logged.
            null
        }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.internal.db.CachedFileDao
import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadLength
import io.github.nesfeder.supabase.offline.internal.db.PendingGuardedMutation
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_CACHE_KEY_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.SyncedMutationRef
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Base directory the in-memory client reports; nothing is ever written under it. */
internal const val IN_MEMORY_BASE_DIRECTORY: String = "/in-memory/offline"

/**
 * In-memory [QueryCacheDao] mirroring Room's per-table invalidation: any write fans out to
 * observers.
 *
 * Safe across threads, as Room's DAOs are. The rows are one immutable map: reads take it whole,
 * writes replace it one at a time under [writeLock], and observers are set after the lock is let
 * go. `open`, with its state `protected`, so test doubles can add recording and scripting hooks.
 */
internal open class InMemoryQueryCacheDao : QueryCacheDao {
    /** Cached payloads keyed by `(table, cacheKey)`. Replace it only under [writeLock]. */
    protected val rows: MutableStateFlow<Map<Pair<String, String>, CachedQueryRow>> =
        MutableStateFlow(emptyMap())

    /**
     * Each row's `rowid`, as SQLite gives them: one past the largest in the table, so an emptied
     * table hands them out again. Replace it only under [writeLock].
     */
    protected val rowIds: MutableStateFlow<Map<Pair<String, String>, Long>> =
        MutableStateFlow(emptyMap())

    /** One observable slot per observed `(table, cacheKey)`. */
    protected val observers:
        MutableStateFlow<Map<Pair<String, String>, MutableStateFlow<CachedQueryRow?>>> =
        MutableStateFlow(emptyMap())

    /** Serializes the writes, so a slow one is never starved by fast ones. */
    private val writeLock = Mutex()

    override suspend fun get(table: String, cacheKey: String): CachedQueryRow? =
        rows.value[table to cacheKey]

    override fun observe(table: String, cacheKey: String): Flow<CachedQueryRow?> {
        val key = table to cacheKey
        observers.update { current ->
            if (key in current) current else current + (key to MutableStateFlow(rows.value[key]))
        }
        val slot = observers.value.getValue(key)
        // A write that read the observers before this one joined them did not set it
        publish(key, slot)
        return slot.asStateFlow().map { it }
    }

    override suspend fun hasRow(table: String, cacheKey: String): Boolean =
        rows.value.containsKey(table to cacheKey)

    override suspend fun allForTable(table: String): List<CachedQueryRow> =
        rows.value.values.filter { it.table == table }

    override suspend fun maxSyncedThrough(): Long =
        rows.value.values.maxOfOrNull { it.syncedThrough } ?: 0L

    override suspend fun minSyncedThroughForTable(table: String): Long? =
        rows.value.values.filter { it.table == table }.minOfOrNull { it.syncedThrough }

    override suspend fun cacheKeysForTable(table: String): List<String> =
        rows.value.values.filter { it.table == table }.map { it.cacheKey }

    override suspend fun rowsWithCacheKeyPrefix(
        table: String,
        prefix: String,
    ): List<CachedQueryRow> =
        rows.value.values.filter { it.table == table && it.cacheKey.startsWith(prefix) }

    override suspend fun deleteWithCacheKeyPrefix(table: String, prefix: String) {
        removeWhere { (rowTable, cacheKey), _ ->
            rowTable == table && cacheKey.startsWith(prefix)
        }
    }

    override suspend fun deleteOrphanedQueryShapes(): Int = removeChosen { current ->
        val payloads =
            current.values
                .filter { it.table != RESERVED_TABLE_NAME }
                .mapTo(mutableSetOf()) { "${it.table}\u001F${it.cacheKey}" }
        current.keys
            .filter { (table, cacheKey) ->
                table == RESERVED_TABLE_NAME &&
                    cacheKey.startsWith(QUERY_SHAPE_CACHE_KEY_PREFIX) &&
                    cacheKey.removePrefix(QUERY_SHAPE_CACHE_KEY_PREFIX) !in payloads
            }
            .toSet()
    }

    override suspend fun upsert(row: CachedQueryRow) {
        val key = row.table to row.cacheKey
        writeLock.withLock {
            journal(listOf(key))
            rows.update { current -> current + (key to row) }
            // REPLACE deletes the row it replaces and inserts anew, under a new rowid
            assignRowId(key)
        }
        observers.value.forEach { (observed, slot) ->
            if (observed.first == row.table) publish(observed, slot)
        }
    }

    /** Gives [key] the `rowid` an insert gets. Caller holds [writeLock]. */
    protected fun assignRowId(key: Pair<String, String>) {
        rowIds.update { ids ->
            val others = ids - key
            others + (key to (others.values.maxOrNull() ?: 0L) + 1L)
        }
    }

    override suspend fun updatePayload(
        table: String,
        cacheKey: String,
        payload: String,
        updatedAt: Long,
    ): Int {
        val key = table to cacheKey
        var changed = false
        writeLock.withLock {
            journal(listOf(key))
            rows.update { current ->
                val existing = current[key] ?: return@update current
                changed = true
                current + (key to existing.copy(payload = payload, updatedAt = updatedAt))
            }
        }
        if (!changed) return 0
        observers.value.forEach { (observed, slot) ->
            if (observed.first == table) publish(observed, slot)
        }
        return 1
    }

    override suspend fun delete(table: String, cacheKey: String) {
        removeWhere { key, _ -> key == (table to cacheKey) }
    }

    override suspend fun deleteTable(table: String) {
        removeWhere { (rowTable, _), _ -> rowTable == table }
    }

    override suspend fun deleteAll() {
        removeWhere { _, _ -> true }
    }

    override suspend fun deleteAllExceptTable(table: String) {
        removeWhere { (rowTable, _), _ -> rowTable != table }
    }

    override suspend fun totalPayloadBytes(): Long =
        rows.value.values
            .filter { it.table != RESERVED_TABLE_NAME }
            .sumOf { it.payload.length.toLong() }

    override suspend fun payloadLengthOf(table: String, cacheKey: String): Long? =
        rows.value[table to cacheKey]?.payload?.length?.toLong()

    override suspend fun payloadLengthsOldestFirst(
        keptCacheKeys: List<String>
    ): List<PayloadLength> {
        val kept = keptCacheKeys.toSet()
        val ids = rowIds.value
        return rows.value.entries
            .filter { (_, row) -> row.table != RESERVED_TABLE_NAME }
            .sortedBy { (_, row) -> fetchedAtOf(row.updatedAt) }
            .map { (key, row) ->
                PayloadLength(
                    table = row.table,
                    rowId = ids[key] ?: error("A row without a rowid: $key"),
                    fetchedAt = fetchedAtOf(row.updatedAt),
                    length = row.payload.length.toLong(),
                    kept = row.cacheKey in kept,
                )
            }
    }

    override suspend fun deletePayloads(
        table: String,
        rowIds: List<Long>,
        fetchedAtOrBefore: Long,
    ): Int {
        val named = rowIds.toSet()
        return removeChosen { current ->
            val ids = this.rowIds.value
            current
                .filter { (key, row) ->
                    row.table == table &&
                        row.table != RESERVED_TABLE_NAME &&
                        ids[key] in named &&
                        fetchedAtOf(row.updatedAt) <= fetchedAtOrBefore
                }
                .keys
        }
    }

    /**
     * Runs [block] with a journal of the rows its coroutine changes, and puts them back as they
     * were when it fails, as a rolled-back transaction leaves them, whether it threw or was
     * canceled; the observers of the rows put back hear of it. Rows other coroutines change
     * meanwhile are left alone. ([InMemoryMutationDao]'s transaction keeps no journal: a test of a
     * clear that fails part-way fails its first statement.)
     */
    override suspend fun runInTransaction(block: suspend () -> Unit) {
        val journal = Journal()
        try {
            withContext(journal) { block() }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { rollBack(journal) }
            throw failure
        }
    }

    /** What the rows a transaction's coroutine changes held before it; see [runInTransaction]. */
    private class Journal : AbstractCoroutineContextElement(Journal) {
        companion object Key : CoroutineContext.Key<Journal>

        val rowsBefore = mutableMapOf<Pair<String, String>, CachedQueryRow?>()
        val rowIdsBefore = mutableMapOf<Pair<String, String>, Long?>()
    }

    /**
     * Records in the running transaction's journal, when there is one, what [keys] hold now, once
     * per key. Caller holds [writeLock].
     */
    private suspend fun journal(keys: Iterable<Pair<String, String>>) {
        val journal = currentCoroutineContext()[Journal] ?: return
        val current = rows.value
        val ids = rowIds.value
        keys.forEach { key ->
            if (key in journal.rowsBefore) return@forEach
            journal.rowsBefore[key] = current[key]
            journal.rowIdsBefore[key] = ids[key]
        }
    }

    /** Puts the rows [journal] recorded back as they were, and tells their observers. */
    private suspend fun rollBack(journal: Journal) {
        val undone = writeLock.withLock {
            rows.update { current ->
                journal.rowsBefore.entries.fold(current) { rows, (key, before) ->
                    if (before == null) rows - key else rows + (key to before)
                }
            }
            rowIds.update { current ->
                journal.rowIdsBefore.entries.fold(current) { ids, (key, before) ->
                    if (before == null) ids - key else ids + (key to before)
                }
            }
            journal.rowsBefore.keys.toList()
        }
        undone.forEach { key -> observers.value[key]?.let { slot -> publish(key, slot) } }
    }

    /** Removes the rows [matches] accepts, as one write, and tells their observers. */
    private suspend fun removeWhere(matches: (Pair<String, String>, CachedQueryRow) -> Boolean) {
        removeChosen { current -> current.filter { (key, row) -> matches(key, row) }.keys }
    }

    /**
     * Removes the keys [choose] picks from the current rows, as one write, and tells their
     * observers.
     *
     * @return how many rows it removed.
     */
    private suspend fun removeChosen(
        choose: (Map<Pair<String, String>, CachedQueryRow>) -> Set<Pair<String, String>>
    ): Int {
        val removed = writeLock.withLock {
            val chosen = choose(rows.value)
            journal(chosen)
            rows.update { current -> current - chosen }
            rowIds.update { current -> current - chosen }
            chosen
        }
        removed.forEach { key -> observers.value[key]?.let { slot -> publish(key, slot) } }
        return removed.size
    }

    /**
     * Sets [slot] to the row at [key], again until the row read after the set is the one set: a
     * writer that set an older row after a newer write had published reads the newer row back.
     */
    private fun publish(key: Pair<String, String>, slot: MutableStateFlow<CachedQueryRow?>) {
        do {
            val row = rows.value[key]
            slot.value = row
        } while (rows.value[key] != row)
    }
}

/**
 * In-memory [CachedFileDao] keyed by [CachedFileRow.key]. Safe across threads: reads take one
 * immutable map whole, and writes replace it one at a time under a lock.
 */
internal open class InMemoryCachedFileDao : CachedFileDao {
    /** Storage-object rows keyed by [CachedFileRow.key]. Replace it only under [writeLock]. */
    protected val rows: MutableStateFlow<Map<String, CachedFileRow>> = MutableStateFlow(emptyMap())

    /** Serializes the writes, so a slow one is never starved by fast ones. */
    private val writeLock = Mutex()

    override suspend fun upsert(row: CachedFileRow) {
        write { current -> current + (row.key to row) }
    }

    override suspend fun getByKey(key: String): CachedFileRow? = rows.value[key]

    override suspend fun delete(key: String) {
        write { current -> current - key }
    }

    override suspend fun delete(bucketId: String, path: String) {
        write { current ->
            current.filterValues { row -> !(row.bucketId == bucketId && row.path == path) }
        }
    }

    override suspend fun deleteAll() {
        write { emptyMap() }
    }

    override suspend fun forObject(
        bucketId: String,
        path: String,
        variantPrefix: String,
        variantBound: String,
    ): List<CachedFileRow> =
        rows.value.values.filter {
            it.bucketId == bucketId &&
                (it.path == path || (it.path >= variantPrefix && it.path < variantBound))
        }

    override suspend fun all(): List<CachedFileRow> = rows.value.values.toList()

    override suspend fun totalSizeBytes(): Long = rows.value.values.sumOf { it.sizeBytes ?: 0L }

    /** Replaces the rows with what [change] makes of them, one write at a time. */
    private suspend fun write(change: (Map<String, CachedFileRow>) -> Map<String, CachedFileRow>) {
        writeLock.withLock { rows.update(change) }
    }
}

/**
 * In-memory [MutationDao] mirroring Room's `AUTOINCREMENT` id (never reused, even after a delete)
 * and its reactive queue, ordered by id as the Room queries are.
 */
internal open class InMemoryMutationDao : MutationDao {
    /** Every queued row, pending and synced, in insertion order. */
    protected val mutations: MutableStateFlow<List<MutationRow>> = MutableStateFlow(emptyList())
    private var nextId: Long = 1L

    override suspend fun insert(row: MutationRow): Long {
        val stored = if (row.id == 0L) row.copy(id = nextId++) else row
        if (stored.id >= nextId) nextId = stored.id + 1
        mutations.update { current -> current.filterNot { it.id == stored.id } + stored }
        return stored.id
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table && it.syncedAtMs == null }.sortedBy { it.id }

    override suspend fun pendingAll(): List<MutationRow> =
        mutations.value.filter { it.syncedAtMs == null }.sortedBy { it.id }

    override suspend fun countPending(): Int = mutations.value.count { it.syncedAtMs == null }

    override suspend fun countPendingForTable(table: String): Int =
        mutations.value.count { it.table == table && it.syncedAtMs == null }

    override suspend fun pendingGuardedAfter(
        table: String,
        afterId: Long,
        owner: String?,
    ): List<PendingGuardedMutation> =
        mutations.value
            .filter {
                it.table == table &&
                    it.syncedAtMs == null &&
                    it.id > afterId &&
                    it.baseVersion != null &&
                    it.owner == owner
            }
            .sortedBy { it.id }
            .map { PendingGuardedMutation(it.id, it.filterJson, it.baseJson, it.owner) }

    override fun pendingAllFlow(): Flow<List<MutationRow>> = mutations.map { all ->
        all.filter { it.syncedAtMs == null }.sortedBy { it.id }
    }

    override suspend fun pendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
    ): List<MutationRow> =
        mutations.value.filter { it.isPendingOf(owner, includeOwnerless) }.sortedBy { it.id }

    override suspend fun pendingOwnedByInTable(
        owner: String,
        includeOwnerless: Boolean,
        table: String,
    ): List<MutationRow> =
        mutations.value
            .filter { it.table == table && it.isPendingOf(owner, includeOwnerless) }
            .sortedBy { it.id }

    override suspend fun countPendingOwnedBy(owner: String, includeOwnerless: Boolean): Int =
        mutations.value.count { it.isPendingOf(owner, includeOwnerless) }

    override suspend fun countPendingOwnedByInTable(
        owner: String,
        includeOwnerless: Boolean,
        table: String,
    ): Int = mutations.value.count { it.table == table && it.isPendingOf(owner, includeOwnerless) }

    override suspend fun hasPendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
        beforeId: Long,
    ): Boolean = mutations.value.any { it.id < beforeId && it.isPendingOf(owner, includeOwnerless) }

    override suspend fun setOwner(id: Long, owner: String) {
        mutations.update { current ->
            current.map { if (it.id == id) it.copy(owner = owner) else it }
        }
    }

    override suspend fun adoptOwnerless(owner: String): Int {
        val adopted = mutations.value.count { it.owner == null && it.syncedAtMs == null }
        if (adopted > 0) {
            mutations.update { current ->
                current.map {
                    if (it.owner == null && it.syncedAtMs == null) it.copy(owner = owner) else it
                }
            }
        }
        return adopted
    }

    /** Whether this row is pending and [owner]'s, or ownerless when [includeOwnerless]. */
    private fun MutationRow.isPendingOf(owner: String, includeOwnerless: Boolean): Boolean =
        syncedAtMs == null && (this.owner == owner || (includeOwnerless && this.owner == null))

    override suspend fun isPending(id: Long): Boolean =
        mutations.value.any { it.id == id && it.syncedAtMs == null }

    override suspend fun byId(id: Long): MutationRow? = mutations.value.firstOrNull { it.id == id }

    override suspend fun syncedForTable(table: String): List<SyncedMutationRef> =
        mutations.value
            .filter { it.table == table && it.syncedAtMs != null }
            .sortedWith(compareBy({ it.syncSequence ?: Long.MIN_VALUE }, { it.id }))
            .map { SyncedMutationRef(it.id, it.syncSequence) }

    override suspend fun tablesWithSyncedMutations(): List<String> =
        mutations.value.filter { it.syncedAtMs != null }.map { it.table }.distinct()

    override suspend fun maxSyncSequence(): Long =
        mutations.value.maxOfOrNull { it.syncSequence ?: 0L } ?: 0L

    override suspend fun recordSchemaCacheMiss(id: Long) {
        mutations.update { current ->
            current.map {
                if (it.id == id) it.copy(schemaCacheMisses = it.schemaCacheMisses + 1) else it
            }
        }
    }

    override suspend fun countAll(): Int = mutations.value.size

    override suspend fun deleteById(id: Long) {
        mutations.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun allForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table }.sortedBy { it.id }

    override suspend fun markSynced(id: Long, syncedAtMs: Long, syncSequence: Long) {
        mutations.update { current ->
            current.map {
                if (it.id == id)
                    it.copy(
                        syncedAtMs = syncedAtMs,
                        syncSequence = syncSequence,
                        sendUnconfirmed = false,
                    )
                else it
            }
        }
    }

    override suspend fun markSendUnconfirmed(id: Long): Int {
        var marked = 0
        mutations.update { current ->
            marked = 0
            current.map {
                if (it.id == id && !it.sendUnconfirmed) {
                    marked = 1
                    it.copy(sendUnconfirmed = true)
                } else {
                    it
                }
            }
        }
        return marked
    }

    override suspend fun updateBase(id: Long, baseVersion: String?, baseJson: String?) {
        mutations.update { current ->
            current.map {
                if (it.id == id) it.copy(baseVersion = baseVersion, baseJson = baseJson) else it
            }
        }
    }

    override suspend fun clearSendUnconfirmed(id: Long) {
        mutations.update { current ->
            current.map { if (it.id == id) it.copy(sendUnconfirmed = false) else it }
        }
    }

    override suspend fun unconfirmedSendIds(): List<Long> =
        mutations.value.filter { it.sendUnconfirmed }.map { it.id }

    override suspend fun deleteAll() {
        mutations.value = emptyList()
    }
}

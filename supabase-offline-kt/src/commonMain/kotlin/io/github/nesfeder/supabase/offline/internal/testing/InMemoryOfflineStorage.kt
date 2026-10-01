// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.db.QUERY_SHAPE_SIGNATURE_PREFIX
import io.github.nesfeder.supabase.offline.internal.db.RESERVED_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.StorageDao
import io.github.nesfeder.supabase.offline.internal.db.StorageRow
import io.github.nesfeder.supabase.offline.internal.db.fetchedAtOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

/** Base directory the in-memory client reports; nothing is ever written under it. */
internal const val IN_MEMORY_BASE_DIRECTORY: String = "/in-memory/offline"

/** [DbPathProvider] for storage that has no file: reports a fixed path that is never opened. */
internal object InMemoryDbPathProvider : DbPathProvider {
    override fun dbPath(): Path = "$IN_MEMORY_BASE_DIRECTORY/offline.db".toPath()
}

/**
 * In-memory [ObjectDao] mirroring Room's per-table invalidation: any write fans out to observers.
 *
 * Safe across threads, as Room's DAOs are. The rows are one immutable map: reads take it whole,
 * writes replace it one at a time under [writeLock], and observers are set after the lock is let
 * go. `open`, with its state `protected`, so test doubles can add recording and scripting hooks.
 */
internal open class InMemoryObjectDao : ObjectDao {
    /** Cached payloads keyed by `(table, signature)`. Replace it only under [writeLock]. */
    protected val rows: MutableStateFlow<Map<Pair<String, String>, ObjectRow>> =
        MutableStateFlow(emptyMap())

    /** One observable slot per observed `(table, signature)`. */
    protected val observers:
        MutableStateFlow<Map<Pair<String, String>, MutableStateFlow<ObjectRow?>>> =
        MutableStateFlow(emptyMap())

    /** Serializes the writes, so a slow one is never starved by fast ones. */
    private val writeLock = Mutex()

    override suspend fun get(table: String, signature: String): ObjectRow? =
        rows.value[table to signature]

    override fun observe(table: String, signature: String): Flow<ObjectRow?> {
        val key = table to signature
        observers.update { current ->
            if (key in current) current else current + (key to MutableStateFlow(rows.value[key]))
        }
        val slot = observers.value.getValue(key)
        // A write that read the observers before this one joined them did not set it
        publish(key, slot)
        return slot.asStateFlow().map { it }
    }

    override suspend fun allForTable(table: String): List<ObjectRow> =
        rows.value.values.filter { it.table == table }

    override suspend fun maxSyncedThrough(): Long =
        rows.value.values.maxOfOrNull { it.syncedThrough } ?: 0L

    override suspend fun minSyncedThroughForTable(table: String): Long? =
        rows.value.values.filter { it.table == table }.minOfOrNull { it.syncedThrough }

    override suspend fun signaturesForTable(table: String): List<String> =
        rows.value.values.filter { it.table == table }.map { it.signature }

    override suspend fun rowsWithSignaturePrefix(table: String, prefix: String): List<ObjectRow> =
        rows.value.values.filter { it.table == table && it.signature.startsWith(prefix) }

    override suspend fun deleteWithSignaturePrefix(table: String, prefix: String) {
        removeWhere { (rowTable, signature), _ ->
            rowTable == table && signature.startsWith(prefix)
        }
    }

    override suspend fun deleteOrphanedQueryShapes(): Int = removeChosen { current ->
        val payloads =
            current.values
                .filter { it.table != RESERVED_TABLE_NAME }
                .mapTo(mutableSetOf()) { "${it.table}\u001F${it.signature}" }
        current.keys
            .filter { (table, signature) ->
                table == RESERVED_TABLE_NAME &&
                    signature.startsWith(QUERY_SHAPE_SIGNATURE_PREFIX) &&
                    signature.removePrefix(QUERY_SHAPE_SIGNATURE_PREFIX) !in payloads
            }
            .toSet()
    }

    override suspend fun upsert(row: ObjectRow) {
        writeLock.withLock {
            rows.update { current -> current + ((row.table to row.signature) to row) }
        }
        observers.value.forEach { (key, slot) -> if (key.first == row.table) publish(key, slot) }
    }

    override suspend fun delete(table: String, signature: String) {
        removeWhere { key, _ -> key == (table to signature) }
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

    override suspend fun deleteOldestPayloadRows(limit: Int, keptSignatures: List<String>): Int {
        if (limit <= 0) return 0
        return removeChosen { current ->
            current.values
                .filter { it.table != RESERVED_TABLE_NAME && it.signature !in keptSignatures }
                .sortedBy { fetchedAtOf(it.updatedAt) }
                .take(limit)
                .mapTo(mutableSetOf()) { it.table to it.signature }
        }
    }

    /** Removes the rows [matches] accepts, as one write, and tells their observers. */
    private suspend fun removeWhere(matches: (Pair<String, String>, ObjectRow) -> Boolean) {
        removeChosen { current -> current.filter { (key, row) -> matches(key, row) }.keys }
    }

    /**
     * Removes the keys [choose] picks from the current rows, as one write, and tells their
     * observers.
     *
     * @return how many rows it removed.
     */
    private suspend fun removeChosen(
        choose: (Map<Pair<String, String>, ObjectRow>) -> Set<Pair<String, String>>
    ): Int {
        val removed = writeLock.withLock {
            val chosen = choose(rows.value)
            rows.update { current -> current - chosen }
            chosen
        }
        removed.forEach { key -> observers.value[key]?.let { slot -> publish(key, slot) } }
        return removed.size
    }

    /**
     * Sets [slot] to the row at [key], again until the row read after the set is the one set: a
     * writer that set an older row after a newer write had published reads the newer row back.
     */
    private fun publish(key: Pair<String, String>, slot: MutableStateFlow<ObjectRow?>) {
        do {
            val row = rows.value[key]
            slot.value = row
        } while (rows.value[key] != row)
    }
}

/**
 * In-memory [StorageDao] keyed by [StorageRow.key]. Safe across threads: reads take one immutable
 * map whole, and writes replace it one at a time under a lock.
 */
internal open class InMemoryStorageDao : StorageDao {
    /** Storage-object rows keyed by [StorageRow.key]. Replace it only under [writeLock]. */
    protected val rows: MutableStateFlow<Map<String, StorageRow>> = MutableStateFlow(emptyMap())

    /** Serializes the writes, so a slow one is never starved by fast ones. */
    private val writeLock = Mutex()

    override suspend fun upsert(row: StorageRow) {
        write { current -> current + (row.key to row) }
    }

    override suspend fun getByKey(key: String): StorageRow? = rows.value[key]

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
    ): List<StorageRow> =
        rows.value.values.filter {
            it.bucketId == bucketId && (it.path == path || it.path.startsWith(variantPrefix))
        }

    override suspend fun all(): List<StorageRow> = rows.value.values.toList()

    override suspend fun totalSizeBytes(): Long = rows.value.values.sumOf { it.sizeBytes ?: 0L }

    /** Replaces the rows with what [change] makes of them, one write at a time. */
    private suspend fun write(change: (Map<String, StorageRow>) -> Map<String, StorageRow>) {
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

    override suspend fun hasPendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
        beforeId: Long,
    ): Boolean = mutations.value.any { it.id < beforeId && it.isPendingOf(owner, includeOwnerless) }

    override suspend fun hasPendingBefore(beforeId: Long): Boolean =
        mutations.value.any { it.syncedAtMs == null && it.id < beforeId }

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

    override suspend fun syncedForTable(table: String): List<MutationRow> =
        mutations.value
            .filter { it.table == table && it.syncedAtMs != null }
            .sortedWith(compareBy({ it.syncSequence ?: Long.MIN_VALUE }, { it.id }))

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

    override suspend fun countPending(): Int = mutations.value.count { it.syncedAtMs == null }

    override fun countPendingFlow(): Flow<Int> = mutations.map { all ->
        all.count { it.syncedAtMs == null }
    }

    override suspend fun countForTable(table: String): Int =
        mutations.value.count { it.table == table }

    override suspend fun countPendingForTable(table: String): Int =
        mutations.value.count { it.table == table && it.syncedAtMs == null }

    override fun countPendingForTableFlow(table: String): Flow<Int> = mutations.map { all ->
        all.count { it.table == table && it.syncedAtMs == null }
    }

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

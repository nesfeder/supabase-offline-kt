package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.db.StorageDao
import io.github.nesfeder.supabase.offline.internal.db.StorageRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
 * `open`, with its state `protected`, so test doubles can extend it with recording and scripting
 * hooks while every in-memory store — the testing artifact's and the engine's own tests' — runs on
 * this one implementation.
 */
internal open class InMemoryObjectDao : ObjectDao {
    /** Cached payloads keyed by `(table, signature)`. */
    protected val rows: MutableMap<Pair<String, String>, ObjectRow> = mutableMapOf()

    /** One observable slot per observed `(table, signature)`. */
    protected val observers: MutableMap<Pair<String, String>, MutableStateFlow<ObjectRow?>> = mutableMapOf()

    override suspend fun get(table: String, signature: String): ObjectRow? = rows[table to signature]

    override fun observe(table: String, signature: String): Flow<ObjectRow?> =
        observers
            .getOrPut(table to signature) { MutableStateFlow(rows[table to signature]) }
            .asStateFlow()
            .map { it }

    override suspend fun allForTable(table: String): List<ObjectRow> =
        rows.values.filter { it.table == table }

    override suspend fun upsert(row: ObjectRow) {
        rows[row.table to row.signature] = row
        observers
            .filterKeys { it.first == row.table }
            .forEach { (key, flow) -> flow.value = rows[key] }
    }

    override suspend fun delete(table: String, signature: String) {
        rows.remove(table to signature)
        observers[table to signature]?.value = null
    }

    override suspend fun deleteTable(table: String) {
        rows.keys.filter { it.first == table }.toList().forEach {
            rows.remove(it)
            observers[it]?.value = null
        }
    }

    override suspend fun deleteAll() {
        val keys = rows.keys.toList()
        rows.clear()
        keys.forEach { observers[it]?.value = null }
    }

    override suspend fun totalPayloadBytes(): Long = rows.values.sumOf { it.payload.length.toLong() }

    override suspend fun deleteOldestPayloadRows(limit: Int): Int {
        if (limit <= 0) return 0
        val oldest = rows.values.sortedBy { it.updatedAt }.take(limit)
        oldest.forEach { row ->
            rows.remove(row.table to row.signature)
            observers[row.table to row.signature]?.value = null
        }
        return oldest.size
    }
}

/** In-memory [StorageDao] keyed by [StorageRow.key]. */
internal open class InMemoryStorageDao : StorageDao {
    /** Storage-object rows keyed by [StorageRow.key]. */
    protected val rows: MutableMap<String, StorageRow> = mutableMapOf()

    override suspend fun upsert(row: StorageRow) {
        rows[row.key] = row
    }

    override suspend fun getByKey(key: String): StorageRow? = rows[key]

    override suspend fun delete(key: String) {
        rows.remove(key)
    }

    override suspend fun delete(bucketId: String, path: String) {
        rows.values.removeAll { it.bucketId == bucketId && it.path == path }
    }

    override suspend fun deleteAll() {
        rows.clear()
    }

    override suspend fun forObject(bucketId: String, path: String, variantPrefix: String): List<StorageRow> =
        rows.values.filter { it.bucketId == bucketId && (it.path == path || it.path.startsWith(variantPrefix)) }

    override suspend fun all(): List<StorageRow> = rows.values.toList()

    override suspend fun totalSizeBytes(): Long = rows.values.sumOf { it.sizeBytes ?: 0L }
}

/** In-memory [MutationDao] mirroring Room's autoGenerate id and reactive per-table queue. */
internal open class InMemoryMutationDao : MutationDao {
    /** Every queued row, pending and synced, in insertion order. */
    protected val mutations: MutableStateFlow<List<MutationRow>> = MutableStateFlow(emptyList())
    private var nextId: Long = 1L

    override suspend fun insert(row: MutationRow) {
        val stored = if (row.id == 0L) row.copy(id = nextId++) else row
        mutations.update { current -> current + stored }
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table && it.syncedAtMs == null }.sortedBy { it.createdAtMs }

    override suspend fun pendingAll(): List<MutationRow> =
        mutations.value.filter { it.syncedAtMs == null }.sortedBy { it.createdAtMs }

    override suspend fun countAll(): Int = mutations.value.size

    override suspend fun countPending(): Int = mutations.value.count { it.syncedAtMs == null }

    override fun countPendingFlow(): Flow<Int> = mutations.map { all -> all.count { it.syncedAtMs == null } }

    override suspend fun countForTable(table: String): Int = mutations.value.count { it.table == table }

    override suspend fun countPendingForTable(table: String): Int =
        mutations.value.count { it.table == table && it.syncedAtMs == null }

    override fun countPendingForTableFlow(table: String): Flow<Int> =
        mutations.map { all -> all.count { it.table == table && it.syncedAtMs == null } }

    override suspend fun deleteById(id: Long) {
        mutations.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun allForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table }.sortedBy { it.createdAtMs }

    override fun allForTableFlow(table: String): Flow<List<MutationRow>> =
        mutations.map { all -> all.filter { it.table == table }.sortedBy { it.createdAtMs } }

    override suspend fun markSynced(id: Long, syncedAtMs: Long) {
        mutations.update { current ->
            current.map { if (it.id == id) it.copy(syncedAtMs = syncedAtMs) else it }
        }
    }

    override suspend fun deleteSyncedForTable(table: String) {
        mutations.update { current -> current.filterNot { it.table == table && it.syncedAtMs != null } }
    }

    override suspend fun deleteAll() {
        mutations.value = emptyList()
    }
}

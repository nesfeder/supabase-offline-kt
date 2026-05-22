package io.github.nesfeder.supabase.internal.db

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

/**
 * Simple in-memory fake implementations of DAOs for tests.
 */

internal class FakeObjectDao : ObjectDao {

    private val mutex = Mutex()
    private val rows = MutableStateFlow<List<ObjectRow>>(emptyList())

    override suspend fun upsert(row: ObjectRow) {
        mutex.withLock {
            rows.update { currentRows ->
                val mutableRows = currentRows.toMutableList()
                val existingIndex =
                    mutableRows.indexOfFirst { it.table == row.table && it.signature == row.signature }
                if (existingIndex >= 0) {
                    mutableRows[existingIndex] = row
                } else {
                    mutableRows += row
                }
                mutableRows
            }
        }
    }

    override suspend fun get(table: String, signature: String): ObjectRow? =
        mutex.withLock {
            rows.value.firstOrNull { it.table == table && it.signature == signature }
        }

    override fun observe(table: String, signature: String): Flow<ObjectRow?> {
        return rows.map { allRows ->
            allRows.firstOrNull { it.table == table && it.signature == signature }
        }
    }

    override fun observeAllForTable(table: String): Flow<List<ObjectRow>> =
        rows.map { allRows ->
            allRows
                .filter { it.table == table }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun getAllForTable(table: String): List<ObjectRow> =
        mutex.withLock {
            rows.value
                .filter { it.table == table }
                .sortedByDescending { it.updatedAt }
        }

    override suspend fun delete(table: String, signature: String) {
        mutex.withLock {
            rows.update { it.filter { row -> !(row.table == table && row.signature == signature) } }
        }
    }

    override suspend fun deleteTable(table: String) {
        mutex.withLock {
            rows.update { it.filter { row -> row.table != table } }
        }
    }

    override suspend fun deleteOldestPayloadRows(limit: Int) {
        mutex.withLock {
            if (limit <= 0) return@withLock

            rows.update { currentRows ->
                val oldest = currentRows
                    .sortedBy { it.updatedAt }
                    .take(limit)
                    .toSet()

                if (oldest.isNotEmpty()) {
                    currentRows.filter { it !in oldest }
                } else {
                    currentRows
                }
            }
        }
    }
}

internal class FakeStorageDao : StorageDao {

    private val mutex = Mutex()
    private val rows = mutableListOf<StorageRow>()

    override suspend fun upsert(row: StorageRow) {
        mutex.withLock {
            val idx = rows.indexOfFirst { it.key == row.key }
            if (idx >= 0) rows[idx] = row else rows += row
        }
    }

    override suspend fun getByKey(key: String): StorageRow? =
        mutex.withLock { rows.firstOrNull { it.key == key } }

    override suspend fun delete(key: String) {
        mutex.withLock {
            rows.removeAll { it.key == key }
        }
    }

    override suspend fun delete(bucketId: String, path: String) {
        mutex.withLock {
            rows.removeAll { it.key == "$bucketId::$path" }
        }
    }
}

internal class FakeMutationDao : MutationDao {

    private val mutex = Mutex()
    private var lastId: Long = 0L
    private val rows = MutableStateFlow<List<MutationRow>>(emptyList())

    override suspend fun insert(row: MutationRow) {
        mutex.withLock {
            val newRow = row.copy(id = ++lastId)
            rows.update { it + newRow }
        }
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> =
        mutex.withLock {
            rows.value
                .filter { it.table == table && it.syncedAtMs == null }
                .sortedBy { it.createdAtMs }
        }

    override suspend fun pendingAll(): List<MutationRow> =
        mutex.withLock {
            rows.value
                .filter { it.syncedAtMs == null }
                .sortedBy { it.createdAtMs }
        }

    override suspend fun countAll(): Int =
        mutex.withLock { rows.value.size }

    override suspend fun countPending(): Int =
        mutex.withLock { rows.value.count { it.syncedAtMs == null } }

    override fun countPendingFlow(): Flow<Int> {
        return rows.map { allRows ->
            allRows.count { it.syncedAtMs == null }
        }
    }

    override suspend fun countForTable(table: String): Int =
        mutex.withLock { rows.value.count { it.table == table } }

    override suspend fun countPendingForTable(table: String): Int =
        mutex.withLock { rows.value.count { it.table == table && it.syncedAtMs == null } }

    override fun countPendingForTableFlow(table: String): Flow<Int> {
        return rows.map { allRows ->
            allRows.count { it.table == table && it.syncedAtMs == null }
        }
    }

    override suspend fun deleteById(id: Long) {
        mutex.withLock {
            rows.update { it.filter { row -> row.id != id } }
        }
    }

    override suspend fun allForTable(table: String): List<MutationRow> =
        mutex.withLock {
            rows.value.filter { it.table == table }.sortedBy { it.createdAtMs }
        }

    override fun allForTableFlow(table: String): Flow<List<MutationRow>> {
        return rows.map { allRows ->
            allRows.filter { it.table == table }.sortedBy { it.createdAtMs }
        }
    }

    override suspend fun markSynced(id: Long, syncedAtMs: Long) {
        mutex.withLock {
            rows.update { currentRows ->
                val mutableRows = currentRows.toMutableList()
                val idx = mutableRows.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    mutableRows[idx] = mutableRows[idx].copy(syncedAtMs = syncedAtMs)
                    mutableRows
                } else {
                    currentRows
                }
            }
        }
    }

    override suspend fun deleteSyncedForTable(table: String) {
        mutex.withLock {
            rows.update { it.filter { row -> !(row.table == table && row.syncedAtMs != null) } }
        }
    }
}

/** Simple test implementation of [DbPathProvider] backed by a fixed path. */
internal class FakeDbPathProvider(
    private val path: Path = "/tmp/supabase-offline-test.db".toPath(),
) : DbPathProvider {

    override fun dbPath(): Path = path
}

/**
 * Convenience helper for constructing an [OfflineStore] in tests with sensible
 * defaults for the new cache-related constructor parameters.
 */
internal fun newTestOfflineStore(
    objectDao: ObjectDao = FakeObjectDao(),
    storageDao: StorageDao = FakeStorageDao(),
    mutationDao: MutationDao = FakeMutationDao(),
    nowMillis: () -> Long = { 0L },
    dbPathProvider: DbPathProvider = FakeDbPathProvider(),
    settings: OfflineCacheSettings = OfflineCacheSettings(
        maxDbSizeBytes = null,          // Unlimited for tests; keeps behaviour simple
        evictionBatchSize = Int.MAX_VALUE,
        minRetainedBytes = 0L,
    ),
    dbSizeProvider: (Path) -> Long? = { null },
): OfflineStore = OfflineStore(
    objectDao = objectDao,
    storageDao = storageDao,
    mutationDao = mutationDao,
    dbPathProvider = dbPathProvider,
    settings = settings,
    nowMillis = nowMillis,
    dbSizeProvider = dbSizeProvider,
)
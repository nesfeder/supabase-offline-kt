@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.internal.testing

import io.github.nesfeder.supabase.offline.InternalOfflineTestingApi
import io.github.nesfeder.supabase.offline.OfflineLogLevel
import io.github.nesfeder.supabase.offline.SdkLogger
import io.github.nesfeder.supabase.offline.SupabaseOfflineClient
import io.github.nesfeder.supabase.offline.SupabaseOfflineClientImpl
import io.github.nesfeder.supabase.offline.internal.db.DbPathProvider
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.db.OfflineCacheSettings
import io.github.nesfeder.supabase.offline.internal.db.OfflineStore
import io.github.nesfeder.supabase.offline.internal.db.StorageDao
import io.github.nesfeder.supabase.offline.internal.db.StorageRow
import io.github.jan.supabase.SupabaseClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okio.Path
import okio.Path.Companion.toPath

/**
 * Builds a fully in-memory [SupabaseOfflineClient] backed by non-persistent DAOs — no Room, no
 * platform database driver, no filesystem — so it runs deterministically in `commonTest` across
 * every target.
 *
 * The client is wired exactly like the production one (real [SupabaseOfflineClientImpl] over a real
 * [OfflineStore]); only the storage substrate is swapped for in-memory maps that mirror Room's
 * per-table invalidation semantics. Size-based eviction is disabled (unlimited cache).
 *
 * This is the hook behind `buildInMemorySupabaseOfflineClient` in the `supabase-offline-kt-testing`
 * artifact. It lives here because the in-memory DAOs implement the SDK's internal storage
 * interfaces, which stay internal; the testing artifact is the supported entry point.
 *
 * @param supabase the (typically mock-engine) Supabase client the offline client wraps.
 * @param coordinatorScope scope hosting the client-scoped refresh loops; pass a test-dispatcher
 *   scope so refresh hops are driven by the test scheduler.
 * @param nowMillis clock used for cache-row timestamps; defaults to a fixed `0L` for determinism.
 * @return an offline client whose cache, storage metadata and mutation queue live in memory.
 */
@InternalOfflineTestingApi
public fun createInMemorySupabaseOfflineClient(
    supabase: SupabaseClient,
    coordinatorScope: CoroutineScope,
    nowMillis: () -> Long = { 0L },
): SupabaseOfflineClient = SupabaseOfflineClientImpl(
    supabase = supabase,
    store = OfflineStore(
        objectDao = InMemoryObjectDao(),
        storageDao = InMemoryStorageDao(),
        mutationDao = InMemoryMutationDao(),
        dbPathProvider = InMemoryDbPathProvider,
        settings = OfflineCacheSettings(
            maxDbSizeBytes = null,
            evictionBatchSize = 0,
            minRetainedBytes = 0,
        ),
        nowMillis = nowMillis,
        dbSizeProvider = { null },
    ),
    baseDirectoryProvider = { IN_MEMORY_BASE_DIRECTORY.toPath() },
    sdkLogger = SdkLogger(null, OfflineLogLevel.INFO),
    coordinatorScope = coordinatorScope,
)

private const val IN_MEMORY_BASE_DIRECTORY = "/in-memory/offline"

private object InMemoryDbPathProvider : DbPathProvider {
    override fun dbPath(): Path = "$IN_MEMORY_BASE_DIRECTORY/offline.db".toPath()
}

/** In-memory [ObjectDao] mirroring Room's per-table invalidation: any write fans out to observers. */
private class InMemoryObjectDao : ObjectDao {
    private val rows = mutableMapOf<Pair<String, String>, ObjectRow>()
    private val observers = mutableMapOf<Pair<String, String>, MutableStateFlow<ObjectRow?>>()

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
private class InMemoryStorageDao : StorageDao {
    private val rows = mutableMapOf<String, StorageRow>()

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
}

/** In-memory [MutationDao] mirroring Room's autoGenerate id and reactive per-table queue. */
private class InMemoryMutationDao : MutationDao {
    private val mutations = MutableStateFlow<List<MutationRow>>(emptyList())
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

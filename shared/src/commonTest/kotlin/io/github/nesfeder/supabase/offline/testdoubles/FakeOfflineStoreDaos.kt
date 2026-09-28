@file:OptIn(kotlin.time.ExperimentalTime::class)

package io.github.nesfeder.supabase.offline.testdoubles

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

/**
 * Shared in-memory fakes for the Supabase offline-store DAOs.
 *
 * Live in `io.github.nesfeder.supabase.offline.testdoubles` so SDK-layer tests (`OfflineStoreApplyLocalMutationTest`,
 * `OfflineStoreEmptyCacheReactivityTest`, `OfflineStoreEvictionTest`, future integration tests)
 * share a single discoverable implementation instead of redeclaring private fakes per file.
 *
 * The fakes mirror Room's per-table invalidation semantics: any write on a table fans out
 * to every observer of that table, so inserts under a signature a collector is already
 * observing do surface downstream — matching real Room and exercising the cache-patch
 * reactivity contract.
 */

/** In-memory [ObjectDao] that mirrors Room's per-table invalidation semantics. */
internal class FakeObjectDao : ObjectDao {
    private val rows = mutableMapOf<Pair<String, String>, ObjectRow>()
    private val observers = mutableMapOf<Pair<String, String>, MutableStateFlow<ObjectRow?>>()

    /** Number of `upsert` writes that have occurred — exposed for write-amplification assertions. */
    var writes: Int = 0
        private set

    /** Cumulative number of rows removed by [deleteOldestPayloadRows] — for eviction assertions. */
    var evictedRows: Int = 0
        private set

    /** Number of times [deleteOldestPayloadRows] has been invoked — for iteration-cap assertions. */
    var deleteCallCount: Int = 0
        private set

    /**
     * When non-null, [totalPayloadBytes] returns this fixed value instead of summing the live
     * rows. Lets eviction tests reproduce the pathological "measured size never drops below the
     * limit" condition that the eviction loop's progress guard and iteration cap defend against.
     */
    var fixedTotalPayloadBytes: Long? = null

    /**
     * When non-null, [deleteOldestPayloadRows] reports this many deleted rows without touching
     * the backing map. Combined with [fixedTotalPayloadBytes], this holds the measured size
     * pinned above the limit while every delete claims progress, exercising the hard
     * iteration-cap backstop.
     */
    var forcedDeleteCount: Int? = null

    /**
     * Seed a cached payload without bumping the [writes] counter. [updatedAt] controls the row's
     * age so eviction tests can assert oldest-first ordering; it defaults to `0L` for callers
     * that don't care about age.
     */
    fun seedPayload(table: String, signature: String, payload: String, updatedAt: Long = 0L) {
        rows[table to signature] = ObjectRow(
            table = table,
            signature = signature,
            payload = payload,
            updatedAt = updatedAt,
        )
    }

    /** Look up a row without going through the suspend contract. */
    fun getRow(table: String, signature: String): ObjectRow? = rows[table to signature]

    override suspend fun get(table: String, signature: String): ObjectRow? = rows[table to signature]

    override fun observe(table: String, signature: String): Flow<ObjectRow?> =
        observers
            .getOrPut(table to signature) { MutableStateFlow(rows[table to signature]) }
            .asStateFlow()
            .map { it }

    /**
     * Optional suspension point inside [allForTable], invoked with the table name **after** the
     * snapshot the call will return has been taken and **before** it is returned.
     *
     * [OfflineStore.applyLocalMutationToCache] is a read-modify-write — snapshot every cached
     * payload for the table, patch each, write each back — and this is the seam between its read
     * and its write. A test parks one writer here (`CompletableDeferred.await()`) so a second
     * writer can run to completion against the *same* pre-write snapshot: the exact interleaving
     * behind a lost update, reproduced deterministically instead of by timing. `null` (the
     * default) makes [allForTable] return immediately, as Room would.
     */
    var onAllForTableSnapshot: (suspend (table: String) -> Unit)? = null

    override suspend fun allForTable(table: String): List<ObjectRow> {
        val snapshot = rows.values.filter { it.table == table }
        onAllForTableSnapshot?.invoke(table)
        return snapshot
    }

    override suspend fun upsert(row: ObjectRow) {
        writes++
        rows[row.table to row.signature] = row
        // Real Room invalidates per-table on any write; fan out to every observer of this
        // table so a newly-inserted row with a matching signature surfaces downstream.
        observers
            .filterKeys { it.first == row.table }
            .forEach { (key, flow) -> flow.value = rows[key] }
    }

    override suspend fun delete(table: String, signature: String) {
        rows.remove(table to signature)
        observers[table to signature]?.value = null
    }

    override suspend fun deleteTable(table: String) {
        val keys = rows.keys.filter { it.first == table }.toList()
        keys.forEach {
            rows.remove(it)
            observers[it]?.value = null
        }
    }

    override suspend fun deleteAll() {
        val keys = rows.keys.toList()
        rows.clear()
        keys.forEach { observers[it]?.value = null }
    }

    override suspend fun totalPayloadBytes(): Long =
        fixedTotalPayloadBytes ?: rows.values.sumOf { it.payload.length.toLong() }

    override suspend fun deleteOldestPayloadRows(limit: Int): Int {
        deleteCallCount++
        forcedDeleteCount?.let { forced ->
            evictedRows += forced
            return forced
        }
        if (limit <= 0) return 0
        // Mirror the DAO's `ORDER BY updated_at ASC LIMIT :limit` — evict the oldest rows first.
        val oldest = rows.values.sortedBy { it.updatedAt }.take(limit)
        oldest.forEach { row ->
            rows.remove(row.table to row.signature)
            observers[row.table to row.signature]?.value = null
        }
        evictedRows += oldest.size
        return oldest.size
    }
}

/**
 * In-memory [MutationDao] that faithfully mirrors Room's mutation-queue semantics: it assigns an
 * auto-incrementing id on insert (like `autoGenerate`), treats rows with a null `syncedAtMs` as
 * pending, orders by `createdAtMs`, and honours [markSynced] / [deleteById] / [deleteSyncedForTable]
 * by id. It is backed by a [MutableStateFlow] so [allForTableFlow] re-emits on every queue change,
 * mirroring real Room's reactive `@Query ... Flow`. Together this lets syncer tests drive a real
 * [io.github.nesfeder.supabase.offline.internal.db.OfflineStore] and observe replay, mark-synced, drop, and
 * dead-letter transitions, while the offline-store overlay substrate sees a queued INSERT appear
 * live.
 *
 * [inserted] exposes a snapshot of the backing list (all rows, pending + synced) so existing tests
 * can assert on queue contents directly.
 */
internal class FakeMutationDao : MutationDao {
    private val mutations = MutableStateFlow<List<MutationRow>>(emptyList())
    private var nextId: Long = 1L

    /** Snapshot of the queued rows (all rows, pending + synced), for queue assertions. */
    internal val inserted: List<MutationRow> get() = mutations.value

    override suspend fun insert(row: MutationRow) {
        // Mirror Room autoGenerate: a caller-supplied id of 0 gets a fresh unique id.
        val stored = if (row.id == 0L) row.copy(id = nextId++) else row
        mutations.update { current -> current + stored }
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table && it.syncedAtMs == null }.sortedBy { it.createdAtMs }

    override suspend fun pendingAll(): List<MutationRow> =
        mutations.value.filter { it.syncedAtMs == null }.sortedBy { it.createdAtMs }

    override suspend fun countAll(): Int = mutations.value.size
    override suspend fun countPending(): Int = mutations.value.count { it.syncedAtMs == null }
    override fun countPendingFlow(): Flow<Int> = throw NotImplementedError()
    override suspend fun countForTable(table: String): Int = mutations.value.count { it.table == table }
    override suspend fun countPendingForTable(table: String): Int =
        mutations.value.count { it.table == table && it.syncedAtMs == null }
    override fun countPendingForTableFlow(table: String): Flow<Int> = throw NotImplementedError()
    override suspend fun deleteById(id: Long) { mutations.update { current -> current.filterNot { it.id == id } } }
    override suspend fun allForTable(table: String): List<MutationRow> =
        mutations.value.filter { it.table == table }.sortedBy { it.createdAtMs }

    // Reactive per-table snapshot — re-emits whenever the queue changes, so the offline-store
    // overlay substrate (`cachePayloadWithOverlayFlow`) sees a queued INSERT appear live.
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

    override suspend fun deleteAll() { mutations.value = emptyList() }
}

/**
 * In-memory [StorageDao] keyed by [StorageRow.key].
 *
 * Object-cache tests that never touch storage can keep using it as an empty stub; storage tests
 * (`OfflineBucketApiTest`, `SupabaseOfflineClientClearCacheTest`) rely on the real upsert/get/delete
 * behavior to assert cache-row tracking and eviction.
 */
internal class FakeStorageDao : StorageDao {
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

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.PayloadLength
import io.github.nesfeder.supabase.offline.internal.db.PendingGuardedMutation
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryMutationDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryQueryCacheDao
import kotlinx.coroutines.flow.update

/*
 * Recording and scripting extensions of the engine's in-memory DAOs, which live in
 * `internal/testing` and back the testing artifact's client too. These add only what a test needs
 * to observe or steer: counters, seeding, scripted failures and interleaving hooks; a test that
 * needs none uses the in-memory DAO directly. Build components over them with newTestStore.
 */

/**
 * [InMemoryQueryCacheDao] with write counters, silent seeding, and eviction and interleaving hooks.
 */
internal class FakeQueryCacheDao : InMemoryQueryCacheDao() {

    /**
     * Number of `upsert` writes, and payload updates that changed a row, that have occurred —
     * exposed for write-amplification assertions.
     */
    var writes: Int = 0
        private set

    /** Cumulative number of rows removed by [deletePayloads] — for eviction assertions. */
    var evictedRows: Int = 0
        private set

    /**
     * Runs inside [allForTable], with the table, after its snapshot is taken and before it is
     * returned. A test parks one `CachePatcher.patchCachedQueries` writer here so a second writes
     * against the same snapshot: a lost update, reproduced without timing. `null` returns at once.
     */
    var onAllForTableSnapshot: (suspend (table: String) -> Unit)? = null

    /**
     * Runs inside [get] between reading the row and returning it; a test parks a first load of a
     * record here while a clear runs. `null` returns at once.
     */
    var onGetSnapshot: (suspend (table: String, cacheKey: String) -> Unit)? = null

    /**
     * Makes [upsert] of a row it accepts fail with an [IllegalStateException], as a full disk or a
     * corrupt page would. `null` never fails.
     */
    var failUpsertOf: ((CachedQueryRow) -> Boolean)? = null

    /** Makes the next [upsert] of a row that [match] accepts fail, once, as a full disk would. */
    fun failNextUpsertOf(match: (CachedQueryRow) -> Boolean) {
        var failed = false
        failUpsertOf = { row ->
            val fails = !failed && match(row)
            if (fails) failed = true
            fails
        }
    }

    /**
     * Seed a cached payload without bumping the [writes] counter or notifying observers.
     * [updatedAt] controls the row's age so eviction tests can assert oldest-first ordering; it
     * defaults to `0L` for callers that don't care about age.
     */
    fun seedPayload(table: String, cacheKey: String, payload: String, updatedAt: Long = 0L) {
        val row =
            CachedQueryRow(
                table = table,
                cacheKey = cacheKey,
                payload = payload,
                updatedAt = updatedAt,
            )
        rows.update { current -> current + ((table to cacheKey) to row) }
        assignRowId(table to cacheKey)
    }

    /** Look up a row without going through the suspend contract. */
    fun getRow(table: String, cacheKey: String): CachedQueryRow? = rows.value[table to cacheKey]

    /** Every row of [table], without going through the suspend contract. */
    fun rowsOf(table: String): List<CachedQueryRow> =
        rows.value.values.filter { row -> row.table == table }

    override suspend fun get(table: String, cacheKey: String): CachedQueryRow? {
        val row = super.get(table, cacheKey)
        onGetSnapshot?.invoke(table, cacheKey)
        return row
    }

    override suspend fun allForTable(table: String): List<CachedQueryRow> {
        val snapshot = super.allForTable(table)
        onAllForTableSnapshot?.invoke(table)
        return snapshot
    }

    /**
     * Runs inside [upsert] before the row is written, where a test parks a store's write inside its
     * locks while another writer queues on them. `null` writes at once.
     */
    var onUpsert: (suspend (row: CachedQueryRow) -> Unit)? = null

    override suspend fun upsert(row: CachedQueryRow) {
        onUpsert?.invoke(row)
        check(failUpsertOf?.invoke(row) != true) { "Scripted write failure" }
        writes++
        super.upsert(row)
    }

    override suspend fun updatePayload(
        table: String,
        cacheKey: String,
        payload: String,
        updatedAt: Long,
    ): Int {
        // A patch's write meets the hooks an upsert does, with the row as it would be written
        getRow(table, cacheKey)?.copy(payload = payload, updatedAt = updatedAt)?.let { row ->
            onUpsert?.invoke(row)
            check(failUpsertOf?.invoke(row) != true) { "Scripted write failure" }
        }
        val changed = super.updatePayload(table, cacheKey, payload, updatedAt)
        writes += changed
        return changed
    }

    override suspend fun deletePayloads(
        table: String,
        rowIds: List<Long>,
        fetchedAtOrBefore: Long,
    ): Int {
        val deleted = super.deletePayloads(table, rowIds, fetchedAtOrBefore)
        evictedRows += deleted
        return deleted
    }
}

/**
 * [delegate], counting what an eviction pass and a patch cost the table: each read of every
 * payload's length (a sum, or the listing in eviction order), each delete statement of a pass, and
 * each transaction. A test asserts the counts, and may hold a pass at [afterPayloadLengthsRead].
 */
internal class PayloadScanCountingDao(private val delegate: QueryCacheDao) :
    QueryCacheDao by delegate {

    /** How many times every payload's length was summed ([totalPayloadBytes]). */
    var payloadSums: Int = 0
        private set

    /** How many times every payload's length was listed ([payloadLengthsOldestFirst]). */
    var payloadLengthReads: Int = 0
        private set

    /** How many delete statements eviction passes ran ([deletePayloads]). */
    var evictionDeletes: Int = 0
        private set

    /** How many transactions were run ([runInTransaction]). */
    var transactions: Int = 0
        private set

    /** Runs after each listing of the payloads' lengths, before it is returned. */
    var afterPayloadLengthsRead: (suspend () -> Unit)? = null

    override suspend fun totalPayloadBytes(): Long {
        payloadSums++
        return delegate.totalPayloadBytes()
    }

    override suspend fun payloadLengthsOldestFirst(
        keptCacheKeys: List<String>
    ): List<PayloadLength> {
        payloadLengthReads++
        val lengths = delegate.payloadLengthsOldestFirst(keptCacheKeys)
        afterPayloadLengthsRead?.invoke()
        return lengths
    }

    override suspend fun deletePayloads(
        table: String,
        rowIds: List<Long>,
        fetchedAtOrBefore: Long,
    ): Int {
        evictionDeletes++
        return delegate.deletePayloads(table, rowIds, fetchedAtOrBefore)
    }

    override suspend fun runInTransaction(block: suspend () -> Unit) {
        transactions++
        delegate.runInTransaction(block)
    }
}

/**
 * [InMemoryMutationDao] that exposes the queue for assertions. The in-memory DAO already mirrors
 * Room's mutation-queue semantics: an auto-incrementing id on insert (like `autoGenerate`), rows
 * with a null `syncedAtMs` treated as pending, ordering by id (insertion order), and a pending flow
 * that re-emits on every queue change. Replayer tests drive the real components over it and observe
 * replay, mark-synced, drop and rejection transitions.
 */
internal class FakeMutationDao : InMemoryMutationDao() {

    /** Snapshot of the queued rows (all rows, pending + synced), for queue assertions. */
    internal val inserted: List<MutationRow>
        get() = mutations.value

    /** How many times a query read every pending row of the queue or of a table. */
    var pendingListReads: Int = 0
        private set

    /** How many times each table's mutation rows, pending and synced, were read as one list. */
    val tableListReads: Map<String, Int>
        get() = tableListReadCounts.toMap()

    private val tableListReadCounts = mutableMapOf<String, Int>()

    /** How many rows were ever inserted into the queue, whether removed since or not. */
    var insertCalls: Int = 0
        private set

    /** When set, the next [deleteAll] fails, as a process dying part-way through clearing would. */
    var failNextDeleteAll: Boolean = false

    /** When set, the next [deleteById] fails, as a process dying before the delete would. */
    var failNextDeleteById: Boolean = false

    /**
     * When set, runs each time a send is recorded ([markSendUnconfirmed]), before the record is
     * written: a point after a mutation is claimed and before its request is built.
     */
    var onMarkSendUnconfirmed: (suspend (id: Long) -> Unit)? = null

    /** When set, the next [markSendUnconfirmed] fails, as a full disk would. */
    var failNextMarkSendUnconfirmed: Boolean = false

    /**
     * When set, the next [hasPendingOwnedBy] fails, as a corrupt page would: the read that asks
     * whether an identity has writes queued.
     */
    var failNextHasPendingOwnedBy: Boolean = false

    /**
     * When set, the next [hasPendingOwnedBy] over the whole queue fails: the read a trigger, a
     * finished write or a discard makes to ask whether anything waits. The head check, bounded by
     * an id, is left alone.
     */
    var failNextWholeQueueCheck: Boolean = false

    /**
     * When set, runs before each [hasPendingOwnedBy] over the whole queue reads it: a test holds it
     * to keep a trigger's check reading.
     */
    var beforeWholeQueueCheck: (suspend () -> Unit)? = null

    /** When set, runs after [unconfirmedSendIds] read the rows and before it returns them. */
    var afterUnconfirmedSendIdsRead: (suspend () -> Unit)? = null

    /**
     * When set, runs before each read of one table's mutation rows ([allForTable],
     * [pendingForTable]), the reads a query's overlay makes; a test throws from it to fail them.
     */
    var beforeTableRead: (suspend (table: String) -> Unit)? = null

    /**
     * When set, runs after each read of one table's mutation rows ([allForTable]), before they are
     * returned: a test delays it so a reader is handed rows that have changed since.
     */
    var afterTableRead: (suspend (table: String) -> Unit)? = null

    /** When set, runs after each transaction has committed, before its caller goes on. */
    var afterTransaction: (suspend () -> Unit)? = null

    override suspend fun runInTransaction(block: suspend () -> Unit) {
        super.runInTransaction(block)
        afterTransaction?.invoke()
    }

    override suspend fun insert(row: MutationRow): Long {
        insertCalls++
        return super.insert(row)
    }

    override suspend fun pendingAll(): List<MutationRow> {
        pendingListReads++
        return super.pendingAll()
    }

    /** How many times an identity's pending mutations were counted, in every table. */
    var pendingCounts: Int = 0
        private set

    /**
     * When set, runs before each count of an identity's pending mutations ([countPendingOwnedBy]):
     * a test holds it to keep a count reading.
     */
    var beforePendingCount: (suspend () -> Unit)? = null

    override suspend fun countPendingOwnedBy(owner: String, includeOwnerless: Boolean): Int {
        pendingCounts++
        beforePendingCount?.invoke()
        return super.countPendingOwnedBy(owner, includeOwnerless)
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> {
        beforeTableRead?.invoke(table)
        pendingListReads++
        return super.pendingForTable(table)
    }

    /**
     * How many times a base forward read the later guarded writes of a table: their filters and
     * bases, never a body or an unguarded row.
     */
    var guardedBaseReads: Int = 0
        private set

    override suspend fun pendingGuardedAfter(
        table: String,
        afterId: Long,
        owner: String?,
    ): List<PendingGuardedMutation> {
        guardedBaseReads++
        return super.pendingGuardedAfter(table, afterId, owner)
    }

    override suspend fun allForTable(table: String): List<MutationRow> {
        beforeTableRead?.invoke(table)
        tableListReadCounts[table] = (tableListReadCounts[table] ?: 0) + 1
        val rows = super.allForTable(table)
        afterTableRead?.invoke(table)
        return rows
    }

    override suspend fun deleteAll() {
        if (failNextDeleteAll) {
            failNextDeleteAll = false
            error("Scripted failure part-way through clearing")
        }
        super.deleteAll()
    }

    override suspend fun hasPendingOwnedBy(
        owner: String,
        includeOwnerless: Boolean,
        beforeId: Long,
    ): Boolean {
        if (failNextHasPendingOwnedBy) {
            failNextHasPendingOwnedBy = false
            error("Scripted failure reading the queue")
        }
        if (failNextWholeQueueCheck && beforeId == Long.MAX_VALUE) {
            failNextWholeQueueCheck = false
            error("Scripted failure reading the queue")
        }
        if (beforeId == Long.MAX_VALUE) beforeWholeQueueCheck?.invoke()
        return super.hasPendingOwnedBy(owner, includeOwnerless, beforeId)
    }

    override suspend fun unconfirmedSendIds(): List<Long> {
        val ids = super.unconfirmedSendIds()
        afterUnconfirmedSendIdsRead?.invoke()
        return ids
    }

    override suspend fun markSendUnconfirmed(id: Long): Int {
        onMarkSendUnconfirmed?.invoke(id)
        if (failNextMarkSendUnconfirmed) {
            failNextMarkSendUnconfirmed = false
            error("Scripted failure recording the send")
        }
        return super.markSendUnconfirmed(id)
    }

    override suspend fun deleteById(id: Long) {
        if (failNextDeleteById) {
            failNextDeleteById = false
            error("Scripted failure before the delete")
        }
        super.deleteById(id)
    }
}

/**
 * A real [MutationDao] — Room's, in the on-disk tests — whose [deleteAll] fails, so a test can
 * check that a clear which fails part-way changes nothing on disk.
 */
internal class FakeDeleteAllFailingMutationDao(real: MutationDao) : MutationDao by real {
    override suspend fun deleteAll() {
        error("Scripted failure of the mutation delete")
    }
}

/**
 * A real [QueryCacheDao], Room's in the on-disk tests, whose [deleteAll] fails. A test checks with
 * it that a clear failing part-way changes nothing on disk.
 */
internal class FakeDeleteAllFailingQueryCacheDao(real: QueryCacheDao) : QueryCacheDao by real {
    override suspend fun deleteAll() {
        error("Scripted failure of the object-cache delete")
    }
}

/**
 * A real [QueryCacheDao], Room's in the on-disk tests, whose second payload write ([updatePayload])
 * fails, as a full disk would part-way through a patch. A test checks with it that a patch failing
 * part-way changes nothing on disk.
 */
internal class FakeSecondPayloadWriteFailingQueryCacheDao(private val real: QueryCacheDao) :
    QueryCacheDao by real {
    private var payloadWrites = 0

    override suspend fun updatePayload(
        table: String,
        cacheKey: String,
        payload: String,
        updatedAt: Long,
    ): Int {
        if (++payloadWrites == 2) error("Scripted failure of the second payload write")
        return real.updatePayload(table, cacheKey, payload, updatedAt)
    }
}

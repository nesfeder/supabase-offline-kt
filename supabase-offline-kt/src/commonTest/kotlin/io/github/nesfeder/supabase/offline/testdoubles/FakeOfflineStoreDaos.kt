// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryMutationDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryObjectDao

/*
 * Recording and scripting extensions of the engine's in-memory DAOs, which live in
 * `internal/testing` and back the testing artifact's client too. These add only what a test needs
 * to observe or steer: counters, seeding, scripted failures and interleaving hooks; a test that
 * needs none uses the in-memory DAO directly. Build a store over them with newTestOfflineStore.
 */

/** [InMemoryObjectDao] with write counters, silent seeding, and eviction and interleaving hooks. */
internal class FakeObjectDao : InMemoryObjectDao() {

    /**
     * Number of `upsert` writes that have occurred — exposed for write-amplification assertions.
     */
    var writes: Int = 0
        private set

    /** Cumulative number of rows removed by [deleteOldestPayloadRows] — for eviction assertions. */
    var evictedRows: Int = 0
        private set

    /**
     * Number of times [deleteOldestPayloadRows] has been invoked — for iteration-cap assertions.
     */
    var deleteCallCount: Int = 0
        private set

    /**
     * When non-null, [totalPayloadBytes] returns this fixed value instead of summing the live rows.
     * Lets eviction tests reproduce the pathological "measured size never drops below the limit"
     * condition that the eviction loop's progress guard and iteration cap defend against.
     */
    var fixedTotalPayloadBytes: Long? = null

    /**
     * When non-null, [deleteOldestPayloadRows] reports this many deleted rows without touching the
     * backing map. Combined with [fixedTotalPayloadBytes], this holds the measured size pinned
     * above the limit while every delete claims progress, exercising the hard iteration-cap
     * backstop.
     */
    var forcedDeleteCount: Int? = null

    /**
     * Runs inside [allForTable], with the table, after its snapshot is taken and before it is
     * returned. A test parks one `OfflineStore.patchCachedQueries` writer here so a second writes
     * against the same snapshot: a lost update, reproduced without timing. `null` returns at once.
     */
    var onAllForTableSnapshot: (suspend (table: String) -> Unit)? = null

    /**
     * Runs inside [get] between reading the row and returning it; a test parks a first load of a
     * record here while a clear runs. `null` returns at once.
     */
    var onGetSnapshot: (suspend (table: String, signature: String) -> Unit)? = null

    /**
     * Makes [upsert] of a row it accepts fail with an [IllegalStateException], as a full disk or a
     * corrupt page would. `null` never fails.
     */
    var failUpsertOf: ((ObjectRow) -> Boolean)? = null

    /** Makes the next [upsert] of a row that [match] accepts fail, once, as a full disk would. */
    fun failNextUpsertOf(match: (ObjectRow) -> Boolean) {
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
    fun seedPayload(table: String, signature: String, payload: String, updatedAt: Long = 0L) {
        rows[table to signature] =
            ObjectRow(
                table = table,
                signature = signature,
                payload = payload,
                updatedAt = updatedAt,
            )
    }

    /** Look up a row without going through the suspend contract. */
    fun getRow(table: String, signature: String): ObjectRow? = rows[table to signature]

    /** Every row of [table], without going through the suspend contract. */
    fun rowsOf(table: String): List<ObjectRow> = rows.values.filter { row -> row.table == table }

    override suspend fun get(table: String, signature: String): ObjectRow? {
        val row = super.get(table, signature)
        onGetSnapshot?.invoke(table, signature)
        return row
    }

    override suspend fun allForTable(table: String): List<ObjectRow> {
        val snapshot = super.allForTable(table)
        onAllForTableSnapshot?.invoke(table)
        return snapshot
    }

    /**
     * Runs inside [upsert] before the row is written, where a test parks a store's write inside its
     * locks while another writer queues on them. `null` writes at once.
     */
    var onUpsert: (suspend (row: ObjectRow) -> Unit)? = null

    override suspend fun upsert(row: ObjectRow) {
        onUpsert?.invoke(row)
        check(failUpsertOf?.invoke(row) != true) { "Scripted write failure" }
        writes++
        super.upsert(row)
    }

    override suspend fun totalPayloadBytes(): Long =
        fixedTotalPayloadBytes ?: super.totalPayloadBytes()

    override suspend fun deleteOldestPayloadRows(limit: Int, keptSignatures: List<String>): Int {
        deleteCallCount++
        forcedDeleteCount?.let { forced ->
            evictedRows += forced
            return forced
        }
        val deleted = super.deleteOldestPayloadRows(limit, keptSignatures)
        evictedRows += deleted
        return deleted
    }
}

/**
 * [InMemoryMutationDao] that exposes the queue for assertions. The in-memory DAO already mirrors
 * Room's mutation-queue semantics: an auto-incrementing id on insert (like `autoGenerate`), rows
 * with a null `syncedAtMs` treated as pending, ordering by id (insertion order), and a pending flow
 * that re-emits on every queue change. Syncer tests drive a real `OfflineStore` over it and observe
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

    /** When set, runs after [unconfirmedSendIds] read the rows and before it returns them. */
    var afterUnconfirmedSendIdsRead: (suspend () -> Unit)? = null

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

    override suspend fun pendingForTable(table: String): List<MutationRow> {
        pendingListReads++
        return super.pendingForTable(table)
    }

    override suspend fun allForTable(table: String): List<MutationRow> {
        tableListReadCounts[table] = (tableListReadCounts[table] ?: 0) + 1
        return super.allForTable(table)
    }

    override suspend fun deleteAll() {
        if (failNextDeleteAll) {
            failNextDeleteAll = false
            error("Scripted failure part-way through clearing")
        }
        super.deleteAll()
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
 * A real [ObjectDao], Room's in the on-disk tests, whose [deleteAll] fails. A test checks with it
 * that a clear failing part-way changes nothing on disk.
 */
internal class FakeDeleteAllFailingObjectDao(real: ObjectDao) : ObjectDao by real {
    override suspend fun deleteAll() {
        error("Scripted failure of the object-cache delete")
    }
}

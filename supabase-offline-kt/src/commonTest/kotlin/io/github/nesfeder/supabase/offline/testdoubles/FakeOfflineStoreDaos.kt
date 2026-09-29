package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.ObjectDao
import io.github.nesfeder.supabase.offline.internal.db.ObjectRow
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryMutationDao
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryObjectDao

/**
 * Recording and scripting extensions of the engine's in-memory DAOs.
 *
 * The storage behaviour itself lives in one place — `InMemoryObjectDao`, `InMemoryStorageDao` and
 * `InMemoryMutationDao` under `internal/testing`, the same substrate the testing artifact's
 * in-memory client runs on. They mirror Room's per-table invalidation: any write on a table fans
 * out to every observer of that table, so inserts under a signature a collector is already
 * observing do surface downstream. The classes here only add what a test needs to observe or
 * steer: write counters, seeding, failure scripting and interleaving hooks. A test with no such
 * need uses the in-memory DAO directly (storage tests use `InMemoryStorageDao` as-is).
 *
 * Build a store over them with [newTestOfflineStore].
 */

/** [InMemoryObjectDao] with write counters, silent seeding, and eviction and interleaving hooks. */
internal class FakeObjectDao : InMemoryObjectDao() {

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
     * Optional suspension point inside [allForTable], invoked with the table name **after** the
     * snapshot the call will return has been taken and **before** it is returned.
     *
     * `OfflineStore.patchCachedQueries` is a read-modify-write — snapshot every cached
     * payload for the table, patch each, write each back — and this is the seam between its read
     * and its write. A test parks one writer here (`CompletableDeferred.await()`) so a second
     * writer can run to completion against the *same* pre-write snapshot: the exact interleaving
     * behind a lost update, reproduced deterministically instead of by timing. `null` (the
     * default) makes [allForTable] return immediately, as Room would.
     */
    var onAllForTableSnapshot: (suspend (table: String) -> Unit)? = null

    /**
     * Optional suspension point inside [get], invoked after the row the call will return has been
     * read and before it is returned: the seam where a first load of a record can be parked while a
     * clear runs. `null` (the default) returns immediately.
     */
    var onGetSnapshot: (suspend (table: String, signature: String) -> Unit)? = null

    /**
     * When it returns `true` for a row, [upsert] of that row fails with an [IllegalStateException]
     * instead of writing it, as a full disk or a corrupt page would. `null` (the default) never fails.
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
        rows[table to signature] = ObjectRow(
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

    override suspend fun upsert(row: ObjectRow) {
        check(failUpsertOf?.invoke(row) != true) { "Scripted write failure" }
        writes++
        super.upsert(row)
    }

    override suspend fun totalPayloadBytes(): Long = fixedTotalPayloadBytes ?: super.totalPayloadBytes()

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
 * with a null `syncedAtMs` treated as pending, ordering by id (insertion order), and reactive per-table
 * flows that re-emit on every queue change. Syncer tests drive a real `OfflineStore` over it and
 * observe replay, mark-synced, drop and dead-letter transitions.
 */
internal class FakeMutationDao : InMemoryMutationDao() {

    /** Snapshot of the queued rows (all rows, pending + synced), for queue assertions. */
    internal val inserted: List<MutationRow> get() = mutations.value

    /** How many times a query read every pending row of the queue or of a table. */
    var pendingListReads: Int = 0
        private set

    /** When set, the next [deleteAll] fails, as a process dying part-way through clearing would. */
    var failNextDeleteAll: Boolean = false

    /** When set, the next [deleteById] fails, as a process dying before the delete would. */
    var failNextDeleteById: Boolean = false

    override suspend fun pendingAll(): List<MutationRow> {
        pendingListReads++
        return super.pendingAll()
    }

    override suspend fun pendingForTable(table: String): List<MutationRow> {
        pendingListReads++
        return super.pendingForTable(table)
    }

    override suspend fun deleteAll() {
        if (failNextDeleteAll) {
            failNextDeleteAll = false
            error("Scripted failure part-way through clearing")
        }
        super.deleteAll()
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
 * A real [MutationDao] — Room's, in the on-disk tests — whose [deleteAll] fails, so a test can check
 * that a clear which fails part-way changes nothing on disk.
 */
internal class FakeDeleteAllFailingMutationDao(real: MutationDao) : MutationDao by real {
    override suspend fun deleteAll() {
        error("Scripted failure of the mutation delete")
    }
}

/**
 * A real [ObjectDao] — Room's, in the on-disk tests — whose [deleteAll] fails, so a test can check
 * that a clear which fails part-way changes nothing on disk.
 */
internal class FakeDeleteAllFailingObjectDao(real: ObjectDao) : ObjectDao by real {
    override suspend fun deleteAll() {
        error("Scripted failure of the object-cache delete")
    }
}

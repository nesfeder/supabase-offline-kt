@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.StorageRow
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryStorageDao
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The in-memory storage DAO, recording how often the file cache scans it, and with a database
 * failure a test can switch on.
 *
 * @property failUpserts when set, every row write throws, as SQLite does on a full disk.
 */
internal class RecordingStorageDao : InMemoryStorageDao() {

    var failUpserts: Boolean = false

    private val allCalls = AtomicInt(0)
    private val totalSizeCalls = AtomicInt(0)
    private val deleteAllCalls = AtomicInt(0)

    /** How many times every row was loaded. */
    val scans: Int get() = allCalls.load()

    /** How many times the total size was summed. */
    val totalSizeQueries: Int get() = totalSizeCalls.load()

    /** How many times every row was deleted. */
    val clears: Int get() = deleteAllCalls.load()

    /** Forgets the calls recorded so far. */
    fun resetCounts() {
        allCalls.store(0)
        totalSizeCalls.store(0)
    }

    override suspend fun upsert(row: StorageRow) {
        if (failUpserts) throw IllegalStateException("database or disk is full (code 13 SQLITE_FULL)")
        super.upsert(row)
    }

    override suspend fun all(): List<StorageRow> {
        allCalls.addAndFetch(1)
        return super.all()
    }

    override suspend fun deleteAll() {
        deleteAllCalls.addAndFetch(1)
        super.deleteAll()
    }

    override suspend fun totalSizeBytes(): Long {
        totalSizeCalls.addAndFetch(1)
        return super.totalSizeBytes()
    }
}

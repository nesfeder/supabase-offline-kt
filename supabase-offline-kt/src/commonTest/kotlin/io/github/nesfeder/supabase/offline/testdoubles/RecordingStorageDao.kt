// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.testdoubles

import io.github.nesfeder.supabase.offline.internal.db.CachedFileRow
import io.github.nesfeder.supabase.offline.internal.testing.InMemoryCachedFileDao
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CompletableDeferred

/**
 * The in-memory storage DAO, recording how often the file cache scans it, with a database failure a
 * test can switch on, and a hold on row writes, so a test can act while a commit holds the file
 * cache's lock.
 */
internal class RecordingCachedFileDao : InMemoryCachedFileDao() {

    /** When set, every row write throws, as SQLite does on a full disk. */
    var failUpserts: Boolean = false

    /** When set, every row write first completes [upsertHeld], then waits for it. */
    var upsertGate: CompletableDeferred<Unit>? = null

    /** Completed once a row write is waiting on [upsertGate]. */
    var upsertHeld: CompletableDeferred<Unit> = CompletableDeferred()

    private val allCalls = AtomicInt(0)
    private val totalSizeCalls = AtomicInt(0)
    private val deleteAllCalls = AtomicInt(0)

    /** How many times every row was loaded. */
    val scans: Int
        get() = allCalls.load()

    /** How many times the total size was summed. */
    val totalSizeQueries: Int
        get() = totalSizeCalls.load()

    /** How many times every row was deleted. */
    val clears: Int
        get() = deleteAllCalls.load()

    /** Forgets the calls recorded so far. */
    fun resetCounts() {
        allCalls.store(0)
        totalSizeCalls.store(0)
    }

    override suspend fun upsert(row: CachedFileRow) {
        if (failUpserts)
            throw IllegalStateException("database or disk is full (code 13 SQLITE_FULL)")
        upsertGate?.let { gate ->
            upsertHeld.complete(Unit)
            gate.await()
        }
        super.upsert(row)
    }

    override suspend fun all(): List<CachedFileRow> {
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

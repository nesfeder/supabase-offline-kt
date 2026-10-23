// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.concurrent.atomics.update

/**
 * Counts of committed changes to a kind of row: mutation rows, or cached payloads.
 *
 * @property everyTable changes that may reach every table's rows, such as a clear.
 * @property byTable changes to one table's rows, by table.
 */
internal data class ChangeCounts(
    val everyTable: Long = 0L,
    val byTable: Map<String, Long> = emptyMap(),
) {
    /** What a reader of [table] watches: both counts that reach it. */
    fun of(table: String): Pair<Long, Long> = everyTable to (byTable[table] ?: 0L)
}

/**
 * [delegate], counting each write once it has returned or failed against the table it changed, or
 * against every table for one that may change any. Every component writes the object cache through
 * the one instance, and only one store is open on a database (see [OpenDatabaseRegistry]), so these
 * are every write its payloads see.
 */
internal class PayloadWriteCountingDao(private val delegate: QueryCacheDao) :
    QueryCacheDao by delegate {

    /** The writes counted so far. */
    private val writes = AtomicReference(ChangeCounts())

    /** How many [runInTransaction] calls are open. */
    private val openTransactions = AtomicInt(0)

    /**
     * The tables counted while a transaction was open, `null` standing for every table, to count
     * again once it ends; see [runInTransaction].
     */
    private val countedInTransaction = AtomicReference<Set<String?>>(emptySet())

    /** The writes counted against [table]: those that may reach every table, and those to it. */
    fun writesTo(table: String): Pair<Long, Long> = writes.load().of(table)

    /** Counts a change to every table made outside this DAO's own writes, such as a commit. */
    fun countWriteToEveryTable() {
        count(table = null)
    }

    /** Counts a write to [table]'s rows, or to any table's when it is `null`. */
    private fun count(table: String?) {
        writes.update { counts ->
            if (table == null) counts.copy(everyTable = counts.everyTable + 1)
            else
                counts.copy(byTable = counts.byTable + (table to (counts.byTable[table] ?: 0L) + 1))
        }
        if (openTransactions.load() > 0) countedInTransaction.update { tables -> tables + table }
    }

    /**
     * [QueryCacheDao.runInTransaction], counting each table written inside it once more when it
     * ends: a statement is counted as it returns, before the transaction commits, so a reader of
     * another connection between the two saw the rows before the write under the count after it,
     * and would keep what it read until the next write.
     */
    override suspend fun runInTransaction(block: suspend () -> Unit) {
        openTransactions.incrementAndFetch()
        try {
            delegate.runInTransaction(block)
        } finally {
            openTransactions.decrementAndFetch()
            // Committed or rolled back, the rows may differ from what was read under the counts
            countedInTransaction.exchange(emptySet()).forEach { table -> count(table) }
        }
    }

    override suspend fun upsert(row: CachedQueryRow) = counted(row.table) { delegate.upsert(row) }

    override suspend fun updatePayload(
        table: String,
        cacheKey: String,
        payload: String,
        updatedAt: Long,
    ): Int = counted(table) { delegate.updatePayload(table, cacheKey, payload, updatedAt) }

    override suspend fun delete(table: String, cacheKey: String) =
        counted(table) { delegate.delete(table, cacheKey) }

    override suspend fun deleteTable(table: String) = counted(table) { delegate.deleteTable(table) }

    override suspend fun deleteAll() = counted(null) { delegate.deleteAll() }

    override suspend fun deleteAllExceptTable(table: String) =
        counted(null) { delegate.deleteAllExceptTable(table) }

    override suspend fun deleteWithCacheKeyPrefix(table: String, prefix: String) =
        counted(table) { delegate.deleteWithCacheKeyPrefix(table, prefix) }

    override suspend fun deleteOrphanedQueryShapes(): Int =
        counted(null) { delegate.deleteOrphanedQueryShapes() }

    override suspend fun deletePayloads(rowIds: List<Long>, fetchedAtOrBefore: Long): Int =
        counted(null) { delegate.deletePayloads(rowIds, fetchedAtOrBefore) }

    /** Runs [write], then counts it against [table] whether it returned or failed. */
    private inline fun <T> counted(table: String?, write: () -> T): T =
        try {
            write()
        } finally {
            count(table)
        }
}

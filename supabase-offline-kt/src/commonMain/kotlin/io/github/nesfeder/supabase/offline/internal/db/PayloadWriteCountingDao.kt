// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.db

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

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
 * The delegate DAO, counting each write once it has committed or failed against the table it
 * changed, or against every table for one that may change any. Every component writes the object
 * cache through the one instance, and only one store is open on a database (see
 * [OpenDatabaseRegistry]), so these are every write its payloads see; a live reader of a table
 * reads its payload again only when the table's count moves ([writeChangesOf]), since a write to
 * one table's rows is, to Room, a change to every row of the one table they all share.
 *
 * A write made inside [runInTransaction] is counted once the outermost one has returned, so no
 * reader hears of it before it can read it. A write made inside another DAO's transaction is
 * counted as it returns, before that commit: its caller counts the tables again once committed
 * ([countWriteToEveryTable]), unless only the engine's reserved rows changed, which no live reader
 * reads.
 *
 * The constructor's `beforeWriteHeard` runs before a reader hears of a counted write, as a slow
 * change notification would; a test delays it so a reader hears of a payload write after other
 * changes. `null` hands each on at once.
 */
internal class PayloadWriteCountingDao(
    private val delegate: QueryCacheDao,
    private val beforeWriteHeard: (suspend (table: String) -> Unit)? = null,
) : QueryCacheDao by delegate {

    /**
     * The writes counted so far: a flow, because live readers collect it and read their payload
     * again only when their table's count moves.
     */
    private val writes = MutableStateFlow(ChangeCounts())

    /** The writes counted against [table]: those that may reach every table, and those to it. */
    fun writesTo(table: String): Pair<Long, Long> = writes.value.of(table)

    /**
     * Emits once at once, and again each time a write to [table]'s rows, or one that may reach
     * every table's, is counted, without reading a row: the reader reads its payload itself.
     * Another table's writes cost it a comparison, not a read.
     */
    fun writeChangesOf(table: String): Flow<Unit> {
        val heard = beforeWriteHeard
        val changes = writes.map { counts -> counts.of(table) }.distinctUntilChanged()
        return if (heard == null) changes.map {} else changes.map { heard(table) }
    }

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

    // Only the reserved rows' query-shape records go
    override suspend fun deleteOrphanedQueryShapes(): Int =
        counted(RESERVED_TABLE_NAME) { delegate.deleteOrphanedQueryShapes() }

    override suspend fun deletePayloads(
        table: String,
        rowIds: List<Long>,
        fetchedAtOrBefore: Long,
    ): Int = counted(table) { delegate.deletePayloads(table, rowIds, fetchedAtOrBefore) }

    /**
     * Runs [block] in one write transaction, as the delegate does, counting the writes it makes
     * through this DAO once the transaction has returned, committed or rolled back. Within an
     * enclosing one, they are counted when the outermost returns.
     */
    override suspend fun runInTransaction(block: suspend () -> Unit) {
        if (currentCoroutineContext()[HeldCounts] != null) return delegate.runInTransaction(block)
        val held = HeldCounts()
        try {
            withContext(held) { delegate.runInTransaction(block) }
        } finally {
            held.release().forEach(::count)
        }
    }

    /**
     * Runs [write], then counts it against [table] whether it returned or failed: once the
     * transaction it was made in returns, or at once outside one.
     */
    private suspend inline fun <T> counted(table: String?, write: () -> T): T {
        val held = currentCoroutineContext()[HeldCounts]
        try {
            return write()
        } finally {
            if (held == null || !held.hold(table)) count(table)
        }
    }

    /**
     * The tables written inside one of this DAO's transactions, `null` for every table, held until
     * it returns.
     */
    private class HeldCounts : AbstractCoroutineContextElement(HeldCounts) {
        /** The tables held, or `null` once the transaction has returned. */
        private val tables = AtomicReference<Set<String?>?>(emptySet())

        /**
         * Holds [table] until the transaction returns; `false`, holding nothing, once it has: a
         * coroutine that outlived it counts its writes at once.
         */
        fun hold(table: String?): Boolean {
            while (true) {
                val held = tables.load() ?: return false
                if (tables.compareAndSet(held, held + table)) return true
            }
        }

        /** The tables held, holding none after. */
        fun release(): Set<String?> = tables.exchange(null).orEmpty()

        companion object Key : CoroutineContext.Key<HeldCounts>
    }
}

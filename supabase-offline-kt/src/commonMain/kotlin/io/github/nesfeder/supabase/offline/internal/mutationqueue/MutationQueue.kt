// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.EnqueueOutcome
import io.github.nesfeder.supabase.offline.internal.cache.ShownQueueCounts
import io.github.nesfeder.supabase.offline.internal.cache.TableWriteLocks
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.ChangeCounts
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationOperation
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.inTransaction
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * The mutation queue over its rows and the records kept beside them: enqueue, the head checks, a
 * send's outcome, rejection, retry, discard, the counts and flows the app is shown, the
 * send-unconfirmed record, and the retirement of replayed mutations. Owns the queue lock; takes a
 * table's write lock only to retire, and never while holding its own.
 *
 * @param queryCacheDao where the rejected list is kept and payloads' coverage is read.
 * @param primaryKeys each table's key columns, which tell a keyed INSERT.
 * @param identityProvider the current identity's stored value, or `null` when it cannot be told.
 * @param identityChanges emits when the session may have changed, so what the app is shown is
 *   filtered again.
 * @param ownerlessMutationsAreAnonymous whether mutations queued with no owner are the anonymous
 *   identity's: without the Auth plugin there is no other.
 * @param liveCacheKeysOf a table's queries with a live reader; retirement keeps what they wait for.
 */
internal class MutationQueue(
    private val mutationDao: MutationDao,
    private val queryCacheDao: QueryCacheDao,
    private val json: Json,
    private val nowMillis: () -> Long,
    private val freshness: CacheFreshness,
    private val tableWriteLocks: TableWriteLocks,
    private val primaryKeys: PrimaryKeys,
    private val identityProvider: suspend () -> String?,
    private val identityChanges: Flow<Any?>,
    private val ownerlessMutationsAreAnonymous: () -> Boolean,
    private val liveCacheKeysOf: (table: String) -> Set<String>,
    engineLogger: () -> EngineLogger,
) {

    /** The mutation queue's record kept beside the rows: the rejected list. */
    internal val rejectedList: RejectedMutationList =
        RejectedMutationList(
            queryCacheDao = queryCacheDao,
            json = json,
            nowMillis = nowMillis,
            logger = engineLogger,
        )

    /**
     * The ids of the mutations whose send started and whose outcome is unknown
     * ([MutationRow.sendUnconfirmed]). Read once, then kept in step with every change this queue
     * makes to them; `null` until first read. See [unconfirmedSends].
     */
    private val unconfirmedSendIds = AtomicReference<Set<Long>?>(null)

    /**
     * The queue lock: held by every change to the queue's rows and the records kept beside them, in
     * short sections of DAO calls; never across a request, and never awaited inside a transaction.
     * Taken after a table's write lock, before the clear lock.
     */
    private val mutationQueueMutex = Mutex()

    /**
     * The highest [MutationRow.syncSequence] handed out, read from the database once; guarded by
     * [mutationQueueMutex]. It never goes back, so a payload's [CachedQueryRow.syncedThrough] never
     * covers a mutation that syncs after the payload was written.
     */
    private var latestSyncSequence: Long? = null

    /** [latestSyncSequence], read once. Caller holds the queue lock; see [holdingQueueLock]. */
    suspend fun latestSyncSequence(): Long =
        latestSyncSequence
            ?: maxOf(mutationDao.maxSyncSequence(), queryCacheDao.maxSyncedThrough()).also { loaded
                ->
                latestSyncSequence = loaded
            }

    /** Mutation ids whose overlay failure was already logged, so a failing one is logged once. */
    private val loggedOverlaySkips = AtomicReference<Set<Long>>(emptySet())

    /**
     * Whether an unclassifiable dry-run status has been reported yet; see
     * [reportUnclassifiableWriteOnce].
     */
    private val unclassifiableWriteReported = AtomicBoolean(false)

    /* ────────────────────────────── Mutation queue ────────────────────────────── */

    /** Queues an INSERT with no recorded owner; see [MutationWriter.enqueueInsert]. */
    suspend fun enqueueInsert(
        table: String,
        body: JsonArray,
        filter: FilterDescriptor? = null,
        defaultToNull: Boolean = true,
    ): Long = MutationWriter(owner = null).enqueueInsert(table, body, filter, defaultToNull)

    /** Queues an UPSERT with no recorded owner; see [MutationWriter.enqueueUpsert]. */
    suspend fun enqueueUpsert(
        table: String,
        bodyJson: JsonArray,
        filter: FilterDescriptor? = null,
        onConflict: String? = null,
        ignoreDuplicates: Boolean = false,
        defaultToNull: Boolean = true,
    ): Long =
        MutationWriter(owner = null)
            .enqueueUpsert(table, bodyJson, filter, onConflict, ignoreDuplicates, defaultToNull)

    /** Queues an UPDATE with no recorded owner; see [MutationWriter.enqueueUpdate]. */
    suspend fun enqueueUpdate(
        table: String,
        body: JsonElement,
        filter: FilterDescriptor? = null,
    ): Long = MutationWriter(owner = null).enqueueUpdate(table, body, filter)

    /** Queues a DELETE with no recorded owner; see [MutationWriter.enqueueDelete]. */
    suspend fun enqueueDelete(table: String, filter: FilterDescriptor? = null): Long =
        MutationWriter(owner = null).enqueueDelete(table, filter)

    /**
     * Queues writes for [owner]: each row is stored with its owner in one insert, and the readers
     * of its table are told.
     *
     * @property owner The stored value of the identity making the writes, or `null` to record none
     *   (a store driven directly, with no session model).
     */
    internal inner class MutationWriter(private val owner: String?) {

        /** The last row this writer stored, with its id; `null` until it stores one. */
        var lastStored: MutationRow? = null
            private set

        /**
         * Queues an INSERT.
         *
         * @return The mutation's id — the mutation row's autoincrement id, stable across restarts.
         */
        suspend fun enqueueInsert(
            table: String,
            body: JsonArray,
            filter: FilterDescriptor? = null,
            defaultToNull: Boolean = true,
        ): Long =
            insert(
                table = table,
                operation = MutationOperation.INSERT,
                bodyJson = body.toString(),
                filter = filter,
                defaultToNull = defaultToNull,
            )

        /**
         * Queues an UPSERT.
         *
         * @return The mutation's id.
         */
        suspend fun enqueueUpsert(
            table: String,
            bodyJson: JsonArray,
            filter: FilterDescriptor? = null,
            onConflict: String? = null,
            ignoreDuplicates: Boolean = false,
            defaultToNull: Boolean = true,
        ): Long =
            insert(
                table = table,
                operation = MutationOperation.UPSERT,
                bodyJson = bodyJson.toString(),
                filter = filter,
                defaultToNull = defaultToNull,
                onConflict = onConflict,
                ignoreDuplicates = ignoreDuplicates,
            )

        /**
         * Queues an UPDATE, with an optional [filter] describing the targeted rows.
         *
         * @return The mutation's id.
         */
        suspend fun enqueueUpdate(
            table: String,
            body: JsonElement,
            filter: FilterDescriptor? = null,
        ): Long =
            insert(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = body.toString(),
                filter = filter,
            )

        /**
         * Queues a DELETE. [filter] describes which rows are removed.
         *
         * @return The mutation's id.
         */
        suspend fun enqueueDelete(table: String, filter: FilterDescriptor? = null): Long =
            insert(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filter = filter,
            )

        /** Stores one pending mutation of [owner]'s and tells [table]'s readers. */
        private suspend fun insert(
            table: String,
            operation: String,
            bodyJson: String,
            filter: FilterDescriptor?,
            defaultToNull: Boolean? = null,
            onConflict: String? = null,
            ignoreDuplicates: Boolean? = null,
        ): Long {
            requireUserTable(table)
            val row =
                MutationRow(
                    table = table,
                    operation = operation,
                    bodyJson = bodyJson,
                    filterJson = filter?.let { encodeFilter(it, json) },
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                    defaultToNull = defaultToNull,
                    onConflict = onConflict,
                    ignoreDuplicates = ignoreDuplicates,
                    owner = owner,
                )
            val id = mutationDao.insert(row)
            lastStored = row.copy(id = id)
            noteMutationsChanged(table)
            return id
        }
    }

    /**
     * Runs [enqueue], which queues one write with [identity] recorded as its owner.
     *
     * The queue is kept per identity: each write records who made it, a replay pass sends only the
     * current identity's writes, and another identity's writes neither block it nor are sent by it.
     *
     * The write is refused when local data was cleared since [startedAtTick]: it belongs to the
     * identity that was cleared. The check and the insert run under the queue lock, which a clear
     * holds, so no clear can fall between them.
     *
     * @param identity The stored value of the identity making the write, or `null` to record none
     *   (a store driven directly, with no session model).
     * @param startedAtTick [monotonicTickNow] as read before the write started, or `null` to skip
     *   the check.
     * @param enqueue Queues the write and returns its id. Must make DAO calls only.
     * @return What happened.
     */
    suspend fun enqueueOwnedBy(
        identity: String?,
        startedAtTick: Long? = null,
        enqueue: suspend MutationWriter.() -> Long,
    ): EnqueueOutcome = mutationQueueMutex.withLock {
        if (freshness.predatesQueueClear(startedAtTick))
            return@withLock EnqueueOutcome.LocalDataCleared
        val writer = MutationWriter(owner = identity)
        val id = writer.enqueue()
        val row = checkNotNull(writer.lastStored) { "enqueue stored no mutation" }
        EnqueueOutcome.Queued(id, row)
    }

    /**
     * How many changes this queue made to each table's mutation rows, each counted once committed:
     * a flow, because readers collect it and read a table's rows again only when its count moves
     * ([mutationsOfTableFlow], [queueChanges]). One queue per database ([OpenDatabaseRegistry]).
     */
    private val mutationChanges = MutableStateFlow(ChangeCounts())

    /** Tells the readers of [tables] that their mutation rows changed; call once committed. */
    private fun noteMutationsChanged(vararg tables: String) {
        noteMutationsChanged(tables.asList())
    }

    /** Tells the readers of [tables] that their mutation rows changed; call once committed. */
    private fun noteMutationsChanged(tables: Collection<String>) {
        if (tables.isEmpty()) return
        mutationChanges.update { counts ->
            counts.copy(
                byTable =
                    counts.byTable +
                        tables.associateWith { table -> (counts.byTable[table] ?: 0L) + 1 }
            )
        }
    }

    /** Tells every table's readers that their mutation rows may have changed. */
    private fun noteEveryTableChanged() {
        mutationChanges.update { counts -> counts.copy(everyTable = counts.everyTable + 1) }
    }

    /**
     * [table]'s mutation rows, pending and synced, in order: read once, and again each time this
     * store changes them. Another table's changes cost this reader a comparison, not a read, and
     * the table's readers share one read, and each body's parse, per change.
     */
    fun mutationsOfTableFlow(table: String): Flow<TableMutations> =
        mutationChanges
            .map { counts -> counts.of(table) }
            .distinctUntilChanged()
            .map { counts -> tableMutationsAt(table, counts) }

    /**
     * Each table's mutation rows as last read for its readers, with the counts they were read at.
     */
    private val lastTableMutations = AtomicReference<Map<String, TableMutations>>(emptyMap())

    /** Held while a table's mutation rows are read for its readers, so they share one read. */
    private val tableReadLocksMutex = Mutex()
    private val tableReadLocks = mutableMapOf<String, Mutex>()

    /**
     * [table]'s rows as read at [counts]: the last read when it was at them, or a new read that
     * keeps the bodies the last one parsed, for rows it still holds unchanged.
     */
    private suspend fun tableMutationsAt(table: String, counts: Pair<Long, Long>): TableMutations {
        val lock = tableReadLocksMutex.withLock { tableReadLocks.getOrPut(table) { Mutex() } }
        return lock.withLock {
            val last = lastTableMutations.load()[table]
            if (last != null && last.counts == counts) return@withLock last
            val read = TableMutations(counts, mutationDao.allForTable(table), json, last)
            lastTableMutations.update { reads -> reads + (table to read) }
            read
        }
    }

    /**
     * A table's mutation rows as read at [counts], shared by its readers, with each row's body
     * parsed at most once for all of them and for the reads after, while the row is unchanged. Two
     * readers parsing one body at once is harmless: both get equal values.
     *
     * @param earlier the table's previous read, whose parsed bodies this one keeps.
     */
    class TableMutations(
        val counts: Pair<Long, Long>,
        val rows: List<MutationRow>,
        private val json: Json,
        earlier: TableMutations?,
    ) {
        private val positions = HashMap<Long, Int>(rows.size * 2)
        private val bodies = arrayOfNulls<JsonElement>(rows.size)

        init {
            rows.forEachIndexed { position, row ->
                positions[row.id] = position
                bodies[position] = earlier?.parsedBodyOf(row)
            }
        }

        /** The body this read parsed for a row with [row]'s id and body, or `null`. */
        private fun parsedBodyOf(row: MutationRow): JsonElement? {
            val position = positions[row.id] ?: return null
            return bodies[position]?.takeIf { rows[position].bodyJson == row.bodyJson }
        }

        /** [mutation]'s body, parsed once when [mutation] is one of [rows]. */
        fun bodyOf(mutation: MutationRow): JsonElement {
            val position =
                positions[mutation.id]?.takeIf { position -> rows[position] === mutation }
                    ?: return json.parseToJsonElement(mutation.bodyJson)
            bodies[position]?.let { parsed ->
                return parsed
            }
            return json.parseToJsonElement(mutation.bodyJson).also { parsed ->
                bodies[position] = parsed
            }
        }
    }

    /**
     * The pending mutations [identity] may replay, in insertion order: its own, and those with no
     * recorded owner when [identity] may adopt them (see [mayAdoptOwnerlessMutations]).
     *
     * @param table The table to look in, or `null` for every table.
     */
    suspend fun pendingMutationsOf(identity: String, table: String? = null): List<MutationRow> {
        val adopts = mayAdoptOwnerlessMutations(identity)
        return if (table == null) mutationDao.pendingOwnedBy(identity, adopts)
        else mutationDao.pendingOwnedByInTable(identity, adopts, table)
    }

    /** Whether [identity] has a mutation to replay, in any table. Reads the owner index only. */
    suspend fun hasPendingMutationsOf(identity: String): Boolean =
        mutationDao.hasPendingOwnedBy(
            identity,
            mayAdoptOwnerlessMutations(identity),
            beforeId = Long.MAX_VALUE,
        )

    /**
     * Records [identity] as the owner of every pending mutation that has none — rows queued with no
     * session model — when [identity] may adopt them; otherwise leaves them untouched.
     */
    suspend fun adoptOwnerlessMutations(identity: String) {
        if (!mayAdoptOwnerlessMutations(identity)) return
        val adopted = mutationQueueMutex.withLock { mutationDao.adoptOwnerless(identity) }
        if (adopted > 0) noteEveryTableChanged()
    }

    /**
     * Whether a mutation owned by [owner] — `null` for one queued with no recorded owner — is
     * [identity]'s.
     */
    private fun belongsTo(owner: String?, identity: String): Boolean =
        owner == identity || (owner == null && mayAdoptOwnerlessMutations(identity))

    /**
     * Whether [identity] may adopt mutations queued with no recorded owner. With the Auth plugin
     * installed they are a signed-in user's, so the anonymous identity adopts them only without it.
     */
    private fun mayAdoptOwnerlessMutations(identity: String): Boolean =
        identity != QueueIdentity.Anonymous.storedValue || ownerlessMutationsAreAnonymous()

    /**
     * Whether [id] is the first of [identity]'s pending mutations, in any table. Reads the owner
     * index only.
     *
     * @return `false` behind another of [identity]'s mutations, or when [id] is not pending.
     */
    suspend fun isAtHead(id: Long, identity: String): Boolean =
        mutationDao.isPending(id) && !hasEarlierPendingMutation(id, identity)

    /** Whether any of [identity]'s mutations queued before [id], in any table, is still pending. */
    suspend fun hasEarlierPendingMutation(id: Long, identity: String): Boolean =
        mutationDao.hasPendingOwnedBy(identity, mayAdoptOwnerlessMutations(identity), id)

    /** Whether the mutation [id] is queued and not yet synced. */
    suspend fun isPending(id: Long): Boolean = mutationDao.isPending(id)

    /**
     * Removes a write sent at the head of the queue once the server accepted it: its mutation row,
     * its send record, and any rejected entry a crash left behind for it. The caller patches the
     * cache with the server's response, so nothing needs to stand in for the write any more.
     *
     * @param id The sent mutation's id.
     */
    suspend fun completeSentMutation(id: Long) {
        removeMutations(listOf(id), clearRejections = true)
    }

    /**
     * Counts one more replay of mutation [id] that PostgREST refused because its schema cache did
     * not know the table or a column.
     *
     * @return The misses counted so far; `0` when the mutation is gone.
     */
    suspend fun recordSchemaCacheMiss(id: Long): Int = mutationQueueMutex.withLock {
        mutationDao.recordSchemaCacheMiss(id)
        mutationDao.byId(id)?.schemaCacheMisses ?: 0
    }

    /**
     * Removes a write sent at the head of the queue that the server refused, or that local data was
     * cleared under: its mutation row.
     *
     * @param id The mutation's id.
     */
    suspend fun abandonSentMutation(id: Long) {
        removeMutations(listOf(id), clearRejections = false)
    }

    /**
     * Discards the pending mutation [id] of [identity]: it leaves the queue unsent, and the overlay
     * stops showing it.
     *
     * @param id The mutation's id.
     * @param identity The asking identity's stored value, or `null` to skip the ownership check.
     * @return `true` when it was pending and [identity]'s to discard.
     */
    suspend fun discardPendingMutation(id: Long, identity: String?): Boolean {
        val pending = mutationDao.byId(id)?.takeIf { row -> row.syncedAtMs == null } ?: return false
        if (identity != null && !belongsTo(pending.owner, identity)) return false
        removeMutations(listOf(id), clearRejections = false)
        return true
    }

    /**
     * Deletes the mutation rows [ids] with what the records hold for them — an unconfirmed send
     * and, when [clearRejections], a rejected entry — in one transaction, and tells the readers of
     * their tables.
     *
     * @param tables The tables of [ids], when the caller knows them; otherwise they are read.
     */
    private suspend fun removeMutations(
        ids: Collection<Long>,
        clearRejections: Boolean,
        tables: Collection<String>? = null,
    ) {
        if (ids.isEmpty()) return
        mutationQueueMutex.withLock {
            val idSet = ids.toSet()
            val touched = tables ?: idSet.mapNotNull { id -> mutationDao.byId(id)?.table }
            // Loaded first, so the change below reaches it.
            unconfirmedSends()
            val rejected =
                if (clearRejections && idSet.any { id -> rejectedList.hasRejection(id) }) {
                    rejectedList.rejectedRowWith(removeIds = idSet)
                } else {
                    null
                }
            mutationDao.runInTransaction {
                idSet.forEach { id -> mutationDao.deleteById(id) }
                rejected?.let { (row, _) -> queryCacheDao.upsert(row) }
            }
            unconfirmedSendIds.update { current -> current?.minus(idSet) }
            rejected?.let { (_, next) -> rejectedList.rejectedWritten(next) }
            loggedOverlaySkips.update { logged -> logged - idSet }
            noteMutationsChanged(touched.toSet())
        }
    }

    /**
     * Moves [mutation] out of the queue and onto the rejected list, in one transaction: the list
     * entry, the mutation row and its unconfirmed send change together or not at all.
     *
     * @param mutation The refused write.
     * @param message Why it was refused.
     * @param serverError The server's refusal, or `null` when the engine refused it locally.
     * @return The persisted entry.
     */
    suspend fun rejectMutation(
        mutation: MutationRow,
        message: String,
        serverError: StoredServerError?,
    ): StoredRejection = mutationQueueMutex.withLock {
        val entry =
            StoredRejection(
                id = mutation.id,
                table = mutation.table,
                operation = mutation.operation,
                bodyJson = mutation.bodyJson,
                filterJson = mutation.filterJson,
                createdAtMs = mutation.createdAtMs,
                defaultToNull = mutation.defaultToNull,
                onConflict = mutation.onConflict,
                ignoreDuplicates = mutation.ignoreDuplicates,
                owner = mutation.owner,
                rejectedAtMs = nowMillis(),
                message = message,
                serverError = serverError,
            )
        val (rejectedRow, list) = rejectedList.rejectedRowWith(add = entry)
        // Loaded first, so the change below reaches it.
        unconfirmedSends()
        mutationDao.runInTransaction {
            queryCacheDao.upsert(rejectedRow)
            mutationDao.deleteById(mutation.id)
        }
        rejectedList.rejectedWritten(list)
        unconfirmedSendIds.update { current -> current?.minus(mutation.id) }
        noteMutationsChanged(mutation.table)
        list.entries.firstOrNull { stored -> stored.id == mutation.id } ?: entry
    }

    /**
     * Puts the rejected mutation [id] back at the tail of the queue, under a new id and owned by
     * [identity], in one transaction with the list entry's removal.
     *
     * @param id The rejected mutation's id.
     * @param identity The stored value of the identity asking, or `null` to skip ownership checks.
     * @return The new pending mutation's id, or `null` when there is no such rejection, its body
     *   was too long to keep, or it belongs to another identity.
     */
    suspend fun retryRejectedMutation(id: Long, identity: String?): Long? =
        mutationQueueMutex.withLock {
            val entry =
                rejectedList.rejections().firstOrNull { rejection -> rejection.id == id }
                    ?: return@withLock null
            // An ownerless entry is only for an identity that may adopt ownerless mutations
            if (identity != null && !belongsTo(entry.owner, identity)) return@withLock null
            val row =
                entry.toRequeuedRow(createdAtMs = nowMillis(), owner = identity)
                    ?: return@withLock null
            val (rejectedRow, list) = rejectedList.rejectedRowWith(removeId = id)
            val newId = mutationDao.inTransaction {
                val inserted = mutationDao.insert(row)
                queryCacheDao.upsert(rejectedRow)
                inserted
            }
            rejectedList.rejectedWritten(list)
            noteMutationsChanged(row.table)
            newId
        }

    /**
     * Removes the rejected mutation [id] from the list.
     *
     * @param id The rejected mutation's id.
     * @return `true` when an entry was removed.
     */
    suspend fun dismissRejectedMutation(id: Long): Boolean = mutationQueueMutex.withLock {
        rejectedList.removeRejection(id) != null
    }

    /** [table]'s pending mutations, whatever their owner, in insertion order. */
    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in insertion order across **all tables**, whatever their owner.
     */
    suspend fun allPendingMutations(): List<MutationRow> = mutationDao.pendingAll()

    /**
     * The current identity's pending mutations — of [table], or of every table when it is `null` —
     * in insertion order, re-emitted on each change.
     *
     * @param table The table to observe, or `null` for the whole queue.
     * @return A flow of pending mutation rows.
     */
    fun pendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        combine(mutationDao.pendingAllFlow(), identityChanges) { rows, _ ->
                visibleTo(identityProvider(), rows).filter { row ->
                    table == null || row.table == table
                }
            }
            .distinctUntilChanged()

    /** The current identity's pending mutations across all tables. */
    suspend fun pendingMutationCount(): Int =
        visibleTo(identityProvider(), mutationDao.pendingAll()).size

    /** Flow of the current identity's pending mutations across all tables. */
    fun pendingMutationCountFlow(): Flow<Int> = pendingMutationsFlow(null).map { rows -> rows.size }

    /** The current identity's pending mutations for a specific [table]. */
    suspend fun pendingMutationCountForTable(table: String): Int =
        visibleTo(identityProvider(), mutationDao.pendingForTable(table)).size

    /** Flow of the current identity's pending mutations for a specific [table]. */
    fun pendingMutationCountForTableFlow(table: String): Flow<Int> =
        pendingMutationsFlow(table).map { rows -> rows.size }

    /*
     * What the app is shown of the queue: the current identity's mutations and the ownerless ones
     * it may adopt, and nothing at all while the identity cannot be told. The overlay, by contrast,
     * shows every identity's then, so a write is never hidden from its maker.
     */

    /**
     * The pending mutations the app is shown, of [table] or of every table, in insertion order, as
     * they change.
     */
    fun shownPendingMutationsFlow(table: String?): Flow<List<MutationRow>> =
        combine(mutationDao.pendingAllFlow(), identityChanges) { rows, _ ->
                shownTo(identityProvider(), rows).filter { row ->
                    table == null || row.table == table
                }
            }
            .distinctUntilChanged()

    /** How many pending mutations the app is shown, of [table] or of every table. */
    suspend fun shownPendingMutationCount(table: String?): Int {
        val identity = identityProvider() ?: return 0
        val adopts = mayAdoptOwnerlessMutations(identity)
        return if (table == null) mutationDao.countPendingOwnedBy(identity, adopts)
        else mutationDao.countPendingOwnedByInTable(identity, adopts, table)
    }

    /**
     * How many pending mutations and rejected entries the app is shown, read together under the
     * queue's lock. A refusal and a retry move a mutation between the two under that lock, so it is
     * in exactly one of them, never both and never neither.
     */
    suspend fun shownQueueCounts(): ShownQueueCounts {
        val identity = identityProvider() ?: return ShownQueueCounts(pending = 0, rejected = 0)
        val adopts = mayAdoptOwnerlessMutations(identity)
        return mutationQueueMutex.withLock {
            ShownQueueCounts(
                pending = mutationDao.countPendingOwnedBy(identity, adopts),
                rejected =
                    shownTo(identity, rejectedList.rejections()) { entry -> entry.owner }.size,
            )
        }
    }

    /**
     * Emits once at once, and once for each committed change to any mutation row or a change of
     * session: when a count of the queue may have moved.
     */
    fun queueChanges(): Flow<Unit> =
        combine(mutationChanges, identityChanges) { _, _ -> }.conflate()

    /** The rejected mutations the app is shown, oldest first, as they change. */
    fun shownRejectionsFlow(): Flow<List<StoredRejection>> =
        combine(rejectedList.rejectionsFlow(), identityChanges) { entries, _ ->
                shownTo(identityProvider(), entries) { entry -> entry.owner }
            }
            .distinctUntilChanged()

    /**
     * The rejected mutation [id] when the app is shown it: [identity]'s, or ownerless and
     * [identity] may adopt it.
     */
    suspend fun shownRejection(id: Long, identity: String): StoredRejection? =
        rejectedList.rejections().firstOrNull { entry ->
            entry.id == id && belongsTo(entry.owner, identity)
        }

    /**
     * Removes the rejected mutation [id] from the list when it is [identity]'s to remove, as
     * [shownRejection] decides.
     *
     * @return `true` when an entry was removed.
     */
    suspend fun dismissRejectedMutationOf(id: Long, identity: String): Boolean =
        mutationQueueMutex.withLock {
            if (shownRejection(id, identity) == null) return@withLock false
            rejectedList.removeRejection(id) != null
        }

    /** [rows] the app may be shown under [identity]; none while it cannot be told. */
    private fun shownTo(identity: String?, rows: List<MutationRow>): List<MutationRow> =
        shownTo(identity, rows) { row -> row.owner }

    /** [entries] the app may be shown under [identity], by [ownerOf]; none without an identity. */
    private fun <T> shownTo(identity: String?, entries: List<T>, ownerOf: (T) -> String?): List<T> {
        if (identity == null) return emptyList()
        return entries.filter { entry -> belongsTo(ownerOf(entry), identity) }
    }

    /**
     * The mutations of [rows] visible to [identity] — its own, and ownerless ones it may adopt — or
     * all of them when there is no identity to filter by.
     */
    fun visibleTo(identity: String?, rows: List<MutationRow>): List<MutationRow> {
        if (identity == null) return rows
        return rows.filter { row -> belongsTo(row.owner, identity) }
    }

    /**
     * Claims the one report, per store, that a write's dry-run status could not be determined.
     *
     * @return `true` the first time only; the caller then logs it at ERROR.
     */
    internal fun claimUnclassifiableWriteReport(): Boolean =
        unclassifiableWriteReported.compareAndSet(expectedValue = false, newValue = true)

    /**
     * Marks mutation [id] replayed with the next sync sequence, clearing its send record. In the
     * same transaction it removes any stale rejected entry; it also advances [table]'s
     * stale-refresh watermark, so a refresh that started before the replay cannot land after it.
     */
    suspend fun recordReplayed(id: Long, table: String) {
        freshness.advanceLocalWriteWatermark(table)
        mutationQueueMutex.withLock {
            val sequence = latestSyncSequence() + 1
            // Its outcome is known now: markSynced clears its send record. And a crash between
            // recording a rejection and removing its row leaves both: the stale entry must go too.
            unconfirmedSends()
            val rejected =
                if (rejectedList.hasRejection(id)) rejectedList.rejectedRowWith(removeId = id)
                else null
            mutationDao.runInTransaction {
                mutationDao.markSynced(id = id, syncedAtMs = nowMillis(), syncSequence = sequence)
                rejected?.let { (row, _) -> queryCacheDao.upsert(row) }
            }
            latestSyncSequence = sequence
            unconfirmedSendIds.update { current -> current?.minus(id) }
            rejected?.let { (_, next) -> rejectedList.rejectedWritten(next) }
            noteMutationsChanged(table)
        }
    }

    /**
     * Records, before its request goes out, that the send of mutation [id] has started — when it is
     * an INSERT whose rows all carry the table's primary key, the only kind whose replay can
     * tolerate finding its rows already on the server. It then replays as an upsert that ignores
     * duplicates, until [clearUnconfirmedSend] or its removal clears the record.
     *
     * @return `true` when this call added the record; `false` when there was one already, or none
     *   is kept for this mutation.
     */
    suspend fun markSendUnconfirmed(id: Long): Boolean {
        val mutation = mutationDao.byId(id) ?: return false
        if (!isKeyedInsert(mutation)) return false
        return mutationQueueMutex.withLock {
            unconfirmedSends()
            val marked = mutationDao.markSendUnconfirmed(id) > 0
            if (marked) unconfirmedSendIds.update { current -> current?.plus(id) }
            marked
        }
    }

    /**
     * Whether [mutation] is an INSERT whose rows all carry its table's primary key: the only kind
     * whose replay can take rows already on the server as its own.
     */
    private fun isKeyedInsert(mutation: MutationRow): Boolean {
        if (mutation.operation != MutationOperation.INSERT) return false
        val body =
            try {
                json.parseToJsonElement(mutation.bodyJson) as? JsonArray
            } catch (_: IllegalArgumentException) {
                // A body that no longer parses is refused at replay; there is nothing to record.
                null
            } ?: return false
        return everyRowCarriesPrimaryKey(body, primaryKeys.of(mutation.table))
    }

    /**
     * Clears the record that the send of mutation [id] may have reached the server: it never did.
     */
    suspend fun clearUnconfirmedSend(id: Long) {
        mutationQueueMutex.withLock {
            unconfirmedSends()
            mutationDao.clearSendUnconfirmed(id)
            unconfirmedSendIds.update { current -> current?.minus(id) }
        }
    }

    /**
     * Whether mutation [id]'s send started and its outcome is unknown, so the server may hold its
     * rows.
     */
    suspend fun hasUnconfirmedSend(id: Long): Boolean = id in unconfirmedSends()

    /**
     * The INSERT mutations whose send started and whose outcome is unknown. Their replay must
     * tolerate finding their rows already on the server; every other INSERT replays as sent. Read
     * once from the rows, then kept in step: a writer reads it before its change, so the change
     * always reaches it.
     */
    suspend fun unconfirmedSends(): Set<Long> {
        unconfirmedSendIds.load()?.let {
            return it
        }
        val loaded = mutationDao.unconfirmedSendIds().toSet()
        unconfirmedSendIds.compareAndSet(null, loaded)
        return unconfirmedSendIds.load() ?: loaded
    }

    /**
     * Retires [table]'s synced mutations that no cached query needs any more, and those beyond the
     * newest [MAX_RETAINED_SYNCED_PER_TABLE]; see [retireSyncedMutationsOf]. Skipped when
     * [requestStartedAtTick] predates the table's last local write.
     */
    suspend fun retireCoveredSyncedMutations(table: String, requestStartedAtTick: Long? = null) {
        if (isReservedTable(table)) return
        if (freshness.isStaleRelativeToLocalWrite(table, requestStartedAtTick)) return
        tableWriteLocks.of(table).withLock { retireSyncedMutationsOf(table) }
    }

    /**
     * Retires, in every table, the synced mutations no cached query needs any more and those beyond
     * each table's bound: a table whose cached queries were dropped or evicted keeps none.
     */
    suspend fun retireUnneededSyncedMutations() {
        mutationDao.tablesWithSyncedMutations().forEach { table ->
            tableWriteLocks.of(table).withLock { retireSyncedMutationsOf(table) }
        }
    }

    /**
     * Retires [table]'s synced mutations that every cached payload of the table already holds
     * ([CachedQueryRow.syncedThrough]), when no live query is waiting for its first payload, and
     * the oldest beyond the newest [MAX_RETAINED_SYNCED_PER_TABLE] whatever still shows them. No
     * clock is read, so a clock set back or forward neither keeps one nor retires one. Caller holds
     * the table's write lock.
     */
    private suspend fun retireSyncedMutationsOf(table: String) {
        val synced = mutationDao.syncedForTable(table)
        if (synced.isEmpty()) return
        val waiting =
            (liveCacheKeysOf(table) - queryCacheDao.cacheKeysForTable(table).toSet()).isNotEmpty()
        val heldByEvery = queryCacheDao.minSyncedThroughForTable(table)
        val held =
            if (waiting) {
                emptyList()
            } else {
                synced.filter { mutation ->
                    val sequence = mutation.syncSequence
                    heldByEvery == null || (sequence != null && sequence <= heldByEvery)
                }
            }
        val beyondBound = (synced - held.toSet()).dropLast(MAX_RETAINED_SYNCED_PER_TABLE)
        removeMutations(
            (held + beyondBound).map { mutation -> mutation.id },
            clearRejections = false,
            tables = listOf(table),
        )
    }

    /** Runs [block] under the queue lock. */
    suspend fun <T> holdingQueueLock(block: suspend () -> T): T = mutationQueueMutex.withLock {
        block()
    }

    /**
     * Claims the one report of mutation [id]'s overlay failure: the overlay is recomputed on every
     * emission, and a mutation that fails once fails every time.
     *
     * @return `true` the first time only.
     */
    fun claimOverlaySkipReport(id: Long): Boolean {
        var first = false
        loggedOverlaySkips.update { logged ->
            first = id !in logged
            logged + id
        }
        return first
    }

    /** [table]'s mutation rows, pending and synced, in order. */
    suspend fun mutationsOfTable(table: String): List<MutationRow> = mutationDao.allForTable(table)

    /**
     * Forgets the records a clear deleted with the queue: the rejected list and the sends whose
     * outcome is unknown. Caller holds the queue lock, and calls it inside the clear's owner
     * change.
     */
    fun forgetRecordsOfClearedQueue() {
        rejectedList.resetCaches()
        unconfirmedSendIds.store(emptySet())
    }

    /**
     * Forgets what readers last read of a cleared queue and the overlay failures reported for it,
     * then tells every table's readers. Caller holds the queue lock.
     */
    fun forgetReadsOfClearedQueue() {
        loggedOverlaySkips.store(emptySet())
        lastTableMutations.store(emptyMap())
        noteEveryTableChanged()
    }

    private companion object {
        /**
         * The most synced mutations a table keeps for cached queries fetched before they replayed;
         * the oldest go first. A bound on work and space, read from no clock.
         */
        const val MAX_RETAINED_SYNCED_PER_TABLE = 1_000
    }
}

// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
@file:OptIn(ExperimentalAtomicApi::class)

package io.github.nesfeder.supabase.offline.internal.mutationqueue

import io.github.nesfeder.supabase.offline.MutationOperation
import io.github.nesfeder.supabase.offline.internal.cache.CacheFreshness
import io.github.nesfeder.supabase.offline.internal.cache.ShownQueueCounts
import io.github.nesfeder.supabase.offline.internal.cache.TableWriteLocks
import io.github.nesfeder.supabase.offline.internal.cache.keyOf
import io.github.nesfeder.supabase.offline.internal.db.CachedQueryRow
import io.github.nesfeder.supabase.offline.internal.db.ChangeCounts
import io.github.nesfeder.supabase.offline.internal.db.MutationDao
import io.github.nesfeder.supabase.offline.internal.db.MutationRow
import io.github.nesfeder.supabase.offline.internal.db.OpenDatabaseRegistry
import io.github.nesfeder.supabase.offline.internal.db.QueryCacheDao
import io.github.nesfeder.supabase.offline.internal.db.STORAGE_UPLOADS_TABLE_NAME
import io.github.nesfeder.supabase.offline.internal.db.inTransaction
import io.github.nesfeder.supabase.offline.internal.db.isReservedTable
import io.github.nesfeder.supabase.offline.internal.db.knownOperation
import io.github.nesfeder.supabase.offline.internal.db.requireUserTable
import io.github.nesfeder.supabase.offline.internal.db.storedName
import io.github.nesfeder.supabase.offline.internal.logging.EngineLogger
import io.github.nesfeder.supabase.offline.internal.postgrest.FilterDescriptor
import io.github.nesfeder.supabase.offline.internal.postgrest.decodeFilterOrNull
import io.github.nesfeder.supabase.offline.internal.postgrest.encodeFilter
import io.github.nesfeder.supabase.offline.internal.storage.StoredUpload
import io.github.nesfeder.supabase.offline.internal.storage.UPLOAD_OPERATION_NAME
import io.github.nesfeder.supabase.offline.internal.storage.isQueuedUpload
import io.github.nesfeder.supabase.offline.internal.storage.storedUploadOf
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonObject

/**
 * The mutation queue: its rows, the records kept beside them, and every change to either, from
 * enqueue to the retirement of replayed mutations. Owns the queue lock; takes a table's write lock
 * only to retire, and never while holding its own.
 *
 * @param queryCacheDao where the rejected list is kept and payloads' coverage is read.
 * @param primaryKeys each table's key columns, which tell a keyed INSERT.
 * @param identityProvider the current identity's stored value, or `null` when it cannot be told.
 * @param identityChanges emits when the session may have changed, so what the app is shown is
 *   filtered again.
 * @param ownerlessMutationsAreAnonymous whether mutations queued with no owner are the anonymous
 *   identity's: without the Auth plugin there is no other.
 * @param liveCacheKeysOf a table's queries with a live reader; retirement keeps what they wait for.
 * @param engineLogger where the rejected list reports a row it could not read, and where a failed
 *   read of the queue is reported.
 * @property versionColumns each table's version column, which guards a queued UPDATE or DELETE of
 *   one row.
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
    private val ownerlessMutationsAreAnonymous: Boolean,
    private val liveCacheKeysOf: (table: String) -> Set<String>,
    private val engineLogger: EngineLogger,
    val versionColumns: VersionColumns = NoVersionColumns,
) {

    /** The mutation queue's record kept beside the rows: the rejected list. */
    internal val rejectedList: RejectedMutationList =
        RejectedMutationList(
            queryCacheDao = queryCacheDao,
            json = json,
            nowMillis = nowMillis,
            logger = engineLogger,
        )

    /** The mutation queue's other record kept beside the rows: the conflicted list. */
    internal val conflictedList: ConflictedMutationList =
        ConflictedMutationList(
            queryCacheDao = queryCacheDao,
            json = json,
            nowMillis = nowMillis,
            logger = engineLogger,
        )

    /**
     * Where a guarded write's base row is read from: nowhere until [readBasesThrough] is called.
     */
    private val cachedRowLookup = AtomicReference<CachedRowLookup?>(null)

    /** Reads every later base from [lookup]; the components call it once, as they are built. */
    fun readBasesThrough(lookup: CachedRowLookup) {
        cachedRowLookup.store(lookup)
    }

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

    /**
     * Queues writes for [owner]: each row is stored with its owner in one insert, and the readers
     * of its table are told.
     *
     * @property owner The stored value of the identity making the writes, or `null` to record none
     *   (a store driven directly, with no session model).
     */
    internal inner class MutationWriter(private val owner: String?) {

        /**
         * Queues an INSERT.
         *
         * @return The row as stored; its id is the row's autoincrement id, stable across restarts.
         */
        suspend fun enqueueInsert(
            table: String,
            body: JsonArray,
            filter: FilterDescriptor? = null,
            defaultToNull: Boolean = true,
        ): MutationRow =
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
         * @return The row as stored.
         */
        suspend fun enqueueUpsert(
            table: String,
            bodyJson: JsonArray,
            filter: FilterDescriptor? = null,
            onConflict: String? = null,
            ignoreDuplicates: Boolean = false,
            defaultToNull: Boolean = true,
        ): MutationRow =
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
         * @param base the write's base, read with [baseFor] before the queue lock; `null` for none.
         * @return The row as stored.
         */
        suspend fun enqueueUpdate(
            table: String,
            body: JsonElement,
            filter: FilterDescriptor? = null,
            base: MutationBase? = null,
        ): MutationRow =
            insert(
                table = table,
                operation = MutationOperation.UPDATE,
                bodyJson = body.toString(),
                filter = filter,
                base = base,
            )

        /**
         * Queues a DELETE. [filter] describes which rows are removed.
         *
         * @param base the write's base, read with [baseFor] before the queue lock; `null` for none.
         * @return The row as stored.
         */
        suspend fun enqueueDelete(
            table: String,
            filter: FilterDescriptor? = null,
            base: MutationBase? = null,
        ): MutationRow =
            insert(
                table = table,
                operation = MutationOperation.DELETE,
                bodyJson = "{}",
                filter = filter,
                base = base,
            )

        /**
         * Queues a Storage upload, whose bytes [upload] names a file of; the file is written first,
         * so a row always has one.
         *
         * @return The row as stored.
         */
        suspend fun enqueueUpload(upload: StoredUpload): MutationRow {
            val row =
                MutationRow(
                    table = STORAGE_UPLOADS_TABLE_NAME,
                    operation = UPLOAD_OPERATION_NAME,
                    bodyJson = upload.toBodyJson(json),
                    filterJson = null,
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                    owner = owner,
                )
            val stored = row.copy(id = mutationDao.insert(row))
            noteMutationsChanged(STORAGE_UPLOADS_TABLE_NAME)
            return stored
        }

        /** Stores one pending mutation of [owner]'s, tells [table]'s readers, and returns it. */
        private suspend fun insert(
            table: String,
            operation: MutationOperation,
            bodyJson: String,
            filter: FilterDescriptor?,
            defaultToNull: Boolean? = null,
            onConflict: String? = null,
            ignoreDuplicates: Boolean? = null,
            base: MutationBase? = null,
        ): MutationRow {
            requireUserTable(table)
            val row =
                MutationRow(
                    table = table,
                    operation = operation.storedName,
                    bodyJson = bodyJson,
                    filterJson = filter?.let { encodeFilter(it, json) },
                    createdAtMs = nowMillis(),
                    syncedAtMs = null,
                    defaultToNull = defaultToNull,
                    onConflict = onConflict,
                    ignoreDuplicates = ignoreDuplicates,
                    owner = owner,
                    baseVersion = base?.version,
                    baseJson = base?.json,
                )
            val stored = row.copy(id = mutationDao.insert(row))
            noteMutationsChanged(table)
            return stored
        }
    }

    /**
     * The base of an UPDATE or DELETE of [table] by [filter] that sets [columns] (none for a
     * DELETE), read from [identity]'s cache: the newest cached row with the key [filter] pins and
     * the version column, or `null` to replay it unguarded. Read before the queue lock, so a
     * forward can land between this read and the insert and leave an older base: its replay then
     * meets a stale base when the overlapping send set none of this write's columns, and otherwise
     * a conflict for the policy (whose default sends the write).
     *
     * @param identity the enqueuing identity's stored value, or `null` when there is none.
     */
    suspend fun baseFor(
        table: String,
        filter: FilterDescriptor?,
        columns: Set<String>?,
        identity: String?,
    ): MutationBase? {
        val versionColumn = versionColumns.of(table) ?: return null
        val lookup = cachedRowLookup.load() ?: return null
        if (filter == null || columns == null) return null
        // A second top-level filter on the version column would be dropped on the wire
        if (filter.conditions.any { condition -> condition.column == versionColumn }) return null
        val keyColumns = primaryKeys.of(table)
        val key = pinnedRowKeyOf(filter, keyColumns)?.let { pinned -> keyOf(pinned, keyColumns) }
        if (key == null) return null
        val row =
            try {
                lookup.cachedRowHolding(table, key, versionColumn, identity)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                engineLogger.warning {
                    "Could not read the cached row of a queued write to $table " +
                        "(${failure::class.simpleName}); it replays without a version check"
                }
                null
            } ?: return null
        val version = versionTextOf(row[versionColumn]) ?: return null
        val baseColumns = (columns + versionColumn).filter { column -> column in row }
        return MutationBase(
            version = version,
            json =
                JsonObject(baseColumns.associateWith { column -> row.getValue(column) }).toString(),
        )
    }

    /**
     * Refreshes the base of every pending guarded mutation of [table] queued after [afterId] by
     * [owner] whose row is one of [rows], which the server returned: their version and their
     * columns become the row's. Only [owner]'s writes: a base is what the device's user saw, so on
     * a shared device another user's queued write keeps its own base and meets the change as a
     * conflict. Runs inside the caller's transaction, under the queue lock, so it reads only the
     * later guarded writes of [owner], their filters and bases and never their bodies
     * ([MutationDao.pendingGuardedAfter]), and decodes a filter only for those.
     *
     * @param owner The completed mutation's owner, as stored; `null` for a mutation with none.
     * @return Whether any base moved.
     */
    private suspend fun forwardBases(
        table: String,
        afterId: Long,
        rows: List<JsonObject>,
        owner: String?,
    ): Boolean {
        val versionColumn = versionColumns.of(table) ?: return false
        if (rows.isEmpty()) return false
        val keyColumns = primaryKeys.of(table)
        val returnedByKey = HashMap<List<String>, JsonObject>()
        rows.forEach { row ->
            if (versionTextOf(row[versionColumn]) == null) return@forEach
            keyOf(row, keyColumns)?.let { key -> returnedByKey[key] = row }
        }
        if (returnedByKey.isEmpty()) return false
        var forwarded = false
        mutationDao.pendingGuardedAfter(table, afterId, owner).forEach { pending ->
            // The owner's exactly, as the query asks: a row with none was queued by a store with
            // no session model, as the completed write then was too, and under a session model a
            // pass adopts every ownerless row, under the send lock, before it sends anything
            if (pending.owner != owner) return@forEach
            val filter = decodeFilterOrNull(pending.filterJson, json) ?: return@forEach
            val key =
                pinnedRowKeyOf(filter, keyColumns)?.let { pinned -> keyOf(pinned, keyColumns) }
                    ?: return@forEach
            val returned = returnedByKey[key] ?: return@forEach
            val version = versionTextOf(returned[versionColumn]) ?: return@forEach
            val earlier = parsedBaseOf(pending.baseJson)
            val refreshed =
                earlier.keys.associateWith { column ->
                    returned[column] ?: earlier.getValue(column)
                } + (versionColumn to returned.getValue(versionColumn))
            mutationDao.updateBase(pending.id, version, JsonObject(refreshed).toString())
            forwarded = true
        }
        return forwarded
    }

    /** A base row as stored in [baseJson]; empty when there is none or it no longer parses. */
    private fun parsedBaseOf(baseJson: String?): JsonObject =
        baseJson?.let(::jsonObjectOrNull) ?: JsonObject(emptyMap())

    /** [text] as a JSON object, or `null` when it is not one or does not parse. */
    private fun jsonObjectOrNull(text: String): JsonObject? = jsonObjectOrNull(text, json)

    /**
     * Runs [enqueue], which queues one write with [identity] as its owner: a replay pass sends only
     * the current identity's writes. Refused when local data was cleared since [startedAtTick]; the
     * check and the insert run under the queue lock, which a clear holds.
     *
     * @param identity the owner's stored value, or `null` to record none (no session model).
     * @param startedAtTick the tick the write started at, or `null` to skip the check.
     * @param enqueue queues the write and returns its row as stored; DAO calls only.
     */
    suspend fun enqueueOwnedBy(
        identity: String?,
        startedAtTick: Long? = null,
        enqueue: suspend MutationWriter.() -> MutationRow,
    ): EnqueueOutcome = mutationQueueMutex.withLock {
        if (freshness.predatesQueueClear(startedAtTick))
            return@withLock EnqueueOutcome.LocalDataCleared
        val row = MutationWriter(owner = identity).enqueue()
        EnqueueOutcome.Queued(row.id, row)
    }

    /**
     * How many changes this queue made to each table's mutation rows, each counted once committed:
     * a flow, because readers collect it and read a table's rows again only when its count moves
     * ([mutationChangesOf], [queueChanges]). One queue per database ([OpenDatabaseRegistry]).
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
     * Emits once at once, and again each time this queue changes [table]'s mutation rows, without
     * reading them: the reader reads them itself, with [tableMutationsNow]. Another table's changes
     * cost it a comparison, not a read.
     */
    fun mutationChangesOf(table: String): Flow<Unit> =
        mutationChanges.map { counts -> counts.of(table) }.distinctUntilChanged().map {}

    /**
     * [table]'s mutation rows, pending and synced, in order, as they stand now. The table's readers
     * share one read, and each body's parse, per change; an overlay reads them in the same step as
     * the payload it lays them over.
     */
    suspend fun tableMutationsNow(table: String): TableMutations =
        tableMutationsAt(table, mutationChanges.value.of(table))

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

    /** How many mutations [pendingMutationsOf] would return, without reading them. */
    suspend fun pendingMutationCountOf(identity: String, table: String?): Int {
        val adopts = mayAdoptOwnerlessMutations(identity)
        return if (table == null) mutationDao.countPendingOwnedBy(identity, adopts)
        else mutationDao.countPendingOwnedByInTable(identity, adopts, table)
    }

    /** Whether [identity] has a mutation to replay, in any table. Reads the owner index only. */
    suspend fun hasPendingMutationsOf(identity: String): Boolean =
        mutationDao.hasPendingOwnedBy(
            identity,
            mayAdoptOwnerlessMutations(identity),
            beforeId = Long.MAX_VALUE,
        )

    /**
     * [hasPendingMutationsOf], or `true` when the queue cannot be read. For a caller deciding
     * whether to ask for a pass, which reads the queue itself: a failed read never loses the pass.
     * Throws only a cancellation.
     */
    suspend fun mayHavePendingMutationsOf(identity: String): Boolean =
        try {
            hasPendingMutationsOf(identity)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            engineLogger.warning {
                "Could not read whether queued writes wait (${failure::class.simpleName}); " +
                    "taking it that they do"
            }
            true
        }

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
        identity != QueueIdentity.Anonymous.storedValue || ownerlessMutationsAreAnonymous

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

    /** The mutation [id] as stored now, when it is queued and not yet synced; `null` otherwise. */
    suspend fun pendingMutation(id: Long): MutationRow? =
        mutationDao.byId(id)?.takeIf { row -> row.syncedAtMs == null }

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
     * [completeSentMutation] for a write to [table] whose response carried [returnedRows]: in the
     * same transaction, later guarded writes to those rows take them as their base.
     */
    suspend fun completeSentMutation(id: Long, table: String, returnedRows: List<JsonObject>) {
        removeMutations(
            listOf(id),
            clearRejections = true,
            forwarding = Forwarding(table, id, returnedRows),
        )
    }

    /**
     * Drops mutation [id], which a conflict decision let the server's row stand over, with any
     * rejected entry a crash left for it.
     */
    suspend fun dropKeptServerMutation(id: Long) {
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
        forwarding: Forwarding? = null,
    ) {
        if (ids.isEmpty()) return
        mutationQueueMutex.withLock {
            val idSet = ids.toSet()
            val found =
                if (tables == null) idSet.mapNotNull { id -> mutationDao.byId(id) } else null
            val touched = tables ?: found.orEmpty().map { row -> row.table }
            // Loaded first, so the change below reaches it.
            unconfirmedSends()
            val rejected =
                if (clearRejections && idSet.any { id -> rejectedList.hasRejection(id) }) {
                    rejectedList.rejectedRowWith(removeIds = idSet)
                } else {
                    null
                }
            // Removed already, as an accepted head write is under its table's readers' lock
            if (touched.isEmpty() && rejected == null) return@withLock
            // A row already gone cannot say whose it was: its response forwards to nothing
            val completed = forwarding?.let { (_, afterId, _) ->
                found?.firstOrNull { row -> row.id == afterId } ?: mutationDao.byId(afterId)
            }
            val forwarded = mutationDao.inTransaction {
                idSet.forEach { id -> mutationDao.deleteById(id) }
                rejected?.let { (row, _) -> queryCacheDao.upsert(row) }
                if (forwarding != null && completed != null) {
                    forwardBases(
                        forwarding.table,
                        forwarding.afterId,
                        forwarding.rows,
                        owner = completed.owner,
                    )
                } else {
                    false
                }
            }
            if (forwarded && forwarding != null) noteMutationsChanged(forwarding.table)
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
                baseVersion = mutation.baseVersion,
                baseJson = mutation.baseJson,
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
     * Moves [mutation] out of the queue and onto the conflicted list, in one transaction, as
     * [rejectMutation] moves a refused write onto the rejected list.
     *
     * @param parked What the replay found, without the mutation's own fields.
     * @return The persisted entry.
     */
    suspend fun parkConflictedMutation(
        mutation: MutationRow,
        parked: ParkedConflict,
    ): StoredConflict = mutationQueueMutex.withLock {
        val entry =
            StoredConflict(
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
                baseVersion = mutation.baseVersion,
                baseJson = mutation.baseJson,
                serverRowJson = parked.serverRow?.toString(),
                serverVersion = parked.serverVersion,
                changedOnServer = parked.changedOnServer.sorted(),
                conflictingColumns = parked.conflictingColumns.sorted(),
                message = parked.message,
                conflictedAtMs = nowMillis(),
            )
        val (conflictedRow, list) = conflictedList.conflictedRowWith(add = entry)
        // Loaded first, so the change below reaches it.
        unconfirmedSends()
        mutationDao.runInTransaction {
            queryCacheDao.upsert(conflictedRow)
            mutationDao.deleteById(mutation.id)
        }
        conflictedList.conflictWritten(list)
        unconfirmedSendIds.update { current -> current?.minus(mutation.id) }
        noteMutationsChanged(mutation.table)
        list.entries.firstOrNull { stored -> stored.id == mutation.id } ?: entry
    }

    /**
     * Puts the conflicted mutation [id] back at the tail of the queue under a new id, owned by
     * [identity] and preconditioned on the server version the entry recorded, in one transaction
     * with the entry's removal. [ConflictDecision.KeepServer] only removes the entry.
     *
     * @param decision [ConflictDecision.KeepLocal] (a row still on the server),
     *   [ConflictDecision.Apply] (an UPDATE of one) or [ConflictDecision.KeepServer].
     * @return What became of the entry.
     */
    suspend fun requeueConflict(
        id: Long,
        identity: String?,
        decision: ConflictDecision,
    ): ConflictRequeue = mutationQueueMutex.withLock {
        val entry =
            conflictedList.conflicts().firstOrNull { conflict -> conflict.id == id }
                ?: return@withLock ConflictRequeue.NotFound
        if (identity != null && !belongsTo(entry.owner, identity))
            return@withLock ConflictRequeue.NotFound
        if (decision == ConflictDecision.KeepServer) {
            conflictedList.removeConflict(id)
            return@withLock ConflictRequeue.Dropped
        }
        val row =
            requeuedRowOf(entry, decision, owner = identity)
                ?: return@withLock ConflictRequeue.Refused
        val (conflictedRow, list) = conflictedList.conflictedRowWith(removeId = id)
        val newId = mutationDao.inTransaction {
            val inserted = mutationDao.insert(row)
            queryCacheDao.upsert(conflictedRow)
            inserted
        }
        conflictedList.conflictWritten(list)
        noteMutationsChanged(row.table)
        ConflictRequeue.Requeued(newId)
    }

    /**
     * The row [entry] re-queues as under [decision], at the tail: its body, or for
     * [ConflictDecision.Apply] the given columns, preconditioned on the server's version; `null`
     * when the decision does not apply to it (a row gone from the server takes neither, since it
     * would replay unguarded and change nothing) or it would resend a body that was not kept.
     */
    private fun requeuedRowOf(
        entry: StoredConflict,
        decision: ConflictDecision,
        owner: String?,
    ): MutationRow? {
        if (entry.serverVersion == null) return null
        if (entry.bodyOmittedChars != null && decision !is ConflictDecision.Apply) return null
        val bodyJson =
            when (decision) {
                ConflictDecision.KeepLocal -> entry.bodyJson
                is ConflictDecision.Apply ->
                    if (
                        entry.operation == MutationOperation.UPDATE.storedName &&
                            entry.serverVersion != null
                    )
                        decision.columns.toString()
                    else return null
                ConflictDecision.KeepServer,
                ConflictDecision.Hold -> return null
            }
        val versionColumn = versionColumns.of(entry.table)
        val server = entry.serverRowJson?.let(::jsonObjectOrNull)
        val columns =
            (jsonObjectOrNull(bodyJson)?.keys.orEmpty() + listOfNotNull(versionColumn)).filter {
                column ->
                server != null && column in server
            }
        val baseJson =
            if (entry.serverVersion == null || server == null) null
            else JsonObject(columns.associateWith { column -> server.getValue(column) }).toString()
        return MutationRow(
            table = entry.table,
            operation = entry.operation,
            bodyJson = bodyJson,
            filterJson = entry.filterJson,
            createdAtMs = nowMillis(),
            syncedAtMs = null,
            defaultToNull = entry.defaultToNull,
            onConflict = entry.onConflict,
            ignoreDuplicates = entry.ignoreDuplicates,
            owner = owner,
            baseVersion = entry.serverVersion,
            baseJson = baseJson,
        )
    }

    /** The conflicted mutations the app is shown, oldest first, as they change. */
    fun shownConflictsFlow(): Flow<List<StoredConflict>> =
        combine(conflictedList.conflictsFlow(), identityChanges) { entries, _ ->
                shownConflictsOf(entries)
            }
            .distinctUntilChanged()

    /** The conflicted mutations the app is shown now, oldest first, as [shownConflictsFlow]. */
    suspend fun shownConflicts(): List<StoredConflict> =
        shownConflictsOf(conflictedList.conflicts())

    /** The conflicted list's [entries] the app is shown under the current identity. */
    private suspend fun shownConflictsOf(entries: List<StoredConflict>): List<StoredConflict> =
        shownTo(identityProvider(), entries) { entry -> entry.owner }

    /** The conflicted mutation [id] when the app is shown it, as [shownRejection] decides. */
    suspend fun shownConflict(id: Long, identity: String): StoredConflict? =
        conflictedList.conflicts().firstOrNull { entry ->
            entry.id == id && belongsTo(entry.owner, identity)
        }

    /**
     * Removes the conflicted mutation [id] when it is [identity]'s to remove.
     *
     * @return `true` when an entry was removed.
     */
    suspend fun dismissConflictOf(id: Long, identity: String): Boolean =
        mutationQueueMutex.withLock {
            if (shownConflict(id, identity) == null) return@withLock false
            conflictedList.removeConflict(id) != null
        }

    /** [table]'s pending mutations, whatever their owner, in insertion order. */
    suspend fun pendingMutationsForTable(table: String): List<MutationRow> =
        mutationDao.pendingForTable(table)

    /**
     * Returns all pending mutations in insertion order across **all tables**, whatever their owner.
     */
    suspend fun allPendingMutations(): List<MutationRow> = mutationDao.pendingAll()

    /** How many mutations are pending, of [table] or of every table, whatever their owner. */
    suspend fun allPendingMutationCount(table: String?): Int =
        if (table == null) mutationDao.countPending() else mutationDao.countPendingForTable(table)

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
        return pendingMutationCountOf(identity, table)
    }

    /**
     * How many pending mutations, rejected and conflicted entries the app is shown, read together
     * under the queue's lock. A refusal, a park, a retry and a resolve move a mutation between them
     * under that lock, so it is in exactly one of them, never two and never none.
     */
    suspend fun shownQueueCounts(): ShownQueueCounts {
        val identity =
            identityProvider() ?: return ShownQueueCounts(pending = 0, rejected = 0, conflicted = 0)
        val adopts = mayAdoptOwnerlessMutations(identity)
        return mutationQueueMutex.withLock {
            ShownQueueCounts(
                pending = mutationDao.countPendingOwnedBy(identity, adopts),
                rejected =
                    shownTo(identity, rejectedList.rejections()) { entry -> entry.owner }.size,
                conflicted =
                    shownTo(identity, conflictedList.conflicts()) { entry -> entry.owner }.size,
            )
        }
    }

    /**
     * The queued Storage uploads the app is shown, in queue order: the current identity's, and the
     * ownerless ones it may adopt; none while the identity cannot be told. Read from the uploads'
     * shared read ([tableMutationsNow]), which is read again only when an upload's row changes, so
     * a lookup costs no query while the queue holds none.
     */
    suspend fun shownPendingUploads(): List<MutationRow> =
        shownTo(
            identityProvider(),
            tableMutationsNow(STORAGE_UPLOADS_TABLE_NAME).rows.filter { row ->
                row.syncedAtMs == null && row.isQueuedUpload
            },
        )

    /**
     * The queued Storage uploads the overlay would show, in queue order: [shownPendingUploads], or
     * every identity's while the identity cannot be told, as a write is never hidden from its
     * maker.
     */
    suspend fun visiblePendingUploads(): List<MutationRow> =
        visibleTo(
            identityProvider(),
            tableMutationsNow(STORAGE_UPLOADS_TABLE_NAME).rows.filter { row ->
                row.syncedAtMs == null && row.isQueuedUpload
            },
        )

    /** [shownPendingUploads] as the queue changes. */
    fun shownPendingUploadsFlow(): Flow<List<MutationRow>> =
        shownPendingMutationsFlow(STORAGE_UPLOADS_TABLE_NAME).map { rows ->
            rows.filter { row -> row.isQueuedUpload }
        }

    /**
     * Every queued file a pending upload or a rejected entry names, whoever's it is. Read under the
     * queue lock ([holdingQueueLock]) by a caller deleting the files it does not name, so no upload
     * is queued in between.
     */
    suspend fun queuedUploadFileNames(): Set<String> {
        val pending =
            mutationDao.pendingForTable(STORAGE_UPLOADS_TABLE_NAME).filter { row ->
                row.isQueuedUpload
            }
        val rejected =
            rejectedList
                .rejections()
                .filter { entry -> entry.isQueuedUpload }
                .map { entry ->
                    entry.bodyJson
                }
        return (pending.map { row -> row.bodyJson } + rejected).mapNotNullTo(HashSet()) { body ->
            storedUploadOf(body, json)?.file
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
                shownRejectionsOf(entries)
            }
            .distinctUntilChanged()

    /** The rejected mutations the app is shown now, oldest first, as [shownRejectionsFlow]. */
    suspend fun shownRejections(): List<StoredRejection> =
        shownRejectionsOf(rejectedList.rejections())

    /** The rejected list's [entries] the app is shown under the current identity. */
    private suspend fun shownRejectionsOf(entries: List<StoredRejection>): List<StoredRejection> =
        shownTo(identityProvider(), entries) { entry -> entry.owner }

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
        recordReplayed(id, table, returnedRows = emptyList(), owner = null)
    }

    /**
     * [recordReplayed] for a replay whose response carried [returnedRows]: in the same transaction,
     * [owner]'s later guarded writes to those rows take them as their base.
     *
     * @param owner The replayed mutation's owner, as stored; `null` for a mutation with none.
     */
    suspend fun recordReplayed(
        id: Long,
        table: String,
        returnedRows: List<JsonObject>,
        owner: String?,
    ) {
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
                forwardBases(table, afterId = id, rows = returnedRows, owner = owner)
            }
            latestSyncSequence = sequence
            unconfirmedSendIds.update { current -> current?.minus(id) }
            rejected?.let { (_, next) -> rejectedList.rejectedWritten(next) }
            noteMutationsChanged(table)
        }
    }

    /**
     * Records, before its request goes out, that the send of mutation [id] has started — when it is
     * an INSERT whose rows all carry the table's primary key, or a queued upload: the kinds whose
     * replay can tolerate finding what they write already on the server. The INSERT then replays as
     * an upsert that ignores duplicates, and the upload first asks Storage whether the object is
     * there, until [clearUnconfirmedSend] or its removal clears the record.
     *
     * @return `true` when this call added the record; `false` when there was one already, or none
     *   is kept for this mutation.
     */
    suspend fun markSendUnconfirmed(id: Long): Boolean {
        val mutation = mutationDao.byId(id) ?: return false
        if (!isKeyedInsert(mutation) && !mutation.isQueuedUpload) return false
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
        if (mutation.knownOperation != MutationOperation.INSERT) return false
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
     * clock is read, so a clock set back or forward neither keeps one nor retires one, and no body
     * is read: ids and sequences decide. Caller holds the table's write lock.
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
     * Forgets the records a clear deleted with the queue: the rejected and conflicted lists and the
     * sends whose outcome is unknown. Caller holds the queue lock, and calls it inside the clear's
     * owner change.
     */
    fun forgetRecordsOfClearedQueue() {
        rejectedList.resetCaches()
        conflictedList.resetCaches()
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

/**
 * A guarded write's base, as [MutationRow.baseVersion] and [MutationRow.baseJson] hold it.
 *
 * @property version The row's version as text.
 * @property json The row's columns the write names, and the version column, as a JSON object.
 */
internal class MutationBase(val version: String, val json: String)

/**
 * Rows a sent write's response carried, which later guarded writes to [table] queued after
 * [afterId] take as their base.
 */
private data class Forwarding(val table: String, val afterId: Long, val rows: List<JsonObject>)

/**
 * What a replay found when it parked a write, beside the write's own fields.
 *
 * @property serverRow The server's row; `null` when it was gone.
 * @property serverVersion Its version; `null` when it was gone.
 * @property changedOnServer The write's columns whose server value differs from the base.
 * @property conflictingColumns Of those, the ones the write also sets to another value.
 * @property message Why it was parked.
 */
internal class ParkedConflict(
    val serverRow: JsonObject?,
    val serverVersion: String?,
    val changedOnServer: Set<String>,
    val conflictingColumns: Set<String>,
    val message: String,
)

/**
 * Finds the cached row a guarded write's base is read from; see [MutationQueue.readBasesThrough].
 */
internal fun interface CachedRowLookup {

    /**
     * The newest row with [key] that holds [column] in [identity]'s cache of [table], or `null`.
     */
    suspend fun cachedRowHolding(
        table: String,
        key: List<String>,
        column: String,
        identity: String?,
    ): JsonObject?
}

/** What [MutationQueue.requeueConflict] did with a conflicted entry. */
internal sealed interface ConflictRequeue {

    /** It is queued again, at the tail, as the pending mutation [id]. */
    data class Requeued(val id: Long) : ConflictRequeue

    /** It left the list, its change dropped: the server's row stands. */
    data object Dropped : ConflictRequeue

    /** There is no such entry the identity may settle. */
    data object NotFound : ConflictRequeue

    /** The decision does not apply to it, or its body was not kept; it stays on the list. */
    data object Refused : ConflictRequeue
}
